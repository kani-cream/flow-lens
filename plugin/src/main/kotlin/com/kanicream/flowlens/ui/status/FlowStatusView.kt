package com.kanicream.flowlens.ui.status

import com.intellij.icons.AllIcons
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.kanicream.flowlens.FlowLensBundle
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Status strip: the analyzed root, the lifecycle state, real counters, the
 * simplified-control-flow disclosure, and the re-analyze affordance. Rendering
 * only; the state it shows is computed by [FlowStatusModel].
 *
 * It occupies its own full-width row under the toolbar (`VISUAL_DESIGN.md` §3).
 * Sharing the toolbar's row left it a few pixels in a narrow tool window, where
 * it first wrapped into a clipped second line and then vanished entirely.
 *
 * The text is a single label so that shortening is Swing's ellipsis rather than
 * a layout deciding which of several labels gets no width at all; only the small
 * fixed markers and the re-analyze link sit beside it.
 */
class FlowStatusView(onReanalyze: () -> Unit) : JPanel(null) {

    private val summary = JBLabel().apply {
        // Let the label be given less than it asks for; it then truncates.
        minimumSize = Dimension(JBUI.scale(40), preferredSize.height)
    }
    private val simplifiedMarker = JBLabel(AllIcons.General.Warning).apply {
        toolTipText = FlowLensBundle.message("status.control.flow.simplified")
    }
    private val diagnosticsMarker = JBLabel(AllIcons.General.Information)
    private val reanalyze = ActionLink(FlowLensBundle.message("action.reanalyze.text")) { onReanalyze() }

    private val markers = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
        add(simplifiedMarker)
        add(diagnosticsMarker)
        add(reanalyze)
    }

    init {
        border = JBUI.Borders.empty(2, 8)
        add(summary)
        add(markers)
        apply(
            FlowStatusViewState(
                rootTitle = null,
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

    /**
     * The summary is laid out explicitly rather than by a border layout, which
     * hands the trailing markers their full width first and can leave the text a
     * negative one — the text then disappears instead of shortening.
     */
    override fun doLayout() {
        val insets = insets
        val innerWidth = (width - insets.left - insets.right).coerceAtLeast(0)
        val innerHeight = (height - insets.top - insets.bottom).coerceAtLeast(0)
        val markerWidth = markers.preferredSize.width
            .coerceAtMost(innerWidth / 2)
            .coerceAtLeast(0)
        markers.setBounds(width - insets.right - markerWidth, insets.top, markerWidth, innerHeight)
        val summaryWidth = (innerWidth - markerWidth - GAP).coerceAtLeast(0)
        summary.setBounds(insets.left, insets.top, summaryWidth, innerHeight)
    }

    /** One row whose height never changes as analysis progresses. */
    override fun getPreferredSize(): Dimension {
        val insets = insets
        return Dimension(
            summary.preferredSize.width + markers.preferredSize.width + GAP +
                insets.left + insets.right,
            rowHeight(),
        )
    }

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(40), rowHeight())

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, rowHeight())

    private fun rowHeight(): Int = summary.preferredSize.height + JBUI.scale(4)

    fun apply(state: FlowStatusViewState) {
        summary.text = summaryText(state)
        summary.icon = iconFor(state.tone)
        simplifiedMarker.isVisible = state.simplifiedControlFlow
        diagnosticsMarker.isVisible = state.diagnostics.isNotEmpty()
        if (state.diagnostics.isNotEmpty()) {
            diagnosticsMarker.toolTipText =
                state.diagnostics.joinToString("<br>", "<html>", "</html>")
        }
        reanalyze.isVisible = state.reanalyzeEnabled
        // Whatever had to be shortened stays readable on hover.
        toolTipText = (listOfNotNull(summaryText(state)) + state.diagnostics)
            .joinToString("<br>", "<html>", "</html>")
        revalidate()
        repaint()
    }

    private fun summaryText(state: FlowStatusViewState): String = listOfNotNull(
        state.rootTitle,
        state.headline,
        state.counters,
        if (state.simplifiedControlFlow) {
            FlowLensBundle.message("status.control.flow.simplified")
        } else {
            null
        },
    ).joinToString("  ·  ")

    private companion object {
        const val GAP = 8
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
