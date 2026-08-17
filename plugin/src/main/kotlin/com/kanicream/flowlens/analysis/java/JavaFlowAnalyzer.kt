package com.kanicream.flowlens.analysis.java

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLoopStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiSuperExpression
import com.intellij.psi.PsiSwitchBlock
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.util.PsiTreeUtil
import com.kanicream.flowlens.analysis.DirectFlowExtraction
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.FlowLanguageAnalyzer
import com.kanicream.flowlens.analysis.TargetClassifier
import com.kanicream.flowlens.analysis.ResolvedCallTarget
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol

/**
 * Java analyzer: explicit method calls, constructor calls, and explicit
 * this(...)/super(...) invocations, in Java evaluation order (qualifier, then
 * arguments left to right, then the call itself).
 */
class JavaFlowAnalyzer : FlowLanguageAnalyzer {

    override val languageId: String = "java"

    override fun supportsDeclaration(element: PsiElement): Boolean =
        element is PsiMethod && element.language == JavaLanguage.INSTANCE

    override fun findEntryPoint(file: PsiFile, offset: Int): PsiElement? {
        if (file.language != JavaLanguage.INSTANCE) return null
        val leaf = file.findElementAt(offset) ?: return null
        val method = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java, false) ?: return null
        // Abstract and native methods have no analyzable body and are not entry points.
        if (method.body == null) return null
        return method
    }

    override fun describeCallable(callable: PsiElement): FlowSymbol = symbolOf(callable as PsiMethod)

    override fun hasAnalyzableBody(declaration: PsiElement): Boolean =
        declaration is PsiMethod && declaration.body != null

    override fun extractDirectFlow(callable: PsiElement): DirectFlowExtraction {
        val method = callable as PsiMethod
        val extraction = Extractor()
        method.body?.let(extraction::walk)
        return DirectFlowExtraction(extraction.calls, extraction.controlFlowSimplified)
    }

    override fun resolveCall(call: ExtractedCall): ResolvedCallTarget {
        return when (val site = call.callSite) {
            is PsiMethodCallExpression -> resolveMethodCall(site)
            is PsiNewExpression -> resolveConstructorCall(site)
            else -> ResolvedCallTarget.UNRESOLVED
        }
    }

    private fun resolveMethodCall(site: PsiMethodCallExpression): ResolvedCallTarget {
        val method = site.resolveMethod() ?: return ResolvedCallTarget.UNRESOLVED
        val confidence = dispatchConfidenceOf(site, method)
        return TargetClassifier.classify(site.project, method, confidence, isConstructor = method.isConstructor)
    }

    private fun resolveConstructorCall(site: PsiNewExpression): ResolvedCallTarget {
        val constructor = site.resolveConstructor()
        if (constructor != null) {
            return TargetClassifier.classify(
                site.project, constructor, DispatchConfidence.EXACT, isConstructor = true,
            )
        }
        // Implicit default constructor: the class is the best available target.
        val psiClass = site.classReference?.resolve() as? PsiClass ?: return ResolvedCallTarget.UNRESOLVED
        return TargetClassifier.classify(
            site.project, psiClass, DispatchConfidence.EXACT,
            isConstructor = true, forceNoBody = true,
        )
    }

    private fun dispatchConfidenceOf(site: PsiMethodCallExpression, method: PsiMethod): DispatchConfidence {
        if (method.isConstructor) return DispatchConfidence.EXACT
        val qualifier = site.methodExpression.qualifierExpression
        if (qualifier is PsiSuperExpression) return DispatchConfidence.EXACT
        val containingClass = method.containingClass
        val nonOverridable = method.hasModifierProperty(PsiModifier.STATIC) ||
            method.hasModifierProperty(PsiModifier.PRIVATE) ||
            method.hasModifierProperty(PsiModifier.FINAL) ||
            containingClass?.hasModifierProperty(PsiModifier.FINAL) == true ||
            containingClass?.isRecord == true
        return when {
            nonOverridable -> DispatchConfidence.EXACT
            method.body == null -> DispatchConfidence.AMBIGUOUS
            else -> DispatchConfidence.DECLARED_TARGET
        }
    }

    private fun symbolOf(method: PsiMethod): FlowSymbol {
        val container = method.containingClass
        val params = method.parameterList.parameters.joinToString(",") { it.type.canonicalText }
        val qualifier = container?.qualifiedName ?: container?.name ?: method.containingFile?.name ?: "?"
        return FlowSymbol(
            languageId = languageId,
            displayName = "${method.name}()",
            containerName = container?.name,
            key = "java:$qualifier#${method.name}($params)",
        )
    }

    /** Post-order walk producing Java evaluation order; lambda and class bodies are boundaries. */
    private class Extractor {
        val calls = mutableListOf<ExtractedCall>()
        var controlFlowSimplified = false

        /** > 0 while walking code that may not execute (branch, loop body, catch). */
        private var conditionalDepth = 0

        fun walk(element: PsiElement) {
            when (element) {
                is PsiComment -> return
                // Traversal boundaries (KNOWN_LIMITATIONS.md section 11): nested callable
                // bodies do not execute as part of this frame's synchronous flow.
                is PsiLambdaExpression -> return
                is PsiClass -> return
                is PsiMethodCallExpression -> {
                    element.methodExpression.qualifierExpression?.let(::walk)
                    element.argumentList.expressions.forEach(::walk)
                    addCall(
                        element,
                        FlowNodeKind.CALL,
                        element.methodExpression.referenceName ?: element.methodExpression.text,
                    )
                }
                is PsiNewExpression -> {
                    element.qualifier?.let(::walk)
                    element.argumentList?.expressions?.forEach(::walk)
                    // An anonymous class body is a boundary; its arguments were walked above.
                    addCall(
                        element,
                        FlowNodeKind.CONSTRUCTOR,
                        element.classReference?.referenceName ?: "new",
                    )
                }
                is PsiIfStatement -> {
                    controlFlowSimplified = true
                    element.condition?.let(::walk)
                    conditional { element.thenBranch?.let(::walk); element.elseBranch?.let(::walk) }
                }
                is PsiConditionalExpression -> {
                    controlFlowSimplified = true
                    walk(element.condition)
                    conditional { element.thenExpression?.let(::walk); element.elseExpression?.let(::walk) }
                }
                is PsiLoopStatement -> {
                    controlFlowSimplified = true
                    // Everything but the loop body is walked in place; the body may
                    // run zero times.
                    element.children.filter { it !== element.body }.forEach(::walk)
                    conditional { element.body?.let(::walk) }
                }
                is PsiSwitchBlock -> {
                    controlFlowSimplified = true
                    element.expression?.let(::walk)
                    conditional { element.body?.let(::walk) }
                }
                is PsiTryStatement -> {
                    controlFlowSimplified = true
                    // try and finally blocks run; catch sections only on failure.
                    element.resourceList?.let(::walk)
                    element.tryBlock?.let(::walk)
                    conditional { element.catchSections.forEach(::walk) }
                    element.finallyBlock?.let(::walk)
                }
                is PsiPolyadicExpression -> {
                    val shortCircuit = element.operationTokenType == JavaTokenType.ANDAND ||
                        element.operationTokenType == JavaTokenType.OROR
                    if (!shortCircuit) {
                        element.children.forEach(::walk)
                        return
                    }
                    controlFlowSimplified = true
                    val operands = element.operands
                    operands.firstOrNull()?.let(::walk)
                    conditional { operands.drop(1).forEach(::walk) }
                }
                else -> {
                    if (element is PsiAnonymousClass) return
                    element.children.forEach(::walk)
                }
            }
        }

        private fun addCall(callSite: PsiElement, kind: FlowNodeKind, shortName: String) {
            calls += ExtractedCall(
                callSite = callSite,
                kind = kind,
                calleeShortName = shortName,
                conditional = conditionalDepth > 0,
            )
        }

        private inline fun conditional(block: () -> Unit) {
            conditionalDepth += 1
            try {
                block()
            } finally {
                conditionalDepth -= 1
            }
        }
    }
}
