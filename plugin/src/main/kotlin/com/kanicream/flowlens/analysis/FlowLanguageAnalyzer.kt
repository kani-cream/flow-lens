package com.kanicream.flowlens.analysis

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin

/**
 * One item of a callable body in language evaluation order: a call, a control
 * structure that owns branches, or a terminator. PSI stays in the plugin layer;
 * the run engine converts these to the neutral model.
 */
sealed interface FlowItem

/**
 * A control structure — condition, switch, loop, or try — with its labelled
 * sections (`V0.2_SPEC.md` §3). Code evaluated before entering the structure is
 * emitted as ordinary items before it, not inside it.
 */
class ExtractedStructure(
    val kind: FlowNodeKind,
    /** The element the structure navigates to. */
    val anchor: PsiElement,
    /** Short source text describing the structure, already summarized. */
    val summary: String?,
    val branches: List<ExtractedBranch>,
    /** Extra facts for the renderer, such as a body that runs at least once. */
    val metadata: Map<String, String> = emptyMap(),
) : FlowItem

/** One labelled section of an [ExtractedStructure]. */
class ExtractedBranch(
    val kind: BranchKind,
    val label: String?,
    val items: List<FlowItem>,
)

/** A `return` or `throw`: the point where this path stops. */
class ExtractedTerminator(
    val kind: FlowNodeKind,
    val anchor: PsiElement,
    /**
     * What is handed back, as written. `return;` and `return total();` are
     * different events, and a terminator that cannot say which is barely worth
     * drawing.
     */
    val summary: String? = null,
) : FlowItem

/**
 * One explicit call discovered inside a callable body, in language evaluation order.
 */
class ExtractedCall(
    val callSite: PsiElement,
    val kind: FlowNodeKind,
    val calleeShortName: String,
    val executionMode: ExecutionMode = ExecutionMode.SYNC,
    val orderingStatus: OrderingStatus = OrderingStatus.DETERMINISTIC,
    /**
     * True when the call sits inside a branch, loop body, catch clause, or
     * short-circuit operand, so it may not execute even though its relative
     * order is known. v0.1 has no branch model, so the renderer uses this to
     * avoid drawing a connector that claims a proven path (`V0.1_SPEC.md` §13).
     */
    val conditional: Boolean = false,
) : FlowItem

/** The ordered direct flow of one callable body. */
class DirectFlowExtraction(
    val items: List<FlowItem>,
    /**
     * True only when the body contains control flow this analyzer did not
     * represent — a short-circuit operand, a `break`, a fall-through
     * (`V0.2_SPEC.md` §6). Represented structures do not set it.
     */
    val controlFlowSimplified: Boolean,
) {
    /** Every call in the body, structures included, in evaluation order. */
    val calls: List<ExtractedCall> get() = flatten(items)

    private fun flatten(items: List<FlowItem>): List<ExtractedCall> = items.flatMap { item ->
        when (item) {
            is ExtractedCall -> listOf(item)
            is ExtractedStructure -> item.branches.flatMap { flatten(it.items) }
            is ExtractedTerminator -> emptyList()
        }
    }
}

/** Resolution outcome for one call site. */
class ResolvedCallTarget(
    val declaration: PsiElement?,
    val symbol: FlowSymbol?,
    val resolutionStatus: ResolutionStatus,
    val dispatchConfidence: DispatchConfidence,
    val sourceOrigin: SourceOrigin,
    /**
     * True when the analyzer could traverse this declaration's body. This is a
     * language fact only; whether the body may actually be entered is a traversal
     * policy decision (origin, project membership, settings) made by the engine.
     */
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
