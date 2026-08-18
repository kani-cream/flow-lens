package com.kanicream.flowlens

import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowProgressStage
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/**
 * Localization drift guard (IMPLEMENTATION_GUARDRAILS.md section 14): EN/JA key
 * parity and full label coverage for every visible enum value.
 */
class FlowLensBundleParityTest {

    private fun load(name: String): Properties {
        val props = Properties()
        javaClass.getResourceAsStream(name)!!.use { props.load(it.reader()) }
        return props
    }

    private val en = load("/messages/FlowLensBundle.properties")
    private val ja = load("/messages/FlowLensBundle_ja.properties")

    @Test
    fun `english and japanese bundles have identical key sets`() {
        assertEquals(en.stringPropertyNames().toSortedSet(), ja.stringPropertyNames().toSortedSet())
    }

    @Test
    fun `every visible enum value has a localized label`() {
        val required = buildList {
            ResolutionStatus.entries.forEach { add("enum.resolution.${it.name}") }
            DispatchConfidence.entries.forEach { add("enum.dispatch.${it.name}") }
            ExecutionMode.entries.forEach { add("enum.execution.${it.name}") }
            OrderingStatus.entries.forEach { add("enum.ordering.${it.name}") }
            SourceOrigin.entries.forEach { add("enum.origin.${it.name}") }
            FlowResultStatus.entries.forEach { add("enum.result.${it.name}") }
            FlowNodeKind.entries.forEach { add("enum.kind.${it.name}") }
            FlowProgressStage.entries.forEach { add("status.stage.${it.name}") }
            // A structure names its own kind on the card, and every section is
            // labelled, so both enums are user-visible (guardrails §14).
            BranchKind.entries.forEach { add("branch.kind.${it.name}") }
            listOf(
                FlowNodeKind.CONDITION,
                FlowNodeKind.SWITCH,
                FlowNodeKind.LOOP,
                FlowNodeKind.TRY,
                FlowNodeKind.RETURN,
                FlowNodeKind.THROW,
            ).forEach { add("card.kind.${it.name}") }
            add("card.kind.SELECT")
            add("card.kind.LOOP_ONCE")
            add("branch.empty")
        }
        val missing = required.filterNot { en.containsKey(it) }
        assertTrue("missing bundle keys: $missing", missing.isEmpty())
    }

    @Test
    fun `diagnostic message keys used by the run engine exist`() {
        val keys = listOf(
            "flow.error.no.entry.point",
            "flow.error.frame.failed",
            "flow.error.frame.invalidated",
            "flow.error.run.failed",
            "flow.warning.choice.unresolved",
        )
        val missing = keys.filterNot { en.containsKey(it) }
        assertTrue("missing diagnostic keys: $missing", missing.isEmpty())
    }

    @Test
    fun `no bundle value is blank`() {
        val blankEn = en.stringPropertyNames().filter { en.getProperty(it).isBlank() }
        val blankJa = ja.stringPropertyNames().filter { ja.getProperty(it).isBlank() }
        assertTrue("blank values: en=$blankEn ja=$blankJa", blankEn.isEmpty() && blankJa.isEmpty())
    }
}
