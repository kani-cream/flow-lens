package com.kanicream.flowlens.core.engine

import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TraversalPolicyTest {

    private fun facts(
        hasBody: Boolean = true,
        origin: SourceOrigin = SourceOrigin.PHYSICAL_SOURCE,
        resolution: ResolutionStatus = ResolutionStatus.PROJECT_LOCAL,
        dispatch: DispatchConfidence = DispatchConfidence.EXACT,
        inTestSource: Boolean = false,
    ) = TargetFacts(hasBody, origin, resolution, dispatch, inTestSource)

    private val defaults = FlowLimits()

    @Test
    fun `authored project source with a body is entered`() {
        assertTrue(TraversalPolicy.mayEnterBody(facts(), defaults))
        assertTrue(
            TraversalPolicy.mayEnterBody(
                facts(dispatch = DispatchConfidence.DECLARED_TARGET), defaults,
            ),
        )
    }

    @Test
    fun `a target without an analyzable body is never entered`() {
        assertFalse(TraversalPolicy.mayEnterBody(facts(hasBody = false), defaults))
    }

    @Test
    fun `ambiguous and unknown dispatch stop traversal`() {
        assertFalse(
            TraversalPolicy.mayEnterBody(facts(dispatch = DispatchConfidence.AMBIGUOUS), defaults),
        )
        assertFalse(
            TraversalPolicy.mayEnterBody(facts(dispatch = DispatchConfidence.UNKNOWN), defaults),
        )
    }

    @Test
    fun `generated and unclassified origins are conservative stops in every configuration`() {
        val permissive = FlowLimits(includeTests = true, includeLibraries = true)
        for (origin in listOf(SourceOrigin.SYNTHETIC, SourceOrigin.GENERATED, SourceOrigin.UNKNOWN)) {
            assertFalse(
                TraversalPolicy.mayEnterBody(facts(origin = origin), defaults),
                "origin $origin with defaults",
            )
            assertFalse(
                TraversalPolicy.mayEnterBody(facts(origin = origin), permissive),
                "origin $origin with everything enabled",
            )
        }
    }

    @Test
    fun `library bodies are entered only when include libraries is on`() {
        val libraryTarget = facts(origin = SourceOrigin.LIBRARY, resolution = ResolutionStatus.EXTERNAL)
        assertFalse(TraversalPolicy.mayEnterBody(libraryTarget, defaults))
        assertTrue(
            TraversalPolicy.mayEnterBody(libraryTarget, FlowLimits(includeLibraries = true)),
        )
    }

    @Test
    fun `external authored source also requires include libraries`() {
        val outsideProject = facts(resolution = ResolutionStatus.EXTERNAL)
        assertFalse(TraversalPolicy.mayEnterBody(outsideProject, defaults))
        assertTrue(
            TraversalPolicy.mayEnterBody(outsideProject, FlowLimits(includeLibraries = true)),
        )
    }

    @Test
    fun `unresolved and built-in targets are terminal even with everything enabled`() {
        val permissive = FlowLimits(includeTests = true, includeLibraries = true)
        for (resolution in listOf(ResolutionStatus.UNRESOLVED, ResolutionStatus.BUILT_IN)) {
            assertFalse(
                TraversalPolicy.mayEnterBody(facts(resolution = resolution), permissive),
                "resolution $resolution",
            )
        }
    }

    @Test
    fun `test sources are entered only when include tests is on`() {
        val testTarget = facts(inTestSource = true)
        assertFalse(TraversalPolicy.mayEnterBody(testTarget, defaults))
        assertTrue(TraversalPolicy.mayEnterBody(testTarget, FlowLimits(includeTests = true)))
    }

    @Test
    fun `enabling libraries does not also enable test traversal`() {
        assertFalse(
            TraversalPolicy.mayEnterBody(
                facts(inTestSource = true), FlowLimits(includeLibraries = true),
            ),
        )
    }
}
