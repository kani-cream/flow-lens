package com.kanicream.flowlens.analysis.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.kanicream.flowlens.analysis.DirectFlowExtraction
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.FlowLanguageAnalyzer
import com.kanicream.flowlens.analysis.PsiClassification
import com.kanicream.flowlens.analysis.ResolvedCallTarget
import com.kanicream.flowlens.analysis.TargetClassifier
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
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
        val calleeName = (callee as? KtNameReferenceExpression)?.getReferencedName()

        // A compiler-generated member has no declaration of its own, so resolution
        // lands on something related instead: `copy` resolves to the primary
        // constructor and `componentN` to the property it returns. Trusting that
        // result reported `u.copy()` as the constructor call `User()` and
        // `u.component1()` as `name()`. When resolution did not land on a callable
        // and the name written at the call site is not the type's own name, the
        // call is a generated member and keeps the name the author wrote.
        val landedOnCallable = resolved is KtNamedFunction ||
            (resolved is PsiMethod && !resolved.isConstructor)
        // Resolution landing on the declaration the author actually named — a
        // function-typed property or parameter being invoked, `handler()` or
        // `cb()` — is an ordinary call, not a generated member. Only a result
        // that names neither the callee nor its type is the compiler filling in
        // a member that has no declaration.
        val resolvedName = (resolved as? PsiNamedElement)?.name
        if (!landedOnCallable &&
            calleeName != null &&
            calleeName != resolvedName &&
            calleeName != ownerTypeName(resolved)
        ) {
            return generatedMemberOf(resolved, calleeName)
        }

        val isConstructor = resolved is KtConstructor<*> || resolved is KtClassOrObject ||
            (resolved is PsiMethod && resolved.isConstructor)
        // `super.foo()` names one implementation statically, like Java's super call.
        val explicitSuper = (site.parent as? KtDotQualifiedExpression)
            ?.receiverExpression is KtSuperExpression
        val confidence = if (explicitSuper) DispatchConfidence.EXACT else dispatchConfidenceOf(resolved)
        return TargetClassifier.classify(
            site.project,
            resolved,
            confidence,
            isConstructor = isConstructor,
            // Constructor calls resolving to the class itself have no explicit body.
            forceNoBody = resolved is KtClassOrObject,
        )
    }

    /** The type that owns [resolved], for naming and grouping generated members. */
    private fun ownerTypeName(resolved: PsiElement): String? =
        PsiTreeUtil.getParentOfType(resolved, KtClassOrObject::class.java, false)?.name
            ?: (resolved as? PsiMethod)?.containingClass?.name

    /**
     * A member the compiler generates. It keeps the name written at the call site,
     * is classified as generated rather than authored source, and is never entered
     * — but it still navigates to the declaration it was generated from
     * (`KNOWN_LIMITATIONS.md` §4).
     */
    private fun generatedMemberOf(resolved: PsiElement, memberName: String): ResolvedCallTarget {
        val owner = PsiTreeUtil.getParentOfType(resolved, KtClassOrObject::class.java, false)
        val qualifier = owner?.fqName?.asString() ?: owner?.name ?: "?"
        return ResolvedCallTarget(
            declaration = resolved,
            symbol = FlowSymbol(
                languageId = languageId,
                displayName = "$memberName()",
                containerName = owner?.name,
                key = "kotlin:$qualifier#$memberName(generated)",
            ),
            resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
            // Which generated member runs is not in doubt; it simply has no body
            // Flow Lens can show.
            dispatchConfidence = DispatchConfidence.EXACT,
            sourceOrigin = SourceOrigin.SYNTHETIC,
            hasAnalyzableBody = false,
            isConstructor = false,
            inTestSource = PsiClassification.isInTestSource(resolved.project, resolved),
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

        /** > 0 while walking code that may not execute (branch, loop body, catch). */
        private var conditionalDepth = 0

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
                        conditional = conditionalDepth > 0,
                    )
                }
                is KtIfExpression -> {
                    controlFlowSimplified = true
                    element.condition?.let(::walk)
                    conditional { element.then?.let(::walk); element.`else`?.let(::walk) }
                }
                is KtWhenExpression -> {
                    controlFlowSimplified = true
                    element.subjectExpression?.let(::walk)
                    conditional { element.entries.forEach(::walk) }
                }
                is KtDoWhileExpression -> {
                    controlFlowSimplified = true
                    // A do-while body and its condition both run at least once.
                    element.children.forEach(::walk)
                }
                is KtLoopExpression -> {
                    controlFlowSimplified = true
                    // `KtLoopExpression.getBody()` returns the expression inside a
                    // container node, so it is a grandchild: the body's owning child
                    // must be found by containment. Comparing by identity would walk
                    // the body twice and emit every call in it as two events.
                    val body = element.body
                    for (child in element.children) {
                        val isBody = body != null && PsiTreeUtil.isAncestor(child, body, false)
                        if (isBody) conditional { walk(child) } else walk(child)
                    }
                }
                is KtTryExpression -> {
                    controlFlowSimplified = true
                    walk(element.tryBlock)
                    conditional { element.catchClauses.forEach(::walk) }
                    element.finallyBlock?.let(::walk)
                }
                is KtBinaryExpression -> {
                    // `?:` short-circuits like `&&` and `||`: the right side runs
                    // only when the left produced null.
                    val shortCircuit = element.operationToken == KtTokens.ANDAND ||
                        element.operationToken == KtTokens.OROR ||
                        element.operationToken == KtTokens.ELVIS
                    if (!shortCircuit) {
                        element.children.forEach(::walk)
                        return
                    }
                    controlFlowSimplified = true
                    element.left?.let(::walk)
                    conditional { element.right?.let(::walk) }
                }
                is KtSafeQualifiedExpression -> {
                    // `a?.f()` calls f only when the receiver is not null.
                    controlFlowSimplified = true
                    element.receiverExpression.let(::walk)
                    conditional { element.selectorExpression?.let(::walk) }
                }
                else -> element.children.forEach(::walk)
            }
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
