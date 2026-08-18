package com.kanicream.flowlens.ui.canvas

import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowBranch
import com.kanicream.flowlens.core.model.FlowFrame
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.NodeId
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin
import com.kanicream.flowlens.service.FlowMetadata
import com.kanicream.flowlens.core.model.FrameId
import java.awt.Rectangle

/** Visual treatment category of a card; states stay distinguishable without color alone. */
enum class CardStyle {
    ENTRY,
    STRUCTURE,
    TERMINATOR,
    PROJECT_CALL,
    DECLARED_TARGET,
    AMBIGUOUS,
    UNRESOLVED,
    EXTERNAL,
    BUILT_IN,
    CYCLE,
    LIMIT,
}

/** One rendered call card. Bounds are absolute canvas coordinates in logical units. */
class CardVM(
    val node: FlowNode,
    /** Container-qualified only when the call leaves the enclosing type. */
    val title: String,
    /** One glyph for the target's certainty: unresolved, ambiguous, declared, generated. */
    val stateGlyph: String?,
    /** One glyph for execution semantics: goroutine or deferred. */
    val executionGlyph: String?,
    /** Short trailing note for rare facts such as test sources. */
    val trailingNote: String?,
    /** Full localized state, shown on hover because the glyphs are compact. */
    val tooltip: String,
    val style: CardStyle,
    val depthLabel: String,
    val boundaryBeforeCard: Boolean,
    val dashedIncomingConnector: Boolean,
    val expandable: Boolean,
    val expanded: Boolean,
    val resolving: Boolean,
    val depthLimited: Boolean,
    val callsInside: Int,
    val childFrame: FrameVM?,
    /** Labelled sections of a structural card, laid out inside its container. */
    val sections: List<SectionVM> = emptyList(),
    /**
     * The target is a callable the developer is tracking (`V0.3_SPEC.md` §4).
     * A mark, never a colour on its own (`VISUAL_DESIGN.md` §21).
     */
    val pinned: Boolean = false,
    /**
     * The implementation the reader chose for this call, when its continuation
     * was chosen rather than proven (`V0.4_SPEC.md` §4.5). Present here means the
     * body below this card belongs to something other than the callable named on
     * it, which the reader has to be able to see.
     */
    val chosenImplementation: String? = null,
) {
    val nodeId: NodeId get() = node.id

    /** The card's own header region: what selection and hit testing use. */
    var bounds: Rectangle = Rectangle()

    /**
     * The box drawn for this call. When the call is expanded this is the whole
     * container — the header plus the analyzed body inside it — so a sibling
     * connector attaches to the container's edge rather than appearing to come
     * out of the last nested call (`VISUAL_DESIGN.md` §7).
     */
    var containerBounds: Rectangle = Rectangle()

    val expandedInline: Boolean get() = childFrame != null || sections.isNotEmpty()

    /** True when this card is a control structure rather than a step. */
    val isStructure: Boolean get() = sections.isNotEmpty()

    /**
     * The clickable expand/collapse control at the right of the line. Expansion
     * used to be triggered by clicking the card's lower half, which was an
     * invisible target and disappeared when cards became one line; a real control
     * can be seen, hovered, and hit.
     */
    val expanderBounds: Rectangle
        get() = if (!expandable) {
            Rectangle()
        } else {
            Rectangle(
                bounds.x + bounds.width - CanvasMetrics.EXPANDER_WIDTH - CanvasMetrics.DEPTH_WIDTH,
                bounds.y,
                CanvasMetrics.EXPANDER_WIDTH,
                bounds.height,
            )
        }

    /** Bottom of everything this card owns, including its limit marker. */
    val occupiedBottom: Int
        get() = containerBounds.y + containerBounds.height +
            if (depthLimited) CanvasMetrics.LIMIT_STUB_HEIGHT else 0
}

/**
 * One labelled section inside a structural card: a `then`, a `case`, a loop
 * body. An empty section is still drawn, so "this case does nothing" stays
 * visible (`V0.2_SPEC.md` §7).
 */
