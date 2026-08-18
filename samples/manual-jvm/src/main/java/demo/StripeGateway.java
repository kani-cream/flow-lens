package demo;

/**
 * A plausible implementation exists, but Flow Lens must NOT auto-select it:
 * a framework can substitute another at runtime, which is what the ambiguous
 * marker is for. Since v0.4 you can choose it yourself — the call stays marked
 * ambiguous and says whose body it is showing.
 *
 * The body has calls so that choosing it visibly changes the map.
 */
public class StripeGateway implements Gateway {
    @Override
    public void charge() {
        callApi();
        record();
    }

    void callApi() { }
    void record() { Audit.record(); }
}
