package com.kanicream.flowlens

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Structural guarantee for the optional Go dependency (API_STABILITY.md section 8):
 * no class outside the Go analyzer package may reference Go plugin types, so Flow
 * Lens can never fail to start because the Go plugin is absent. Same guard for the
 * optional Kotlin dependency.
 */
class OptionalDependencyIsolationTest {

    private fun classesDir(): File {
        val dir = File(System.getProperty("flowlens.classes.dir", "build/classes/kotlin/main"))
        assertTrue("compiled classes not found at ${dir.absolutePath}", dir.isDirectory)
        return dir
    }

    private fun violations(marker: String, allowedPackagePath: String): List<String> {
        val root = classesDir()
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
            val relative = file.relativeTo(root).path
            if (relative.startsWith(allowedPackagePath)) return@forEach
            val bytes = file.readBytes()
            if (bytes.toString(Charsets.ISO_8859_1).contains(marker)) bad += relative
        }
        return bad
    }

    @Test
    fun `only the go analyzer package references go plugin types`() {
        val bad = violations("com/goide", "com/kanicream/flowlens/analysis/go")
        if (bad.isNotEmpty()) fail("Go plugin types leak into mandatory classes: $bad")
    }

    @Test
    fun `only the kotlin analyzer package references kotlin plugin psi`() {
        val bad = violations("org/jetbrains/kotlin/psi", "com/kanicream/flowlens/analysis/kotlin")
        if (bad.isNotEmpty()) fail("Kotlin PSI types leak into mandatory classes: $bad")
    }
}