class SectionVM(
    val title: String,
    val cards: List<CardVM>,
) {
    var bounds: Rectangle = Rectangle()

    /** The label row; the section's cards are laid out under it. */
    var titleBounds: Rectangle = Rectangle()
}

/** One rendered frame container (analyzed callable body). */
class FrameVM(
    val frameId: com.kanicream.flowlens.core.model.FrameId,
    val title: String,
    val subtitle: String,
    val isRoot: Boolean,
    val entryLocation: com.kanicream.flowlens.core.model.FlowLocation?,
    val cards: List<CardVM>,
    val pinned: Boolean = false,
) {
    /**
     * Identifies the entry for selection. The root is a frame rather than a call
     * event, but the user still clicks it and expects it to behave like anything
     * else on the canvas.
     */
    val selectionKey: FrameId get() = frameId

    var bounds: Rectangle = Rectangle()

    /**
     * Only the root frame draws a header of its own; an expanded child frame is
     * rendered inside its call card, which already names the callable.
     */
    val rendersHeader: Boolean get() = isRoot

    /** The clickable header strip; double-click opens the entry declaration. */
    val headerBounds: Rectangle
        get() = if (rendersHeader) {
            Rectangle(bounds.x, bounds.y, bounds.width, CanvasMetrics.FRAME_HEADER)
        } else {
            Rectangle()
        }
}

/** Logical (unscaled) layout constants; the canvas applies zoom and UI scale. */
object CanvasMetrics {
    const val CARD_WIDTH = 260

    /**
     * One line per call. A flow is a sequence, so it grows downwards by nature;
     * the way to keep it readable is to spend as little height per call as
     * possible, not to break the sequence into columns. Everything that does not
     * fit on the line lives in the details panel and the hover tooltip.
     */
    const val CARD_HEIGHT = 30
    const val CONNECTOR_GAP = 20

    /** Width of the expand/collapse control and of the depth label beside it. */
    const val EXPANDER_WIDTH = 46
    const val DEPTH_WIDTH = 34
    const val BOUNDARY_GAP = 40
    const val FRAME_PADDING = 14
    const val FRAME_HEADER = 34
    const val CHILD_INDENT = 18
    const val CANVAS_MARGIN = 24
    const val LIMIT_STUB_HEIGHT = 20

    /** Space between an expanded call's header and the body drawn inside it. */
    const val NESTED_TOP_GAP = 10
    const val NESTED_BOTTOM_PAD = 12

    /** The label row of a branch section, and the inset of its contents. */
    const val SECTION_TITLE_HEIGHT = 18
    const val SECTION_GAP = 6
    /** Height reserved for a section that contains nothing. */
    const val EMPTY_SECTION_HEIGHT = 14
}

/**
 * Builds the view-model tree for one result snapshot and computes a stable
 * top-to-bottom layout. Deterministic: an unchanged prefix of the model keeps its
 * positions when new content is appended, so progressive updates do not shuffle
 * the existing map.
 */
object CanvasViewModelBuilder {

    fun build(
        result: FlowAnalysisResult,
        expandedNodes: Set<NodeId>,
        pinnedKeys: Set<String> = emptySet(),
    ): FrameVM? {
        val root = result.rootFrame ?: return null
        val rootVM = frameVM(result, root, expandedNodes, isRoot = true, pinnedKeys = pinnedKeys)
        layoutFrame(
            frame = rootVM,
            x = CanvasMetrics.CANVAS_MARGIN,
            y = CanvasMetrics.CANVAS_MARGIN,
            padding = CanvasMetrics.FRAME_PADDING,
            withHeader = true,
        )
        return rootVM
    }

    /**
     * The call nodes that have to be expanded for [target] to be on screen.
     *
     * A reason in the status summary points at a node that is usually deep — a
     * depth-limited call is by definition at the depth limit — and a link that
     * silently does nothing is worse than no link (`V0.3_SPEC.md` §7.3).
     */
    fun revealPath(result: FlowAnalysisResult, target: NodeId): Set<NodeId> {
        val frameOf = mutableMapOf<NodeId, FrameId>()
        val ownerOf = mutableMapOf<FrameId, NodeId>()
        for (frame in result.frames.values) {
            fun index(events: List<FlowNode>) {
                for (node in events) {
                    frameOf[node.id] = frame.id
                    node.targetFrameId?.let { ownerOf[it] = node.id }
                    node.branches.forEach { index(it.events) }
                }
            }
            index(frame.events)
        }
        val path = mutableSetOf<NodeId>()
        var frame = frameOf[target] ?: return path
        while (frame != result.rootFrame?.id) {
            val owner = ownerOf[frame] ?: break
            if (!path.add(owner)) break
            frame = frameOf[owner] ?: break
        }
        return path
    }

