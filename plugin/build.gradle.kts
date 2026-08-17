import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Repo Lens shipped with Plugin Verifier warnings caused by compiler-generated
        // Java-default-interface compatibility bridges (e.g. on ToolWindowFactory).
        // The stable no-compatibility mode stops emitting those bridges; Verifier
        // output for the compiled plugin is the acceptance evidence.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        // Exact patch pinned per API_STABILITY.md section 2. 2026.1.5 (build
        // 261.27258) is the earliest 261 patch known to load the current
        // marketplace Go plugin for the 261 line (evidence: Repo Lens baseline).
        intellijIdea("2026.1.5")
        // Java PSI for the Java analyzer. Required dependency: Flow Lens targets
        // IntelliJ IDEA Ultimate where the Java plugin is always present.
        bundledPlugin("com.intellij.java")
        // Kotlin PSI + resolution for the Kotlin analyzer. Optional at runtime
        // (flow-lens-kotlin.xml) so a disabled Kotlin plugin cannot break startup.
        bundledPlugin("org.jetbrains.kotlin")
        // Go PSI for the Go analyzer. Marketplace plugin, optional at runtime
        // (flow-lens-go.xml): Flow Lens must load and analyze Java/Kotlin without it.
        plugin("org.jetbrains.plugins.go", "261.26222.22")
        testFramework(TestFrameworkType.Platform)
        // Java-specific fixtures (LightJavaCodeInsightFixtureTestCase, mock JDK).
        testFramework(TestFrameworkType.Plugin.Java)
    }

    testImplementation("junit:junit:4.13.2")
}

tasks.named<RunIdeTask>("runIde") {
    // Flow Lens does not claim dynamic unload safety (IMPLEMENTATION_GUARDRAILS.md
    // section 16); restart the sandbox to pick up rebuilt jars.
    systemProperty("idea.auto.reload.plugins", "false")
}

tasks.buildSearchableOptions {
    // The headless searchable-options build requires the IDE default (English)
    // locale; on a Japanese-locale machine it fails with "Locale must be default".
    jvmArgs("-Duser.language=en", "-Duser.country=US")
}

tasks.test {
    // The unified IntelliJ distribution bundles many language plugins whose
    // listeners fail to initialize in the headless test harness; load only what
    // the fixtures need.
    systemProperty(
        "idea.load.plugins.id",
        "com.kanicream.flowlens,com.intellij.java,org.jetbrains.kotlin,org.jetbrains.plugins.go",
    )
    // For OptionalDependencyIsolationTest: the compiled production classes to scan.
    systemProperty(
        "flowlens.classes.dir",
        layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath,
    )
}

intellijPlatform {
    // Distribution/sandbox name; the Gradle subproject stays ":plugin".
    projectName = "flow-lens"
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}
