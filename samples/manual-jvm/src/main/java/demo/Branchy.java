package demo;

/** Control-flow disclosure (S): expect the "Control flow simplified" status. */
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