    /** All cards currently visible, in top-to-bottom layout order, for keyboard navigation. */
    fun visibleCards(root: FrameVM?): List<CardVM> {
        val out = mutableListOf<CardVM>()
        fun collectCards(cards: List<CardVM>) {
            for (card in cards) {
                out += card
                card.sections.forEach { collectCards(it.cards) }
                card.childFrame?.let { collectCards(it.cards) }
            }
        }
        root?.let { collectCards(it.cards) }
        return out
    }

    private fun frameVM(
        result: FlowAnalysisResult,
        frame: FlowFrame,
        expandedNodes: Set<NodeId>,
        isRoot: Boolean,
        pinnedKeys: Set<String>,
    ): FrameVM {
        val cards = frame.events.map { node ->
            cardVM(result, node, expandedNodes, frame.symbol.containerName, pinnedKeys)
        }
        return FrameVM(
            frameId = frame.id,
            title = frame.symbol.displayName,
            subtitle = listOfNotNull(
                frame.symbol.containerName,
                frame.symbol.languageId,
                FlowLensBundle.message("card.depth.label", frame.depth),
            ).joinToString(" · "),
            isRoot = isRoot,
            entryLocation = frame.entryLocation,
            cards = cards,
            pinned = frame.symbol.key in pinnedKeys,
        )
    }

    /** All visible frames (root plus expanded child frames), deepest last. */
    fun visibleFrames(root: FrameVM?): List<FrameVM> {
        val out = mutableListOf<FrameVM>()
        fun collect(frame: FrameVM) {
            out += frame
            frame.cards.forEach { it.childFrame?.let(::collect) }
        }
        root?.let(::collect)
        return out
    }

    private fun cardVM(
        result: FlowAnalysisResult,
        node: FlowNode,
        expandedNodes: Set<NodeId>,
        ownerType: String?,
        pinnedKeys: Set<String>,
    ): CardVM {
        val style = styleOf(node)
        val childFrame = node.targetFrameId?.let(result::frame)
        val expandable = childFrame != null && childFrame.events.isNotEmpty()
        val expanded = expandable && node.id in expandedNodes
        val sections = node.branches.map { branch ->
            SectionVM(
                title = sectionTitleOf(branch),
                cards = branch.events.map { event ->
                    cardVM(result, event, expandedNodes, ownerType, pinnedKeys)
                },
            )
        }
        val childVM = if (expanded && childFrame != null) {
            frameVM(result, childFrame, expandedNodes, isRoot = false, pinnedKeys = pinnedKeys)
        } else {
            // A collapsed child frame is not laid out at all, so a deep result
            // costs nothing until the user opens it.
            null
        }
        return CardVM(
            node = node,
            title = titleOf(node, ownerType),
            stateGlyph = stateGlyphOf(node, style),
            executionGlyph = executionGlyphOf(node),
            trailingNote = when {
                // A chosen continuation outranks the test badge here: it changes
                // what the body below the card is, which the reader must not miss.
                node.metadata[FlowMetadata.CHOSEN] != null ->
                    FlowLensBundle.message("card.badge.chosen", node.metadata[FlowMetadata.CHOSEN]!!)
                node.metadata[FlowMetadata.TEST_SOURCE] == "true" ->
                    FlowLensBundle.message("card.badge.test.source")
                else -> null
            },
            tooltip = tooltipOf(node),
            style = style,
            depthLabel = FlowLensBundle.message("card.depth.label", node.depth),
            boundaryBeforeCard = node.resolutionStatus == ResolutionStatus.EXTERNAL,
            // The ordinary connector claims "this runs next". That is untrue for a
            // conditional call (`V0.1_SPEC.md` §13) and equally untrue for a
            // goroutine, which may run at any time, or a deferred call, which runs
            // when the frame returns (§21 case R: a deferred call must not be
            // presented as an ordinary immediate call).
            dashedIncomingConnector = node.orderingStatus != OrderingStatus.DETERMINISTIC ||
                node.metadata[FlowMetadata.CONDITIONAL] == "true" ||
                node.executionMode != ExecutionMode.SYNC,
            expandable = expandable,
            expanded = expanded,
            pinned = node.targetSymbol?.key?.let { it in pinnedKeys } == true,
            chosenImplementation = node.metadata[FlowMetadata.CHOSEN],
            // The target frame exists but has not been analyzed yet: a transient
            // UI-only state that never counts against the node budget. Once the run
            // is terminal nothing is being resolved any more, so a frame left
            // unanalyzed by cancellation or truncation must not keep claiming
            // progress (REPO_LENS_LESSONS.md 3.6).
            resolving = childFrame != null && !childFrame.bodyComplete && !result.isTerminal,
            depthLimited = node.metadata[FlowMetadata.LIMIT] == FlowMetadata.LIMIT_DEPTH,
            callsInside = childFrame?.events?.size ?: 0,
            childFrame = childVM,
            sections = sections,
        )
    }

