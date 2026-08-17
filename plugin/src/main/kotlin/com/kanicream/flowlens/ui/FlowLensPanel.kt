package com.kanicream.flowlens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Tool-window content: pure composition of the toolbar, status strip, canvas,
 * and details panel. Behavior lives in [FlowLensController].
 */
class FlowLensPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val controller = FlowLensController(project)

    init {
        Disposer.register(this, controller)
        val header = JPanel(BorderLayout()).apply {
            add(FlowToolbar(project, controller).createComponent(this@FlowLensPanel), BorderLayout.WEST)
            add(controller.statusView, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(controller.canvas), BorderLayout.CENTER)
        add(controller.detailsPanel, BorderLayout.SOUTH)
    }

    override fun dispose() = Unit
}
