package demo;

import java.io.IOException;

/**
 * Manual sandbox root for exception handling and termination (V0.2_SPEC.md
 * cases G, H, I, J). Put the caret in deliver() and run Analyze Flow.
 *
 * Expected shape on the canvas:
 *
 *   ⛨ try
 *       TRY                    send()
 *       CATCH IOException      retryLater()   the label is unquoted source text
 *       FINALLY                close()
 *   ↻ loop (runs at least once) keepGoing()   G: the card says so for a do-while
 *       EACH ITERATION
 *           attempt()                        body first, condition after
 *           keepGoing()
 *   ◀ return                             I: a bare return says only "return"
 *
 * Then analyze summarize(): its two returns must read differently — "return 0"
 * and "return total()" — and neither like the bare one in deliver(). A
 * terminator that cannot say what it hands back is barely worth drawing.
 *
 * Then analyze abort(): the throw expression is evaluated first, so reason() and
 * the exception constructor appear before the ✖ throw marker, and the marker
 * itself names the exception (case J).
 */
public class Resilience {

    void deliver() {
        try {
            send();
        } catch (IOException e) {
            retryLater();
        } finally {
            close();
        }

        do {
            attempt();
        } while (keepGoing());

        return;
    }

    int summarize(boolean skip) {
        if (skip) {
            return 0;
        }
        return total();
    }

    void abort() {
        throw new IllegalStateException(reason());
    }

    void send() throws IOException { }
    void retryLater() { }
    void close() { }
    void attempt() { }
    boolean keepGoing() { return false; }
    String reason() { return "stopped"; }
    int total() { return 1; }
}
