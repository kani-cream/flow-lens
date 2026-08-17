package com.kanicream.flowlens.ui.canvas

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.analysis.FlowAnalyzerCapabilities
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.NodeId
import java.awt.BasicStroke
import java.awt.Color
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
import javax.swing.SwingUtilities
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
    var onNavigateToTarget: (CardVM) -> Unit = {}
    var onNavigateToCallSite: (CardVM) -> Unit = {}
    var onNavigateToFrameEntry: (FrameVM) -> Unit = {}

    private var result: FlowAnalysisResult? = null
    private val expandedNodes = mutableSetOf<NodeId>()
    private var rootVM: FrameVM? = null
    private var visibleCards: List<CardVM> = emptyList()
    private var visibleFrames: List<FrameVM> = emptyList()
    private var selectedNodeId: NodeId? = null
    private var zoom = 1.0
    private var dragStart: Point? = null
    private var dragOrigin: Point? = null

    init {
        isFocusable = true
        installMouseHandling()
        installKeyboardHandling()
    }

    /** Applies a new snapshot. Expansion/selection view state survives within a run. */
    fun setResult(newResult: FlowAnalysisResult?) {
        val runChanged = result?.runId != newResult?.runId
        result = newResult
        if (runChanged) {
            expandedNodes.clear()
            selectedNodeId = null
            zoom = 1.0
            onSelectionChanged(null)
        }
        rebuild()
    }

    fun selectedCard(): CardVM? = visibleCards.firstOrNull { it.nodeId == selectedNodeId }

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

    fun fitToView() {
        val bounds = rootVM?.bounds ?: return
        val viewport = visibleRect.takeIf { it.width > 0 && it.height > 0 } ?: return
        val contentW = bounds.x + bounds.width + CanvasMetrics.CANVAS_MARGIN
        val contentH = bounds.y + bounds.height + CanvasMetrics.CANVAS_MARGIN
        val scale = min(
            viewport.width.toDouble() / scaled(contentW),
            viewport.height.toDouble() / scaled(contentH),
        )
        zoom = (zoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM)
        refreshGeometry()
        scrollRectToVisible(Rectangle(0, 0, 1, 1))
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
    }

    private fun paintFrame(g2: Graphics2D, frame: FrameVM) {
        val b = frame.bounds
        // Frame container.
        g2.color = if (frame.isRoot) Palette.rootFrameBg else Palette.frameBg
        g2.fillRoundRect(b.x, b.y, b.width, b.height, 16, 16)
        g2.color = if (frame.isRoot) Palette.rootFrameBorder else Palette.frameBorder
        g2.stroke = BasicStroke(if (frame.isRoot) 2f else 1f)
        g2.drawRoundRect(b.x, b.y, b.width, b.height, 16, 16)
        // Header.
        g2.font = JBUI.Fonts.label().asBold()
        g2.color = Palette.text
        val headerY = b.y + 21
        val prefix = if (frame.isRoot) "▶ " else ""
        g2.drawString(prefix + frame.title, b.x + CanvasMetrics.FRAME_PADDING, headerY)
        g2.font = JBUI.Fonts.smallFont()
        g2.color = Palette.mutedText
        val entryTag = if (frame.isRoot) FlowLensBundle.message("card.entry.badge") + " · " else ""
        g2.drawString(entryTag + frame.subtitle, b.x + CanvasMetrics.FRAME_PADDING, headerY + 13)

        var previous: CardVM? = null
        for (card in frame.cards) {
            previous?.let { paintConnector(g2, it, card) }
            paintCard(g2, card)
            if (card.depthLimited) paintDepthLimitStub(g2, card)
            card.childFrame?.let { paintFrame(g2, it) }
            previous = card
        }
    }

    /** Explicit continuation marker: more code exists but was not analyzed. */
    private fun paintDepthLimitStub(g2: Graphics2D, card: CardVM) {
        val x = card.bounds.x + 14
        val y = card.bounds.y + card.bounds.height
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

    private fun paintConnector(g2: Graphics2D, from: CardVM, to: CardVM) {
        val fromBottom = from.occupiedBottom
        val x = from.bounds.x + from.bounds.width / 2
        val yEnd = to.bounds.y
        g2.color = Palette.connector
        g2.stroke = if (to.dashedIncomingConnector) DASHED_STROKE else SOLID_STROKE
        g2.drawLine(x, fromBottom + 2, x, yEnd - 2)
        // Arrow head.
        g2.fillPolygon(
            intArrayOf(x - 4, x + 4, x),
            intArrayOf(yEnd - 6, yEnd - 6, yEnd - 1),
            3,
        )
        if (to.boundaryBeforeCard) {
            // Local project-boundary crossing (VISUAL_DESIGN.md section 11).
            val midY = (fromBottom + yEnd) / 2
            g2.stroke = SOLID_STROKE
            g2.drawLine(x - 3, midY - 5, x - 3, midY + 5)
            g2.drawLine(x + 3, midY - 5, x + 3, midY + 5)
            g2.font = JBUI.Fonts.miniFont()
            g2.color = Palette.boundaryText
            g2.drawString(FlowLensBundle.message("card.boundary.label"), x + 10, midY + 4)
        }
    }

    private fun paintCard(g2: Graphics2D, card: CardVM) {
        val b = card.bounds
        val selected = card.nodeId == selectedNodeId
        g2.color = cardBackground(card.style)
        g2.fillRoundRect(b.x, b.y, b.width, b.height, 12, 12)
        g2.color = if (selected) Palette.selectionBorder else cardBorder(card.style)
        g2.stroke = when {
            selected -> BasicStroke(2f)
            card.style == CardStyle.UNRESOLVED || card.style == CardStyle.AMBIGUOUS -> DASHED_STROKE
            else -> SOLID_STROKE
        }
        g2.drawRoundRect(b.x, b.y, b.width, b.height, 12, 12)

        val textX = b.x + 12
        g2.font = JBUI.Fonts.label()
        g2.color = if (card.style == CardStyle.LIMIT || card.style == CardStyle.CYCLE) {
            Palette.mutedText
        } else {
            Palette.text
        }
        val glyph = when (card.style) {
            CardStyle.UNRESOLVED -> "? "
            CardStyle.AMBIGUOUS -> "◇ "
            CardStyle.CYCLE, CardStyle.LIMIT -> ""
            else -> ""
        }
        g2.drawString(truncate(g2, glyph + card.title, b.width - 60), textX, b.y + 19)

        g2.font = JBUI.Fonts.smallFont()
        g2.color = Palette.mutedText
        val secondLine = buildString {
            card.subtitle?.let { append(it) }
            if (card.expandable) {
                if (isNotEmpty()) append("  ")
                append(if (card.expanded) "▾ " else "▸ ")
                append(FlowLensBundle.message("card.calls.inside", card.callsInside))
            }
            if (card.resolving) {
                if (isNotEmpty()) append("  ")
                append(FlowLensBundle.message("card.resolving"))
            }
        }
        if (secondLine.isNotEmpty()) {
            g2.drawString(truncate(g2, secondLine, b.width - 60), textX, b.y + 35)
        }
        // Depth tag, right-aligned.
        g2.drawString(card.depthLabel, b.x + b.width - 34, b.y + 19)

        if (card.badges.isNotEmpty()) {
            g2.font = JBUI.Fonts.miniFont()
            g2.color = Palette.badgeText
            g2.drawString(
                truncate(g2, card.badges.joinToString("  "), b.width - 24),
                textX,
                b.y + b.height - 8,
            )
        }
    }

    private fun cardBackground(style: CardStyle): Color = when (style) {
        CardStyle.ENTRY -> Palette.rootFrameBg
        CardStyle.EXTERNAL -> Palette.externalBg
        CardStyle.BUILT_IN -> Palette.externalBg
        CardStyle.UNRESOLVED -> Palette.unresolvedBg
        CardStyle.AMBIGUOUS -> Palette.ambiguousBg
        CardStyle.CYCLE, CardStyle.LIMIT -> Palette.markerBg
        else -> Palette.cardBg
    }

    private fun cardBorder(style: CardStyle): Color = when (style) {
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
                if (card == null) {
                    dragStart = e.locationOnScreen
                    dragOrigin = visibleRect.location
                    // Entry/frame headers open the frame's declaration
                    // (V0.1_SPEC.md section 18: entry opens entry declaration).
                    if (e.clickCount == 2) {
                        frameHeaderAt(e.point)?.let(onNavigateToFrameEntry)
                    }
                }
                select(card)
                if (card != null && e.clickCount == 2) {
                    onNavigateToTarget(card)
                } else if (card != null && card.expandable && e.clickCount == 1 &&
                    e.y / totalScale() > card.bounds.y + 24
                ) {
                    // Click on the expander line toggles inline frame expansion.
                    toggleExpansion(card)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                dragStart = null
                dragOrigin = null
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
                    val factor = if (e.wheelRotation < 0) 1.1 else 1 / 1.1
                    zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    refreshGeometry()
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
                val handled = when {
                    e.keyCode == KeyEvent.VK_DOWN -> moveSelection(1)
                    e.keyCode == KeyEvent.VK_UP -> moveSelection(-1)
                    // Shift+Enter is the explicit call-site action; plain Enter keeps
                    // the documented default of opening the target (`V0.1_SPEC.md` §18).
                    e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown ->
                        selectedCard()?.also(onNavigateToCallSite) != null
                    e.keyCode == KeyEvent.VK_ENTER ->
                        selectedCard()?.also(onNavigateToTarget) != null
                    e.keyCode == KeyEvent.VK_SPACE -> selectedCard()?.let(::toggleExpansion) == true
                    e.keyCode == KeyEvent.VK_RIGHT -> selectedCard()?.let(::expand) == true
                    e.keyCode == KeyEvent.VK_LEFT -> selectedCard()?.let(::collapse) == true
                    e.keyCode == KeyEvent.VK_ESCAPE -> {
                        val hadSelection = selectedNodeId != null
                        select(null)
                        hadSelection
                    }
                    else -> false
                }
                if (handled) e.consume()
            }
        })
    }

    /** Returns true when the selection actually moved. */
    private fun moveSelection(delta: Int): Boolean {
        if (visibleCards.isEmpty()) return false
        val index = visibleCards.indexOfFirst { it.nodeId == selectedNodeId }
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
        onSelectionChanged(card)
        repaint()
    }

    private fun cardAt(point: Point): CardVM? {
        val logical = toLogical(point)
        // Deepest (nested) card wins, so hit-test in reverse layout order.
        return visibleCards.lastOrNull { it.bounds.contains(logical) }
    }

    private fun frameHeaderAt(point: Point): FrameVM? {
        val logical = toLogical(point)
        // Deepest (nested) frame wins, so hit-test in reverse layout order.
        return visibleFrames.lastOrNull { it.headerBounds.contains(logical) }
    }

    private fun toLogical(point: Point): Point {
        val s = totalScale()
        return Point((point.x / s).roundToInt(), (point.y / s).roundToInt())
    }

    private fun rebuild() {
        rootVM = result?.let { CanvasViewModelBuilder.build(it, expandedNodes) }
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
        val boundaryText = JBColor(Color(0x9A7B2D), Color(0xC0A35E))
        val text = JBColor(Color(0x1D1F23), Color(0xDFE1E5))
        val mutedText = JBColor(Color(0x6C707E), Color(0x9DA0A8))
        val badgeText = JBColor(Color(0x5A5D66), Color(0xB4B8BF))
    }

    companion object {
        private const val MIN_ZOOM = 0.25
        private const val MAX_ZOOM = 2.5
        private val SOLID_STROKE = BasicStroke(1.4f)
        private val DASHED_STROKE = BasicStroke(
            1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f,
        )
    }
}
