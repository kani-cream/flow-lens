package com.kanicream.flowlens.analysis.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.kanicream.flowlens.analysis.DirectFlowExtraction
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.FlowLanguageAnalyzer
import com.kanicream.flowlens.analysis.ResolvedCallTarget
import com.kanicream.flowlens.analysis.TargetClassifier
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Kotlin analyzer: named function calls, member calls, extension calls, and
 * constructor calls, in evaluation order. Compiler-generated declarations
 * (data-class copy/componentN, generated bridges) are classified SYNTHETIC through
 * [TargetClassifier] and never become recursive targets.
 */
class KotlinFlowAnalyzer : FlowLanguageAnalyzer {

    override val languageId: String = "kotlin"

    override fun supportsDeclaration(element: PsiElement): Boolean =
        element is KtNamedFunction || element is KtConstructor<*>

    override fun findEntryPoint(file: PsiFile, offset: Int): PsiElement? {
        if (file.language != KotlinLanguage.INSTANCE) return null
        val leaf = file.findElementAt(offset) ?: return null
        val function = PsiTreeUtil.getParentOfType(
            leaf, KtNamedFunction::class.java, KtSecondaryConstructor::class.java,
        ) ?: return null
        if (!hasAnalyzableBody(function)) return null
        return function
    }

    override fun describeCallable(callable: PsiElement): FlowSymbol = when (callable) {
        is KtNamedFunction -> symbolOf(callable)
        is KtConstructor<*> -> constructorSymbolOf(callable)
        else -> error("unsupported callable ${callable.javaClass.simpleName}")
    }

    override fun hasAnalyzableBody(declaration: PsiElement): Boolean = when (declaration) {
        is KtNamedFunction -> declaration.hasBody()
        is KtSecondaryConstructor -> declaration.bodyExpression != null
        // A primary constructor has no explicit body to traverse in v0.1.
        is KtPrimaryConstructor -> false
        else -> false
    }

    override fun extractDirectFlow(callable: PsiElement): DirectFlowExtraction {
        val extractor = Extractor()
        when (callable) {
            is KtNamedFunction -> callable.bodyBlockExpression?.let(extractor::walk)
                ?: callable.bodyExpression?.let(extractor::walk)
            is KtSecondaryConstructor -> callable.bodyExpression?.let(extractor::walk)
        }
        return DirectFlowExtraction(extractor.calls, extractor.controlFlowSimplified)
    }

    override fun resolveCall(call: ExtractedCall): ResolvedCallTarget {
        val site = call.callSite as? KtCallExpression ?: return ResolvedCallTarget.UNRESOLVED
        val callee = site.calleeExpression ?: return ResolvedCallTarget.UNRESOLVED
        val resolved = callee.mainReference?.resolve() ?: return ResolvedCallTarget.UNRESOLVED
        val isConstructor = resolved is KtConstructor<*> || resolved is KtClass ||
            (resolved is PsiMethod && resolved.isConstructor)
        val confidence = dispatchConfidenceOf(resolved)
        return TargetClassifier.classify(
            site.project,
            resolved,
            confidence,
            isConstructor = isConstructor,
            // Constructor calls resolving to the class itself have no explicit body.
            forceNoBody = resolved is KtClass,
        )
    }

    private fun dispatchConfidenceOf(resolved: PsiElement): DispatchConfidence = when (resolved) {
        is KtConstructor<*>, is KtClass -> DispatchConfidence.EXACT
        is KtNamedFunction -> kotlinFunctionConfidence(resolved)
        is PsiMethod -> javaTargetConfidence(resolved)
        else -> DispatchConfidence.UNKNOWN
    }

    private fun kotlinFunctionConfidence(function: KtNamedFunction): DispatchConfidence {
        val container = function.containingClassOrObject
        if (container == null || function.isTopLevel) return DispatchConfidence.EXACT
        val inInterface = (container as? KtClass)?.isInterface() == true
        val isAbstract = function.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
            (inInterface && !function.hasBody())
        if (isAbstract) return DispatchConfidence.AMBIGUOUS
        val overridable = function.hasModifier(KtTokens.OPEN_KEYWORD) ||
            function.hasModifier(KtTokens.OVERRIDE_KEYWORD) ||
            inInterface ||
            (container as? KtClass)?.hasModifier(KtTokens.ABSTRACT_KEYWORD) == true
        return if (overridable) DispatchConfidence.DECLARED_TARGET else DispatchConfidence.EXACT
    }

    private fun javaTargetConfidence(method: PsiMethod): DispatchConfidence = when {
        method.isConstructor -> DispatchConfidence.EXACT
        method.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) ||
            method.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE) ||
            method.hasModifierProperty(com.intellij.psi.PsiModifier.FINAL) -> DispatchConfidence.EXACT
        method.body == null -> DispatchConfidence.AMBIGUOUS
        else -> DispatchConfidence.DECLARED_TARGET
    }

    private fun symbolOf(function: KtNamedFunction): FlowSymbol {
        val container = function.containingClassOrObject
        val file = function.containingKtFile
        val qualifier = container?.fqName?.asString()
            ?: function.fqName?.parent()?.asString()
            ?: file.name
        val params = function.valueParameters.joinToString(",") { it.typeReference?.text ?: "?" }
        val receiver = function.receiverTypeReference?.text?.let { "$it." } ?: ""
        return FlowSymbol(
            languageId = languageId,
            displayName = "$receiver${function.name}()",
            containerName = container?.name,
            key = "kotlin:$qualifier#$receiver${function.name}($params)",
        )
    }

    private fun constructorSymbolOf(constructor: KtConstructor<*>): FlowSymbol {
        val owner = constructor.containingClassOrObject
        val qualifier = owner?.fqName?.asString() ?: owner?.name ?: "?"
        val params = constructor.valueParameters.joinToString(",") { it.typeReference?.text ?: "?" }
        return FlowSymbol(
            languageId = languageId,
            displayName = "${owner?.name ?: "?"}()",
            containerName = owner?.name,
            key = "kotlin:$qualifier#<init>($params)",
        )
    }

    /** Post-order walk producing evaluation order; lambda/class/local-fn bodies are boundaries. */
    private class Extractor {
        val calls = mutableListOf<ExtractedCall>()
        var controlFlowSimplified = false

        fun walk(element: PsiElement) {
            when (element) {
                // Boundaries (KNOWN_LIMITATIONS.md section 11).
                is KtLambdaExpression, is KtObjectLiteralExpression -> return
                is KtNamedFunction, is KtClassOrObject -> return
                is KtCallExpression -> {
                    // The qualifier chain (receiver) is walked by the enclosing
                    // KtQualifiedExpression before this call is reached.
                    element.valueArguments.forEach { arg ->
                        arg.getArgumentExpression()?.let(::walk)
                    }
                    calls += ExtractedCall(
                        callSite = element,
                        kind = FlowNodeKind.CALL,
                        calleeShortName = (element.calleeExpression as? KtNameReferenceExpression)
                            ?.getReferencedName() ?: element.calleeExpression?.text ?: "?",
                    )
                }
                else -> {
                    if (isControlFlowConstruct(element)) controlFlowSimplified = true
                    element.children.forEach(::walk)
                }
            }
        }

        private fun isControlFlowConstruct(element: PsiElement): Boolean =
            element is KtIfExpression ||
                element is KtWhenExpression ||
                element is KtLoopExpression ||
                element is KtTryExpression ||
                (element is KtBinaryExpression &&
                    (element.operationToken == KtTokens.ANDAND || element.operationToken == KtTokens.OROR))
    }
}
