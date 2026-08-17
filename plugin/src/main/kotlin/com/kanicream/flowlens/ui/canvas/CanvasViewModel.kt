package com.kanicream.flowlens.ui.canvas

import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowAnalysisResult
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
    val title: String,
    val subtitle: String?,
    val badges: List<String>,
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

    val expandedInline: Boolean get() = childFrame != null

    /** Bottom of everything this card owns, including its limit marker. */
    val occupiedBottom: Int
        get() = containerBounds.y + containerBounds.height +
            if (depthLimited) CanvasMetrics.LIMIT_STUB_HEIGHT else 0
}

/** One rendered frame container (analyzed callable body). */
class FrameVM(
    val frameId: com.kanicream.flowlens.core.model.FrameId,
    val title: String,
    val subtitle: String,
    val isRoot: Boolean,
    val entryLocation: com.kanicream.flowlens.core.model.FlowLocation?,
    val cards: List<CardVM>,
) {
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
    const val CARD_HEIGHT = 46
    const val CARD_BADGE_EXTRA = 18
    const val CONNECTOR_GAP = 26
    const val BOUNDARY_GAP = 40
    const val FRAME_PADDING = 14
    const val FRAME_HEADER = 34
    const val CHILD_INDENT = 18
    const val CANVAS_MARGIN = 24
    const val LIMIT_STUB_HEIGHT = 20

    /** Space between an expanded call's header and the body drawn inside it. */
    const val NESTED_TOP_GAP = 10
    const val NESTED_BOTTOM_PAD = 12
}

/**
 * Builds the view-model tree for one result snapshot and computes a stable
 * top-to-bottom layout. Deterministic: an unchanged prefix of the model keeps its
 * positions when new content is appended, so progressive updates do not shuffle
 * the existing map.
 */
object CanvasViewModelBuilder {

    fun build(result: FlowAnalysisResult, expandedNodes: Set<NodeId>): FrameVM? {
        val root = result.rootFrame ?: return null
        val rootVM = frameVM(result, root, expandedNodes, isRoot = true)
        layoutFrame(
            frame = rootVM,
            x = CanvasMetrics.CANVAS_MARGIN,
            y = CanvasMetrics.CANVAS_MARGIN,
            padding = CanvasMetrics.FRAME_PADDING,
            withHeader = true,
        )
        return rootVM
    }

    /** All cards currently visible, in top-to-bottom layout order, for keyboard navigation. */
    fun visibleCards(root: FrameVM?): List<CardVM> {
        val out = mutableListOf<CardVM>()
        fun collect(frame: FrameVM) {
            for (card in frame.cards) {
                out += card
                card.childFrame?.let(::collect)
            }
        }
        root?.let(::collect)
        return out
    }

    private fun frameVM(
        result: FlowAnalysisResult,
        frame: FlowFrame,
        expandedNodes: Set<NodeId>,
        isRoot: Boolean,
    ): FrameVM {
        val cards = frame.events.map { node -> cardVM(result, node, expandedNodes) }
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
    ): CardVM {
        val style = styleOf(node)
        val childFrame = node.targetFrameId?.let(result::frame)
        val expandable = childFrame != null && childFrame.events.isNotEmpty()
        val expanded = expandable && node.id in expandedNodes
        val childVM = if (expanded && childFrame != null) {
            frameVM(result, childFrame, expandedNodes, isRoot = false)
        } else {
            // A collapsed child frame is not laid out at all, so a deep result
            // costs nothing until the user opens it.
            null
        }
        return CardVM(
            node = node,
            title = titleOf(node),
            subtitle = node.targetSymbol?.containerName,
            badges = badgesOf(node, style),
            style = style,
            depthLabel = FlowLensBundle.message("card.depth.label", node.depth),
            boundaryBeforeCard = node.resolutionStatus == ResolutionStatus.EXTERNAL,
            // A conditional call is not a proven continuation, so it must not get
            // the ordinary certain connector either (`V0.1_SPEC.md` §13).
            dashedIncomingConnector = node.orderingStatus != OrderingStatus.DETERMINISTIC ||
                node.metadata[FlowMetadata.CONDITIONAL] == "true",
            expandable = expandable,
            expanded = expanded,
            // The target frame exists but has not been analyzed yet: a transient
            // UI-only state that never counts against the node budget. Once the run
            // is terminal nothing is being resolved any more, so a frame left
            // unanalyzed by cancellation or truncation must not keep claiming
            // progress (REPO_LENS_LESSONS.md 3.6).
            resolving = childFrame != null && !childFrame.bodyComplete && !result.isTerminal,
            depthLimited = node.metadata[FlowMetadata.LIMIT] == FlowMetadata.LIMIT_DEPTH,
            callsInside = childFrame?.events?.size ?: 0,
            childFrame = childVM,
        )
    }

