package com.kanicream.flowlens.analysis

/**
 * Shortens source text for display on a structural card. The full source stays
 * one navigation away, so the card only needs enough to tell branches apart
 * (`VISUAL_DESIGN.md` §13: long conditions summarized).
 *
 * Summaries are display data: diagnostics never include them (guardrails §13).
 */
object SourceSummary {

    const val MAX_LENGTH: Int = 48

    fun of(text: String?): String? {
        val collapsed = text?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length <= MAX_LENGTH) {
            collapsed
        } else {
            collapsed.take(MAX_LENGTH - 1).trimEnd() + "…"
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
