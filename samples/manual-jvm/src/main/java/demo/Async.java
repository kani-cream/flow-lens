package demo;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Manual sandbox root for callback bodies and their timing (V0.5_SPEC.md cases
 * A, F, G, J, N, O). Put the caret in place() and run Analyze Flow.
 *
 * Expected shape on the canvas:
 *
 *   submit()                       the call that hands the body somewhere
 *     ⇢ { } → submit()             A: the body, set in, on a dashed connector
 *         charge()                   open it: charge() is inside the body,
 *                                    not in place()'s own sequence
 *   forEach()
 *     { } → forEach()              F: the JDK runs this one before returning,
 *         audit()                    so it is an ordinary step — solid connector
 *   runLater()
 *     ⧖ { } → runLater()           G: nothing justifies a timing for a method
 *         cleanup()                  of this project, so the card says so
 *   save()                         O: the next synchronous step follows the
 *                                    CALL, not the bodies handed to it
 *
 * Five things to check, because each is a way the map could lie:
 *
 *   1. charge() must NOT appear in place()'s own sequence. It runs when the
 *      executor gets to it, not where the lambda is written.
 *   2. The ⧖ card's tooltip and the details panel must say the timing is not
 *      determined. Silence would let you supply your own assumption.
 *   3. save()'s connector must come from runLater() — the CALL — and run past
 *      the body set in beneath it. A line from the body to save() would say
 *      "once the body has run, save()", which for an asynchronous one is the
 *      opposite of what is known.
 *   4. The status area must count exactly one callback with an undetermined
 *      timing, and clicking that line must select the runLater() body.
 *   5. Nothing under `stream()` below: an intermediate operation is lazy, so
 *      the lambda's timing is not determined either.
 *
 * Then analyze stored(): the lambda is assigned, never handed to a call, so
 * there is NO callback card. Its invocation site is elsewhere, and following it
 * would be reverse analysis (KNOWN_LIMITATIONS.md §42).
 *
 * Then analyze twice(): one call, two bodies, drawn in argument order (J).
 */
public class Async {

    ExecutorService executor;
    List<String> receipts;

    void place() {
        executor.submit(() -> charge());
        receipts.forEach(receipt -> audit(receipt));
        runLater(() -> cleanup());
        save();
    }

    /** N: never handed to a call, so it is not a callback event. */
    void stored() {
        Runnable task = () -> charge();
        save();
    }

    /**
     * J: two bodies handed to ONE call. They are drawn `#1` and `#2` — their
     * position in the argument list, which is the only thing that separates
     * them. That is an identity, not an order.
     */
    void twice() {
        pair(() -> charge(), () -> audit("second"));
    }

    /**
     * A lazy pipeline. `map()` is an intermediate operation, so the lambda does
     * not run here — it runs when a terminal operation starts the pipeline,
     * which may be in another statement, or never. The card must say the timing
     * is not determined rather than claim it runs in place.
     */
    void lazily() {
        receipts.stream().map(receipt -> { charge(); return receipt; }).count();
        save();
    }

    /** Not on any documented list, so the timing of what it is given is unknown. */
    void runLater(Runnable task) { }

    void pair(Runnable first, Runnable second) { }

    void charge() { }

    void audit(String receipt) { }

    void cleanup() { }

    void save() { }
}
