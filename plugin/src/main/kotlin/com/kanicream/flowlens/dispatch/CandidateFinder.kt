package com.kanicream.flowlens.dispatch

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.workflow.FlowEntryRef

/** One implementation a call could reach (`V0.4_SPEC.md` §3). */
data class DispatchCandidate(val symbol: FlowSymbol, val entry: FlowEntryRef)

/**
 * The candidates found for one call, and whether the search was cut short.
 *
 * [partial] is carried rather than dropped: a list that quietly showed some of
 * the implementations would read as all of them, and the reader would conclude
 * the others do not exist.
 */
data class CandidateSearchResult(
    val candidates: List<DispatchCandidate>,
    val partial: Boolean,
) {
    val isEmpty: Boolean get() = candidates.isEmpty()

    companion object {
        val NONE = CandidateSearchResult(emptyList(), partial = false)
    }
}

/**
 * Finds the implementations a call could reach.
 *
 * `DefinitionsScopedSearch` is what the IDE runs for Go to Implementation, so
 * Java, Kotlin, and Go are covered by one code path and a language added later
 * is covered too. Call inside a read action.
 */
object CandidateFinder {

    /** `V0.4_SPEC.md` §3.4. */
    const val MAX_CANDIDATES = 20

    fun find(project: Project, declaration: PsiElement): CandidateSearchResult {
        val found = LinkedHashMap<String, DispatchCandidate>()
        var partial = false

        // forEach with a processor rather than iterating the query: Query.iterator
        // is scheduled for removal, and the processor form is also what lets the
        // search stop at the cap instead of enumerating a whole hierarchy.
        DefinitionsScopedSearch.search(declaration).forEach(
            Processor { candidate ->
                // Only an implementation that could actually be followed is worth
                // offering: a library or body-less one would be chosen and then
                // stop (`V0.4_SPEC.md` §3.3).
                val analyzer = FlowAnalyzerRegistry.forDeclaration(candidate)
                val file = candidate.containingFile?.virtualFile
                if (analyzer == null ||
                    !analyzer.hasAnalyzableBody(candidate) ||
                    file == null ||
                    !FlowEntryRef.isInsideProject(project, file)
                ) {
                    return@Processor true
                }
                val symbol = analyzer.describeCallable(candidate)
                if (!FlowEntryRef.isStorable(symbol) || symbol.key == describeSelf(declaration)) {
                    return@Processor true
                }
                found[symbol.key] = DispatchCandidate(symbol, FlowEntryRef.of(symbol, project, file))
                // Searching one past the cap lets the caller say the list is
                // partial rather than guess.
                if (found.size > MAX_CANDIDATES) {
                    partial = true
                    false
                } else {
                    true
                }
            },
        )

        val candidates = found.values
            .sortedWith(compareBy({ it.symbol.containerName ?: "" }, { it.symbol.displayName }))
            .take(MAX_CANDIDATES)
        return CandidateSearchResult(candidates, partial)
    }

    /** The declaration's own key, so it is not offered as an implementation of itself. */
    private fun describeSelf(declaration: PsiElement): String? =
        FlowAnalyzerRegistry.forDeclaration(declaration)?.describeCallable(declaration)?.key
}
