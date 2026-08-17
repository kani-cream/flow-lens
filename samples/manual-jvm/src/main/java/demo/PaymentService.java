package demo;

public class PaymentService {
    /** Ordinary virtual method: expect the "declared target" marker. */
    public void work() {
        prepare();
    }

    private void prepare() { }
}
