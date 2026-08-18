package com.kanicream.flowlens.analysis.java

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiContinueStatement
import com.intellij.psi.PsiDoWhileStatement
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiSwitchLabeledRuleStatement
import com.intellij.psi.PsiThrowStatement
import com.intellij.psi.PsiWhileStatement
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
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.kanicream.flowlens.analysis.DirectFlowExtraction
import com.kanicream.flowlens.analysis.ExtractedBranch
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.ExtractedStructure
import com.kanicream.flowlens.analysis.ExtractedTerminator
import com.kanicream.flowlens.analysis.FlowItem
import com.kanicream.flowlens.analysis.SourceSummary
import com.kanicream.flowlens.analysis.FlowLanguageAnalyzer
import com.kanicream.flowlens.analysis.TargetClassifier
import com.kanicream.flowlens.analysis.ResolvedCallTarget
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.service.FlowMetadata

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
        return DirectFlowExtraction(extraction.items(), extraction.controlFlowSimplified)
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
            // Java methods are virtual by default, so reporting every ordinary
            // call as "runtime override may differ" drowns the cases where that
            // is actually true. The declared body is the only implementation
            // unless something in the project overrides it.
            isOverriddenInProject(method) -> DispatchConfidence.DECLARED_TARGET
            else -> DispatchConfidence.EXACT
        }
    }

    /**
     * Whether some subclass overrides [method].
     *
     * Both searches are index-backed, bounded to the first hit, and cached per
     * declaration until PSI changes, so a frame with many calls does not turn
     * into a series of repeated searches. The cheap class-level question is asked
     * first: most calls resolve to a type nothing extends, and then no
     * per-method search happens at all.
     *
     * Runtime substitution that leaves no source behind — proxies, generated
     * bytecode, dependency injection — is out of scope by design
     * (`KNOWN_LIMITATIONS.md` §2).
     */
    private fun isOverriddenInProject(method: PsiMethod): Boolean {
        val containingClass = method.containingClass ?: return false
        if (!hasInheritors(containingClass)) return false
        return CachedValuesManager.getCachedValue(method) {
            CachedValueProvider.Result.create(
                OverridingMethodsSearch.search(method).findFirst() != null,
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }
    }

    private fun hasInheritors(psiClass: PsiClass): Boolean =
        CachedValuesManager.getCachedValue(psiClass) {
            CachedValueProvider.Result.create(
                ClassInheritorsSearch.search(psiClass).findFirst() != null,
                PsiModificationTracker.MODIFICATION_COUNT,
            )
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

    /**
     * Walks a body in Java evaluation order, emitting calls, control structures
     * with their branches, and terminators. Lambda and class bodies are
     * traversal boundaries.
     *
     * Code evaluated before a structure is entered — a condition, a switch
     * selector, a loop initializer — is emitted before the structure; code that
     * repeats or is chosen between lives inside it (`V0.2_SPEC.md` §4).
     */
    private class Extractor {
        private val root = mutableListOf<FlowItem>()
        private var sink: MutableList<FlowItem> = root

        /** Set only by control flow this analyzer does not represent. */
        var controlFlowSimplified = false
            private set

        /** > 0 while walking a short-circuit operand, which has no structure. */
        private var conditionalDepth = 0

        fun items(): List<FlowItem> = root.toList()

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
                    addCall(
                        element,
                        FlowNodeKind.CONSTRUCTOR,
                        element.classReference?.referenceName ?: "new",
                    )
                }
                is PsiIfStatement -> walkIf(element)
                is PsiConditionalExpression -> walkTernary(element)
                is PsiSwitchBlock -> walkSwitch(element)
                is PsiLoopStatement -> walkLoop(element)
                is PsiTryStatement -> walkTry(element)
                is PsiReturnStatement -> {
                    element.returnValue?.let(::walk)
                    sink += ExtractedTerminator(FlowNodeKind.RETURN, element)
                }
                is PsiThrowStatement -> {
                    element.exception?.let(::walk)
                    sink += ExtractedTerminator(FlowNodeKind.THROW, element)
                }
                is PsiBreakStatement, is PsiContinueStatement -> {
                    // Jumps are not drawn as edges in v0.2, so the result stays
                    // disclosed as simplified (`V0.2_SPEC.md` §6).
                    controlFlowSimplified = true
                }
                is PsiPolyadicExpression -> walkPolyadic(element)
                else -> element.children.forEach(::walk)
            }
        }

        private fun walkIf(element: PsiIfStatement) {
            element.condition?.let(::walk)
            val branches = mutableListOf<ExtractedBranch>()
            branches += branch(BranchKind.THEN, null) { element.thenBranch?.let(::walk) }
            element.elseBranch?.let { elseBranch ->
                branches += branch(BranchKind.ELSE, null) { walk(elseBranch) }
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.CONDITION,
                anchor = element,
                summary = SourceSummary.of(element.condition?.text),
                branches = branches,
            )
        }

        private fun walkTernary(element: PsiConditionalExpression) {
            walk(element.condition)
            sink += ExtractedStructure(
                kind = FlowNodeKind.CONDITION,
                anchor = element,
                summary = SourceSummary.of(element.condition.text),
                branches = listOf(
                    branch(BranchKind.THEN, null) { element.thenExpression?.let(::walk) },
                    branch(BranchKind.ELSE, null) { element.elseExpression?.let(::walk) },
                ),
            )
        }

        private fun walkSwitch(element: PsiSwitchBlock) {
            element.expression?.let(::walk)
            val branches = mutableListOf<ExtractedBranch>()
            var current: MutableList<PsiElement>? = null
            var currentLabel: PsiSwitchLabelStatementBase? = null
            fun flush() {
                val statements = current ?: return
                val label = currentLabel
                branches += branch(
                    if (label?.isDefaultCase == true) BranchKind.DEFAULT else BranchKind.CASE,
                    labelTextOf(label),
                ) { statements.forEach(::walk) }
            }
            for (child in element.body?.children.orEmpty()) {
                when (child) {
                    is PsiSwitchLabelStatementBase -> {
                        flush()
                        currentLabel = child
                        current = mutableListOf()
                        // A rule-style label (`case 1 -> f();`) carries its body.
                        (child as? PsiSwitchLabeledRuleStatement)?.body?.let { current?.add(it) }
                    }
                    is PsiComment -> Unit
                    else -> current?.add(child)
                }
            }
            flush()
            sink += ExtractedStructure(
                kind = FlowNodeKind.SWITCH,
                anchor = element,
                summary = SourceSummary.of(element.expression?.text),
                branches = branches,
            )
        }

        private fun walkLoop(element: PsiLoopStatement) {
            // Whatever runs once before the loop is emitted before the container.
            when (element) {
                is PsiForStatement -> element.initialization?.let(::walk)
                is PsiForeachStatement -> element.iteratedValue?.let(::walk)
                else -> Unit
            }
            val body = branch(BranchKind.BODY, null) {
                // Everything evaluated per iteration belongs inside, in source
                // order: condition, body, then update.
                when (element) {
                    is PsiForStatement -> {
                        element.condition?.let(::walk)
                        element.body?.let(::walk)
                        element.update?.let(::walk)
                    }
                    is PsiWhileStatement -> {
                        element.condition?.let(::walk)
                        element.body?.let(::walk)
                    }
                    is PsiDoWhileStatement -> {
                        element.body?.let(::walk)
                        element.condition?.let(::walk)
                    }
                    else -> element.body?.let(::walk)
                }
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.LOOP,
                anchor = element,
                summary = SourceSummary.of(loopHeaderOf(element)),
                branches = listOf(body),
                metadata = if (element is PsiDoWhileStatement) {
                    mapOf(FlowMetadata.LOOP_RUNS_AT_LEAST_ONCE to "true")
                } else {
                    emptyMap()
                },
            )
        }

        private fun walkTry(element: PsiTryStatement) {
            element.resourceList?.let(::walk)
            val branches = mutableListOf<ExtractedBranch>()
            branches += branch(BranchKind.TRY, null) { element.tryBlock?.let(::walk) }
            for (section in element.catchSections) {
                branches += branch(
                    BranchKind.CATCH,
                    SourceSummary.of(section.catchType?.presentableText),
                ) { section.catchBlock?.let(::walk) }
            }
            element.finallyBlock?.let { finally ->
                branches += branch(BranchKind.FINALLY, null) { walk(finally) }
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.TRY,
                anchor = element,
                summary = null,
                branches = branches,
            )
        }

        private fun walkPolyadic(element: PsiPolyadicExpression) {
            val shortCircuit = element.operationTokenType == JavaTokenType.ANDAND ||
                element.operationTokenType == JavaTokenType.OROR
            if (!shortCircuit) {
                element.children.forEach(::walk)
                return
            }
            // An operand that may never be evaluated is marked rather than given
            // a structure of its own (`V0.2_SPEC.md` §2).
            controlFlowSimplified = true
            val operands = element.operands
            operands.firstOrNull()?.let(::walk)
            conditionalDepth += 1
            try {
                operands.drop(1).forEach(::walk)
            } finally {
                conditionalDepth -= 1
            }
        }

        private fun labelTextOf(label: PsiSwitchLabelStatementBase?): String? {
            if (label == null || label.isDefaultCase) return null
            return SourceSummary.of(label.caseLabelElementList?.text)
        }

        private fun loopHeaderOf(element: PsiLoopStatement): String? = when (element) {
            is PsiForeachStatement ->
                "${element.iterationParameter.name} : ${element.iteratedValue?.text.orEmpty()}"
            is PsiWhileStatement -> element.condition?.text
            is PsiDoWhileStatement -> element.condition?.text
            is PsiForStatement -> element.condition?.text
            else -> null
        }

        /** Collects everything [collect] emits into a new labelled section. */
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

        private fun addCall(callSite: PsiElement, kind: FlowNodeKind, shortName: String) {
            sink += ExtractedCall(
                callSite = callSite,
                kind = kind,
                calleeShortName = shortName,
                conditional = conditionalDepth > 0,
            )
        }
    }
}
