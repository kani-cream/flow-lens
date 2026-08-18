package com.kanicream.flowlens.workflow

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.core.model.FlowLimits

/** A recent entry as the UI uses it. */
data class RecentFlow(val entry: FlowEntryRef, val limits: FlowLimits)

/**
 * Recently analyzed entry points, newest first (`V0.3_SPEC.md` §5).
 *
 * Stored in the workspace file rather than the shared project file: a list that
 * reorders on every analysis would otherwise produce a commit-worthy diff every
 * few minutes for everyone on the team.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowLensRecents", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class FlowLensRecents : PersistentStateComponent<FlowLensRecents.State> {

    class State {
        var recents: MutableList<SavedFlowState> = mutableListOf()
    }

    // Written from the analysis coroutine, read from the EDT and from the
    // platform's save thread; a plain field would let each of them see a stale
    // list (guardrails §7).
    private val lock = Any()
    private var state = State()

    var onChanged: () -> Unit = {}

    override fun getState(): State = synchronized(lock) { state }

    override fun loadState(state: State) {
        synchronized(lock) { this.state = state }
    }

    fun recents(): List<RecentFlow> = synchronized(lock) { state.recents.toList() }.mapNotNull { recent ->
        val entry = recent.entry?.toRef() ?: return@mapNotNull null
        RecentFlow(
            entry = entry,
            limits = FlowLimits(
                maxDepth = recent.maxDepth,
                maxNodes = recent.maxNodes,
                includeTests = recent.includeTests,
                includeLibraries = recent.includeLibraries,
            ),
        )
    }

    /**
     * Records an analysis. Re-analyzing an entry moves it to the top instead of
     * duplicating it, so the list stays a set ordered by recency.
     */
    fun record(ref: FlowEntryRef, limits: FlowLimits) {
        val entry = SavedFlowState().apply {
            name = ref.displayName
            this.entry = EntryState.of(ref)
            maxDepth = limits.maxDepth
            maxNodes = limits.maxNodes
            includeTests = limits.includeTests
            includeLibraries = limits.includeLibraries
        }
        synchronized(lock) {
            state.recents = (listOf(entry) + state.recents.filterNot { it.entry?.key == ref.key })
                .take(MAX_RECENTS)
                .toMutableList()
        }
        onChanged()
    }

    fun clear() {
        synchronized(lock) { state.recents = mutableListOf() }
        onChanged()
    }

    companion object {
        const val MAX_RECENTS = 10

        fun getInstance(project: Project): FlowLensRecents =
            project.getService(FlowLensRecents::class.java)
    }
}
