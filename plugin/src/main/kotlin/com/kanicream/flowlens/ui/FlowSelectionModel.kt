package com.kanicream.flowlens.ui

import com.kanicream.flowlens.ui.canvas.CardVM

/**
 * The currently selected flow event and the navigation context derived from it.
 * The canvas clears the selection itself when a rebuild removes the selected
 * node, so this model only carries and broadcasts the current choice.
 * Kept separate from the canvas so selection can be observed and tested without
 * rendering (guardrails §12).
 */
class FlowSelectionModel {

    private val listeners = mutableListOf<(CardVM?) -> Unit>()

    var selected: CardVM? = null
        private set

    fun addListener(listener: (CardVM?) -> Unit) {
        listeners += listener
    }

    fun select(card: CardVM?) {
        if (card?.nodeId == selected?.nodeId && card === selected) return
        selected = card
        listeners.forEach { it(card) }
    }

    fun clear() = select(null)
}
