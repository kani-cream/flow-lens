package demo;

/** Cycle case (K): analyze run(); expect "cycle to run()" inside work(). */
public class Cycle {
    void run() { work(); }
    void work() { run(); }
}
