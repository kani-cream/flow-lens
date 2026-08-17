package demo;

/** Depth-limit case (L): analyze run(); d3 shows the depth-limit marker. */
public class Deep {
    void run() { d1(); }
    void d1() { d2(); }
    void d2() { d3(); }
    void d3() { d4(); }
    void d4() { }
}