    private fun styleOf(node: FlowNode): CardStyle = when {
        node.kind == FlowNodeKind.ENTRY -> CardStyle.ENTRY
        node.kind == FlowNodeKind.CYCLE -> CardStyle.CYCLE
        node.kind == FlowNodeKind.LIMIT -> CardStyle.LIMIT
        node.resolutionStatus == ResolutionStatus.UNRESOLVED -> CardStyle.UNRESOLVED
        node.resolutionStatus == ResolutionStatus.EXTERNAL -> CardStyle.EXTERNAL
        node.resolutionStatus == ResolutionStatus.BUILT_IN -> CardStyle.BUILT_IN
        node.dispatchConfidence == DispatchConfidence.AMBIGUOUS -> CardStyle.AMBIGUOUS
        node.dispatchConfidence == DispatchConfidence.DECLARED_TARGET -> CardStyle.DECLARED_TARGET
        else -> CardStyle.PROJECT_CALL
    }

    private fun titleOf(node: FlowNode): String = when (node.kind) {
        FlowNodeKind.LIMIT -> FlowLensBundle.message("card.limit.node")
        FlowNodeKind.CYCLE -> FlowLensBundle.message(
            "card.cycle.label", node.targetSymbol?.displayName ?: "?",
        )
        else -> node.targetSymbol?.displayName ?: "?"
    }

    private fun badgesOf(node: FlowNode, style: CardStyle): List<String> = buildList {
        when (style) {
            CardStyle.DECLARED_TARGET -> add(FlowLensBundle.message("card.badge.declared.target"))
            CardStyle.AMBIGUOUS -> add(FlowLensBundle.message("card.badge.ambiguous"))
            CardStyle.UNRESOLVED -> add(FlowLensBundle.message("card.badge.unresolved"))
            CardStyle.EXTERNAL -> add(FlowLensBundle.message("card.badge.external"))
            CardStyle.BUILT_IN -> add(FlowLensBundle.message("card.badge.built.in"))
            else -> Unit
        }
        if (node.kind == FlowNodeKind.CONSTRUCTOR) {
            add(FlowLensBundle.message("card.badge.constructor"))
        }
        when (node.executionMode) {
            ExecutionMode.GOROUTINE -> add(FlowLensBundle.message("card.badge.goroutine"))
            ExecutionMode.DEFERRED -> add(FlowLensBundle.message("card.badge.deferred"))
            else -> Unit
        }
        if (node.metadata[FlowMetadata.LIMIT] == FlowMetadata.LIMIT_DEPTH) {
            add(FlowLensBundle.message("card.limit.depth"))
        }
        if (node.metadata[FlowMetadata.TEST_SOURCE] == "true") {
            add(FlowLensBundle.message("card.badge.test.source"))
        }
        // A generated member has no authored body to open, so say why rather than
        // letting the card look like an ordinary call that simply has no calls.
        if (node.metadata[FlowMetadata.ORIGIN] == SourceOrigin.SYNTHETIC.name) {
            add(FlowLensBundle.message("card.badge.generated"))
        }
    }

    // ---- layout ----

    /**
     * Widths are decided before positioning so every card in one frame is the
     * same width and an expanded call is wide enough to hold its body.
     */
    private fun measureFrameWidth(frame: FrameVM): Int =
        frame.cards.maxOfOrNull(::measureCardWidth) ?: CanvasMetrics.CARD_WIDTH

    private fun measureCardWidth(card: CardVM): Int =
        card.childFrame
            ?.let { measureFrameWidth(it) + 2 * CanvasMetrics.CHILD_INDENT }
            ?: CanvasMetrics.CARD_WIDTH

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
        val cardX = x + padding
        var cursorY = y + padding + if (withHeader) CanvasMetrics.FRAME_HEADER else 0
        frame.cards.forEachIndexed { index, card ->
            if (index > 0) {
                cursorY += if (card.boundaryBeforeCard) {
                    CanvasMetrics.BOUNDARY_GAP
                } else {
                    CanvasMetrics.CONNECTOR_GAP
                }
            }
            val headerHeight = CanvasMetrics.CARD_HEIGHT +
                if (card.badges.isEmpty()) 0 else CanvasMetrics.CARD_BADGE_EXTRA
            card.bounds = Rectangle(cardX, cursorY, contentWidth, headerHeight)
            cursorY += headerHeight

            val body = card.childFrame
            if (body == null) {
                card.containerBounds = card.bounds
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
        frame.bounds = Rectangle(x, y, contentWidth + padding * 2, cursorY - y + padding)
        return frame.bounds
    }
}
