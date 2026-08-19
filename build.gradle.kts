plugins {
    // Kotlin 2.3.x is required (not 2.2.x): the Kotlin analyzer compiles against the
    // bundled Kotlin plugin of IntelliJ 2026.1, whose jars carry Kotlin 2.4 metadata.
    // A compiler reads metadata one minor version ahead of itself, so 2.3.x reads 2.4
    // metadata without -Xskip-metadata-version-check, which API_STABILITY.md forbids
    // as a production compatibility strategy.
    kotlin("jvm") version "2.3.21" apply false
    id("org.jetbrains.intellij.platform") version "2.18.1" apply false
}

allprojects {
    group = "com.kanicream.flowlens"
    // v0.1: multi-language method flow + Flow Canvas (see plan/V0.1_SPEC.md).
    version = "0.5.0"
}

subprojects {
    // Apache 2.0 §4(a) and §4(d): whoever receives a jar receives the licence
    // and the notice with it, rather than being expected to find the repository.
    // Every module, because each is shipped as its own jar inside the plugin
    // distribution.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.named<Jar>("jar") {
            from(rootProject.file("LICENSE")) { into("META-INF") }
            from(rootProject.file("NOTICE")) { into("META-INF") }
        }
    }
}
