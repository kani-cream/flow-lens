package com.kanicream.flowlens.ui.canvas

/**
 * Zoom arithmetic for the canvas, kept pure so the fit rule can be tested
 * without a realized Swing hierarchy.
 *
 * Units: content sizes are logical (layout) units, viewport sizes are device
 * pixels, and [uiScale] is the IDE's UI scale factor. A zoom of `1.0` means one
 * logical unit is drawn at `uiScale` pixels.
 */
object CanvasZoom {

    const val MIN: Double = 0.25
    const val MAX: Double = 2.5
    const val STEP: Double = 1.2

    /**
     * The zoom at which the whole content fits the viewport.
     *
     * The result depends only on content and viewport size, never on the current
     * zoom, so fitting is idempotent: pressing Fit twice cannot keep shrinking
     * the map. Fitting never magnifies past 100% — a small flow should fill the
     * viewport with whitespace, not with oversized cards.
     */
    fun fitZoom(
        contentWidth: Int,
        contentHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        uiScale: Double,
    ): Double? {
        if (contentWidth <= 0 || contentHeight <= 0) return null
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        if (uiScale <= 0.0) return null
        val fit = minOf(
            viewportWidth / (contentWidth * uiScale),
            viewportHeight / (contentHeight * uiScale),
        )
        return fit.coerceIn(MIN, minOf(MAX, 1.0))
    }

    fun clamp(zoom: Double): Double = zoom.coerceIn(MIN, MAX)
}
