package com.kanicream.flowlens.ui.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.SystemInfo
import com.kanicream.flowlens.FlowLensBundle
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Canvas commands as real platform actions rather than raw key handling.
 *
 * This is what makes the keyboard discoverable: the actions appear in Find
 * Action and in Settings → Keymap where they can be rebound, the context menu
 * renders each one with its shortcut, and the IDE can detect conflicts. The
 * shortcuts are registered against the canvas component, so keys as ordinary as
 * Enter or Space stay inert everywhere else.
 */
class FlowCanvasActions(private val canvas: FlowCanvas, parent: Disposable) {

    private val actions = listOf(
        canvasAction("flow.action.open.target", KeyEvent.VK_ENTER, 0) {
            navigateToSelection(takeFocus = false)
        },
        canvasAction("flow.action.open.call.site", KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK) {
            canvas.selectedCard()?.let { canvas.onNavigateToCallSite(it, false) }
        },
        // The only command here that gives the keyboard away: reading the flow
        // should not cost the ability to keep reading it. It borrows the
        // platform's own Jump to Source binding rather than naming a key, so it
        // is F4 on Windows and Linux, Cmd+Down on macOS where F4 belongs to the
        // OS, and whatever the user rebound it to.
        CanvasAction(
            id = "flow.action.jump.to.source",
            text = FlowLensBundle.message("flow.action.jump.to.source.text"),
            description = FlowLensBundle.message("flow.action.jump.to.source.description"),
            shortcuts = jumpToSourceShortcuts(),
        ) { navigateToSelection(takeFocus = true) },
        canvasAction("flow.action.toggle.expansion", KeyEvent.VK_SPACE, 0) {
            canvas.selectedCard()?.let(canvas::toggleExpansion)
        },
        // A pin marks the callable, so the entry can be pinned as well as a call.
        canvasAction("flow.action.toggle.pin", KeyEvent.VK_P, menuMask()) {
            canvas.onTogglePin(canvas.selectedCard())
        },
        canvasAction(
            "flow.action.analyze.from.here",
            KeyEvent.VK_B,
            menuMask(),
            // An external, unresolved, or body-less target offers nothing to
            // analyze, so the command says so instead of quietly doing nothing
            // (`V0.3_SPEC.md` §6.1).
            isEnabled = { canvas.selectedCard()?.let(canvas.canAnalyzeFrom) == true },
        ) {
            canvas.selectedCard()?.let(canvas.onAnalyzeFromHere)
        },
    )

    private fun navigateToSelection(takeFocus: Boolean) {
        val card = canvas.selectedCard()
        if (card != null) {
            canvas.onNavigateToTarget(card, takeFocus)
        } else {
            canvas.selectedEntry()?.let { canvas.onNavigateToFrameEntry(it, takeFocus) }
        }
    }

    private val menuGroup = DefaultActionGroup().apply {
        actions.forEach(::add)
        add(Separator.getInstance())
        add(canvasAction("action.zoom.in", KeyEvent.VK_EQUALS, menuMask()) { canvas.zoomIn() })
        add(canvasAction("action.zoom.out", KeyEvent.VK_MINUS, menuMask()) { canvas.zoomOut() })
        add(canvasAction("action.fit", null, 0) { canvas.fitToView() })
    }

    init {
        // Registering against the canvas keeps the bindings local to it while
        // still exposing them to the keymap and the context menu.
        menuGroup.childActionsOrStubs.forEach { action ->
            (action as? CanvasAction)?.register(canvas, parent)
        }
    }

    /** The commands this component binds, in menu order. */
    fun commands(): List<CanvasAction> =
        menuGroup.childActionsOrStubs.filterIsInstance<CanvasAction>()

    /** Runs a command by id, returning false when there is no such command. */
    fun run(id: String): Boolean {
        val command = commands().firstOrNull { it.id == id } ?: return false
        command.performNow()
        return true
    }

    /**
     * What the IDE currently binds to Jump to Source, which differs by platform
     * and by keymap. Falls back to F4 only if the platform action is missing.
     */
    private fun jumpToSourceShortcuts(): ShortcutSet =
        ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE)?.shortcutSet
            ?: CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0))

    /** Shows the actions the user can run on the canvas, each with its shortcut. */
    fun showContextMenu(at: Point) {
        ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.POPUP, menuGroup)
            .component
            .show(canvas, at.x, at.y)
    }

    private fun canvasAction(
        textKey: String,
        keyCode: Int?,
        modifiers: Int,
        isEnabled: () -> Boolean = { true },
        perform: () -> Unit,
    ): CanvasAction = CanvasAction(
        id = textKey,
        isEnabled = isEnabled,
        text = FlowLensBundle.message("${textKey}.text"),
        description = FlowLensBundle.message("${textKey}.description"),
        shortcuts = keyCode
            ?.let { CustomShortcutSet(KeyStroke.getKeyStroke(it, modifiers)) }
            ?: CustomShortcutSet.EMPTY,
        perform = perform,
    )

    /**
     * The platform's menu modifier, derived from the OS rather than from the
     * toolkit: `Toolkit.getMenuShortcutKeyMaskEx` throws in a headless JVM, which
     * made building the tool window fail outright on a headless host.
     */
    private fun menuMask(): Int =
        if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK

    class CanvasAction(
        val id: String,
        text: String,
        description: String,
        val shortcuts: ShortcutSet,
        private val isEnabled: () -> Boolean = { true },
        private val perform: () -> Unit,
    ) : AnAction(text, description, null), DumbAware {

        fun performNow() = perform()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = isEnabled()
        }

        fun register(component: javax.swing.JComponent, parent: Disposable) {
            if (shortcuts.shortcuts.isEmpty()) return
            registerCustomShortcutSet(shortcuts, component, parent)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = perform()
    }
}
