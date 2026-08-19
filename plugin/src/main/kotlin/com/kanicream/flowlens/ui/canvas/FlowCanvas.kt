package com.kanicream.flowlens.ui.canvas

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.keymap.KeymapUtil
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.analysis.FlowAnalyzerCapabilities
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.NodeId
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Flow Canvas rendering surface. Renders the semantic map from immutable snapshots;
 * it never owns analysis state or orchestration (REPO_LENS_LESSONS.md 5.3).
 *
 * View state (zoom, selection, expansion) is deliberately separate from the
 * analysis snapshot; changing it never triggers re-analysis.
 */
class FlowCanvas : JComponent() {

    var onSelectionChanged: (CardVM?) -> Unit = {}
    /** (card, takeFocus): false shows the code, true hands the keyboard over. */
    var onNavigateToTarget: (CardVM, Boolean) -> Unit = { _, _ -> }
    var onNavigateToCallSite: (CardVM, Boolean) -> Unit = { _, _ -> }
    var onNavigateToFrameEntry: (FrameVM, Boolean) -> Unit = { _, _ -> }
    var onContextMenu: (Point) -> Unit = {}

    /** Pin or unpin the selected callable (`V0.3_SPEC.md` §4.5). */
    var onTogglePin: (CardVM?) -> Unit = {}

    /** Promote the selected call's target to the new root (`V0.3_SPEC.md` §6). */
    var onAnalyzeFromHere: (CardVM) -> Unit = {}

    /** Whether that command has anything to do for a card. */
    var canAnalyzeFrom: (CardVM) -> Boolean = { false }

    /** Offer the implementations an ambiguous call could reach (`V0.4_SPEC.md` §3). */
    var onChooseImplementation: (CardVM) -> Unit = {}

    /** Whether that card is a call whose continuation could be chosen. */
    var canChooseImplementation: (CardVM) -> Boolean = { false }
    var onEntrySelected: () -> Unit = {}

    private var result: FlowAnalysisResult? = null
    private val expandedNodes = mutableSetOf<NodeId>()
    private var rootVM: FrameVM? = null
    private var visibleCards: List<CardVM> = emptyList()
    private var visibleFrames: List<FrameVM> = emptyList()
    private var selectedNodeId: NodeId? = null
    private var entrySelected = false
    private var zoom = 1.0
    private var hoveredExpander: NodeId? = null
    private var dragStart: Point? = null
    private var dragOrigin: Point? = null

    init {
        isFocusable = true
        ToolTipManager.sharedInstance().registerComponent(this)
        installMouseHandling()
        installKeyboardHandling()
    }

    /** Applies a new snapshot. Expansion/selection view state survives within a run. */
    /**
     * Callables the developer is tracking. Set by the controller; the canvas only
     * marks them (`V0.3_SPEC.md` §4.9: a pin changes nothing about analysis).
     */
    var pinnedKeys: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    fun setResult(newResult: FlowAnalysisResult?) {
        val runChanged = result?.runId != newResult?.runId
        result = newResult
        if (runChanged) {
            expandedNodes.clear()
            selectedNodeId = null
            entrySelected = false
            zoom = 1.0
            onSelectionChanged(null)
            onZoomChanged()
        }
        rebuild()
    }

    fun selectedCard(): CardVM? = visibleCards.firstOrNull { it.nodeId == selectedNodeId }

    /**
     * Selects a node by id and scrolls it into view, so a status summary can be a
     * way into the map (`V0.3_SPEC.md` §7.3). A node inside a collapsed frame is
     * not visible and cannot be selected; the caller gets false.
     */
    fun selectNode(nodeId: NodeId): Boolean {
        if (visibleCards.none { it.nodeId == nodeId }) {
            // The node is inside a collapsed frame; open the frames on the way to
            // it rather than failing silently.
            val snapshot = result ?: return false
            val path = CanvasViewModelBuilder.revealPath(snapshot, nodeId)
            if (path.isEmpty()) return false
            expandedNodes += path
            rebuild()
        }
        val card = visibleCards.firstOrNull { it.nodeId == nodeId } ?: return false
        select(card)
        scrollToCard(card)
        requestFocusInWindow()
        return true
    }

    /** The entry frame when it is the current selection, for navigation actions. */
    fun selectedEntry(): FrameVM? = rootVM?.takeIf { entrySelected }

