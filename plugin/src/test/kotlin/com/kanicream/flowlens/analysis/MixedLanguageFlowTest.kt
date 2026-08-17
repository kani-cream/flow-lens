package com.kanicream.flowlens.analysis

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Mixed-language dispatch (V0.1_SPEC.md acceptance E): Java root resolves into a
 * Kotlin body, whose calls resolve back into Java, with the registry switching
 * analyzers per resolved declaration.
 */
class MixedLanguageFlowTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    fun `test java to kotlin to java resolution switches analyzers`() {
        myFixture.addFileToProject(
            "Repository.java",
            "public class Repository { public static void persist() { } }",
        )
        myFixture.addFileToProject(
            "service.kt",
            """
            object KtService {
                @JvmStatic
                fun handle() { Repository.persist() }
            }
            """.trimIndent(),
        )
        val controller = myFixture.configureByText(
            "Controller.java",
            "public class Controller { void run() { KtService.handle(); } }",
        )

        // Step 1: Java analyzer extracts and resolves the call into Kotlin.
        val javaAnalyzer = FlowAnalyzerRegistry.analyzers().first { it.languageId == "java" }
        val rootMethod = PsiTreeUtil.findChildrenOfType(controller, PsiMethod::class.java)
            .first { it.name == "run" }
        val rootExtraction = javaAnalyzer.extractDirectFlow(rootMethod)
        assertEquals(listOf("handle"), rootExtraction.calls.map { it.calleeShortName })
        val kotlinTarget = javaAnalyzer.resolveCall(rootExtraction.calls.single())

        assertEquals(ResolutionStatus.PROJECT_LOCAL, kotlinTarget.resolutionStatus)
        assertEquals(SourceOrigin.PHYSICAL_SOURCE, kotlinTarget.sourceOrigin)
        assertTrue("Kotlin authored body must be recursable", kotlinTarget.hasAnalyzableBody)
        assertEquals("kotlin", kotlinTarget.symbol!!.languageId)
        assertTrue(
            "declaration must unwrap to the authored Kotlin function",
            kotlinTarget.declaration is KtNamedFunction,
        )

        // Step 2: the registry hands the Kotlin declaration to the Kotlin analyzer.
        val nextAnalyzer = FlowAnalyzerRegistry.forDeclaration(kotlinTarget.declaration!!)
        assertNotNull(nextAnalyzer)
        assertEquals("kotlin", nextAnalyzer!!.languageId)

        // Step 3: the Kotlin analyzer resolves back into Java.
        val kotlinExtraction = nextAnalyzer.extractDirectFlow(kotlinTarget.declaration!!)
        assertEquals(listOf("persist"), kotlinExtraction.calls.map { it.calleeShortName })
        val javaTarget = nextAnalyzer.resolveCall(kotlinExtraction.calls.single())
        assertEquals(ResolutionStatus.PROJECT_LOCAL, javaTarget.resolutionStatus)
        assertEquals("java", javaTarget.symbol!!.languageId)
        assertTrue(javaTarget.hasAnalyzableBody)
        assertEquals("java", FlowAnalyzerRegistry.forDeclaration(javaTarget.declaration!!)!!.languageId)
    }

    fun `test kotlin light method from java resolve is not treated as synthetic`() {
        myFixture.addFileToProject(
            "util.kt",
            "fun topLevelUtil() { }",
        )
        val caller = myFixture.configureByText(
            "Caller.java",
            "public class Caller { void run() { UtilKt.topLevelUtil(); } }",
        )
        val javaAnalyzer = FlowAnalyzerRegistry.analyzers().first { it.languageId == "java" }
        val method = PsiTreeUtil.findChildrenOfType(caller, PsiMethod::class.java)
            .first { it.name == "run" }
        val target = javaAnalyzer.resolveCall(javaAnalyzer.extractDirectFlow(method).calls.single())
        assertEquals(SourceOrigin.PHYSICAL_SOURCE, target.sourceOrigin)
        assertTrue(target.hasAnalyzableBody)
    }
}
