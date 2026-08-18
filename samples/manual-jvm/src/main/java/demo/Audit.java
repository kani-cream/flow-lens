package demo;

/**
 * Called from more than one root on purpose. A Flow Pin marks a callable rather
 * than a call site (`V0.3_SPEC.md` §4), and nothing in the other samples was
 * shared between two flows, so that could not be seen.
 *
 * Pin record() from either root and it stays marked in the other.
 */
public class Audit {

    static void record() {
        write();
    }

    private static void write() { }
}
