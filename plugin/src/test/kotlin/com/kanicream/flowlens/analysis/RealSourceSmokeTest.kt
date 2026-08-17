package com.kanicream.flowlens.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import java.io.File

/**
 * Dogfooding smoke test (`TEST_STRATEGY.md` §E): runs entry detection, extraction,
 * and resolution over this repository's own Kotlin and Java sources plus the Go
 * sample, which contain far messier shapes than hand-written fixtures — sealed
 * interfaces, objects, data classes, inline functions, lambdas, when expressions,
 * generics, and long files.
 *
 * The assertions are about robustness and honesty rather than resolution rates:
 * the fixture has no IntelliJ platform on its classpath, so many targets are
 * legitimately unresolved. What must hold is that nothing throws, every event has
 * a call site, and no target is silently promoted to authored project source.
 */
class RealSourceSmokeTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private fun repoRoot(): File? =
        System.getProperty("flowlens.repoRoot")?.let(::File)?.takeIf { it.isDirectory }

    private fun sampleFiles(root: File, relativeDir: String, extension: String, limit: Int): List<File> {
        val dir = File(root, relativeDir)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == extension }
            .sortedBy { it.path }
            .take(limit)
            .toList()
    }

    private data class Stats(
        var declarations: Int = 0,
        var entryPoints: Int = 0,
        var calls: Int = 0,
        var resolved: Int = 0,
        var physicalTargets: Int = 0,
    )

    private fun analyze(psiFile: PsiFile, stats: Stats) {
        val declarations = PsiTreeUtil.collectElements(psiFile) { element ->
            FlowAnalyzerRegistry.forDeclaration(element) != null
        }
        for (declaration in declarations) {
            val analyzer = FlowAnalyzerRegistry.forDeclaration(declaration) ?: continue
            stats.declarations += 1
            if (!analyzer.hasAnalyzableBody(declaration)) continue

            // Entry detection from inside the declaration must find it back.
            val offset = declaration.textRange.startOffset + 1
            val entry: PsiElement? = analyzer.findEntryPoint(psiFile, offset)
            if (entry != null) stats.entryPoints += 1

            val extraction = analyzer.extractDirectFlow(declaration)
            for (call in extraction.calls) {
                stats.calls += 1
                assertTrue("every event must have a call site", call.callSite.isValid)
                assertTrue("callee name must not be blank", call.calleeShortName.isNotBlank())
                val target = analyzer.resolveCall(call)
                if (target.resolutionStatus != ResolutionStatus.UNRESOLVED) stats.resolved += 1
                if (target.sourceOrigin == SourceOrigin.PHYSICAL_SOURCE) {
                    stats.physicalTargets += 1
                    assertNotNull(
                        "authored targets must carry a symbol",
                        target.symbol,
                    )
                }
                if (target.hasAnalyzableBody) {
                    assertNotNull("an enterable target must have a declaration", target.declaration)
                }
            }
        }
    }

    fun `test analyzing this repository's own sources produces no failures`() {
        val root = repoRoot()
        if (root == null) {
            // The property is set by the Gradle test task; skip rather than fail
            // when the suite runs from an unusual harness.
            return
        }
        val kotlinSources = sampleFiles(root, "plugin/src/main/kotlin", "kt", limit = 12) +
            sampleFiles(root, "core/src/main/kotlin", "kt", limit = 12)
        val javaSources = sampleFiles(root, "samples/manual-jvm/src/main/java", "java", limit = 12)
        val goSources = sampleFiles(root, "samples/manual-go", "go", limit = 4)
        assertTrue("expected real sources to analyze", kotlinSources.isNotEmpty())

        val stats = Stats()
        for (source in kotlinSources + javaSources + goSources) {
            val psiFile = myFixture.addFileToProject(
                "smoke/${source.parentFile.name}_${source.name}",
                source.readText(),
            )
            analyze(psiFile, stats)
        }

        assertTrue("real sources should contain analyzable declarations", stats.declarations > 20)
        assertTrue("entry detection should find the containing declaration", stats.entryPoints > 20)
        assertTrue("real sources should contain calls", stats.calls > 50)
        // Diagnostics only; the harness lacks the platform classpath, so a low
        // resolution rate here is expected and not a defect.
        println(
            "REAL_SOURCE_SMOKE declarations=${stats.declarations} entryPoints=${stats.entryPoints} " +
                "calls=${stats.calls} resolved=${stats.resolved} physicalTargets=${stats.physicalTargets}",
        )
    }

    fun `test analyzing the same sources twice is stable`() {
        val root = repoRoot() ?: return
        val source = sampleFiles(root, "core/src/main/kotlin", "kt", limit = 3).firstOrNull() ?: return
        val psiFile = myFixture.addFileToProject("stable/${source.name}", source.readText())

        val first = Stats()
        val second = Stats()
        analyze(psiFile, first)
        analyze(psiFile, second)
        assertEquals(first, second)
    }
}
