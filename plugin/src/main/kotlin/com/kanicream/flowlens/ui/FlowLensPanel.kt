package com.kanicream.flowlens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Tool-window content: pure composition of the toolbar, status strip, canvas,
 * and details panel. Behavior lives in [FlowLensController].
 *
 * Canvas and details share a draggable splitter rather than a fixed-height
 * footer: how much room the details of a selected node deserve depends on the
 * node, and the proportion is remembered per IDE installation.
 */
class FlowLensPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    /** Internal so lifecycle tests can assert the disposal chain. */
    internal val controller = FlowLensController(project)

    init {
        Disposer.register(this, controller)
        // The status needs the full width to be readable, so it gets its own row
        // under the toolbar rather than competing with it for space
        // (`VISUAL_DESIGN.md` §3).
        val toolbar = FlowToolbar(project, controller)
        // Zooming from the canvas must refresh the toolbar's percentage.
        controller.onViewStateChanged = toolbar::refresh
        val header = JPanel(BorderLayout()).apply {
            add(
                JPanel(BorderLayout()).apply {
                    add(toolbar.createComponent(this@FlowLensPanel), BorderLayout.WEST)
                },
                BorderLayout.NORTH,
            )
            add(controller.statusView, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(
            JBSplitter(true, SPLITTER_KEY, DEFAULT_CANVAS_PROPORTION).apply {
                firstComponent = JBScrollPane(controller.canvas)
                secondComponent = controller.detailsPanel
            },
            BorderLayout.CENTER,
        )
    }

    override fun dispose() = Unit

    private companion object {
        const val SPLITTER_KEY = "FlowLens.canvasDetailsSplitter"
        const val DEFAULT_CANVAS_PROPORTION = 0.72f
    }
}
