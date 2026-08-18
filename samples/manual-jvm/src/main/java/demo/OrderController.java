package demo;

/**
 * Manual sandbox root for Java (V0.1_SPEC.md acceptance A, B, C, D, G, H, J, E).
 * Put the caret in purchase() and run Analyze Flow.
 *
 * Expected root event order:
 *   load, convert, save, validate, validate, work, charge, Receipt(new),
 *   trim(EXTERNAL), handle(Kotlin), record
 *
 * record() is also called from Pins.demo(), so a pin on it is visible in both
 * flows — that is what makes a pin a mark on a callable rather than on a call
 * site (`V0.3_SPEC.md` §4).
 */
public class OrderController {

    private final PaymentService service = new PaymentService();

    /**
     * Injected, not constructed here. Which implementation arrives is genuinely
     * unknown from this file, which is what makes the ambiguity at the call site
     * real rather than an artefact of Flow Lens not tracking the receiver.
     *
     * With `= new StripeGateway()` the answer was knowable by reading this class,
     * and offering PaypalGateway as a choice was misleading — see
     * `KNOWN_LIMITATIONS.md` §37.
     */
    private final Gateway gateway;

    OrderController(Gateway gateway) {
        this.gateway = gateway;
    }

    void purchase(String rawOrder) {
        save(convert(load()));            // B: nested -> load, convert, save
        validate(1);                      // D: duplicate target,
        validate(2);                      //    two separate call cards
        service.work();                   // G: DECLARED TARGET (PremiumPaymentService overrides it)
        gateway.charge();                 // H: AMBIGUOUS — the receiver is injected,
                                          //    so either implementation can arrive
        new Receipt();                    // constructor card
        rawOrder.trim();                  // J: EXTERNAL + PROJECT BOUNDARY
        KtService.handle();               // E: Java -> Kotlin -> Java
        Audit.record();                   // shared with Pins.demo(): pin it in
    }                                     // either flow and see it in the other

    String load() { return ""; }
    String convert(String s) { return s; }
    void save(String s) { audit(); }      // expandable child frame (1 call inside)
    void validate(int v) { }
    void audit() { }
}
