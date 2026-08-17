package demo

/**
 * Kotlin layer of the mixed flow (E) and the synthetic negative control.
 * Expand handle() from the Java root, or analyze it directly.
 *
 * Expected inside handle(): persist (Java), copy (terminal, never expandable),
 * greet (authored member, expandable).
 */
object KtService {
    @JvmStatic
    fun handle() {
        Repository.persist()      // Kotlin -> Java
        val u = User("a")
        u.copy()                  // negative control: synthetic, must NOT expand
        u.greet()                 // authored member: must be expandable
    }
}

data class User(val name: String) {
    fun greet() {
        println(name)
    }
}
