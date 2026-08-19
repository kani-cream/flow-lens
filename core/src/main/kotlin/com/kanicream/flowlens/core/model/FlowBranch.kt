package com.kanicream.flowlens.core.model

/** The role a branch plays inside its structure. Localized by the UI, never here. */
enum class BranchKind {
    THEN,
    ELSE,
    CASE,
    DEFAULT,
    BODY,
    TRY,
    CATCH,
    FINALLY,

    /**
     * The members of a library group: one run of steps drawn as one card, not
     * one alternative among several (`V1.0_GROUPING_SPEC.md` §4).
     */
    GROUP,
}

/**
 * One labelled section of a structural node: a `then`, a `case`, a loop body, a
 * `catch`. Empty sections are kept, so "this case does nothing" stays
 * distinguishable from "this case does not exist" (`V0.2_SPEC.md` §7).
 *
 * [label] is short source-derived text such as a case value or an exception
 * type. It exists for display; diagnostics never include it (guardrails §13).
 */
data class FlowBranch(
    val kind: BranchKind,
    val label: String?,
    val events: List<FlowNode>,
) {
    val isEmpty: Boolean get() = events.isEmpty()

    /**
     * Whether a call in this section may be skipped. A `try` body and a
     * `finally` both run, so a call inside them is not conditional; every other
     * section is one alternative among several (`V0.2_SPEC.md` §5).
     */
    val isConditional: Boolean
        get() = when (kind) {
            // A try body, a finally, and the members of a group all run. Only a
            // section that is one alternative among several may be skipped.
            BranchKind.TRY, BranchKind.FINALLY, BranchKind.GROUP -> false
            else -> true
        }
}
