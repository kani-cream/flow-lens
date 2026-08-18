package com.kanicream.flowlens.ui.status

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Dimension

/**
 * The status strip shares the header row with the toolbar, so it must stay one
 * line at any width. Regression: a flow layout wrapped the counters onto a
 * second row in a narrow tool window, where they were clipped in half.
 */
class FlowStatusViewTest : BasePlatformTestCase() {

    private fun state(
        counters: String? = "215 nodes · 42 frames · 7 external · 3 ambiguous",
        simplified: Boolean = true,
        reanalyze: Boolean = true,
        diagnostics: List<String> = listOf("One method body could not be analyzed"),
    ) = FlowStatusViewState(
        headline = "Completed (truncated at node limit)",
        counters = counters,
        tone = StatusTone.WARNING,
        stopEnabled = false,
        reanalyzeEnabled = reanalyze,
        simplifiedControlFlow = simplified,
        diagnostics = diagnostics,
    )

    fun `test the strip stays one row however little width it is given`() {
        val view = FlowStatusView(onReanalyze = {})
        view.apply(state())
        val singleRow = view.preferredSize.height

        for (width in listOf(1200, 600, 320, 120, 40)) {
            view.size = Dimension(width, singleRow)
            view.doLayout()
            assertEquals(
                "the strip must not grow a second row at ${width}px",
                singleRow,
                view.preferredSize.height,
            )
            assertTrue(
                "every element stays on the first row at ${width}px",
                view.components.filter { it.isVisible }.all { it.y + it.height <= singleRow },
            )
        }
    }

    fun `test a narrow strip has a minimum width small enough for a narrow tool window`() {
        val view = FlowStatusView(onReanalyze = {})
        view.apply(state())
        assertEquals(0, view.minimumSize.width)
        assertEquals(view.preferredSize.height, view.minimumSize.height)
    }

    fun `test what cannot be shown is still readable on hover`() {
        val view = FlowStatusView(onReanalyze = {})
        view.apply(state())
        val tooltip = view.toolTipText
        assertTrue(tooltip.contains("215 nodes"))
        assertTrue(tooltip.contains("Completed"))
        assertTrue(tooltip.contains("could not be analyzed"))
    }

    fun `test an idle strip is the same height as a busy one`() {
        val view = FlowStatusView(onReanalyze = {})
        view.apply(state())
        val busy = view.preferredSize.height
        view.apply(state(counters = null, simplified = false, reanalyze = false, diagnostics = emptyList()))
        assertEquals("the header must not resize as analysis progresses", busy, view.preferredSize.height)
    }
}
