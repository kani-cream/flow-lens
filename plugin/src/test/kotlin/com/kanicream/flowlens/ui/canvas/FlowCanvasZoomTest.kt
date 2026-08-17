package com.kanicream.flowlens.ui.canvas

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.core.engine.FlowEventSpec
import com.kanicream.flowlens.core.engine.FlowModelBuilder
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId

/**
 * Zoom controls (`VISUAL_DESIGN.md` §20): a flow can be enlarged and shrunk, not
 * only fitted, and the current level is observable so the toolbar can show it.
 */
class FlowCanvasZoomTest : BasePlatformTestCase() {

    private fun canvasWithResult(): FlowCanvas {
        val symbol = FlowSymbol("java", "root()", "Owner", "java:Owner#root")
        val builder = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = builder.openRootFrame(symbol, null)
        builder.addEvent(
            root,
            FlowEventSpec(
                kind = FlowNodeKind.CALL,
                callSiteLocation = null,
                targetSymbol = FlowSymbol("java", "a()", "Owner", "java:Owner#a"),
                resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
                dispatchConfidence = DispatchConfidence.EXACT,
            ),
        )
        return FlowCanvas().apply { setResult(builder.snapshot(FlowResultStatus.COMPLETED)) }
    }

    fun `test a new canvas starts at one hundred percent`() {
        assertEquals(100, canvasWithResult().zoomPercent)
    }

    fun `test zooming in and out changes the level and reset returns to one hundred`() {
        val canvas = canvasWithResult()
        canvas.zoomIn()
        val enlarged = canvas.zoomPercent
        assertTrue("zoom in must enlarge, was $enlarged", enlarged > 100)

        canvas.zoomOut()
        assertEquals("one step back returns to the starting level", 100, canvas.zoomPercent)

        canvas.zoomOut()
        assertTrue(canvas.zoomPercent < 100)
        canvas.resetZoom()
        assertEquals(100, canvas.zoomPercent)
    }

    fun `test zoom is clamped to the supported range`() {
        val canvas = canvasWithResult()
        repeat(50) { canvas.zoomIn() }
        val maxPercent = canvas.zoomPercent
        canvas.zoomIn()
        assertEquals("zoom must stop at the maximum", maxPercent, canvas.zoomPercent)

        repeat(80) { canvas.zoomOut() }
        val minPercent = canvas.zoomPercent
        canvas.zoomOut()
        assertEquals("zoom must stop at the minimum", minPercent, canvas.zoomPercent)
        assertTrue(minPercent in 1..99)
        assertTrue(maxPercent in 101..1000)
    }

    fun `test zoom changes notify the toolbar indicator`() {
        val canvas = canvasWithResult()
        var notifications = 0
        canvas.onZoomChanged = { notifications += 1 }
        canvas.zoomIn()
        canvas.zoomOut()
        assertEquals(2, notifications)

        // A no-op zoom at the limit must not spam the indicator.
        repeat(60) { canvas.zoomOut() }
        val settled = notifications
        canvas.zoomOut()
        assertEquals(settled, notifications)
    }

    fun `test a new run resets the zoom`() {
        val canvas = canvasWithResult()
        canvas.zoomIn()
        assertTrue(canvas.zoomPercent > 100)

        val other = FlowModelBuilder(RunId(2), FlowLimits(), 0)
        other.openRootFrame(FlowSymbol("java", "other()", null, "java:Other#other"), null)
        canvas.setResult(other.snapshot(FlowResultStatus.COMPLETED))
        assertEquals(100, canvas.zoomPercent)
    }
}
