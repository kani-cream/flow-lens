package com.kanicream.flowlens.ui.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
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
            val card = canvas.selectedCard()
            if (card != null) {
                canvas.onNavigateToTarget(card)
            } else {
                canvas.selectedEntry()?.let(canvas.onNavigateToFrameEntry)
            }
        },
        canvasAction("flow.action.open.call.site", KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK) {
            canvas.selectedCard()?.let(canvas.onNavigateToCallSite)
        },
        canvasAction("flow.action.toggle.expansion", KeyEvent.VK_SPACE, 0) {
            canvas.selectedCard()?.let(canvas::toggleExpansion)
        },
    )

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
        perform: () -> Unit,
    ): CanvasAction = CanvasAction(
        text = FlowLensBundle.message("${textKey}.text"),
        description = FlowLensBundle.message("${textKey}.description"),
        shortcut = keyCode?.let { KeyStroke.getKeyStroke(it, modifiers) },
        perform = perform,
    )

    /**
     * The platform's menu modifier, derived from the OS rather than from the
     * toolkit: `Toolkit.getMenuShortcutKeyMaskEx` throws in a headless JVM, which
     * made building the tool window fail outright on a headless host.
     */
    private fun menuMask(): Int =
        if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK

    private class CanvasAction(
        text: String,
        description: String,
        private val shortcut: KeyStroke?,
        private val perform: () -> Unit,
    ) : AnAction(text, description, null), DumbAware {

        fun register(component: javax.swing.JComponent, parent: Disposable) {
            val stroke = shortcut ?: return
            registerCustomShortcutSet(CustomShortcutSet(stroke), component, parent)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = perform()
    }
}
