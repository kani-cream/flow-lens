package com.kanicream.flowlens.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.workflow.FlowEntryRef
import com.kanicream.flowlens.workflow.FlowEntryResolver
import com.kanicream.flowlens.workflow.FlowLensFlows
import com.kanicream.flowlens.workflow.FlowLensRecents
import javax.swing.JComponent

/**
 * The saved flows, recent analyses, and pins, as one menu (`V0.3_SPEC.md` §4–5).
 *
 * A popup rather than a side panel: the tool window is routinely narrow, and a
 * permanent list would take width from the canvas to show something the user
 * looks at between analyses rather than during one.
 */
class FlowsMenu(
    private val project: Project,
    private val openEntry: (FlowEntryRef, FlowLimits?) -> Unit,
    private val saveCurrent: () -> Unit,
    private val canSaveCurrent: () -> Boolean,
) {

    fun show(component: JComponent, x: Int, y: Int) {
        ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.POPUP, buildGroup())
            .component
            .show(component, x, y)
    }

    private fun buildGroup(): DefaultActionGroup {
        val flows = FlowLensFlows.getInstance(project)
        val recents = FlowLensRecents.getInstance(project)
        val saved = flows.savedFlows()
        val pins = flows.pins()
        val recent = recents.recents()

        // Only a VFS lookup here. Resolving a key parses the file and walks every
        // element of it, and doing that for a dozen entries would freeze the EDT
        // each time the menu opens (guardrails §6). The expensive check happens on
        // activation, for the one entry the user chose.
        val present = (saved.map { it.entry } + pins + recent.map { it.entry })
            .associate { entryKey(it) to FlowEntryResolver.fileExists(project, it) }

        return DefaultActionGroup().apply {
            add(SaveCurrentAction())

            addSection("flows.group.saved", saved.map { flow ->
                OpenEntryAction(flow.name, flow.entry, flow.limits, present)
            })
            addSection("flows.group.recent", recent.map { flow ->
                OpenEntryAction(flow.entry.displayName, flow.entry, flow.limits, present)
            })
            addSection("flows.group.pins", pins.map { pin ->
                OpenEntryAction(pin.displayName, pin, null, present)
            })
        }
    }

    private fun DefaultActionGroup.addSection(titleKey: String, items: List<AnAction>) {
        if (items.isEmpty()) return
        add(Separator(FlowLensBundle.message(titleKey)))
        items.forEach(::add)
    }

    private inner class SaveCurrentAction : AnAction(
        FlowLensBundle.message("flows.save.current"),
        FlowLensBundle.message("flows.save.current.description"),
        null,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = canSaveCurrent()
        }

        override fun actionPerformed(e: AnActionEvent) = saveCurrent()
    }

    /**
     * Opens a stored entry. An entry whose declaration is gone stays in the list
     * and says so, disabled: `V0.3_SPEC.md` §8 forbids resolving it to whatever
     * has a similar name, and a mark that quietly moved would make every other
     * mark untrustworthy.
     */
    private inner class OpenEntryAction(
        name: String,
        private val ref: FlowEntryRef,
        private val limits: FlowLimits?,
        present: Map<String, Boolean>,
    ) : AnAction(
        if (present[entryKey(ref)] == true) {
            label(name, ref)
        } else {
            FlowLensBundle.message("flows.entry.unresolved", label(name, ref))
        },
        null,
        null,
    ), DumbAware {
        private val found = present[entryKey(ref)] == true

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = found
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (found) openEntry(ref, limits)
        }
    }

    private companion object {
        fun label(name: String, ref: FlowEntryRef): String =
            ref.containerName?.let { "$name  —  $it" } ?: name

        /** Two entries can share a key and differ by file; they are not the same row. */
        fun entryKey(ref: FlowEntryRef): String = "${ref.path}::${ref.key}"
    }
}
