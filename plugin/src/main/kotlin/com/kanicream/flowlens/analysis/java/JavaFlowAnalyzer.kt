package com.kanicream.flowlens.analysis.java

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiYieldStatement
import com.intellij.psi.PsiSwitchStatement
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
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiMethodReferenceExpression
import com.kanicream.flowlens.analysis.CallbackTiming
import com.kanicream.flowlens.analysis.ExtractedCallback
import com.kanicream.flowlens.analysis.KnownCallbackApis
import com.kanicream.flowlens.analysis.SymbolQualifier
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
        element is PsiLambdaExpression ||
        element is PsiMethod && element.language == JavaLanguage.INSTANCE

    override fun findEntryPoint(file: PsiFile, offset: Int): PsiElement? {
        if (file.language != JavaLanguage.INSTANCE) return null
        val leaf = file.findElementAt(offset) ?: return null
        val method = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java, false) ?: return null
        // Abstract and native methods have no analyzable body and are not entry points.
        if (method.body == null) return null
        return method
    }

    override fun describeCallable(callable: PsiElement): FlowSymbol = when (callable) {
        is PsiLambdaExpression -> lambdaSymbolOf(callable)
        else -> symbolOf(callable as PsiMethod)
    }

    /**
     * A lambda has no name of its own. Keyed by its position in the file so two
     * lambdas in one method stay distinct, which cycle detection and pins both
     * depend on.
     */
    private fun lambdaSymbolOf(lambda: PsiLambdaExpression): FlowSymbol = FlowSymbol(
        languageId = languageId,
        displayName = "{ }",
        containerName = PsiTreeUtil.getParentOfType(lambda, PsiMethod::class.java)?.name,
        key = "java:lambda@${SymbolQualifier.fileQualifier(lambda)}:${lambda.textOffset}",
    )

    override fun isAnonymousBody(declaration: PsiElement): Boolean =
        declaration is PsiLambdaExpression

    override fun hasAnalyzableBody(declaration: PsiElement): Boolean = when (declaration) {
        is PsiLambdaExpression -> declaration.body != null
        is PsiMethod -> declaration.body != null
        else -> false
    }

    override fun extractDirectFlow(callable: PsiElement): DirectFlowExtraction {
        val extraction = Extractor()
        // A lambda body is an expression or a block; both walk the same way.
        when (callable) {
            is PsiLambdaExpression -> callable.body?.let(extraction::walk)
            else -> (callable as PsiMethod).body?.let(extraction::walk)
        }
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
        // A bare file name repeats across packages, and an anonymous class has
        // no qualified name at all, so the fallback is the project-relative path.
        val qualifier = container?.qualifiedName ?: SymbolQualifier.fileQualifier(method)
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

        /**
         * Calls extracted so far. The disclosure in §6 is about flow the map does
         * not show, so a construct only earns it when a call actually sits in the
         * part that is not represented: `a != null && b > 0` hides nothing.
         */
        private var callsExtracted = 0

        fun items(): List<FlowItem> = root.toList()

        fun walk(element: PsiElement) {
            when (element) {
                is PsiComment -> return
                // A lambda does not run where it is written, so it is not walked
                // here. It is emitted after the call that received it, carrying
                // the timing that says when it does run (`V0.5_SPEC.md` §3).
                is PsiLambdaExpression -> return
                // A method reference names a body it does not run here either,
                // but it is not a callback event: v0.5 covers bodies written in
                // place (`KNOWN_LIMITATIONS.md` §45).
                is PsiMethodReferenceExpression -> return
                is PsiClass -> return
                is PsiMethodCallExpression -> {
                    element.methodExpression.qualifierExpression?.let(::walk)
                    element.argumentList.expressions.forEach(::walk)
                    val name = element.methodExpression.referenceName ?: element.methodExpression.text
                    addCall(element, FlowNodeKind.CALL, name)
                    addCallbacks(element.argumentList, name) { element.resolveMethod() }
                }
                is PsiNewExpression -> {
                    element.qualifier?.let(::walk)
                    element.argumentList?.expressions?.forEach(::walk)
                    addCall(
                        element,
                        FlowNodeKind.CONSTRUCTOR,
                        element.classReference?.referenceName ?: "new",
                    )
                    element.argumentList?.let { args ->
                        addCallbacks(args, element.classReference?.referenceName ?: "new") {
                            element.resolveConstructor()
                        }
                    }
                }
                is PsiIfStatement -> walkIf(element)
                is PsiConditionalExpression -> walkTernary(element)
                is PsiSwitchBlock -> walkSwitch(element)
                is PsiLoopStatement -> walkLoop(element)
                is PsiTryStatement -> walkTry(element)
                is PsiReturnStatement -> {
                    element.returnValue?.let(::walk)
                    sink += ExtractedTerminator(
                        FlowNodeKind.RETURN,
                        element,
                        SourceSummary.of(element.returnValue?.text),
                    )
                }
                is PsiThrowStatement -> {
                    element.exception?.let(::walk)
                    sink += ExtractedTerminator(
                        FlowNodeKind.THROW,
                        element,
                        SourceSummary.of(element.exception?.text),
                    )
                }
                is PsiBreakStatement -> {
                    // A `break` that ends a switch case is already expressed by the
                    // case boundary. Only a jump the map does not show — out of a
                    // loop, or to a label — earns the disclosure (`V0.2_SPEC.md` §6).
                    if (element.findExitedStatement() !is PsiSwitchStatement) {
                        controlFlowSimplified = true
                    }
                }
                is PsiContinueStatement -> controlFlowSimplified = true
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
            // `case 1 -> f();` cannot fall through, so the check below does not
            // apply to a rule-style switch at all.
            var anyRuleLabel = false
            val groups = mutableListOf<Pair<PsiSwitchLabelStatementBase?, List<PsiElement>>>()
            var current: MutableList<PsiElement>? = null
            var currentLabel: PsiSwitchLabelStatementBase? = null
            fun flush() {
                val statements = current ?: return
                groups += currentLabel to statements.toList()
            }
            for (child in element.body?.children.orEmpty()) {
                when (child) {
                    is PsiSwitchLabelStatementBase -> {
                        flush()
                        currentLabel = child
                        current = mutableListOf()
                        // A guard (`case Integer i when check(i)`) is evaluated to
                        // choose this case, so it belongs inside the section.
                        child.guardExpression?.let { current?.add(it) }
                        // A rule-style label (`case 1 -> f();`) carries its body.
                        (child as? PsiSwitchLabeledRuleStatement)?.let {
                            anyRuleLabel = true
                            it.body?.let { body -> current?.add(body) }
                        }
                    }
                    is PsiComment -> Unit
                    else -> current?.add(child)
                }
            }
            flush()

            // A case that neither ends nor is empty runs on into the next one, and
            // v0.2 draws the cases as independent sections (`V0.2_SPEC.md` §6).
            if (!anyRuleLabel && groups.dropLast(1).any { (_, statements) -> fallsThrough(statements) }) {
                controlFlowSimplified = true
            }

            val branches = groups.map { (label, statements) ->
                branch(
                    if (label?.isDefaultCase == true) BranchKind.DEFAULT else BranchKind.CASE,
                    labelTextOf(label),
                ) { statements.forEach(::walk) }
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.SWITCH,
                anchor = element,
                summary = SourceSummary.of(element.expression?.text),
                branches = branches,
            )
        }

        /**
         * Whether [statements] reach the end of their case instead of leaving it.
         * A braced case (`case 1: { a(); break; }`) leaves through the last
         * statement of its block, so the block is looked into rather than counted
         * as an unterminated statement.
         */
        private fun fallsThrough(statements: List<PsiElement>): Boolean {
            val last = statements.lastOrNull { it !is PsiComment && it !is PsiWhiteSpace } ?: return false
            return !leavesTheCase(last)
        }

        private fun leavesTheCase(statement: PsiElement): Boolean = when (statement) {
            is PsiBreakStatement, is PsiContinueStatement -> true
            is PsiReturnStatement, is PsiThrowStatement, is PsiYieldStatement -> true
            is PsiBlockStatement -> statement.codeBlock.statements.lastOrNull()
                ?.let(::leavesTheCase) ?: false
            else -> false
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
            val operands = element.operands
            operands.firstOrNull()?.let(::walk)
            val before = callsExtracted
            conditionalDepth += 1
            try {
                operands.drop(1).forEach(::walk)
            } finally {
                conditionalDepth -= 1
            }
            if (callsExtracted > before) controlFlowSimplified = true
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

        /**
         * Emits one event per lambda handed to this call, in argument order.
         *
         * Java has no language-level signal that a lambda runs in place, so the
         * timing comes from the documented API list and is otherwise
         * undetermined — which the map states rather than hides.
         */
        private fun addCallbacks(
            arguments: PsiExpressionList,
            receiverName: String,
            resolve: () -> PsiMethod?,
        ) {
            val lambdas = arguments.expressions.filterIsInstance<PsiLambdaExpression>()
            if (lambdas.isEmpty()) return
            val timing = KnownCallbackApis.javaTiming(qualifiedNameOf(resolve()))
                ?: CallbackTiming.UNDETERMINED
            for (lambda in lambdas) {
                sink += ExtractedCallback(
                    body = lambda,
                    receiverShortName = receiverName,
                    executionMode = timing.executionMode,
                    orderingStatus = timing.orderingStatus,
                    conditional = conditionalDepth > 0,
                )
            }
        }

        private fun qualifiedNameOf(method: PsiMethod?): String? {
            val owner = method?.containingClass?.qualifiedName ?: return null
            return "$owner.${method.name}"
        }

        private fun addCall(callSite: PsiElement, kind: FlowNodeKind, shortName: String) {
            callsExtracted += 1
            sink += ExtractedCall(
                callSite = callSite,
                kind = kind,
                calleeShortName = shortName,
                conditional = conditionalDepth > 0,
            )
        }
    }
}
