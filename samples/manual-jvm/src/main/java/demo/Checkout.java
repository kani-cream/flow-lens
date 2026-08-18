package demo;

import java.util.List;

/**
 * Manual sandbox root for v0.2 structure (V0.2_SPEC.md cases A, B, C, E, F, M, Q).
 * Put the caret in checkout() and run Analyze Flow.
 *
 * Expected shape on the canvas:
 *
 *   authorize()                  the condition is evaluated before the container
 *   ◆ if authorize()             one container, two labelled sections
 *       THEN   charge()
 *       ELSE   reject()
 *   tier()                       the subject is evaluated before the container
 *   ◈ switch tier()
 *       CASE "GOLD"      bonus()
 *       CASE "SILVER"    nothing        <- Q: an empty case is still shown
 *       DEFAULT          standard()
 *   ↻ loop                       E: size() repeats, so it is INSIDE the body,
 *       EACH ITERATION   size(), pack()    once, not duplicated outside it
 *   lines()                      F: the iterable runs once, BEFORE the loop
 *   ↻ loop                       M: containers nest
 *       EACH ITERATION   ◆ if valid()
 *                            THEN  ship()
 *   receipt()                    the sequence resumes after every container
 *
 * The status bar must NOT say "control flow simplified": every construct here is
 * represented now. That warning is the subject of Disclosure.java.
 */
public class Checkout {

    void checkout(String customer) {
        if (authorize()) {
            charge();
        } else {
            reject();
        }

        switch (tier()) {
            case "GOLD":
                bonus();
                break;
            case "SILVER":
                break;
            default:
                standard();
        }

        for (int i = 0; i < size(); i++) {
            pack();
        }

        for (String line : lines()) {
            if (valid(line)) {
                ship(line);
            }
        }

        receipt();
    }

    boolean authorize() { return true; }
    String tier() { return "GOLD"; }
    int size() { return 3; }
    List<String> lines() { return List.of(); }
    boolean valid(String line) { return true; }

    void charge() { }
    void reject() { }
    void bonus() { }
    void standard() { }
    void pack() { }
    void ship(String line) { }
    void receipt() { }
}
