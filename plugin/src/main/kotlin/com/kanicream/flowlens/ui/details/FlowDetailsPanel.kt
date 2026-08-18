package com.kanicream.flowlens.ui.details

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.FlowNode
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Full facts about the selected event plus its two navigation destinations
 * (`V0.1_SPEC.md` §18). Rendering only; the facts come from [FlowDetailsModel].
 */
class FlowDetailsPanel(
    private val onOpenTarget: () -> Unit,
    private val onOpenCallSite: () -> Unit,
) : JPanel(BorderLayout()) {

    private val title = JBLabel().apply { font = JBFont.label().asBold() }
    private val subtitle = JBLabel().apply { foreground = JBColor.GRAY }
    private val rows = JPanel(GridBagLayout())
    // The shortcut is shown on the button because that is where the user is
    // already looking when they perform the action for the first time.
    private val openTarget = JButton(
        FlowLensBundle.message("details.action.open.target.with.shortcut"),
    )
    private val openCallSite = JButton(
        FlowLensBundle.message("details.action.open.call.site.with.shortcut"),
    )

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(6, 10),
        )
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(title)
            add(subtitle)
        }
        add(header, BorderLayout.NORTH)
        // No fixed height: the splitter above decides how much room the details
        // get, so a long detail list can be shown without scrolling.
        add(
            JBScrollPane(rows).apply {
                border = JBUI.Borders.empty()
                minimumSize = Dimension(0, JBUI.scale(40))
            },
            BorderLayout.CENTER,
        )
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(openCallSite)
                add(openTarget)
            },
            BorderLayout.SOUTH,
        )
        openTarget.addActionListener { onOpenTarget() }
        openCallSite.addActionListener { onOpenCallSite() }
        show(null)
    }

    fun show(node: FlowNode?) {
        val state = FlowDetailsModel.stateOf(node)
        title.text = state.title
        subtitle.text = state.subtitle.orEmpty()
        subtitle.isVisible = state.subtitle != null
        openTarget.isEnabled = state.openTargetEnabled
        openCallSite.isEnabled = state.openCallSiteEnabled
        renderRows(state.rows)
    }

    private fun renderRows(details: List<DetailRow>) {
        rows.removeAll()
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            anchor = GridBagConstraints.LINE_START
            insets = JBUI.insets(1, 0, 1, 12)
        }
        val valueConstraints = GridBagConstraints().apply {
            gridx = 1
            anchor = GridBagConstraints.LINE_START
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(1, 0)
        }
        details.forEachIndexed { index, row ->
            labelConstraints.gridy = index
            valueConstraints.gridy = index
            rows.add(JBLabel(row.label).apply { foreground = JBColor.GRAY }, labelConstraints)
            rows.add(JBLabel(row.value), valueConstraints)
        }
        // Absorb the remaining vertical space so rows stay top-aligned.
        rows.add(
            JPanel().apply { isOpaque = false },
            GridBagConstraints().apply {
                gridx = 0
                gridy = details.size
                weighty = 1.0
                fill = GridBagConstraints.VERTICAL
            },
        )
        rows.revalidate()
        rows.repaint()
    }
}