    /** Returns true when the expansion state actually changed. */
    fun toggleExpansion(card: CardVM): Boolean {
        if (!card.expandable) return false
        if (!expandedNodes.remove(card.nodeId)) expandedNodes += card.nodeId
        rebuild()
        return true
    }

    private fun expand(card: CardVM): Boolean {
        if (!card.expandable || card.expanded) return false
        expandedNodes += card.nodeId
        rebuild()
        return true
    }

    private fun collapse(card: CardVM): Boolean {
        if (!expandedNodes.remove(card.nodeId)) return false
        rebuild()
        return true
    }

    /** Current zoom as a percentage, for the toolbar indicator. */
    val zoomPercent: Int get() = (zoom * 100).roundToInt()

    var onZoomChanged: () -> Unit = {}

    fun zoomIn() = applyZoom(zoom * CanvasZoom.STEP)

    fun zoomOut() = applyZoom(zoom / CanvasZoom.STEP)

    fun resetZoom() = applyZoom(1.0)

    /**
     * Scales the map so the whole flow fits the viewport. The target zoom is
     * derived from content and viewport size only, so pressing Fit repeatedly
     * always lands on the same result.
     */
    fun fitToView() {
        val bounds = rootVM?.bounds ?: return
        val viewport = viewportSize() ?: return
        val target = CanvasZoom.fitZoom(
            contentWidth = bounds.x + bounds.width + CanvasMetrics.CANVAS_MARGIN,
            contentHeight = bounds.y + bounds.height + CanvasMetrics.CANVAS_MARGIN,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            uiScale = JBUIScale.scale(1f).toDouble(),
        ) ?: return
        zoom = target
        refreshGeometry()
        onZoomChanged()
        // Fitting shows everything, so the map starts at its origin.
        SwingUtilities.invokeLater { scrollRectToVisible(Rectangle(0, 0, 1, 1)) }
    }

    /**
     * The space actually available for the map. `visibleRect` reports the
     * component's own visible part, which collapses to the content size when the
     * content is smaller than the window and would make fitting meaningless.
     */
    private fun viewportSize(): Dimension? {
        val extent = (parent as? JViewport)?.extentSize ?: visibleRect.size
        return extent.takeIf { it.width > 0 && it.height > 0 }
    }

    /** Zooms around the viewport centre so the content under the eye stays put. */
    private fun applyZoom(target: Double) {
        val clamped = CanvasZoom.clamp(target)
        if (clamped == zoom) return
        val viewport = visibleRect
        val centerX = (viewport.x + viewport.width / 2) / totalScale()
        val centerY = (viewport.y + viewport.height / 2) / totalScale()
        zoom = clamped
        refreshGeometry()
        onZoomChanged()
        if (viewport.width <= 0 || viewport.height <= 0) return
        // The scroll pane needs the new preferred size before the viewport can be
        // repositioned, so recentre after this layout pass.
        SwingUtilities.invokeLater {
            val scale = totalScale()
            scrollRectToVisible(
                Rectangle(
                    max(0, (centerX * scale - viewport.width / 2).roundToInt()),
                    max(0, (centerY * scale - viewport.height / 2).roundToInt()),
                    viewport.width,
                    viewport.height,
                ),
            )
        }
    }

