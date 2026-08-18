package demo;

/**
 * A second implementation, so the picker has something to choose between
 * (`V0.4_SPEC.md` §3). With one candidate the list is a formality; with two it
 * shows what the feature is for — and choosing the wrong one is visible on the
 * map, because the card names whose body it is showing.
 */
public class PaypalGateway implements Gateway {
    @Override
    public void charge() {
        redirect();
    }

    void redirect() { }
}
