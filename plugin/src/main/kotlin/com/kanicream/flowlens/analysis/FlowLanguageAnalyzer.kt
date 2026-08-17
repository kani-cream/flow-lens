package com.kanicream.flowlens.analysis

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin

/**
 * One explicit call discovered inside a callable body, in language evaluation order.
 * PSI stays in the plugin layer; the run engine converts elements to the neutral model.
 */
class ExtractedCall(
    val callSite: PsiElement,
    val kind: FlowNodeKind,
    val calleeShortName: String,
    val executionMode: ExecutionMode = ExecutionMode.SYNC,
    val orderingStatus: OrderingStatus = OrderingStatus.DETERMINISTIC,
)

/** The ordered direct flow of one callable body. */
class DirectFlowExtraction(
    val calls: List<ExtractedCall>,
    val controlFlowSimplified: Boolean,
)

/** Resolution outcome for one call site. */
class ResolvedCallTarget(
    val declaration: PsiElement?,
    val symbol: FlowSymbol?,
    val resolutionStatus: ResolutionStatus,
    val dispatchConfidence: DispatchConfidence,
    val sourceOrigin: SourceOrigin,
    val hasAnalyzableBody: Boolean,
    val isConstructor: Boolean = false,
    val inTestSource: Boolean = false,
) {
    companion object {
        val UNRESOLVED = ResolvedCallTarget(
            declaration = null,
            symbol = null,
            resolutionStatus = ResolutionStatus.UNRESOLVED,
            dispatchConfidence = DispatchConfidence.UNKNOWN,
            sourceOrigin = SourceOrigin.UNKNOWN,
            hasAnalyzableBody = false,
        )
    }
}

/**
 * Language adapter contract. Selected per resolved callable through
 * [FlowAnalyzerRegistry], so a flow may cross Java/Kotlin boundaries.
 *
 * All methods are called inside a read action by the run engine; implementations
 * must not start their own long-running work or touch UI.
 */
interface FlowLanguageAnalyzer {
    /** Stable analyzer id, also used for capability reporting. */
    val languageId: String

    /** True when [element] is a callable declaration this analyzer can analyze. */
    fun supportsDeclaration(element: PsiElement): Boolean

    /** Containing supported callable declaration at [offset], or null. No guessing. */
    fun findEntryPoint(file: PsiFile, offset: Int): PsiElement?

    /** Language-neutral symbol descriptor for a supported callable. */
    fun describeCallable(callable: PsiElement): FlowSymbol

    /** True when [declaration] has an explicit body this analyzer can traverse. */
    fun hasAnalyzableBody(declaration: PsiElement): Boolean

    /** Ordered explicit calls of the callable body. */
    fun extractDirectFlow(callable: PsiElement): DirectFlowExtraction

    /** Resolves one extracted call site to its target classification. */
    fun resolveCall(call: ExtractedCall): ResolvedCallTarget

    companion object {
        val EP_NAME: ExtensionPointName<FlowLanguageAnalyzer> =
            ExtensionPointName.create("com.kanicream.flowlens.flowLanguageAnalyzer")
    }
}
