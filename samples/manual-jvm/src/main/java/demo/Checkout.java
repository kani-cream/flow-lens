package demo;

import java.util.List;

/**
 * Manual sandbox root for v0.2 structure (V0.2_SPEC.md cases A, B, C, E, F, M, Q).
 * Put the caret in checkout() and run Analyze Flow.
 *
 * Expected shape on the canvas:
 *
 *   authorize()                     the condition is evaluated before the container
 *   ◆ if authorize()               one container, two labelled sections
 *       THEN   charge()
 *       ELSE   reject()
 *   tier()                          the subject is evaluated before the container
 *   ◈ switch tier()
 *       CASE 1        bonus()       the label is the source text, unquoted
 *       CASE 2        nothing       <- Q: an empty case is still shown
 *       DEFAULT       standard()
 *   ↻ loop i < size()              E: size() repeats, so it is INSIDE the body,
 *       EACH ITERATION                  once, not duplicated outside it
 *           size()
 *           pack()
 *   lines()                         F: the iterable runs once, BEFORE the loop
 *   ↻ loop line : lines()          M: containers nest
 *       EACH ITERATION
 *           valid(line)             the condition of the nested if runs first,
 *           ◆ if valid(line)          as its own card inside the body
 *               THEN  ship(line)
 *   receipt()                       the sequence resumes after every container
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
            case 1:
                bonus();
                break;
            case 2:
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
    int tier() { return 1; }
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
