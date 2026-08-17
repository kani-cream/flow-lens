package com.kanicream.flowlens.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.service.FlowMetadata
import com.kanicream.flowlens.ui.canvas.CardVM
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Selected-node details and navigation actions. Rendering-only; navigation is
 * delegated to callbacks owned by the panel controller.
 */
class FlowDetailsPanel(
    private val onOpenTarget: (CardVM) -> Unit,
    private val onOpenCallSite: (CardVM) -> Unit,
) : JPanel(BorderLayout()) {

    private val label = JBLabel(FlowLensBundle.message("details.no.selection")).apply {
        border = JBUI.Borders.empty(6, 10)
    }
    private val openTargetButton = JButton(FlowLensBundle.message("details.action.open.target"))
    private val openCallSiteButton = JButton(FlowLensBundle.message("details.action.open.call.site"))
    private var current: CardVM? = null

    init {
        border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        add(label, BorderLayout.CENTER)
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4))
        actions.add(openCallSiteButton)
        actions.add(openTargetButton)
        add(actions, BorderLayout.EAST)
        openTargetButton.addActionListener { current?.let(onOpenTarget) }
        openCallSiteButton.addActionListener { current?.let(onOpenCallSite) }
        showSelection(null)
    }

    fun showSelection(card: CardVM?) {
        current = card
        if (card == null) {
            label.text = FlowLensBundle.message("details.no.selection")
            openTargetButton.isEnabled = false
            openCallSiteButton.isEnabled = false
            return
        }
        val node = card.node
        val parts = mutableListOf<String>()
        node.targetSymbol?.let { symbol ->
            parts += "${FlowLensBundle.message("details.symbol")}: ${symbol.displayName}"
            symbol.containerName?.let { parts += "${FlowLensBundle.message("details.container")}: $it" }
            parts += "${FlowLensBundle.message("details.language")}: ${symbol.languageId}"
        }
        parts += "${FlowLensBundle.message("details.kind")}: " +
            FlowLensBundle.message("enum.kind.${node.kind.name}")
        node.resolutionStatus?.let {
            parts += "${FlowLensBundle.message("details.resolution")}: " +
                FlowLensBundle.message("enum.resolution.${it.name}")
        }
        node.dispatchConfidence?.let {
            var text = "${FlowLensBundle.message("details.dispatch")}: " +
                FlowLensBundle.message("enum.dispatch.${it.name}")
            if (it == DispatchConfidence.DECLARED_TARGET) {
                text += " (${FlowLensBundle.message("details.dispatch.override.hint")})"
            }
            parts += text
        }
        parts += "${FlowLensBundle.message("details.execution")}: " +
            FlowLensBundle.message("enum.execution.${node.executionMode.name}")
        parts += "${FlowLensBundle.message("details.ordering")}: " +
            FlowLensBundle.message("enum.ordering.${node.orderingStatus.name}")
        node.metadata[FlowMetadata.ORIGIN]?.let {
            parts += "${FlowLensBundle.message("details.origin")}: " +
                FlowLensBundle.message("enum.origin.$it")
        }
        node.callSiteLocation?.let {
            parts += "${FlowLensBundle.message("details.location")}: ${it.presentablePath}:${it.line}"
        }
        label.text = "<html>" + parts.joinToString(" · ") + "</html>"
        openTargetButton.isEnabled = node.targetLocation != null
        openCallSiteButton.isEnabled = node.callSiteLocation != null
    }
}