    /** `THEN`, `CASE 1`, `catch (IOException)`: the kind, plus its source label. */
    private fun sectionTitleOf(branch: FlowBranch): String {
        val kind = FlowLensBundle.message("branch.kind.${branch.kind.name}")
        return branch.label?.let { "$kind $it" } ?: kind
    }

    private fun styleOf(node: FlowNode): CardStyle = when {
        node.kind == FlowNodeKind.ENTRY -> CardStyle.ENTRY
        node.isStructure -> CardStyle.STRUCTURE
        node.kind == FlowNodeKind.RETURN || node.kind == FlowNodeKind.THROW -> CardStyle.TERMINATOR
        node.kind == FlowNodeKind.CYCLE -> CardStyle.CYCLE
        node.kind == FlowNodeKind.LIMIT -> CardStyle.LIMIT
        node.resolutionStatus == ResolutionStatus.UNRESOLVED -> CardStyle.UNRESOLVED
        node.resolutionStatus == ResolutionStatus.EXTERNAL -> CardStyle.EXTERNAL
        node.resolutionStatus == ResolutionStatus.BUILT_IN -> CardStyle.BUILT_IN
        node.dispatchConfidence == DispatchConfidence.AMBIGUOUS -> CardStyle.AMBIGUOUS
        node.dispatchConfidence == DispatchConfidence.DECLARED_TARGET -> CardStyle.DECLARED_TARGET
        else -> CardStyle.PROJECT_CALL
    }

    /**
     * The callable's name, qualified by its type only when that type differs from
     * the one whose body we are reading. Repeating the enclosing type on every
     * card costs a line and says nothing; showing it when it changes turns it
     * into a signal that the call leaves the current type.
     */
    private fun titleOf(node: FlowNode, ownerType: String?): String = when (node.kind) {
        FlowNodeKind.CONDITION, FlowNodeKind.SWITCH, FlowNodeKind.LOOP, FlowNodeKind.TRY ->
            structureTitleOf(node)
        FlowNodeKind.RETURN, FlowNodeKind.THROW -> {
            // `return;` and `return total();` stop the path for different
            // reasons, so the card says which.
            val kind = FlowLensBundle.message("card.kind.${node.kind.name}")
            node.sourceSummary?.let { "$kind $it" } ?: kind
        }
        FlowNodeKind.LIMIT -> FlowLensBundle.message("card.limit.node")
        FlowNodeKind.CYCLE -> FlowLensBundle.message(
            "card.cycle.label", node.targetSymbol?.displayName ?: "?",
        )
        else -> {
            val symbol = node.targetSymbol ?: return "?"
            val container = symbol.containerName
            if (container != null && container != ownerType) {
                "$container.${symbol.displayName}"
            } else {
                symbol.displayName
            }
        }
    }

