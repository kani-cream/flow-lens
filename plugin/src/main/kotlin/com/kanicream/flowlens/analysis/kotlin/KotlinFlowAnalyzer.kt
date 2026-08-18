package com.kanicream.flowlens.analysis.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.kanicream.flowlens.analysis.DirectFlowExtraction
import com.kanicream.flowlens.analysis.ExtractedBranch
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.ExtractedStructure
import com.kanicream.flowlens.analysis.ExtractedTerminator
import com.kanicream.flowlens.analysis.FlowItem
import com.kanicream.flowlens.analysis.SourceSummary
import com.kanicream.flowlens.analysis.FlowLanguageAnalyzer
import com.kanicream.flowlens.analysis.PsiClassification
import com.kanicream.flowlens.analysis.ResolvedCallTarget
import com.kanicream.flowlens.analysis.TargetClassifier
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin
import com.kanicream.flowlens.service.FlowMetadata
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
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
        return DirectFlowExtraction(extractor.items(), extractor.controlFlowSimplified)
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

    /**
     * Walks a body in evaluation order, emitting calls, control structures with
     * their branches, and terminators. Lambda, object-literal, nested function,
     * and class bodies are traversal boundaries.
     */
    private class Extractor {
        private val root = mutableListOf<FlowItem>()
        private var sink: MutableList<FlowItem> = root

        var controlFlowSimplified = false
            private set

        /** > 0 while walking an operand that short-circuits and has no structure. */
        private var conditionalDepth = 0

        /**
         * Calls extracted so far. The §6 disclosure is about flow the map does not
         * show, so a construct earns it only when a call actually sits in the part
         * that is not represented: `a != null && b > 0` hides nothing.
         */
        private var callsExtracted = 0

        fun items(): List<FlowItem> = root.toList()

        fun walk(element: PsiElement) {
            when (element) {
                // Boundaries (KNOWN_LIMITATIONS.md section 11).
                is KtLambdaExpression, is KtObjectLiteralExpression -> return
                is KtNamedFunction, is KtClassOrObject -> return
                is KtCallExpression -> {
                    element.valueArguments.forEach { arg ->
                        arg.getArgumentExpression()?.let(::walk)
                    }
                    callsExtracted += 1
                    sink += ExtractedCall(
                        callSite = element,
                        kind = FlowNodeKind.CALL,
                        calleeShortName = (element.calleeExpression as? KtNameReferenceExpression)
                            ?.getReferencedName() ?: element.calleeExpression?.text ?: "?",
                        conditional = conditionalDepth > 0,
                    )
                }
                is KtIfExpression -> walkIf(element)
                is KtWhenExpression -> walkWhen(element)
                is KtDoWhileExpression -> walkLoop(element, runsAtLeastOnce = true)
                is KtLoopExpression -> walkLoop(element, runsAtLeastOnce = false)
                is KtTryExpression -> walkTry(element)
                is KtReturnExpression -> {
                    element.returnedExpression?.let(::walk)
                    sink += ExtractedTerminator(FlowNodeKind.RETURN, element)
                }
                is KtThrowExpression -> {
                    element.thrownExpression?.let(::walk)
                    sink += ExtractedTerminator(FlowNodeKind.THROW, element)
                }
                is KtBreakExpression, is KtContinueExpression -> controlFlowSimplified = true
                is KtSafeQualifiedExpression -> {
                    // `a?.f()` runs f only when the receiver is not null; v0.2
                    // marks it rather than giving it a structure. `a?.name` skips
                    // no call, so it discloses nothing.
                    walk(element.receiverExpression)
                    val before = callsExtracted
                    conditional { element.selectorExpression?.let(::walk) }
                    if (callsExtracted > before) controlFlowSimplified = true
                }
                is KtBinaryExpression -> walkBinary(element)
                else -> element.children.forEach(::walk)
            }
        }

        private fun walkIf(element: KtIfExpression) {
            element.condition?.let(::walk)
            val branches = mutableListOf<ExtractedBranch>()
            branches += branch(BranchKind.THEN, null) { element.then?.let(::walk) }
            element.`else`?.let { elseBranch ->
                branches += branch(BranchKind.ELSE, null) { walk(elseBranch) }
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.CONDITION,
                anchor = element,
                summary = SourceSummary.of(element.condition?.text),
                branches = branches,
            )
        }

        private fun walkWhen(element: KtWhenExpression) {
            element.subjectExpression?.let(::walk)
            val branches = element.entries.map { entry ->
                branch(
                    if (entry.isElse) BranchKind.DEFAULT else BranchKind.CASE,
                    if (entry.isElse) {
                        null
                    } else {
                        SourceSummary.of(entry.conditions.joinToString(", ") { it.text })
                    },
                ) {
                    // A subjectless `when` puts its guard in the entry condition.
                    // The guard is evaluated as part of choosing this entry, so it
                    // belongs inside the section rather than before the structure.
                    entry.conditions.forEach(::walk)
                    entry.expression?.let(::walk)
                }
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.SWITCH,
                anchor = element,
                summary = SourceSummary.of(element.subjectExpression?.text),
                branches = branches,
            )
        }

        private fun walkLoop(element: KtLoopExpression, runsAtLeastOnce: Boolean) {
            // A `for` iterates one sequence, evaluated once before the loop.
            (element as? KtForExpression)?.loopRange?.let(::walk)
            val body = branch(BranchKind.BODY, null) {
                when (element) {
                    is KtDoWhileExpression -> {
                        element.body?.let(::walk)
                        element.condition?.let(::walk)
                    }
                    is KtWhileExpression -> {
                        element.condition?.let(::walk)
                        element.body?.let(::walk)
                    }
                    else -> element.body?.let(::walk)
                }
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.LOOP,
                anchor = element,
                summary = SourceSummary.of(loopHeaderOf(element)),
                branches = listOf(body),
                metadata = if (runsAtLeastOnce) {
                    mapOf(FlowMetadata.LOOP_RUNS_AT_LEAST_ONCE to "true")
                } else {
                    emptyMap()
                },
            )
        }

        private fun walkTry(element: KtTryExpression) {
            val branches = mutableListOf<ExtractedBranch>()
            branches += branch(BranchKind.TRY, null) { walk(element.tryBlock) }
            for (clause in element.catchClauses) {
                branches += branch(
                    BranchKind.CATCH,
                    SourceSummary.of(clause.catchParameter?.typeReference?.text),
                ) { clause.catchBody?.let(::walk) }
            }
            element.finallyBlock?.let { finally ->
                branches += branch(BranchKind.FINALLY, null) { walk(finally.finalExpression) }
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.TRY,
                anchor = element,
                summary = null,
                branches = branches,
            )
        }

        private fun walkBinary(element: KtBinaryExpression) {
            val shortCircuit = element.operationToken == KtTokens.ANDAND ||
                element.operationToken == KtTokens.OROR ||
                element.operationToken == KtTokens.ELVIS
            if (!shortCircuit) {
                element.children.forEach(::walk)
                return
            }
            element.left?.let(::walk)
            val before = callsExtracted
            conditional { element.right?.let(::walk) }
            if (callsExtracted > before) controlFlowSimplified = true
        }

        private fun loopHeaderOf(element: KtLoopExpression): String? = when (element) {
            is KtForExpression ->
                "${element.loopParameter?.text.orEmpty()} in ${element.loopRange?.text.orEmpty()}"
            is KtWhileExpression -> element.condition?.text
            is KtDoWhileExpression -> element.condition?.text
            else -> null
        }

        private inline fun branch(
            kind: BranchKind,
            label: String?,
            collect: () -> Unit,
        ): ExtractedBranch {
            val previous = sink
            val collected = mutableListOf<FlowItem>()
            sink = collected
            try {
                collect()
            } finally {
                sink = previous
            }
            return ExtractedBranch(kind, label, collected.toList())
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
