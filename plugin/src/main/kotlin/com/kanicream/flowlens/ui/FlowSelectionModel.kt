package com.kanicream.flowlens.ui

import com.kanicream.flowlens.core.model.NodeId
import com.kanicream.flowlens.ui.canvas.CardVM

/**
 * The currently selected flow event and the navigation context derived from it.
 * Kept separate from the canvas so selection can be observed and tested without
 * rendering (guardrails §12).
 */
class FlowSelectionModel {

    private val listeners = mutableListOf<(CardVM?) -> Unit>()

    var selected: CardVM? = null
        private set

    val selectedNodeId: NodeId? get() = selected?.nodeId

    fun addListener(listener: (CardVM?) -> Unit) {
        listeners += listener
    }

    fun select(card: CardVM?) {
        if (card?.nodeId == selected?.nodeId && card === selected) return
        selected = card
        listeners.forEach { it(card) }
    }

    /** Drops a selection whose node no longer exists in the current result. */
    fun retainOnly(visibleNodeIds: Set<NodeId>) {
        val current = selected ?: return
        if (current.nodeId !in visibleNodeIds) select(null)
    }

    fun clear() = select(null)
}