    /**
     * A structure names what it decides on: the condition, the switch subject,
     * the loop header. A `select` says so, and a `do`-`while` says its body runs
     * at least once, because neither is visible from the summary alone.
     */
    private fun structureTitleOf(node: FlowNode): String {
        val kindKey = when {
            node.metadata[FlowMetadata.SELECT] == "true" -> "card.kind.SELECT"
            node.metadata[FlowMetadata.LOOP_RUNS_AT_LEAST_ONCE] == "true" -> "card.kind.LOOP_ONCE"
            else -> "card.kind.${node.kind.name}"
        }
        val kind = FlowLensBundle.message(kindKey)
        return node.sourceSummary?.let { "$kind $it" } ?: kind
    }

    /**
     * Card style already carries external, unresolved, and ambiguous states
     * through the box treatment, so only what the box cannot say gets a glyph.
     */
    private fun stateGlyphOf(node: FlowNode, style: CardStyle): String? = when {
        node.kind == FlowNodeKind.CONDITION -> "◆"
        node.kind == FlowNodeKind.SWITCH -> "◈"
        node.kind == FlowNodeKind.LOOP -> "↻"
        node.kind == FlowNodeKind.TRY -> "⛨"
        node.kind == FlowNodeKind.RETURN -> "◀"
        node.kind == FlowNodeKind.THROW -> "✖"
        style == CardStyle.UNRESOLVED -> "?"
        style == CardStyle.AMBIGUOUS -> "◇"
        style == CardStyle.DECLARED_TARGET -> "◆"
        node.metadata[FlowMetadata.ORIGIN] == SourceOrigin.SYNTHETIC.name -> "⊘"
        node.kind == FlowNodeKind.CONSTRUCTOR -> "+"
        else -> null
    }

    private fun executionGlyphOf(node: FlowNode): String? = when (node.executionMode) {
        ExecutionMode.GOROUTINE -> "⚡"
        ExecutionMode.DEFERRED -> "↩"
        else -> null
    }

    /** The full state in words, since the card itself only has room for glyphs. */
    private fun tooltipOf(node: FlowNode): String = buildList {
        node.targetSymbol?.let { symbol ->
            add(listOfNotNull(symbol.containerName, symbol.displayName).joinToString("."))
            add(symbol.languageId)
        }
        node.resolutionStatus?.let { add(FlowLensBundle.message("enum.resolution.${it.name}")) }
        node.dispatchConfidence?.let { add(FlowLensBundle.message("enum.dispatch.${it.name}")) }
        if (node.executionMode != ExecutionMode.SYNC) {
            add(FlowLensBundle.message("enum.execution.${node.executionMode.name}"))
        }
        node.metadata[FlowMetadata.CHOSEN]?.let {
            add(FlowLensBundle.message("card.tooltip.chosen", it))
        }
        node.metadata[FlowMetadata.ORIGIN]?.let { add(FlowLensBundle.message("enum.origin.$it")) }
        if (node.metadata[FlowMetadata.CONDITIONAL] == "true") {
            add(FlowLensBundle.message("details.conditional.hint"))
        }
        if (node.metadata[FlowMetadata.LIMIT] == FlowMetadata.LIMIT_DEPTH) {
            add(FlowLensBundle.message("details.limit.depth"))
        }
    }.joinToString(" · ")

    // ---- layout ----

    /**
     * Widths are decided before positioning so every card in one frame is the
     * same width and an expanded call is wide enough to hold its body.
     */
    private fun measureFrameWidth(frame: FrameVM): Int =
        frame.cards.maxOfOrNull(::measureCardWidth) ?: CanvasMetrics.CARD_WIDTH

    private fun measureCardWidth(card: CardVM): Int {
        val body = card.childFrame?.let { measureFrameWidth(it) }
        val sections = card.sections
            .mapNotNull { section -> section.cards.maxOfOrNull(::measureCardWidth) }
            .maxOrNull()
        val inner = listOfNotNull(body, sections).maxOrNull() ?: return CanvasMetrics.CARD_WIDTH
        return inner + 2 * CanvasMetrics.CHILD_INDENT
    }

