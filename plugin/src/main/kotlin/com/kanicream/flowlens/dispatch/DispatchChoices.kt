package com.kanicream.flowlens.dispatch

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.workflow.FlowEntryRef

/**
 * One reader's decision: when the traversal would stop at [fromKey] because it
 * cannot prove a continuation, continue into [to] instead (`V0.4_SPEC.md` §4).
 */
data class DispatchChoice(
    val fromKey: String,
    val fromDisplayName: String,
    val to: FlowEntryRef,
)

/**
 * The dispatch choices in effect, for one project.
 *
 * Keyed by callable rather than by call site: that is how the decision is made
 * — "in this codebase, that interface is that class" — and a call site's
 * identity would not survive the re-analysis a choice triggers
 * (`V0.4_SPEC.md` §4.1).
 *
 * Not persisted. A choice lasts for the session (`V0.4_SPEC.md` §2).
 */
@Service(Service.Level.PROJECT)
class DispatchChoices {

    private val lock = Any()
    private var choices: Map<String, DispatchChoice> = emptyMap()

    /** Notified after any change, so the UI can re-run and refresh. */
    var onChanged: () -> Unit = {}

    fun all(): List<DispatchChoice> = synchronized(lock) {
        choices.values.sortedBy { it.fromDisplayName }
    }

    /** An immutable snapshot for one run, so a change mid-run cannot alter it. */
    fun snapshot(): Map<String, FlowEntryRef> = synchronized(lock) {
        choices.mapValues { (_, choice) -> choice.to }
    }

    fun choiceFor(key: String): DispatchChoice? = synchronized(lock) { choices[key] }

    fun choose(choice: DispatchChoice) {
        synchronized(lock) { choices = choices + (choice.fromKey to choice) }
        onChanged()
    }

    fun clear(fromKey: String) {
        synchronized(lock) { choices = choices - fromKey }
        onChanged()
    }

    /**
     * Removes choices the run just found unusable, without asking for another
     * run. The run that discovered them already produced a result that does not
     * apply them and carries the warning; re-analysing would replace that result
     * with one from a newer run, and the warning would never reach the reader
     * (`V0.4_SPEC.md` §4.6).
     */
    fun dropStale(keys: Set<String>) {
        if (keys.isEmpty()) return
        synchronized(lock) { choices = choices - keys }
    }

    fun clearAll() {
        synchronized(lock) { choices = emptyMap() }
        onChanged()
    }

    companion object {
        fun getInstance(project: Project): DispatchChoices =
            project.getService(DispatchChoices::class.java)
    }
}
