package com.kanicream.flowlens.ui.canvas

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.core.engine.FlowEventSpec
import com.kanicream.flowlens.core.engine.FlowModelBuilder
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Who owns the keyboard after a navigation. Reading a flow is a sequence of
 * moves, so showing a declaration must not end the sequence: the user is
 * following an order of execution, not leaving to edit. Only an explicit Jump to
 * Source hands the keyboard to the editor.
 */
class CanvasNavigationFocusTest : BasePlatformTestCase() {

    private fun symbol(name: String) = FlowSymbol("java", "$name()", "Owner", "java:Owner#$name")

    private fun result(): FlowAnalysisResult {
        val builder = FlowModelBuilder(RunId(1), FlowLimits(), sourceRevision = 0)
        val root = builder.openRootFrame(symbol("run"), null)
        builder.addEvent(
            root,
            FlowEventSpec(
                kind = FlowNodeKind.CALL,
                callSiteLocation = null,
                targetSymbol = symbol("charge"),
                resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
                dispatchConfidence = DispatchConfidence.EXACT,
            ),
        )
        return builder.snapshot(FlowResultStatus.COMPLETED)
    }

    /** A canvas showing one call, with that call selected by arrow key. */
    private fun canvasWithSelection(): FlowCanvas {
        val canvas = FlowCanvas()
        canvas.setSize(600, 400)
        canvas.setResult(result())
        repeat(2) { pressArrowDown(canvas) }
        assertNotNull("the test needs a selected card to navigate from", canvas.selectedCard())
        return canvas
    }

    private fun pressArrowDown(canvas: FlowCanvas) = pressArrow(canvas, KeyEvent.VK_DOWN)

    private fun pressArrow(canvas: FlowCanvas, keyCode: Int, modifiers: Int = 0) {
        val event = KeyEvent(
            canvas,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )
        canvas.keyListeners.forEach { it.keyPressed(event) }
    }

    private val showTarget = "flow.action.open.target"
    private val showCallSite = "flow.action.open.call.site"
    private val jumpToSource = "flow.action.jump.to.source"

    fun `test showing a target keeps the keyboard on the canvas`() {
        val canvas = canvasWithSelection()
        val actions = FlowCanvasActions(canvas, testRootDisposable)
        val focusRequests = mutableListOf<Boolean>()
        canvas.onNavigateToTarget = { _, takeFocus -> focusRequests += takeFocus }

        assertTrue(actions.run(showTarget))
        assertEquals(
            "Enter shows the declaration; the next arrow key must still move the flow",
            listOf(false),
            focusRequests,
        )
    }

    fun `test jump to source is the one command that hands the keyboard over`() {
        val canvas = canvasWithSelection()
        val actions = FlowCanvasActions(canvas, testRootDisposable)
        val focusRequests = mutableListOf<Boolean>()
        canvas.onNavigateToTarget = { _, takeFocus -> focusRequests += takeFocus }

        assertTrue(actions.run(jumpToSource))
        assertEquals(listOf(true), focusRequests)
    }

    fun `test showing the call site also keeps the keyboard`() {
        val canvas = canvasWithSelection()
        val actions = FlowCanvasActions(canvas, testRootDisposable)
        val focusRequests = mutableListOf<Boolean>()
        canvas.onNavigateToCallSite = { _, takeFocus -> focusRequests += takeFocus }

        assertTrue(actions.run(showCallSite))
        assertEquals(listOf(false), focusRequests)
    }

    fun `test no other command may take the keyboard away from the flow`() {
        val canvas = canvasWithSelection()
        val actions = FlowCanvasActions(canvas, testRootDisposable)
        val handedOver = mutableListOf<String>()
        canvas.onNavigateToTarget = { _, takeFocus -> if (takeFocus) handedOver += "target" }
        canvas.onNavigateToCallSite = { _, takeFocus -> if (takeFocus) handedOver += "callSite" }
        canvas.onNavigateToFrameEntry = { _, takeFocus -> if (takeFocus) handedOver += "entry" }

        val others = actions.commands().map { it.id }.filter { it != jumpToSource }
        assertTrue("the canvas should bind more than one command", others.size > 1)
        others.forEach(actions::run)

        assertEquals(
            "only Jump to Source may end the reading session",
            emptyList<String>(),
            handedOver,
        )
    }

    fun `test jump to source uses whatever the IDE binds, not a hardcoded key`() {
        // F4 opens Spotlight on macOS, where the IDE binds Jump to Source to
        // Cmd+Down instead. Borrowing the platform action also follows a rebind.
        val canvas = canvasWithSelection()
        val actions = FlowCanvasActions(canvas, testRootDisposable)
        val platform = ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE)
        assertNotNull("the platform must still provide Jump to Source", platform)

        val command = actions.commands().single { it.id == jumpToSource }
        assertEquals(
            platform.shortcutSet.shortcuts.toList(),
            command.shortcuts.shortcuts.toList(),
        )
        assertTrue("Jump to Source must have a shortcut", command.shortcuts.shortcuts.isNotEmpty())
    }

    fun `test a modified arrow is left to whoever bound it`() {
        // A KeyListener runs before registered shortcuts, so treating Cmd+Down as
        // an ordinary move would swallow Jump to Source on macOS.
        val canvas = canvasWithSelection()
        val before = canvas.selectedCard()

        pressArrow(canvas, KeyEvent.VK_UP, InputEvent.META_DOWN_MASK)
        assertSame("a modified arrow must not move the selection", before, canvas.selectedCard())

        pressArrow(canvas, KeyEvent.VK_UP)
        assertNotSame("a plain arrow still moves it", before, canvas.selectedCard())
    }

    fun `test the entry can also be shown without losing the keyboard`() {
        val canvas = FlowCanvas()
        canvas.setSize(600, 400)
        canvas.setResult(result())
        // Above the first call is the entry, so it is reached by moving up.
        pressArrowDown(canvas)
        pressArrow(canvas, KeyEvent.VK_UP)
        assertNotNull("moving above the first call selects the entry", canvas.selectedEntry())

        val actions = FlowCanvasActions(canvas, testRootDisposable)
        val focusRequests = mutableListOf<Boolean>()
        canvas.onNavigateToFrameEntry = { _, takeFocus -> focusRequests += takeFocus }

        assertTrue(actions.run(showTarget))
        assertEquals(listOf(false), focusRequests)
    }
}
