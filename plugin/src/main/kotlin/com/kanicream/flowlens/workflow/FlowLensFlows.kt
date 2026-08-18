package com.kanicream.flowlens.workflow

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.core.model.FlowLimits

/**
 * Serialized form of one stored callable. Mutable and nullary-constructible by
 * contract of the XML serializer; every read goes through [toRef], which drops
 * anything that cannot be understood rather than throwing (`V0.3_SPEC.md` §9).
 */
class EntryState {
    var key: String? = null
    var languageId: String? = null
    var displayName: String? = null
    var containerName: String? = null
    var path: String? = null

    fun toRef(): FlowEntryRef? {
        val key = key?.takeIf(String::isNotBlank) ?: return null
        val language = languageId?.takeIf(String::isNotBlank) ?: return null
        val display = displayName?.takeIf(String::isNotBlank) ?: return null
        val path = path?.takeIf(String::isNotBlank) ?: return null
        return FlowEntryRef(key, language, display, containerName, path)
    }

    companion object {
        fun of(ref: FlowEntryRef): EntryState = EntryState().apply {
            key = ref.key
            languageId = ref.languageId
            displayName = ref.displayName
            containerName = ref.containerName
            path = ref.path
        }
    }
}

/** A named entry point plus the limits it was analyzed with (`V0.3_SPEC.md` §5). */
class SavedFlowState {
    var name: String? = null
    var entry: EntryState? = null
    var maxDepth: Int = FlowLimits.DEFAULT_MAX_DEPTH
    var maxNodes: Int = FlowLimits.DEFAULT_MAX_NODES
    var includeTests: Boolean = false
    var includeLibraries: Boolean = false
}

/** A saved flow as the UI uses it. */
data class SavedFlow(val name: String, val entry: FlowEntryRef, val limits: FlowLimits)

/**
 * Pins and saved flows, project-scoped and shared (`V0.3_SPEC.md` §9).
 *
 * Nothing here stores an analysis result. A result describes one source
 * revision and is stale as soon as the file changes, so opening a saved flow
 * re-runs it (`V0.3_SPEC.md` §2).
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowLensFlows", storages = [Storage("flowLensFlows.xml")])
class FlowLensFlows : PersistentStateComponent<FlowLensFlows.State> {

    class State {
        var pins: MutableList<EntryState> = mutableListOf()
        var saved: MutableList<SavedFlowState> = mutableListOf()
    }

    private var state = State()

    /** Bumped on every change so the UI can refresh without observing the lists. */
    var onChanged: () -> Unit = {}

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    // ---- pins ----

    fun pins(): List<FlowEntryRef> = state.pins.mapNotNull(EntryState::toRef)

    fun isPinned(key: String): Boolean = state.pins.any { it.key == key }

    /** Pinned keys, for the canvas to mark cards without a lookup per card. */
    fun pinnedKeys(): Set<String> = state.pins.mapNotNull { it.key }.toSet()

    /** Pins [ref], or unpins it when it is already pinned (`V0.3_SPEC.md` §4.6). */
    fun togglePin(ref: FlowEntryRef): Boolean {
        val pinned = isPinned(ref.key)
        state.pins = if (pinned) {
            state.pins.filterNot { it.key == ref.key }.toMutableList()
        } else {
            (state.pins + EntryState.of(ref)).toMutableList()
        }
        onChanged()
        return !pinned
    }

    fun removePin(key: String) {
        state.pins = state.pins.filterNot { it.key == key }.toMutableList()
        onChanged()
    }

    // ---- saved flows ----

    fun savedFlows(): List<SavedFlow> = state.saved.mapNotNull { saved ->
        val entry = saved.entry?.toRef() ?: return@mapNotNull null
        SavedFlow(
            name = saved.name?.takeIf(String::isNotBlank) ?: entry.displayName,
            entry = entry,
            limits = FlowLimits(
                maxDepth = saved.maxDepth,
                maxNodes = saved.maxNodes,
                includeTests = saved.includeTests,
                includeLibraries = saved.includeLibraries,
            ),
        )
    }

    /** Saving the same entry twice replaces it rather than growing the list. */
    fun save(name: String, ref: FlowEntryRef, limits: FlowLimits) {
        val entry = SavedFlowState().apply {
            this.name = name
            this.entry = EntryState.of(ref)
            maxDepth = limits.maxDepth
            maxNodes = limits.maxNodes
            includeTests = limits.includeTests
            includeLibraries = limits.includeLibraries
        }
        state.saved = (state.saved.filterNot { it.entry?.key == ref.key } + entry).toMutableList()
        onChanged()
    }

    fun removeSaved(key: String) {
        state.saved = state.saved.filterNot { it.entry?.key == key }.toMutableList()
        onChanged()
    }

    companion object {
        fun getInstance(project: Project): FlowLensFlows = project.getService(FlowLensFlows::class.java)
    }
}
