package demo;

/**
 * Manual sandbox root for Flow Pins (`V0.3_SPEC.md` §10, cases A–E).
 * Put the caret in demo() and run Analyze Flow.
 *
 * A pin marks a callable, so one pin on Audit.record() should mark:
 *
 *   ★ record()          twice here, at the top level and inside a branch
 *   ★ record()          in OrderController.purchase(), a different flow
 *
 * Expected shape:
 *
 *   ★ record()                     case B: two calls, both marked
 *   ◆ if flagged()
 *       THEN   ★ record()          case C: a section is not a blind spot
 *       ELSE   skip()
 *   finish()
 *
 * Order of operations:
 *
 *   1. select the first record() card and press ⌘P — both record() cards gain ★
 *   2. press ⌘P again on either one — both marks go away (case E)
 *   3. pin it again, analyze OrderController.purchase() — record() is marked
 *      there too (case A), because the pin is on the function, not the call
 *   4. pin the entry demo() itself, re-analyze — the entry is marked (case D)
 *   5. restart the sandbox — the pins are still there (case F)
 */
public class Pins {

    void demo(boolean unused) {
        Audit.record();
        if (flagged()) {
            Audit.record();
        } else {
            skip();
        }
        finish();
    }

    boolean flagged() { return true; }
    void skip() { }
    void finish() { }
}
