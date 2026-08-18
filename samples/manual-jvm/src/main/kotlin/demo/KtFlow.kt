package demo

/**
 * Manual sandbox root for Kotlin control flow (V0.2_SPEC.md cases D, F, G, H, M).
 * Put the caret in process() and run Analyze Flow.
 *
 * Expected shape on the canvas. Note that a `when` is labelled `switch`: the
 * card names the kind of structure, and v0.2 uses one word per kind across all
 * three languages.
 *
 *   kind(code)                        the subject is evaluated before the container
 *   ◈ switch kind(code)
 *       CASE 1       express()
 *       CASE 2       nothing          an empty entry is still shown
 *       DEFAULT      standard()
 *   ◈ switch                          subjectless, so the card has nothing to name
 *       CASE ready()                  the guard is what chooses the entry, so it
 *           ready()                     runs INSIDE the entry, as its own card
 *           start()
 *       DEFAULT      hold()
 *   orders()                          evaluated once, before the loop
 *   ↻ loop order in orders()         containers nest
 *       EACH ITERATION
 *           valid(order)              the nested condition runs first
 *           ◆ if valid(order)
 *               THEN  submit(order)
 *   ↻ loop (runs at least once) keepGoing()
 *       EACH ITERATION
 *           attempt()                 body first, condition after
 *           keepGoing()
 *   ⛨ try
 *       TRY                    send()
 *       CATCH IllegalStateException   retryLater()
 *       FINALLY                close()
 *   done()
 *
 * The status bar must NOT warn here. Then analyze risky(): the elvis operand
 * may be skipped and v0.2 does not draw it, so that one does warn.
 */
object KtFlow {

    fun process(code: Int) {
        when (kind(code)) {
            1 -> express()
            2 -> {}
            else -> standard()
        }

        when {
            ready() -> start()
            else -> hold()
        }

        for (order in orders()) {
            if (valid(order)) {
                submit(order)
            }
        }

        do {
            attempt()
        } while (keepGoing())

        try {
            send()
        } catch (e: IllegalStateException) {
            retryLater()
        } finally {
            close()
        }

        done()
    }

    /** The elvis operand may be skipped, and v0.2 marks it instead of drawing it. */
    fun risky(name: String?) {
        val resolved = name?.trim() ?: fallback()
        record(resolved)
    }

    private fun kind(code: Int): Int = code
    private fun ready(): Boolean = true
    private fun orders(): List<String> = emptyList()
    private fun valid(order: String): Boolean = true
    private fun keepGoing(): Boolean = false
    private fun fallback(): String = "none"

    private fun express() {}
    private fun standard() {}
    private fun start() {}
    private fun hold() {}
    private fun submit(order: String) {}
    private fun attempt() {}
    private fun send() {}
    private fun retryLater() {}
    private fun close() {}
    private fun done() {}
    private fun record(value: String) {}
}