    /**
     * Assigns bounds top to bottom and returns the frame's occupied rectangle.
     * An expanded call lays its body out inside the call's own container, so the
     * parent's sequence stays one column of boxes.
     */
    private fun layoutFrame(
        frame: FrameVM,
        x: Int,
        y: Int,
        padding: Int,
        withHeader: Boolean,
    ): Rectangle {
        val contentWidth = measureFrameWidth(frame)
        val top = y + padding + if (withHeader) CanvasMetrics.FRAME_HEADER else 0
        val bottom = layoutCards(frame.cards, x + padding, top, contentWidth)
        frame.bounds = Rectangle(x, y, contentWidth + padding * 2, bottom - y + padding)
        return frame.bounds
    }

    /**
     * Lays a structure's sections inside its container: a label row, then the
     * section's own cards indented under it. An empty section still occupies a
     * row so "this case does nothing" stays visible.
     */
    private fun layoutSections(card: CardVM, cardX: Int, headerBottom: Int, width: Int): Int {
        var cursorY = headerBottom + CanvasMetrics.NESTED_TOP_GAP
        val innerX = cardX + CanvasMetrics.CHILD_INDENT
        val innerWidth = width - 2 * CanvasMetrics.CHILD_INDENT
        for (section in card.sections) {
            val sectionTop = cursorY
            section.titleBounds =
                Rectangle(innerX, cursorY, innerWidth, CanvasMetrics.SECTION_TITLE_HEIGHT)
            cursorY += CanvasMetrics.SECTION_TITLE_HEIGHT
            cursorY = if (section.cards.isEmpty()) {
                cursorY + CanvasMetrics.EMPTY_SECTION_HEIGHT
            } else {
                layoutCards(section.cards, innerX, cursorY, innerWidth)
            }
            section.bounds = Rectangle(innerX, sectionTop, innerWidth, cursorY - sectionTop)
            cursorY += CanvasMetrics.SECTION_GAP
        }
        return cursorY - CanvasMetrics.SECTION_GAP + CanvasMetrics.NESTED_BOTTOM_PAD
    }

    /**
     * Lays out one sequence of cards top to bottom and returns where it ends.
     * A frame's body and a branch section are the same thing at this level, so
     * both use it.
     */
    private fun layoutCards(cards: List<CardVM>, cardX: Int, top: Int, contentWidth: Int): Int {
        var cursorY = top
        cards.forEachIndexed { index, card ->
            if (index > 0) {
                cursorY += if (card.boundaryBeforeCard) {
                    CanvasMetrics.BOUNDARY_GAP
                } else {
                    CanvasMetrics.CONNECTOR_GAP
                }
            }
            card.bounds = Rectangle(cardX, cursorY, contentWidth, CanvasMetrics.CARD_HEIGHT)
            cursorY += CanvasMetrics.CARD_HEIGHT

            val body = card.childFrame
            if (card.sections.isNotEmpty()) {
                cursorY = layoutSections(card, cardX, cursorY, contentWidth)
                card.containerBounds =
                    Rectangle(cardX, card.bounds.y, contentWidth, cursorY - card.bounds.y)
            } else if (body == null) {
                card.containerBounds = Rectangle(card.bounds)
            } else {
                // The body is inset by CHILD_INDENT on both sides, which is why
                // the container was measured that much wider than its content.
                val bodyRect = layoutFrame(
                    frame = body,
                    x = cardX + CanvasMetrics.CHILD_INDENT,
                    y = cursorY + CanvasMetrics.NESTED_TOP_GAP,
                    padding = 0,
                    withHeader = false,
                )
                cursorY = bodyRect.y + bodyRect.height + CanvasMetrics.NESTED_BOTTOM_PAD
                card.containerBounds =
                    Rectangle(cardX, card.bounds.y, contentWidth, cursorY - card.bounds.y)
            }
            // A call that could not be entered because of the depth limit keeps an
            // explicit continuation marker: a connector never just stops.
            if (card.depthLimited) cursorY += CanvasMetrics.LIMIT_STUB_HEIGHT
        }
        return cursorY
    }
}
