package com.kanicream.flowlens.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.kanicream.flowlens.dispatch.DispatchCandidate
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Picks the implementation a call should be followed into (`V0.4_SPEC.md` §3).
 *
 * Its own dialog rather than `Messages.showChooseDialog`, which is deprecated,
 * and which offers no room for the sentence that matters here: choosing is not
 * the same as proving.
 */
class ChooseImplementationDialog(
    project: Project,
    private val message: String,
    private val candidates: List<DispatchCandidate>,
    private val caveat: String,
) : DialogWrapper(project) {

    private val list = JBList(candidates.map(::label)).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = 0
        visibleRowCount = MAX_VISIBLE_ROWS
    }

    init {
        title = message
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        preferredSize = Dimension(JBUI.scale(420), JBUI.scale(240))
        add(
            JBLabel(caveat).apply {
                setCopyable(true)
                font = JBUI.Fonts.smallFont()
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(list), BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    /** The chosen candidate, or null when the dialog was cancelled. */
    fun chosen(): DispatchCandidate? =
        candidates.getOrNull(list.selectedIndex).takeIf { exitCode == OK_EXIT_CODE }

    private companion object {
        const val MAX_VISIBLE_ROWS = 10

        fun label(candidate: DispatchCandidate): String =
            listOfNotNull(candidate.symbol.containerName, candidate.symbol.displayName)
                .joinToString(".")
    }
}
