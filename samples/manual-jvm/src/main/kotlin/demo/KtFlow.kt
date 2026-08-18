package demo

/**
 * Manual sandbox root for Kotlin control flow (V0.2_SPEC.md cases D, F, G, H, M).
 * Put the caret in process() and run Analyze Flow.
 *
 * Expected shape on the canvas:
 *
 *   kind()                       the subject is evaluated before the container
 *   ◈ when kind()
 *       CASE "1"     express()
 *       CASE "2"     nothing            an empty entry is still shown
 *       DEFAULT      standard()
 *   ◈ when                       subjectless: the guard is what chooses the
 *       CASE "ready()"   ready(), start()   entry, so it sits INSIDE the entry
 *       DEFAULT          hold()
 *   orders()                     the range is evaluated once, before the loop
 *   ↻ loop                       containers nest
 *       EACH ITERATION   ◆ if valid()
 *                            THEN  submit()
 *   ↻ loop (runs at least once)  a do-while says so on the card
 *       EACH ITERATION   attempt(), keepGoing()
 *   ⛨ try / catch "IllegalStateException" / finally
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
