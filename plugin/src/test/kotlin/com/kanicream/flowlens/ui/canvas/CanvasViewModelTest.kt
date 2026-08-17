package com.kanicream.flowlens.ui.canvas

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.core.engine.FlowEventSpec
import com.kanicream.flowlens.core.engine.FlowModelBuilder
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.NodeId
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId

/**
 * Flow Canvas view-model/layout behavior (TEST_STRATEGY.md Layer D) without pixel
 * assertions: expansion policy, distinct semantic treatments, connector styling,
 * layout stability, and the ~100-node feasibility budget.
 */
class CanvasViewModelTest : BasePlatformTestCase() {

    private fun symbol(name: String) = FlowSymbol("java", "$name()", "Owner", "java:Owner#$name")

    private fun spec(
        name: String,
        resolution: ResolutionStatus = ResolutionStatus.PROJECT_LOCAL,
        dispatch: DispatchConfidence = DispatchConfidence.EXACT,
        execution: ExecutionMode = ExecutionMode.SYNC,
        ordering: OrderingStatus = OrderingStatus.DETERMINISTIC,
        kind: FlowNodeKind = FlowNodeKind.CALL,
    ) = FlowEventSpec(
        kind = kind,
        callSiteLocation = null,
        targetSymbol = symbol(name),
        resolutionStatus = resolution,
        dispatchConfidence = dispatch,
        executionMode = execution,
        orderingStatus = ordering,
    )

    fun `test root frame expanded and child frames collapsed by default`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        val call = b.addEvent(root, spec("child"))!!
        val childFrame = b.openChildFrame(root, call, symbol("child"), null)
        b.addEvent(childFrame, spec("inner"))
        val result = b.snapshot(FlowResultStatus.COMPLETED)

