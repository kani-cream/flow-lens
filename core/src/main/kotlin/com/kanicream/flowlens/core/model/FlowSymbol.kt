package com.kanicream.flowlens.core.model

/**
 * Language-neutral description of a callable symbol.
 *
 * [key] is the identity used for cycle detection along a traversal path: two call
 * sites targeting the same callable share one key while remaining distinct nodes.
 */
data class FlowSymbol(
    val languageId: String,
    val displayName: String,
    val containerName: String?,
    val key: String,
) {
    init {
        require(key.isNotBlank()) { "FlowSymbol.key must not be blank" }
        require(displayName.isNotBlank()) { "FlowSymbol.displayName must not be blank" }
    }
}
