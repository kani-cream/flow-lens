package com.kanicream.flowlens.ui.status

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.kanicream.flowlens.FlowLensBundle
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Status strip: current lifecycle state, real counters, simplified-control-flow
 * disclosure, and the re-analyze affordance. Rendering only; the state it shows
 * is computed by [FlowStatusModel].
 */
class FlowStatusView(onReanalyze: () -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)) {

    private val headline = JBLabel()
    private val counters = JBLabel().apply { foreground = JBColor.GRAY }
    private val simplified = JBLabel(
        FlowLensBundle.message("status.control.flow.simplified"),
        AllIcons.General.Warning,
        JBLabel.LEADING,
    )
    private val diagnostics = JBLabel(AllIcons.General.Information)
    private val reanalyze = ActionLink(FlowLensBundle.message("action.reanalyze.text")) { onReanalyze() }

    init {
        border = JBUI.Borders.empty(2, 8)
        add(headline)
        add(counters)
        add(simplified)
        add(diagnostics)
        add(reanalyze)
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

    fun apply(state: FlowStatusViewState) {
        headline.text = state.headline
        headline.icon = iconFor(state.tone)
        counters.text = state.counters.orEmpty()
        counters.isVisible = state.counters != null
        simplified.isVisible = state.simplifiedControlFlow
        diagnostics.isVisible = state.diagnostics.isNotEmpty()
        if (state.diagnostics.isNotEmpty()) {
            diagnostics.text = state.diagnostics.first()
            diagnostics.toolTipText = state.diagnostics.joinToString("<br>", "<html>", "</html>")
        }
        reanalyze.isVisible = state.reanalyzeEnabled
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