    override fun getPreferredSize(): Dimension {
        val bounds = rootVM?.bounds ?: return Dimension(scaledZoomed(400), scaledZoomed(300))
        return Dimension(
            scaledZoomed(bounds.x + bounds.width + CanvasMetrics.CANVAS_MARGIN),
            scaledZoomed(bounds.y + bounds.height + CanvasMetrics.CANVAS_MARGIN),
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = background ?: UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)
            val root = rootVM
            if (root == null) {
                paintEmptyHint(g2)
                return
            }
            g2.transform(AffineTransform.getScaleInstance(totalScale(), totalScale()))
            paintFrame(g2, root)
        } finally {
            g2.dispose()
        }
    }

    // ---- painting ----

    /**
     * Empty state: how to start, plus which language integrations are usable.
     * An unavailable integration is shown with its reason instead of silently
     * producing nothing when the user tries that language (guardrails §3.1).
     */
    private fun paintEmptyHint(g2: Graphics2D) {
        g2.font = JBUI.Fonts.label()
        val fm = g2.fontMetrics
        val hint = FlowLensBundle.message("toolwindow.empty.hint")
        var y = height / 2 - fm.height
        g2.color = Palette.mutedText
        g2.drawString(hint, max(12, (width - fm.stringWidth(hint)) / 2), y)

        g2.font = JBUI.Fonts.smallFont()
        val small = g2.fontMetrics
        for (capability in FlowAnalyzerCapabilities.current()) {
            y += small.height + 4
            val text = if (capability.available) {
                "✓  ${capability.displayName}"
            } else {
                "—  ${FlowLensBundle.message(
                    "capability.unavailable",
                    capability.displayName,
                    capability.requirement,
                )}"
            }
            g2.color = if (capability.available) Palette.text else Palette.mutedText
            g2.drawString(text, max(12, (width - small.stringWidth(text)) / 2), y)
        }

        // The keyboard contract is easiest to learn before there is anything to
        // read on the canvas; it disappears as soon as a flow is drawn.
        y += small.height + 12
        // The jump shortcut differs by platform and keymap, so the hint asks the
        // IDE what it currently is rather than naming a key that may belong to
        // the OS — F4 opens Spotlight on macOS.
        val jump = jumpToSourceShortcutText()
        val keys = if (jump == null) {
            // Nothing is bound to Jump to Source, so the hint says nothing about it
            // rather than naming a key that would do nothing.
            FlowLensBundle.message("toolwindow.key.hints.no.jump")
        } else {
            FlowLensBundle.message("toolwindow.key.hints", jump)
        }
        g2.color = Palette.mutedText
        g2.drawString(keys, max(12, (width - small.stringWidth(keys)) / 2), y)
    }

    /** The current Jump to Source binding, or null when the keymap has none. */
    private fun jumpToSourceShortcutText(): String? {
        val action = ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE)
            ?: return null
        return KeymapUtil.getFirstKeyboardShortcutText(action).ifEmpty { null }
    }

    /** Only the root frame draws a container of its own; calls own their bodies. */
    private fun paintFrame(g2: Graphics2D, frame: FrameVM) {
        val b = frame.bounds
        g2.color = Palette.rootFrameBg
        g2.fillRoundRect(b.x, b.y, b.width, b.height, 16, 16)
        g2.color = Palette.rootFrameBorder
        g2.stroke = BasicStroke(2f)
        g2.drawRoundRect(b.x, b.y, b.width, b.height, 16, 16)
        if (entrySelected) paintSelectionRing(g2, b, 16)

        g2.font = JBUI.Fonts.label().asBold()
        g2.color = Palette.text
        val headerY = b.y + 21
        val entryPrefix = if (frame.pinned) "$PIN_GLYPH ▶ " else "▶ "
        g2.drawString(entryPrefix + frame.title, b.x + CanvasMetrics.FRAME_PADDING, headerY)
        g2.font = JBUI.Fonts.smallFont()
        g2.color = Palette.mutedText
        g2.drawString(
            FlowLensBundle.message("card.entry.badge") + " · " + frame.subtitle,
            b.x + CanvasMetrics.FRAME_PADDING,
            headerY + 13,
        )

        paintCards(g2, frame, bodyTop = b.y + CanvasMetrics.FRAME_HEADER)
    }

    /**
     * Paints one frame's cards in order with the sequence connectors between
     * them. The first card has no predecessor, so when it is one that may not run
     * it gets a lead-in connector of its own: otherwise a conditional first call
     * would look exactly like one that always runs.
     */
    private fun paintCards(g2: Graphics2D, frame: FrameVM, bodyTop: Int) {
        paintCardSequence(g2, frame.cards, bodyTop)
    }

    private fun paintCardSequence(g2: Graphics2D, cards: List<CardVM>, bodyTop: Int) {
        // Two predecessors, because a callback is drawn in the column without
        // being a link in it: `previous` is whatever card sits above, and `chain`
        // is the last card the sequence actually ran through.
        var previous: CardVM? = null
        var chain: CardVM? = null
        for (card in cards) {
            val from = if (card.attached) previous else chain
            if (from == null) {
                if (card.dashedIncomingConnector || card.boundaryBeforeCard) {
                    paintLeadIn(g2, card, bodyTop)
                }
            } else {
                // Attached cards came between, so the connector goes around them
                // rather than through: what follows the call follows the call.
                paintConnector(g2, from, card, detour = !card.attached && previous !== from)
            }
            paintCardTree(g2, card)
            if (card.depthLimited) paintDepthLimitStub(g2, card)
            previous = card
            if (!card.attached) chain = card
        }
    }

    /**
     * The connector from the body's start into its first call, drawn when that
     * call needs one: because it may not run, or because reaching it already
     * crosses out of the project.
     */
    private fun paintLeadIn(g2: Graphics2D, card: CardVM, bodyTop: Int) {
        val x = card.bounds.x + card.bounds.width / 2
        val yEnd = card.bounds.y
        if (yEnd - bodyTop < 6) return
        g2.color = Palette.connector
        g2.stroke = if (card.dashedIncomingConnector) DASHED_STROKE else SOLID_STROKE
        g2.drawLine(x, bodyTop, x, yEnd - 2)
        g2.fillPolygon(
            intArrayOf(x - 4, x + 4, x),
            intArrayOf(yEnd - 6, yEnd - 6, yEnd - 1),
            3,
        )
        if (card.boundaryBeforeCard) {
            paintBoundaryMarker(g2, x, CanvasMetrics.boundaryMarkerY(card.bounds.y))
        }
    }

    /**
     * A structure's labelled sections, stacked inside its container. Each label
     * row names the section, and an empty one says so rather than leaving a gap
     * that reads as a rendering fault.
     */
    private fun paintSections(g2: Graphics2D, card: CardVM) {
        for (section in card.sections) {
            val title = section.titleBounds
            g2.color = Palette.sectionRule
            g2.stroke = SOLID_STROKE
            g2.drawLine(title.x, title.y + title.height - 4, title.x + title.width, title.y + title.height - 4)
            g2.font = JBUI.Fonts.miniFont().asBold()
            g2.color = Palette.sectionLabel
            g2.drawString(section.title, title.x + 2, title.y + title.height - 8)

            if (section.cards.isEmpty()) {
                g2.font = JBUI.Fonts.smallFont()
                g2.color = Palette.mutedText
                g2.drawString(
                    FlowLensBundle.message("branch.empty"),
                    title.x + 14,
                    title.y + title.height + 10,
                )
                continue
            }
            paintCardSequence(g2, section.cards, bodyTop = title.y + title.height)
        }
    }

    /** What the card's own label costs, in the font it is drawn with. */
    private fun nameWidth(g2: Graphics2D, card: CardVM): Int {
        val prefix = listOfNotNull(
            PIN_GLYPH.takeIf { card.pinned },
            card.stateGlyph,
            card.executionGlyph,
        ).joinToString(" ")
        val text = if (prefix.isEmpty()) card.title else "$prefix ${card.title}"
        val font = g2.font
        g2.font = JBUI.Fonts.label()
        val width = g2.fontMetrics.stringWidth(text)
        g2.font = font
        return width
    }

    /** Explicit continuation marker: more code exists but was not analyzed. */
    private fun paintDepthLimitStub(g2: Graphics2D, card: CardVM) {
        val x = card.containerBounds.x + 14
        val y = card.containerBounds.y + card.containerBounds.height
        g2.color = Palette.connector
        g2.stroke = DASHED_STROKE
        g2.drawLine(x, y + 2, x, y + CanvasMetrics.LIMIT_STUB_HEIGHT - 6)
        g2.font = JBUI.Fonts.smallFont()
        g2.color = Palette.mutedText
        g2.drawString(
            FlowLensBundle.message("card.limit.depth.hint"),
            x + 8,
            y + CanvasMetrics.LIMIT_STUB_HEIGHT - 5,
        )
    }

    /**
     * The sequence connector between two siblings. It starts below everything the
     * previous call owns — including an expanded body — so the line always
     * expresses "the next step in this frame", never a call made by nested code.
     */
    private fun paintConnector(g2: Graphics2D, from: CardVM, to: CardVM, detour: Boolean = false) {
        val fromBottom = from.occupiedBottom
        // A detour runs down the gutter that the attached cards' indent opens up,
        // so the line is beside them instead of appearing to pass through them.
        val x = if (detour) {
            from.containerBounds.x + CanvasMetrics.CHILD_INDENT / 2
        } else {
            from.containerBounds.x + from.containerBounds.width / 2
        }
        val yEnd = to.bounds.y
        g2.color = Palette.connector
        g2.stroke = if (to.dashedIncomingConnector) DASHED_STROKE else SOLID_STROKE
        g2.drawLine(x, fromBottom + 2, x, yEnd - 2)
        g2.fillPolygon(
            intArrayOf(x - 4, x + 4, x),
            intArrayOf(yEnd - 6, yEnd - 6, yEnd - 1),
            3,
        )
        if (to.boundaryBeforeCard) {
            paintBoundaryMarker(g2, x, CanvasMetrics.boundaryMarkerY(to.bounds.y))
        }
    }

    /** Local project-boundary crossing (VISUAL_DESIGN.md section 11). */
    private fun paintBoundaryMarker(g2: Graphics2D, x: Int, midY: Int) {
        g2.color = Palette.connector
        g2.stroke = SOLID_STROKE
        g2.drawLine(x - 3, midY - 5, x - 3, midY + 5)
        g2.drawLine(x + 3, midY - 5, x + 3, midY + 5)
        g2.font = JBUI.Fonts.miniFont()
        g2.color = Palette.boundaryText
        g2.drawString(FlowLensBundle.message("card.boundary.label"), x + 10, midY + 4)
    }

    /**
     * An expanded call is drawn as one container: the card is its header and the
     * analyzed body sits inside it. The sequence connector therefore leaves the
     * container's edge instead of appearing to come out of the last nested call.
     */
    private fun paintCardTree(g2: Graphics2D, card: CardVM) {
        paintCardBox(g2, card)
        paintCardContent(g2, card)
        if (card.isStructure) {
            paintSections(g2, card)
            return
        }
        val body = card.childFrame ?: return
        // Separator between the call's own header and the body it contains.
        val header = card.bounds
        g2.color = Palette.cardBorder
        g2.stroke = DASHED_STROKE
        g2.drawLine(
            header.x + 12,
            header.y + header.height + CanvasMetrics.NESTED_TOP_GAP / 2,
            header.x + header.width - 12,
            header.y + header.height + CanvasMetrics.NESTED_TOP_GAP / 2,
        )
        card.chosenImplementation?.let { chosen ->
            // The body under a chosen call belongs to a different callable than
            // the one named on the card, so it says whose it is rather than
            // leaving the reader to infer it from a badge.
            g2.font = JBUI.Fonts.miniFont()
            g2.color = Palette.mutedText
            g2.drawString(
                FlowLensBundle.message("card.body.chosen", chosen),
                header.x + 18,
                header.y + header.height + CanvasMetrics.NESTED_TOP_GAP / 2 + JBUI.scale(11),
            )
        }
        paintCards(g2, body, bodyTop = header.y + header.height + CanvasMetrics.NESTED_TOP_GAP / 2)
    }

    /** Draws the box for a call: just the card, or the whole container when expanded. */
    private fun paintCardBox(g2: Graphics2D, card: CardVM) {
        val box = card.containerBounds
        g2.color = cardBackground(card.style)
        g2.fillRoundRect(box.x, box.y, box.width, box.height, 12, 12)
        // The element always keeps its own border: recolouring it for selection
        // used to erase the state it encodes, such as an external target's border.
        g2.color = cardBorder(card.style)
        g2.stroke = when {
            card.style == CardStyle.UNRESOLVED || card.style == CardStyle.AMBIGUOUS -> DASHED_STROKE
            else -> SOLID_STROKE
        }
        g2.drawRoundRect(box.x, box.y, box.width, box.height, 12, 12)
        if (card.nodeId == selectedNodeId) paintSelectionRing(g2, box, 12)
    }

    /**
     * Selection is drawn as a ring outside the element, so it never competes with
     * the element's own colour — including the entry frame, which is blue for a
     * different reason.
     */
    private fun paintSelectionRing(g2: Graphics2D, box: Rectangle, arc: Int) {
        g2.color = Palette.selectionBorder
        g2.stroke = BasicStroke(2f)
        val inset = SELECTION_RING_GAP
        g2.drawRoundRect(
            box.x - inset,
            box.y - inset,
            box.width + inset * 2,
            box.height + inset * 2,
            arc + inset,
            arc + inset,
        )
    }

    /**
     * One line per call: state and execution glyphs, the callable, and a compact
     * right-hand group for expansion state and depth. Everything else is in the
     * details panel and the hover tooltip.
     */
    private fun paintCardContent(g2: Graphics2D, card: CardVM) {
        val b = card.bounds
        val baseline = b.y + b.height / 2 + 5
        g2.font = JBUI.Fonts.smallFont()

        // Depth sits at the far right; the expand control keeps a fixed slot next
        // to it so it is always in the same place and always hittable.
        g2.color = Palette.mutedText
        g2.drawString(card.depthLabel, b.x + b.width - CanvasMetrics.DEPTH_WIDTH + 4, baseline)

        var rightEdge = b.x + b.width - CanvasMetrics.DEPTH_WIDTH
        if (card.expandable) {
            val expander = card.expanderBounds
            if (card.nodeId == hoveredExpander) {
                g2.color = Palette.expanderHover
                g2.fillRoundRect(expander.x + 2, expander.y + 4, expander.width - 4, expander.height - 8, 8, 8)
            }
            g2.color = Palette.text
            val label = (if (card.expanded) "▾ " else "▸ ") + card.callsInside
            val labelWidth = g2.fontMetrics.stringWidth(label)
            g2.drawString(label, expander.x + (expander.width - labelWidth) / 2, baseline)
            rightEdge = expander.x
        } else if (card.resolving) {
            val label = FlowLensBundle.message("card.resolving")
            g2.color = Palette.mutedText
            g2.drawString(label, rightEdge - g2.fontMetrics.stringWidth(label) - 6, baseline)
            rightEdge -= g2.fontMetrics.stringWidth(label) + 6
        }
        card.trailingNote?.let { note ->
            g2.color = Palette.mutedText
            // The callable's name is the card's identity: it gets the width it
            // needs, and the note takes what is left. A fixed share for the note
            // still truncated the name, which is the wrong thing to lose — the
            // note's content is also in the tooltip, the details panel, the body
            // header below, and both exports.
            // TITLE_MARGIN is what the name's own drawing subtracts below; leaving
            // it out let a marginal note squeeze in and truncate the name anyway,
            // which is the failure this whole rule exists to prevent.
            val nameWidth = nameWidth(g2, card)
            val room = rightEdge - b.x - nameWidth - TITLE_MARGIN - NOTE_GAP
            val shown = if (room >= JBUI.scale(MIN_NOTE_WIDTH)) truncate(g2, note, room) else null
            if (shown != null) {
                val noteWidth = g2.fontMetrics.stringWidth(shown)
                g2.drawString(shown, rightEdge - noteWidth - NOTE_GAP, baseline)
                rightEdge -= noteWidth + NOTE_GAP
            }
        }
        val trailingWidth = b.x + b.width - rightEdge

        val prefix = listOfNotNull(
            PIN_GLYPH.takeIf { card.pinned },
            card.stateGlyph,
            card.executionGlyph,
        ).joinToString(" ")
        g2.font = JBUI.Fonts.label()
        g2.color = when (card.style) {
            CardStyle.LIMIT, CardStyle.CYCLE -> Palette.mutedText
            else -> Palette.text
        }
        val available = b.width - trailingWidth - TITLE_MARGIN
        val text = if (prefix.isEmpty()) card.title else "$prefix ${card.title}"
        g2.drawString(truncate(g2, text, available), b.x + 12, baseline)
    }

    private fun cardBackground(style: CardStyle): Color = when (style) {
        CardStyle.ENTRY -> Palette.rootFrameBg
        CardStyle.STRUCTURE -> Palette.structureBg
        CardStyle.TERMINATOR -> Palette.terminatorBg
        CardStyle.EXTERNAL -> Palette.externalBg
        CardStyle.BUILT_IN -> Palette.externalBg
        CardStyle.UNRESOLVED -> Palette.unresolvedBg
        CardStyle.AMBIGUOUS -> Palette.ambiguousBg
        CardStyle.CYCLE, CardStyle.LIMIT -> Palette.markerBg
        else -> Palette.cardBg
    }

    private fun cardBorder(style: CardStyle): Color = when (style) {
        CardStyle.STRUCTURE -> Palette.structureBorder
        CardStyle.DECLARED_TARGET -> Palette.declaredBorder
        CardStyle.EXTERNAL, CardStyle.BUILT_IN -> Palette.externalBorder
        else -> Palette.cardBorder
    }

    private fun truncate(g2: Graphics2D, text: String, maxWidth: Int): String {
        val fm = g2.fontMetrics
        if (fm.stringWidth(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && fm.stringWidth("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    // ---- interaction ----

    private fun installMouseHandling() {
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                val card = cardAt(e.point)
                if (e.isPopupTrigger) {
                    select(card)
                    onContextMenu(e.point)
                    return
                }
                if (card == null && frameHeaderAt(e.point) == null) {
                    dragStart = e.locationOnScreen
                    dragOrigin = visibleRect.location
                }
                if (card == null && frameHeaderAt(e.point) != null) {
                    selectEntry()
                    if (e.clickCount == 2) rootVM?.let { onNavigateToFrameEntry(it, false) }
                    return
                }
                select(card)
                if (card == null) return
                if (e.clickCount == 2) {
                    onNavigateToTarget(card, false)
                } else if (card.expandable && card.expanderBounds.contains(toLogical(e.point))) {
                    toggleExpansion(card)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                dragStart = null
                dragOrigin = null
                // Popup triggers arrive on release on some platforms.
                if (e.isPopupTrigger) {
                    select(cardAt(e.point))
                    onContextMenu(e.point)
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                // The card shows compact glyphs, so hovering must be able to say
                // the same thing in words.
                val card = cardAt(e.point)
                toolTipText = card?.tooltip?.takeIf { it.isNotBlank() }
                val overExpander = card
                    ?.takeIf { it.expandable && it.expanderBounds.contains(toLogical(e.point)) }
                    ?.nodeId
                if (overExpander != hoveredExpander) {
                    hoveredExpander = overExpander
                    cursor = if (overExpander != null) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                    repaint()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = dragStart ?: return
                val origin = dragOrigin ?: return
                val dx = e.locationOnScreen.x - start.x
                val dy = e.locationOnScreen.y - start.y
                val r = Rectangle(origin.x - dx, origin.y - dy, visibleRect.width, visibleRect.height)
                scrollRectToVisible(r)
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (e.isControlDown || e.isMetaDown) {
                    if (e.wheelRotation < 0) zoomIn() else zoomOut()
                } else {
                    parent?.dispatchEvent(SwingUtilities.convertMouseEvent(this@FlowCanvas, e, parent))
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    private fun installKeyboardHandling() {
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Only consume keys that actually did something, so unused arrows
                // still reach the scroll pane and pan a wide flow.
                // A modified arrow belongs to whoever bound it — Jump to Source is
                // Cmd+Down on macOS — and a KeyListener runs before the registered
                // shortcuts, so consuming one here would swallow that command.
                val plainArrow = !e.isMetaDown && !e.isControlDown && !e.isAltDown && !e.isShiftDown
                val handled = when {
                    e.keyCode == KeyEvent.VK_DOWN && plainArrow -> moveSelection(1)
                    e.keyCode == KeyEvent.VK_UP && plainArrow -> moveSelection(-1)
                    // Enter, Shift+Enter, Space, and the zoom keys are registered
                    // actions so they appear in the keymap and the context menu.
                    e.keyCode == KeyEvent.VK_RIGHT && plainArrow ->
                        selectedCard()?.let(::expand) == true
                    e.keyCode == KeyEvent.VK_LEFT && plainArrow ->
                        selectedCard()?.let(::collapse) == true
                    isZoomShortcut(e) && e.keyCode == KeyEvent.VK_0 -> {
                        resetZoom()
                        true
                    }
                    e.keyCode == KeyEvent.VK_ESCAPE -> {
                        // The entry keeps its selection in its own flag, so both
                        // have to count as something Escape cleared.
                        val hadSelection = selectedNodeId != null || entrySelected
                        select(null)
                        hadSelection
                    }
                    else -> false
                }
                if (handled) e.consume()
            }
        })
    }

    private fun isZoomShortcut(e: KeyEvent): Boolean = e.isControlDown || e.isMetaDown

    /** Returns true when the selection actually moved. */
    private fun moveSelection(delta: Int): Boolean {
        if (visibleCards.isEmpty()) return false
        if (entrySelected) {
            if (delta <= 0) return false
            select(visibleCards.first())
            scrollToCard(visibleCards.first())
            return true
        }
        val index = visibleCards.indexOfFirst { it.nodeId == selectedNodeId }
        if (index == 0 && delta < 0) {
            // Above the first call is the entry, so the sequence has a natural top.
            selectEntry()
            return true
        }
        val next = (if (index < 0) 0 else index + delta).coerceIn(0, visibleCards.lastIndex)
        if (next == index) return false
        select(visibleCards[next])
        scrollToCard(visibleCards[next])
        return true
    }

    private fun scrollToCard(card: CardVM) {
        val s = totalScale()
        val b = card.bounds
        scrollRectToVisible(
            Rectangle(
                (b.x * s).roundToInt() - 20,
                (b.y * s).roundToInt() - 20,
                (b.width * s).roundToInt() + 40,
                (b.height * s).roundToInt() + 40,
            ),
        )
    }

    private fun select(card: CardVM?) {
        selectedNodeId = card?.nodeId
        entrySelected = false
        onSelectionChanged(card)
        repaint()
    }

    /** Selects the entry frame; the details panel describes it like any element. */
    private fun selectEntry() {
        selectedNodeId = null
        entrySelected = rootVM != null
        onEntrySelected()
        repaint()
    }

    private fun cardAt(point: Point): CardVM? {
        val logical = toLogical(point)
        // Deepest (nested) card wins, so hit-test in reverse layout order.
        return visibleCards.lastOrNull { it.bounds.contains(logical) }
    }

    private fun frameHeaderAt(point: Point): FrameVM? {
        val logical = toLogical(point)
        // Only the root frame draws a header; an expanded call is opened through
        // its own card, which is that container's header.
        return visibleFrames.lastOrNull { it.rendersHeader && it.headerBounds.contains(logical) }
    }

    private fun toLogical(point: Point): Point {
        val s = totalScale()
        return Point((point.x / s).roundToInt(), (point.y / s).roundToInt())
    }

    private fun rebuild() {
        rootVM = result?.let { CanvasViewModelBuilder.build(it, expandedNodes, pinnedKeys) }
        visibleCards = CanvasViewModelBuilder.visibleCards(rootVM)
        visibleFrames = CanvasViewModelBuilder.visibleFrames(rootVM)
        val selected = selectedNodeId
        if (selected != null && visibleCards.none { it.nodeId == selected }) {
            selectedNodeId = null
            onSelectionChanged(null)
        }
        refreshGeometry()
    }

    private fun refreshGeometry() {
        revalidate()
        repaint()
    }

    private fun totalScale(): Double = zoom * JBUIScale.scale(1f)

    private fun scaled(logical: Int): Int = (logical * JBUIScale.scale(1f)).roundToInt()

    private fun scaledZoomed(logical: Int): Int = (logical * totalScale()).roundToInt()

    private object Palette {
        val cardBg = JBColor(Color(0xF7F8FA), Color(0x2B2D30))
        val structureBg = JBColor(Color(0xF4F1FA), Color(0x2C2A33))
        val structureBorder = JBColor(Color(0xA694CC), Color(0x7A6BA8))
        val terminatorBg = JBColor(Color(0xF0F1F3), Color(0x2A2C2F))
        val sectionRule = JBColor(Color(0xD7D9DE), Color(0x45484D))
        val sectionLabel = JBColor(Color(0x5A5D66), Color(0x9DA0A8))
        val cardBorder = JBColor(Color(0xC9CDD6), Color(0x4E5157))
        val rootFrameBg = JBColor(Color(0xEDF3FF), Color(0x25324D))
        val rootFrameBorder = JBColor(Color(0x4A88E8), Color(0x548AF7))
        val frameBg = JBColor(Color(0xF2F3F5), Color(0x26282B))
        val frameBorder = JBColor(Color(0xD3D6DC), Color(0x43454A))
        val externalBg = JBColor(Color(0xF3EFE7), Color(0x33302A))
        val externalBorder = JBColor(Color(0xC4A96A), Color(0x8A7A4D))
        val unresolvedBg = JBColor(Color(0xF5EFEF), Color(0x332B2B))
        val ambiguousBg = JBColor(Color(0xF3EEF7), Color(0x2F2A33))
        val markerBg = JBColor(Color(0xEFEFEF), Color(0x2A2A2A))
        val declaredBorder = JBColor(Color(0x9C7BD0), Color(0x8A6FC0))
        val selectionBorder = JBColor(Color(0x3574F0), Color(0x548AF7))
        val connector = JBColor(Color(0x8A8E99), Color(0x6F737A))
        val expanderHover = JBColor(Color(0xDCE3EE), Color(0x3A4048))
        val boundaryText = JBColor(Color(0x9A7B2D), Color(0xC0A35E))
        val text = JBColor(Color(0x1D1F23), Color(0xDFE1E5))
        val mutedText = JBColor(Color(0x6C707E), Color(0x9DA0A8))
        val badgeText = JBColor(Color(0x5A5D66), Color(0xB4B8BF))
    }

    companion object {
        /** Marks a callable the developer is tracking (`V0.3_SPEC.md` §4). */
        const val PIN_GLYPH = "★"

        /** Below this a note is an ellipsis and nothing else, so it is dropped. */
        private const val MIN_NOTE_WIDTH = 44

        private const val NOTE_GAP = 6

        /** The name's own left inset plus breathing room on the right. */
        private const val TITLE_MARGIN = 26

        private const val SELECTION_RING_GAP = 3
        private val SOLID_STROKE = BasicStroke(1.4f)
        private val DASHED_STROKE = BasicStroke(
            1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f,
        )
    }
}
