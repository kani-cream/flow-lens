package demo;

/**
 * The narrowed "control flow simplified" disclosure (V0.2_SPEC.md §6). In v0.1
 * this warning appeared on every branch and so meant nothing. Analyze each
 * method below in turn and check the status bar — that is the whole point of
 * this file.
 *
 *   represented()   CLEAN   an if and a loop are drawn, so nothing is hidden
 *   plainGuard()    CLEAN   the short circuit skips no call, so it hides nothing
 *   allCasesBreak() CLEAN   a break that ends a case is the case boundary,
 *                           which the map already draws
 *   arrowSwitch()   CLEAN   rule-style cases cannot fall through
 *   hidden()        WARNS   audit() may be skipped by the short circuit, and
 *                           v0.2 does not draw that as a structure
 *   fallsThrough()  WARNS   case 1 runs on into case 2, and the sections read
 *                           as independent
 *   leavesLoop()    WARNS   the break jumps out of the loop and no edge says so
 */
public class Disclosure {

    void represented(boolean flag) {
        if (flag) {
            a();
        } else {
            b();
        }
        for (int i = 0; i < 3; i++) {
            c();
        }
    }

    void plainGuard(String s, int n) {
        if (s != null && n > 0) {
            a();
        }
    }

    void allCasesBreak(int k) {
        switch (k) {
            case 1:
                a();
                break;
            default:
                b();
        }
    }

    void arrowSwitch(int k) {
        switch (k) {
            case 1 -> a();
            default -> b();
        }
    }

    void hidden(boolean flag) {
        if (flag && audit()) {
            a();
        }
    }

    void fallsThrough(int k) {
        switch (k) {
            case 1:
                a();
            case 2:
                b();
                break;
        }
    }

    void leavesLoop() {
        for (int i = 0; i < 3; i++) {
            if (i == 1) {
                break;
            }
            c();
        }
    }

    boolean audit() { return true; }
    void a() { }
    void b() { }
    void c() { }
}
