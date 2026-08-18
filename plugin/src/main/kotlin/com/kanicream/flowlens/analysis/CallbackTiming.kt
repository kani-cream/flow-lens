package com.kanicream.flowlens.analysis

import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.OrderingStatus

/**
 * When a callback body runs, and what justified saying so (`V0.5_SPEC.md` §4).
 *
 * `UNKNOWN` is the default and is not a failure: a map that says "this runs at a
 * time I cannot determine" is telling the truth, while one that guesses trades
 * an honest omission for a confident error.
 */
data class CallbackTiming(
    val executionMode: ExecutionMode,
    val orderingStatus: OrderingStatus,
) {
    companion object {
        /** Runs during the call; the flow continues after it. */
        val IN_PLACE = CallbackTiming(ExecutionMode.SYNC, OrderingStatus.DETERMINISTIC)

        /** Runs concurrently, at a time the language does not fix. */
        val ASYNC = CallbackTiming(ExecutionMode.ASYNC, OrderingStatus.UNSPECIFIED)

        val GOROUTINE = CallbackTiming(ExecutionMode.GOROUTINE, OrderingStatus.UNSPECIFIED)

        val DEFERRED = CallbackTiming(ExecutionMode.DEFERRED, OrderingStatus.UNSPECIFIED)

        /** Nothing justified a better answer. */
        val UNDETERMINED = CallbackTiming(ExecutionMode.UNKNOWN, OrderingStatus.UNSPECIFIED)
    }
}

/**
 * The APIs whose timing is documented, per `V0.5_SPEC.md` §4.3.
 *
 * Deliberately short and explicit. A project's own asynchronous helper is
 * undetermined until its API is listed here, which is honest rather than
 * helpful — and is recorded as a limitation instead of being papered over with
 * a name-based guess ("anything called *Async").
 */
object KnownCallbackApis {

    /** Java methods that invoke the lambda before returning. */
    private val JAVA_IN_PLACE: Set<String> = setOf(
        "java.lang.Iterable.forEach",
        "java.util.Map.forEach",
        "java.util.Optional.map",
        "java.util.Optional.flatMap",
        "java.util.Optional.filter",
        "java.util.Optional.ifPresent",
        "java.util.Optional.orElseGet",
        "java.util.stream.Stream.map",
        "java.util.stream.Stream.filter",
        "java.util.stream.Stream.forEach",
        "java.util.stream.Stream.peek",
        "java.util.Map.computeIfAbsent",
        "java.util.Map.computeIfPresent",
        "java.util.Map.compute",
        "java.util.List.removeIf",
        "java.util.Collection.removeIf",
    )

    /** Java methods that hand the lambda to another thread. */
    private val JAVA_ASYNC: Set<String> = setOf(
        "java.util.concurrent.ExecutorService.submit",
        "java.util.concurrent.Executor.execute",
        "java.util.concurrent.ScheduledExecutorService.schedule",
        "java.util.concurrent.ScheduledExecutorService.scheduleAtFixedRate",
        "java.util.concurrent.CompletableFuture.supplyAsync",
        "java.util.concurrent.CompletableFuture.runAsync",
        "java.util.concurrent.CompletableFuture.thenApplyAsync",
        "java.util.concurrent.CompletableFuture.thenAcceptAsync",
        "java.util.concurrent.CompletableFuture.thenRunAsync",
        "java.util.concurrent.CompletableFuture.thenComposeAsync",
        "java.lang.Thread.Thread",
    )

    /** Coroutine builders that start concurrent work. */
    private val KOTLIN_ASYNC: Set<String> = setOf(
        "kotlinx.coroutines.launch",
        "kotlinx.coroutines.async",
    )

    /**
     * Coroutine functions that run the block before the next statement. They may
     * change dispatcher or thread; the map is about order, not about threads.
     */
    private val KOTLIN_IN_PLACE: Set<String> = setOf(
        "kotlinx.coroutines.withContext",
        "kotlinx.coroutines.runBlocking",
        "kotlinx.coroutines.coroutineScope",
        "kotlinx.coroutines.supervisorScope",
    )

    fun javaTiming(qualifiedName: String?): CallbackTiming? = when (qualifiedName) {
        in JAVA_IN_PLACE -> CallbackTiming.IN_PLACE
        in JAVA_ASYNC -> CallbackTiming.ASYNC
        else -> null
    }

    fun kotlinTiming(fqName: String?): CallbackTiming? = when (fqName) {
        in KOTLIN_ASYNC -> CallbackTiming.ASYNC
        in KOTLIN_IN_PLACE -> CallbackTiming.IN_PLACE
        else -> null
    }
}
