package com.kanicream.flowlens.ui.status

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.kanicream.flowlens.FlowLensBundle
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel

/**
 * Status strip: current lifecycle state, real counters, simplified-control-flow
 * disclosure, and the re-analyze affordance. Rendering only; the state it shows
 * is computed by [FlowStatusModel].
 *
 * The strip is one row and never wraps. It shares the header with the toolbar,
 * whose height it must not exceed: a flow layout wrapped the counters onto a
 * second row in a narrow tool window, where they were then clipped in half.
 * Instead the counters give up width first and truncate, and the full text stays
 * available as the strip's tooltip.
 */
class FlowStatusView(onReanalyze: () -> Unit) : JPanel(GridBagLayout()) {

    private val headline = JBLabel()
    private val counters = JBLabel().apply {
        foreground = JBColor.GRAY
        // Allows the label to be given less than it wants, which makes Swing
        // truncate the text with an ellipsis instead of forcing a wider row.
        minimumSize = Dimension(0, preferredSize.height)
    }
    private val simplified = JBLabel(
        FlowLensBundle.message("status.control.flow.simplified"),
        AllIcons.General.Warning,
        JBLabel.LEADING,
    ).apply { minimumSize = Dimension(0, preferredSize.height) }
    private val diagnostics = JBLabel(AllIcons.General.Information)
    private val reanalyze = ActionLink(FlowLensBundle.message("action.reanalyze.text")) { onReanalyze() }

    init {
        border = JBUI.Borders.empty(2, 8)
        val fixed = GridBagConstraints().apply {
            gridy = 0
            weightx = 0.0
            insets = JBUI.insetsRight(8)
            anchor = GridBagConstraints.LINE_START
        }
        val shrinkable = GridBagConstraints().apply {
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insetsRight(8)
            anchor = GridBagConstraints.LINE_START
        }
        add(headline, fixed)
        add(counters, shrinkable)
        add(simplified, fixed)
        add(diagnostics, fixed)
        add(reanalyze, fixed)
        apply(
            FlowStatusViewState(
                headline = FlowLensBundle.message("status.idle"),
                counters = null,
                tone = StatusTone.IDLE,
                stopEnabled = false,
                reanalyzeEnabled = false,
                simplifiedControlFlow = false,
                diagnostics = emptyList(),
            ),
        )
    }

    /** One row: the strip must never grow the header beyond the toolbar's height. */
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width, rowHeight())
    }

    override fun getMinimumSize(): Dimension = Dimension(0, rowHeight())

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, rowHeight())

    private fun rowHeight(): Int =
        headline.preferredSize.height + JBUI.scale(4)

    fun apply(state: FlowStatusViewState) {
        headline.text = state.headline
        headline.icon = iconFor(state.tone)
        counters.text = state.counters.orEmpty()
        counters.isVisible = state.counters != null
        simplified.isVisible = state.simplifiedControlFlow
        diagnostics.isVisible = state.diagnostics.isNotEmpty()
        if (state.diagnostics.isNotEmpty()) {
            diagnostics.text = ""
            diagnostics.toolTipText = state.diagnostics.joinToString("<br>", "<html>", "</html>")
        }
        reanalyze.isVisible = state.reanalyzeEnabled
        // Whatever had to be truncated is still readable on hover.
        toolTipText = listOfNotNull(
            state.headline,
            state.counters,
            if (state.simplifiedControlFlow) {
                FlowLensBundle.message("status.control.flow.simplified")
            } else {
                null
            },
        ).joinToString(" · ") + state.diagnostics.joinToString("") { "\n$it" }
        revalidate()
        repaint()
    }

    /** Tone is carried by an icon as well as color, never color alone. */
    private fun iconFor(tone: StatusTone) = when (tone) {
        StatusTone.IDLE -> null
        StatusTone.RUNNING -> AllIcons.Process.Step_1
        StatusTone.DONE -> AllIcons.General.InspectionsOK
        StatusTone.WARNING -> AllIcons.General.Warning
        StatusTone.ERROR -> AllIcons.General.Error
    }
}
