package demo;

/**
 * v0.1 showed this as two flattened calls with a "control flow simplified"
 * warning. Since v0.2 the if is a container with THEN and ELSE sections and the
 * status bar is clean. Checkout.java is the fuller v0.2 root.
 */
public class Branchy {
    void run(boolean flag) {
        if (flag) {
            a();
        } else {
            b();
        }
    }

    void a() { }
    void b() { }
}
