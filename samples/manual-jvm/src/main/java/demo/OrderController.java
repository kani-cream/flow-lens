package demo;

/**
 * Manual sandbox root for Java (V0.1_SPEC.md acceptance A, B, C, D, G, H, J, E).
 * Put the caret in purchase() and run Analyze Flow.
 *
 * Expected root event order:
 *   load, convert, save, validate, validate, work, charge, Receipt(new),
 *   trim(EXTERNAL), handle(Kotlin)
 */
public class OrderController {

    private final PaymentService service = new PaymentService();
    private final Gateway gateway = new StripeGateway();

    void purchase(String rawOrder) {
        save(convert(load()));            // B: nested -> load, convert, save
        validate(1);                      // D: duplicate target,
        validate(2);                      //    two separate call cards
        service.work();                   // G: DECLARED TARGET (PremiumPaymentService overrides it)
        gateway.charge();                 // H: AMBIGUOUS (interface, impl exists)
        new Receipt();                    // constructor card
        rawOrder.trim();                  // J: EXTERNAL + PROJECT BOUNDARY
        KtService.handle();               // E: Java -> Kotlin -> Java
    }

    String load() { return ""; }
    String convert(String s) { return s; }
    void save(String s) { audit(); }      // expandable child frame (1 call inside)
    void validate(int v) { }
    void audit() { }
}
