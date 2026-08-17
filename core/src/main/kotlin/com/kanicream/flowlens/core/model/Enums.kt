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
