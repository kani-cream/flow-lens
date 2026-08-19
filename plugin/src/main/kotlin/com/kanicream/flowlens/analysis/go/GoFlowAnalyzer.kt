package com.kanicream.flowlens.analysis.go

import com.goide.psi.GoAndExpr
import com.goide.psi.GoBinaryExpr
import com.goide.psi.GoBreakStatement
import com.goide.psi.GoCaseClause
import com.goide.psi.GoCommClause
import com.goide.psi.GoExprCaseClause
import com.goide.psi.GoContinueStatement
import com.goide.psi.GoReturnStatement
import com.goide.psi.GoCallExpr
import com.goide.psi.GoOrExpr
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
import com.goide.psi.GoStatement
import com.goide.psi.GoSwitchStatement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
import com.kanicream.flowlens.analysis.CallbackTiming
import com.kanicream.flowlens.analysis.ExtractedCallback
import com.kanicream.flowlens.analysis.SymbolQualifier
import com.kanicream.flowlens.analysis.TargetClassifier
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin
import com.kanicream.flowlens.service.FlowMetadata

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
        element is GoFunctionOrMethodDeclaration || element is GoFunctionLit

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

    override fun describeCallable(callable: PsiElement): FlowSymbol = when (callable) {
        is GoFunctionLit -> literalSymbolOf(callable)
        else -> symbolOf(callable as GoFunctionOrMethodDeclaration)
    }

    /** A function literal has no name; its position keeps two in one function distinct. */
    private fun literalSymbolOf(literal: GoFunctionLit): FlowSymbol = FlowSymbol(
        languageId = languageId,
        displayName = "func() { }",
        containerName = (literal.containingFile as? GoFile)?.packageName,
        key = "go:literal@${SymbolQualifier.fileQualifier(literal)}:${literal.textOffset}",
    )

    override fun isAnonymousBody(declaration: PsiElement): Boolean =
        declaration is GoFunctionLit

    override fun hasAnalyzableBody(declaration: PsiElement): Boolean = when (declaration) {
        is GoFunctionLit -> declaration.block != null
        is GoFunctionOrMethodDeclaration -> declaration.block != null
        else -> false
    }

    override fun extractDirectFlow(callable: PsiElement): DirectFlowExtraction {
        val extractor = Extractor()
        when (callable) {
            is GoFunctionLit -> callable.block?.let(extractor::walk)
            else -> (callable as GoFunctionOrMethodDeclaration).block?.let(extractor::walk)
        }
        return DirectFlowExtraction(extractor.items(), extractor.controlFlowSimplified)
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

    /**
     * Go names a method as `Server.Start`, so the receiver belongs to the display
     * name, and the scope a call can leave is the package, not the type. The
     * container is therefore the package: qualifying by the receiver as well
     * printed `Server.Server.Start()`, and qualifying same-package functions by
     * their type printed `main.notify()` for a call that crosses nothing.
     */
    private fun symbolOf(declaration: GoFunctionOrMethodDeclaration): FlowSymbol {
        val name = declaration.name ?: "?"
        val receiver = (declaration as? GoMethodDeclaration)
            ?.receiverType?.text?.removePrefix("*")
        val packageName = (declaration.containingFile as? GoFile)?.packageName
        val display = if (receiver.isNullOrEmpty()) "$name()" else "$receiver.$name()"
        // Two directories can both be `package main`, and the package name alone
        // then gives their functions one key. That key is what cycle detection
        // compares along a path, so two unrelated `run()` would read as a cycle.
        // A Go package is its directory, so the directory is the unique part.
        val qualifier = SymbolQualifier.directoryQualifier(declaration) ?: packageName ?: "?"
        return FlowSymbol(
            languageId = languageId,
            displayName = display,
            containerName = packageName,
            key = "go:$qualifier.${receiver?.plus(".") ?: ""}$name",
        )
    }

    /**
     * Walks a body in Go evaluation order, emitting calls, control structures
     * with their branches, and terminators. Function literals are boundaries.
     */
    private class Extractor {
        private val root = mutableListOf<FlowItem>()
        private var sink: MutableList<FlowItem> = root

        var controlFlowSimplified = false
            private set

        /** > 0 while walking a short-circuit operand, which has no structure. */
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
                is PsiComment -> return
                // A closure does not run where it is written; it is emitted after
                // the call that received it (`V0.5_SPEC.md` §3).
                is GoFunctionLit -> return
                is GoCallExpr -> {
                    element.expression?.let(::walk)
                    element.argumentList.expressionList.forEach(::walk)
                    val mode = executionModeOf(element)
                    // `go func() { … }()` invokes a body written right here. The
                    // call and the body are one thing, and there is no declaration
                    // to resolve, so emitting a call as well produced a second
                    // card that could only ever report itself as unresolved.
                    if (element.expression !is GoFunctionLit) {
                        callsExtracted += 1
                        sink += ExtractedCall(
                            callSite = element,
                            kind = FlowNodeKind.CALL,
                            calleeShortName = calleeNameOf(element),
                            executionMode = mode,
                            conditional = conditionalDepth > 0,
                        )
                    }
                    addCallbacks(element, mode)
                }
                is GoIfStatement -> walkIf(element)
                is GoForStatement -> walkLoop(element)
                is GoSwitchStatement -> walkSwitch(element, isSelect = false)
                is GoSelectStatement -> walkSwitch(element, isSelect = true)
                is GoReturnStatement -> {
                    element.expressionList.forEach(::walk)
                    // Go returns several values at once, so the card lists them.
                    sink += ExtractedTerminator(
                        FlowNodeKind.RETURN,
                        element,
                        SourceSummary.of(
                            element.expressionList
                                .joinToString(", ") { it.text }
                                .ifEmpty { null },
                        ),
                    )
                }
                is GoBreakStatement -> {
                    // A `break` that leaves a switch or select case is already
                    // expressed by the case boundary; only a jump out of a loop is
                    // flow the map does not show (`V0.2_SPEC.md` §6).
                    // A labelled break leaves whatever the label names, which the
                    // nearest enclosing statement does not tell us, so it always
                    // counts as flow the map does not draw.
                    val target = PsiTreeUtil.getParentOfType(
                        element,
                        GoForStatement::class.java,
                        GoSwitchStatement::class.java,
                        GoSelectStatement::class.java,
                    )
                    if (element.labelRef != null || target is GoForStatement) {
                        controlFlowSimplified = true
                    }
                }
                is GoContinueStatement -> controlFlowSimplified = true
                is GoAndExpr, is GoOrExpr -> {
                    val binary = element as GoBinaryExpr
                    binary.left?.let(::walk)
                    val before = callsExtracted
                    conditional { binary.right?.let(::walk) }
                    if (callsExtracted > before) controlFlowSimplified = true
                }
                else -> element.children.forEach(::walk)
            }
        }

        /**
         * `go func() { … }()` and `defer func() { … }()` hand a body to the
         * runtime. Go states the timing itself, so unlike Java there is nothing
         * to look up — but it states it about **the function being invoked**,
         * not about its arguments.
         *
         * The Go specification is explicit: for a `go` statement "the function
         * value and parameters are evaluated as usual in the calling goroutine",
         * and a `defer` statement saves them the same way. So in
         * `go helper(func() { … })` the literal is a value handed to `helper`,
         * and when it runs depends on what `helper` does with it — which is
         * exactly the thing this analyzer does not know.
         */
        private fun addCallbacks(call: GoCallExpr, mode: ExecutionMode) {
            val invokedInPlace = call.expression as? GoFunctionLit
            val literals = buildList {
                invokedInPlace?.let(::add)
                addAll(call.argumentList.expressionList.filterIsInstance<GoFunctionLit>())
            }
            if (literals.isEmpty()) return
            // The keyword's timing belongs to the literal that is being called.
            val invokedTiming = when (mode) {
                ExecutionMode.GOROUTINE -> CallbackTiming.GOROUTINE
                ExecutionMode.DEFERRED -> CallbackTiming.DEFERRED
                else -> CallbackTiming.IN_PLACE
            }
            val arguments = literals.filter { it !== invokedInPlace }
            literals.forEachIndexed { index, literal ->
                val handedOver = literal !== invokedInPlace
                sink += ExtractedCallback(
                    body = literal,
                    // A body invoked where it is written was handed to nobody,
                    // so there is no receiver to name it after.
                    receiverShortName = if (handedOver) calleeNameOf(call) else null,
                    executionMode = if (handedOver) {
                        CallbackTiming.UNDETERMINED.executionMode
                    } else {
                        invokedTiming.executionMode
                    },
                    orderingStatus = if (handedOver) {
                        CallbackTiming.UNDETERMINED.orderingStatus
                    } else {
                        invokedTiming.orderingStatus
                    },
                    conditional = conditionalDepth > 0,
                    ordinal = if (handedOver) arguments.indexOf(literal) else 0,
                    siblingCount = if (handedOver) arguments.size else 1,
                )
            }
        }

        private fun walkIf(element: GoIfStatement) {
            // The init statement and the condition both run before the branches.
            val header = listOfNotNull(element.initStatement, element.condition)
            header.forEach(::walk)
            val branches = mutableListOf<ExtractedBranch>()
            val elseStatement = element.elseStatement
            branches += branch(BranchKind.THEN, null) {
                element.children
                    .filterNot { child -> containsAny(child, header) || child === elseStatement }
                    .forEach(::walk)
            }
            elseStatement?.let { branches += branch(BranchKind.ELSE, null) { walk(it) } }
            sink += ExtractedStructure(
                kind = FlowNodeKind.CONDITION,
                anchor = element,
                summary = SourceSummary.of(element.condition?.text),
                branches = branches,
            )
        }

        private fun walkLoop(element: GoForStatement) {
            // Evaluated once, before the loop is entered: a `for` initializer and
            // the sequence a `range` walks. Everything else repeats (`V0.2_SPEC.md` §4).
            val forClause = element.forClause
            val rangeClause = element.rangeClause
            listOfNotNull(forClause?.initStatement, rangeClause?.rangeExpression).forEach(::walk)
            val body = branch(BranchKind.BODY, null) {
                forClause?.expression?.let(::walk)
                element.children
                    .filterNot { it === forClause || it === rangeClause }
                    .forEach(::walk)
                forClause?.postStatement?.let(::walk)
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.LOOP,
                anchor = element,
                summary = SourceSummary.of(
                    element.forClause?.text ?: element.rangeClause?.text ?: element.expression?.text,
                ),
                branches = listOf(body),
            )
        }

        private fun walkSwitch(element: PsiElement, isSelect: Boolean) {
            // Everything outside the clauses — the subject, an init statement —
            // runs before a case is chosen.
            val clauses = element.children.filterIsInstance<GoCaseClause>()
            element.children.filterNot { it is GoCaseClause }.forEach(::walk)
            val branches = clauses.map { clause ->
                val isDefault = clause.default != null
                branch(
                    if (isDefault) BranchKind.DEFAULT else BranchKind.CASE,
                    if (isDefault) null else SourceSummary.of(caseLabelOf(clause)),
                ) {
                    // A case label is an expression in Go — `case isReady():` or
                    // `case v := <-recv():`. It is evaluated to choose this case,
                    // so its calls belong inside the section rather than nowhere.
                    when (clause) {
                        is GoExprCaseClause -> clause.expressionList.forEach(::walk)
                        is GoCommClause -> clause.commCase?.let(::walk)
                        else -> Unit
                    }
                    clause.statementList.forEach(::walk)
                }
            }
            sink += ExtractedStructure(
                kind = FlowNodeKind.SWITCH,
                anchor = element,
                summary = SourceSummary.of(switchSubjectOf(element)),
                branches = branches,
                metadata = if (isSelect) mapOf(FlowMetadata.SELECT to "true") else emptyMap(),
            )
        }

        /**
         * The case label as written. Taken from the clause's own parts rather
         * than from its text up to the first colon, because `case v := <-ch:`
         * contains a colon of its own and would otherwise read as `v`.
         */
        private fun caseLabelOf(clause: GoCaseClause): String? = when (clause) {
            is GoExprCaseClause -> clause.expressionList.joinToString(", ") { it.text }
            is GoCommClause -> clause.commCase?.text?.removePrefix("case")?.trim()
            else -> null
        }?.trim()?.ifEmpty { null }

        /**
         * What the switch decides on. An init statement is left out: it runs once
         * before the container and is already drawn as its own card there, so
         * repeating it in the title would say the same thing twice. Java and
         * Kotlin name only the subject for the same reason.
         */
        private fun switchSubjectOf(element: PsiElement): String? = element.children
            .filterNot { it is GoCaseClause || it is GoStatement }
            .joinToString(" ") { it.text }
            .trim()
            .removePrefix("switch")
            .removePrefix("select")
            .trim()
            .ifEmpty { null }

        private fun containsAny(child: PsiElement, parts: List<PsiElement>): Boolean =
            parts.any { PsiTreeUtil.isAncestor(child, it, false) }

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

        private fun calleeNameOf(call: GoCallExpr): String = when (val callee = call.expression) {
            is GoReferenceExpression -> callee.identifier.text
            // A literal has no name, and its whole source text is not one.
            is GoFunctionLit -> "func"
            else -> callee.text
        }

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
    }
}
