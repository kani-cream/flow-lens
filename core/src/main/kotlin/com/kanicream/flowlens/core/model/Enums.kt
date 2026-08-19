package com.kanicream.flowlens.core.model

/** Semantic kind of a flow event. Must not duplicate resolution state. */
enum class FlowNodeKind {
    ENTRY,
    CALL,
    CONSTRUCTOR,
    CONDITION,
    SWITCH,
    LOOP,
    RETURN,
    THROW,
    TRY,
    CATCH,
    FINALLY,
    /**
     * A callable body handed to another callable: a lambda, a trailing lambda,
     * a Go function literal. It owns a frame like a call does, and carries the
     * execution mode and ordering that say when it runs (`V0.5_SPEC.md` §3).
     */
    CALLBACK,

    /**
     * A run of consecutive library calls that were not entered, drawn as one
     * card carrying its count and its members (`V1.0_GROUPING_SPEC.md` §4).
     *
     * Repetition is what makes a map unreadable, not the library: forty-seven
     * `RouterGroup.GET()` cards say nothing the first one did not, while ten
     * different library calls in a row each say something. So a group collapses
     * a run, and a single call is never one.
     */
    EXTERNAL_GROUP,
    CYCLE,
    LIMIT,
    STATUS,
}

/** Where the resolved target lives relative to the project. */
enum class ResolutionStatus {
    PROJECT_LOCAL,
    EXTERNAL,
    UNRESOLVED,
    BUILT_IN,
}

/** How certain the analyzer is that the analyzed target is the runtime target. */
enum class DispatchConfidence {
    EXACT,
    DECLARED_TARGET,
    AMBIGUOUS,
    UNKNOWN,
}

/** Known execution semantics of a call. Never invented; UNKNOWN when not established. */
enum class ExecutionMode {
    SYNC,
    ASYNC,
    GOROUTINE,
    DEFERRED,
    UNKNOWN,
}

/** Whether the sequential order of sibling events is language-guaranteed. */
enum class OrderingStatus {
    DETERMINISTIC,
    APPROXIMATE,
    UNSPECIFIED,
}

/** Provenance of a resolved declaration. Only PHYSICAL_SOURCE is recursed by default. */
enum class SourceOrigin {
    PHYSICAL_SOURCE,
    SYNTHETIC,
    GENERATED,
    LIBRARY,
    UNKNOWN,
}

/** Lifecycle state of one analysis result. */
enum class FlowResultStatus {
    RUNNING,
    COMPLETED,
    TRUNCATED,
    CANCELLED,
    STALE,
    FAILED,
}