        val vm = CanvasViewModelBuilder.build(result, expandedNodes = emptySet())!!
        assertTrue(vm.isRoot)
        assertEquals(1, vm.cards.size)
        val card = vm.cards.single()
        assertTrue(card.expandable)
        assertFalse(card.expanded)
        assertNull("collapsed child frame must not be laid out", card.childFrame)
        assertEquals(1, card.callsInside)
    }

    fun `test expanding a card lays out its nested frame below the card`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        val call = b.addEvent(root, spec("child"))!!
        val childFrame = b.openChildFrame(root, call, symbol("child"), null)
        b.addEvent(childFrame, spec("inner"))
        val result = b.snapshot(FlowResultStatus.COMPLETED)

        val vm = CanvasViewModelBuilder.build(result, expandedNodes = setOf(call))!!
        val card = vm.cards.single()
        assertTrue(card.expanded)
        val nested = card.childFrame!!
        assertEquals(1, nested.cards.size)
        assertTrue("nested frame starts below its owning card", nested.bounds.y > card.bounds.y)
        assertTrue("nested frame is indented", nested.bounds.x > vm.bounds.x)
        assertTrue("nested content stays inside parent bounds vertically",
            nested.bounds.y + nested.bounds.height <= vm.bounds.y + vm.bounds.height)
    }

    fun `test semantic states map to distinct visual treatments`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        b.addEvent(root, spec("exact"))
        b.addEvent(root, spec("declared", dispatch = DispatchConfidence.DECLARED_TARGET))
        b.addEvent(root, spec("ambiguous", dispatch = DispatchConfidence.AMBIGUOUS))
        b.addEvent(root, spec("unresolved", resolution = ResolutionStatus.UNRESOLVED,
            dispatch = DispatchConfidence.UNKNOWN))
        b.addEvent(root, spec("external", resolution = ResolutionStatus.EXTERNAL))
        b.addEvent(root, spec("cycle", kind = FlowNodeKind.CYCLE))
        b.addLimitEvent(root)
        val result = b.snapshot(FlowResultStatus.TRUNCATED)

        val styles = CanvasViewModelBuilder.build(result, emptySet())!!.cards.map { it.style }
        assertEquals(
            listOf(
                CardStyle.PROJECT_CALL,
                CardStyle.DECLARED_TARGET,
                CardStyle.AMBIGUOUS,
                CardStyle.UNRESOLVED,
                CardStyle.EXTERNAL,
                CardStyle.CYCLE,
                CardStyle.LIMIT,
            ),
            styles,
        )
        assertEquals(styles.size, styles.distinct().size)
    }

    fun `test external calls get a boundary marker and goroutine defer get badges`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        b.addEvent(root, spec("post", resolution = ResolutionStatus.EXTERNAL))
        b.addEvent(root, spec("notify", execution = ExecutionMode.GOROUTINE))
        b.addEvent(root, spec("cleanup", execution = ExecutionMode.DEFERRED))
        val cards = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.COMPLETED), emptySet())!!.cards
        assertTrue(cards[0].boundaryBeforeCard)
        assertFalse(cards[1].boundaryBeforeCard)
        assertTrue(cards[1].badges.isNotEmpty())
        assertTrue(cards[2].badges.isNotEmpty())
        assertTrue(cards[1].badges.single() != cards[2].badges.single())
    }

    fun `test non deterministic ordering uses a different connector treatment`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        b.addEvent(root, spec("a"))
        b.addEvent(root, spec("b", ordering = OrderingStatus.APPROXIMATE))
        val cards = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.COMPLETED), emptySet())!!.cards
        assertFalse(cards[0].dashedIncomingConnector)
        assertTrue(cards[1].dashedIncomingConnector)
    }

    fun `test appending events keeps existing card positions stable`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        b.addEvent(root, spec("a"))
        b.addEvent(root, spec("b"))
        val early = CanvasViewModelBuilder.build(b.snapshot(FlowResultStatus.RUNNING), emptySet())!!
        b.addEvent(root, spec("c"))
        val late = CanvasViewModelBuilder.build(b.snapshot(FlowResultStatus.COMPLETED), emptySet())!!
        assertEquals(early.cards[0].bounds, late.cards[0].bounds)
        assertEquals(early.cards[1].bounds, late.cards[1].bounds)
        assertTrue(late.cards[2].bounds.y > late.cards[1].bounds.y)
    }

    fun `test one hundred nodes lay out without overlap in bounded time`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(maxNodes = 100), 0)
        val root = b.openRootFrame(symbol("root"), null)
        var frame = root
        var added = 0
        // Mix of flat events and nested frames to exercise recursion.
        outer@ while (true) {
            repeat(8) {
                if (b.addEvent(frame, spec("call$added")) == null) return@repeat
                added += 1
            }
            val call = b.addEvent(frame, spec("nest$added")) ?: break@outer
            added += 1
            frame = b.openChildFrame(frame, call, symbol("nest$added"), null)
        }
        b.addLimitEvent(frame)
        val result = b.snapshot(FlowResultStatus.TRUNCATED)
        assertEquals(100, result.nodeCount)

        val expandAll = result.frames.values
            .flatMap { it.events }
            .filter { it.targetFrameId != null }
            .map { it.id }
            .toSet()
        val start = System.nanoTime()
        val vm = CanvasViewModelBuilder.build(result, expandAll)!!
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val cards = CanvasViewModelBuilder.visibleCards(vm)
        assertEquals(100, cards.size)
        assertTrue("layout of 100 nodes should be fast, took ${elapsedMs}ms", elapsedMs < 500)
        // No two cards overlap.
        for (i in cards.indices) {
            for (j in i + 1 until cards.size) {
                assertFalse(
                    "cards $i and $j overlap",
                    cards[i].bounds.intersects(cards[j].bounds.let {
                        java.awt.Rectangle(it.x, it.y, it.width, it.height)
                    }),
                )
            }
        }
        // Keyboard order is top-to-bottom.
        assertEquals(cards.map { it.bounds.y }.sorted(), cards.map { it.bounds.y })
    }

    fun `test conditional calls do not get the certain connector`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        b.addEvent(root, spec("always"))
        b.addEvent(
            root,
            spec("maybe").copy(
                metadata = mapOf(com.kanicream.flowlens.service.FlowMetadata.CONDITIONAL to "true"),
            ),
        )
        val cards = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.COMPLETED), emptySet())!!.cards
        assertFalse(cards[0].dashedIncomingConnector)
        assertTrue("a call that may not execute must not imply a proven path", cards[1].dashedIncomingConnector)
    }

    fun `test a queued child frame renders as resolving and is not yet expandable`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        val call = b.addEvent(root, spec("child"))!!
        val child = b.openChildFrame(root, call, symbol("child"), null)
        val beforeAnalysis = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.RUNNING), emptySet())!!.cards.single()
        assertTrue(beforeAnalysis.resolving)
        assertFalse(beforeAnalysis.expandable)

        b.addEvent(child, spec("inner"))
        b.markFrameComplete(child)
        val afterAnalysis = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.COMPLETED), emptySet())!!.cards.single()
        assertFalse(afterAnalysis.resolving)
        assertTrue(afterAnalysis.expandable)
        assertEquals(1, afterAnalysis.callsInside)
    }

    fun `test a terminated run stops claiming that frames are still resolving`() {
        // Regression: a run cancelled or truncated while child frames were queued
        // left those cards showing a resolving indicator forever, so a stopped
        // analysis looked like it was still working.
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        val call = b.addEvent(root, spec("child"))!!
        b.openChildFrame(root, call, symbol("child"), null)

        val whileRunning = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.RUNNING), emptySet())!!.cards.single()
        assertTrue(whileRunning.resolving)

        for (terminal in listOf(
            FlowResultStatus.CANCELLED,
            FlowResultStatus.TRUNCATED,
            FlowResultStatus.STALE,
            FlowResultStatus.FAILED,
            FlowResultStatus.COMPLETED,
        )) {
            val card = CanvasViewModelBuilder
                .build(b.snapshot(terminal), emptySet())!!.cards.single()
            assertFalse("still resolving after $terminal", card.resolving)
        }
    }

    fun `test depth limited calls reserve space for an explicit continuation marker`() {
        val limited = FlowEventSpec(
            kind = FlowNodeKind.CALL,
            callSiteLocation = null,
            targetSymbol = symbol("deep"),
            resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
            dispatchConfidence = DispatchConfidence.EXACT,
            metadata = mapOf(
                com.kanicream.flowlens.service.FlowMetadata.LIMIT to
                    com.kanicream.flowlens.service.FlowMetadata.LIMIT_DEPTH,
            ),
        )
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        b.addEvent(root, limited)
        b.addEvent(root, spec("next"))
        val cards = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.COMPLETED), emptySet())!!.cards
        assertTrue(cards[0].depthLimited)
        assertFalse(cards[1].depthLimited)
        assertTrue(
            "the marker must occupy layout space below the card",
            cards[0].occupiedBottom >= cards[0].bounds.y + cards[0].bounds.height +
                CanvasMetrics.LIMIT_STUB_HEIGHT,
        )
        assertTrue("the next card starts below the marker", cards[1].bounds.y > cards[0].occupiedBottom)
    }

    fun `test frames expose entry locations and clickable headers for navigation`() {
        // Regression (sandbox feedback): double-clicking the root/entry header must
        // navigate back to the entry declaration (V0.1_SPEC.md section 18).
        val entry = com.kanicream.flowlens.core.model.FlowLocation(
            com.kanicream.flowlens.core.model.LocationId(1), "Sample.java", 16,
        )
        val childEntry = com.kanicream.flowlens.core.model.FlowLocation(
            com.kanicream.flowlens.core.model.LocationId(2), "Sample.java", 29,
        )
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), entry)
        val call = b.addEvent(root, spec("child"))!!
        val child = b.openChildFrame(root, call, symbol("child"), childEntry)
        b.addEvent(child, spec("inner"))
        val result = b.snapshot(FlowResultStatus.COMPLETED)

        val vm = CanvasViewModelBuilder.build(result, setOf(call))!!
        val frames = CanvasViewModelBuilder.visibleFrames(vm)
        assertEquals(2, frames.size)
        assertEquals(entry, frames[0].entryLocation)
        assertEquals(childEntry, frames[1].entryLocation)
        for (frame in frames) {
            assertEquals(frame.bounds.x, frame.headerBounds.x)
            assertEquals(frame.bounds.y, frame.headerBounds.y)
            assertEquals(CanvasMetrics.FRAME_HEADER, frame.headerBounds.height)
        }
        // Collapsed view hides the child frame from hit testing.
        assertEquals(
            1,
            CanvasViewModelBuilder.visibleFrames(
                CanvasViewModelBuilder.build(result, emptySet()),
            ).size,
        )
    }

    fun `test selection list excludes cards hidden by collapsed parents`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("root"), null)
        val call = b.addEvent(root, spec("child"))!!
        val child = b.openChildFrame(root, call, symbol("child"), null)
        val innerId: NodeId = b.addEvent(child, spec("inner"))!!
        val result = b.snapshot(FlowResultStatus.COMPLETED)
        val collapsed = CanvasViewModelBuilder.visibleCards(
            CanvasViewModelBuilder.build(result, emptySet()),
        )
        assertEquals(1, collapsed.size)
        val expanded = CanvasViewModelBuilder.visibleCards(
            CanvasViewModelBuilder.build(result, setOf(call)),
        )
        assertEquals(2, expanded.size)
        assertTrue(expanded.any { it.nodeId == innerId })
    }
}
