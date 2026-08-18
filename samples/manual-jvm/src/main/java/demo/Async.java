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
 *   ⇢ { } → submit()               A: the body itself, on a dashed connector
 *       charge()                     open it: charge() is inside the body,
 *                                    not in place()'s own sequence
 *   forEach()
 *   { } → forEach()                F: the JDK runs this one before returning,
 *       audit()                      so it is an ordinary step — solid connector
 *   runLater()
 *   ⧖ { } → runLater()             G: nothing justifies a timing for a method
 *       cleanup()                    of this project, so the card says so
 *   save()                         O: the next synchronous step follows the
 *                                    CALL, not the callbacks drawn above it
 *
 * Four things to check, because each is a way the map could lie:
 *
 *   1. charge() must NOT appear in place()'s own sequence. It runs when the
 *      executor gets to it, not where the lambda is written.
 *   2. The ⧖ card's tooltip and the details panel must say the timing is not
 *      determined. Silence would let you supply your own assumption.
 *   3. save() must have a solid connector. Two callbacks sit above it, but it
 *      is what runs next.
 *   4. The status area must count exactly one callback with an undetermined
 *      timing, and clicking that line must select the runLater() body.
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

    /** J: two bodies handed to one call, in argument order. */
    void twice() {
        pair(() -> charge(), () -> audit("second"));
    }

    /** Not on any documented list, so the timing of what it is given is unknown. */
    void runLater(Runnable task) { }

    void pair(Runnable first, Runnable second) { }

    void charge() { }

    void audit(String receipt) { }

    void cleanup() { }

    void save() { }
}
