package demo;

/**
 * Interface target: expect AMBIGUOUS, and the traversal stops there (v0.1 case H).
 *
 * Since v0.4 the stop is a choice rather than a wall. Select the charge() card in
 * purchase() and run "Choose Implementation": StripeGateway and PaypalGateway are
 * both offered, and picking one follows it.
 *
 * What to check afterwards:
 *
 *   - the card still shows the ambiguous marker — a choice is not a proof;
 *   - it still names charge() on Gateway, the callable actually called;
 *   - a badge says whose body is below it, and the tooltip and the details panel
 *     say it was chosen by you rather than proven by analysis;
 *   - the Flows menu lists the choice and can clear it, which re-runs;
 *   - both exports repeat the choice.
 */
public interface Gateway {
    void charge();
}
