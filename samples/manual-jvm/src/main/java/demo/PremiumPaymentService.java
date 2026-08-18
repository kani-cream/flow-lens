package demo;

/**
 * Exists so PaymentService.work() is genuinely overridable: with a real override
 * in the project, the call to work() is reported as a declared target rather than
 * exact. Without this class the same call would be exact, because nothing could
 * replace the body at runtime.
 */
public class PremiumPaymentService extends PaymentService {
    @Override
    public void work() {
        // Intentionally different from the base implementation.
    }
}
