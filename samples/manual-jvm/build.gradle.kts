plugins {
    kotlin("jvm") version "2.3.21"
}

repositories {
    mavenCentral()
}

dependencies {
    // The coroutine builders are on the documented-timing list (V0.5_SPEC.md
    // §4.3), so the sandbox needs them to demonstrate cases B and D.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

kotlin {
    jvmToolchain(21)
}
