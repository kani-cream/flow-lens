package com.kanicream.flowlens.ui.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fit-to-view arithmetic. "Fit" means the whole flow ends up inside the
 * viewport — not "shrink by some factor" — so the rule must be idempotent and
 * independent of the zoom the user happens to be at.
 */
class CanvasZoomTest {

    private fun fit(
        contentW: Int,
        contentH: Int,
        viewW: Int,
        viewH: Int,
        uiScale: Double = 1.0,
    ) = CanvasZoom.fitZoom(contentW, contentH, viewW, viewH, uiScale)

    @Test
    fun `content twice the viewport fits at half zoom`() {
        assertEquals(0.5, fit(1000, 1000, 500, 500)!!, 1e-9)
    }

    @Test
    fun `the tighter dimension decides the fit`() {
        // Height needs 0.25, width only 0.5: the map must fit in both directions.
        assertEquals(0.25, fit(1000, 2000, 500, 500)!!, 1e-9)
    }

    @Test
    fun `fitting is idempotent and does not depend on the current zoom`() {
        // Regression: the old implementation multiplied the current zoom by the
        // ratio, so pressing Fit twice kept shrinking the map.
        val first = fit(1000, 1000, 500, 500)!!
        val second = fit(1000, 1000, 500, 500)!!
        assertEquals(first, second, 1e-9)
    }

    @Test
    fun `a small flow is not magnified past one hundred percent`() {
        assertEquals(1.0, fit(100, 100, 1000, 1000)!!, 1e-9)
    }

    @Test
    fun `an enormous flow stops at the minimum zoom`() {
        assertEquals(CanvasZoom.MIN, fit(100_000, 100_000, 500, 500)!!, 1e-9)
    }

    @Test
    fun `the ui scale is part of the content's pixel size`() {
        // At 2x UI scale the same logical content occupies twice the pixels, so
        // it must be zoomed out twice as far to fit.
        val normal = fit(1000, 1000, 1000, 1000, uiScale = 1.0)!!
        val hidpi = fit(1000, 1000, 1000, 1000, uiScale = 2.0)!!
        assertEquals(1.0, normal, 1e-9)
        assertEquals(0.5, hidpi, 1e-9)
    }

    @Test
    fun `degenerate sizes yield no fit rather than a nonsense zoom`() {
        assertNull(fit(0, 100, 500, 500))
        assertNull(fit(100, 100, 0, 500))
        assertNull(CanvasZoom.fitZoom(100, 100, 500, 500, uiScale = 0.0))
    }

    @Test
    fun `zoom steps stay inside the supported range`() {
        assertEquals(CanvasZoom.MAX, CanvasZoom.clamp(CanvasZoom.MAX * 10), 1e-9)
        assertEquals(CanvasZoom.MIN, CanvasZoom.clamp(CanvasZoom.MIN / 10), 1e-9)
        assertTrue(CanvasZoom.STEP > 1.0)
    }
}
