package com.kanicream.flowlens.analysis.go

import com.goide.GoLanguage
import com.goide.psi.GoCallExpr
import com.goide.psi.GoDeferStatement
import com.goide.psi.GoFile
import com.goide.psi.GoForStatement
import com.goide.psi.GoFunctionLit
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoGoStatement
import com.goide.psi.GoIfStatement
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoSelectStatement
import com.goide.psi.GoSwitchStatement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.kanicream.flowlens.analysis.DirectFlowExtraction
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.FlowLanguageAnalyzer
import com.kanicream.flowlens.analysis.PsiClassification
import com.kanicream.flowlens.analysis.ResolvedCallTarget
import com.kanicream.flowlens.analysis.TargetClassifier
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin

/**
 * Go analyzer: package functions, receiver methods, and ordinary calls.
 * `go f()` is preserved as GOROUTINE and `defer f()` as DEFERRED execution mode;
 * argument expressions of a deferred call remain ordered by Go evaluation semantics
 * while the deferred invocation itself is not an immediate synchronous continuation.
 *
 * This class is loaded only through the optional Go descriptor (flow-lens-go.xml);
 * no mandatory startup path references Go plugin types.
 */
class GoFlowAnalyzer : FlowLanguageAnalyzer {

    override val languageId: String = "go"

    override fun supportsDeclaration(element: PsiElement): Boolean =
        element is GoFunctionOrMethodDeclaration

    override fun findEntryPoint(file: PsiFile, offset: Int): PsiElement? {
        if (file !is GoFile) return null
        val leaf = file.findElementAt(offset) ?: return null
        val declaration = PsiTreeUtil.getParentOfType(
            leaf, GoFunctionOrMethodDeclaration::class.java, false,
        ) ?: return null
        // A function literal between the caret and the declaration means the caret
        // is inside a closure, which is not an independent entry point in v0.1.
        val literal = PsiTreeUtil.getParentOfType(leaf, GoFunctionLit::class.java, false)
        if (literal != null && PsiTreeUtil.isAncestor(declaration, literal, false)) return null
        if (declaration.block == null) return null
        return declaration
    }

    override fun describeCallable(callable: PsiElement): FlowSymbol =
        symbolOf(callable as GoFunctionOrMethodDeclaration)

    override fun hasAnalyzableBody(declaration: PsiElement): Boolean =
        declaration is GoFunctionOrMethodDeclaration && declaration.block != null

    override fun extractDirectFlow(callable: PsiElement): DirectFlowExtraction {
        val declaration = callable as GoFunctionOrMethodDeclaration
        val extractor = Extractor()
        declaration.block?.let(extractor::walk)
        return DirectFlowExtraction(extractor.calls, extractor.controlFlowSimplified)
    }

    override fun resolveCall(call: ExtractedCall): ResolvedCallTarget {
        val site = call.callSite as? GoCallExpr ?: return ResolvedCallTarget.UNRESOLVED
        val reference = (site.expression as? GoReferenceExpression)?.reference
            ?: return ResolvedCallTarget.UNRESOLVED
        val resolved = reference.resolve() ?: return ResolvedCallTarget.UNRESOLVED
        if (resolved is GoMethodSpec) {
            // Interface method: no single continuation body can responsibly be chosen.
            return TargetClassifier.classify(site.project, resolved, DispatchConfidence.AMBIGUOUS)
        }
        if (resolved !is GoFunctionOrMethodDeclaration) {
            // Built-ins and non-callable targets: conservative terminal treatment.
            return builtInOrUnknown(site, resolved)
        }
        // Go dispatch through a concrete function/method is static.
        return TargetClassifier.classify(site.project, resolved, DispatchConfidence.EXACT)
    }

    private fun builtInOrUnknown(site: GoCallExpr, resolved: PsiElement): ResolvedCallTarget {
        val origin = PsiClassification.sourceOriginOf(site.project, resolved)
        val isBuiltIn = resolved.containingFile?.name == "builtin.go"
        return ResolvedCallTarget(
            declaration = resolved,
            symbol = null,
            resolutionStatus = if (isBuiltIn) ResolutionStatus.BUILT_IN else ResolutionStatus.UNRESOLVED,
            dispatchConfidence = DispatchConfidence.UNKNOWN,
            sourceOrigin = if (isBuiltIn) SourceOrigin.LIBRARY else origin,
            hasAnalyzableBody = false,
        )
    }

    private fun symbolOf(declaration: GoFunctionOrMethodDeclaration): FlowSymbol {
        val name = declaration.name ?: "?"
        val receiver = (declaration as? GoMethodDeclaration)
            ?.receiverType?.text?.removePrefix("*")
        val packageName = (declaration.containingFile as? GoFile)?.packageName
        val display = if (receiver.isNullOrEmpty()) "$name()" else "$receiver.$name()"
        return FlowSymbol(
            languageId = languageId,
            displayName = display,
            containerName = receiver ?: packageName,
            key = "go:${packageName ?: "?"}.${receiver?.plus(".") ?: ""}$name",
        )
    }

    /** Post-order walk producing Go evaluation order; function literals are boundaries. */
    private class Extractor {
        val calls = mutableListOf<ExtractedCall>()
        var controlFlowSimplified = false

        fun walk(element: PsiElement) {
            when (element) {
                is PsiComment -> return
                // Closures are traversal boundaries (KNOWN_LIMITATIONS.md section 11).
                is GoFunctionLit -> return
                is GoCallExpr -> {
                    element.expression?.let(::walk)
                    element.argumentList.expressionList.forEach(::walk)
                    calls += ExtractedCall(
                        callSite = element,
                        kind = FlowNodeKind.CALL,
                        calleeShortName = calleeNameOf(element),
                        executionMode = executionModeOf(element),
                    )
                }
                else -> {
                    if (isControlFlowConstruct(element)) controlFlowSimplified = true
                    element.children.forEach(::walk)
                }
            }
        }

        private fun calleeNameOf(call: GoCallExpr): String =
            (call.expression as? GoReferenceExpression)?.identifier?.text
                ?: call.expression?.text ?: "?"

        /**
         * `go f()` and `defer f()` apply to the statement's own call expression;
         * nested argument calls evaluate immediately and stay SYNC.
         */
        private fun executionModeOf(call: GoCallExpr): ExecutionMode {
            val parent = call.parent
            return when {
                parent is GoGoStatement && parent.expression === call -> ExecutionMode.GOROUTINE
                parent is GoDeferStatement && parent.expression === call -> ExecutionMode.DEFERRED
                else -> ExecutionMode.SYNC
            }
        }

        private fun isControlFlowConstruct(element: PsiElement): Boolean =
            element is GoIfStatement ||
                element is GoForStatement ||
                element is GoSwitchStatement ||
                element is GoSelectStatement
    }
}
