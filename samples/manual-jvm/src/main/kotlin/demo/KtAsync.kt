package demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual sandbox root for Kotlin callback timing (V0.5_SPEC.md cases B, C, D, E).
 * Put the caret in process() and run Analyze Flow.
 *
 * Kotlin is the language where "runs in place" can usually be justified rather
 * than guessed, and this file is arranged so the three justifications are next
 * to each other:
 *
 *   forEach()
 *   { } → forEach()            C: `inline` without `noinline` means the body
 *       audit()                  cannot escape the call, so it has run by the
 *                                time the call returns — a language guarantee
 *   withContext()
 *   { } → withContext()        D: it may change thread, but it runs before the
 *       charge()                 next statement, and the map is about order
 *   launch()
 *   ⇢ { } → launch()           B: starts concurrent work, so the ordering is
 *       cleanup()                unspecified and the connector is dashed
 *   retry()
 *   ⧖ { } → retry()            a project's own helper: nothing justifies a
 *       attempt()                timing, so the card says it is not determined
 *   save()                     still the next synchronous step
 *
 * Then analyze suspending(): a call to a `suspend fun` is an ordinary call (E).
 * Suspending is about how it waits, not about handing a body elsewhere, so
 * there is no callback card for persist().
 *
 * Then analyze escaping(): `noinline` removes exactly the guarantee that made
 * case C safe, so the same shape of code reports an undetermined timing. That
 * is the difference between a rule and a habit.
 */
class KtAsync(private val scope: CoroutineScope) {

    suspend fun process(receipts: List<String>) {
        receipts.forEach { audit(it) }
        withContext(Dispatchers.Default) { charge() }
        scope.launch { cleanup() }
        retry { attempt() }
        save()
    }

    suspend fun suspending() {
        persist()
        save()
    }

    fun escaping() {
        keepForLater { charge() }
        save()
    }

    /** Not on any documented list: the timing of what it is given is unknown. */
    private fun retry(block: () -> Unit) = block()

    /** `noinline` lets the body outlive the call, so nothing can be promised. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun keepForLater(noinline block: () -> Unit) = store(block)

    private fun store(block: () -> Unit) {}

    private suspend fun persist() {}

    private fun audit(receipt: String) {}
    private fun charge() {}
    private fun cleanup() {}
    private fun attempt() {}
    private fun save() {}
}
