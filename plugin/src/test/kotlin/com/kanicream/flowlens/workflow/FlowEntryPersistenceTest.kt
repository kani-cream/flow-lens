package com.kanicream.flowlens.workflow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor

/**
 * Durable identity (`V0.3_SPEC.md` §3) and what happens when it fails (§8).
 * A stored entry survives edits that move the declaration, and a stored entry
 * that no longer exists is reported rather than resolved to something nearby.
 */
class FlowEntryPersistenceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private val sample = """
        package demo;

        public class Payments {
            void charge(int amount) { audit(); }
            void charge(String reason) { audit(); }
            void audit() { }
        }
    """.trimIndent()

    private fun configure(text: String = sample) {
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText("Payments.java", text)
        }
    }

    private fun refFor(signature: String): FlowEntryRef {
        val offset = myFixture.file.text.indexOf(signature) + signature.length - 2
        return runReadActionBlocking {
            val (analyzer, declaration) = com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
                .findEntryPoint(myFixture.file, offset)!!
            FlowEntryRef.of(
                analyzer.describeCallable(declaration),
                project,
                myFixture.file.virtualFile,
            )
        }
    }

    fun `test an overload is stored as its own entry`() {
        configure()
        val intOverload = refFor("void charge(int amount)")
        val stringOverload = refFor("void charge(String reason)")
        assertFalse(
            "two overloads must not share one identity",
            intOverload.key == stringOverload.key,
        )
        assertTrue(intOverload.key.contains("charge"))
    }

    fun `test a stored entry survives an edit that moves the declaration`() {
        configure()
        val ref = refFor("void charge(int amount)")

        // Everything above the declaration shifts; an offset-based identity would
        // now point into the wrong member (REPO_LENS_LESSONS.md 3.8).
        configure(
            """
            package demo;

            public class Payments {
                private final String note = "added above";
                private final String more = "and again";

                void charge(int amount) { audit(); }
                void charge(String reason) { audit(); }
                void audit() { }
            }
            """.trimIndent(),
        )

        val resolution = runReadActionBlocking { FlowEntryResolver.resolve(project, ref) }
        assertTrue("the declaration is still there", resolution is EntryResolution.Found)
        val found = resolution as EntryResolution.Found
        val symbol = runReadActionBlocking {
            com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
                .forDeclaration(found.declaration)!!
                .describeCallable(found.declaration)
        }
        assertEquals("it resolved to the same overload", ref.key, symbol.key)
    }

    fun `test K a renamed declaration is reported, never guessed`() {
        configure()
        val ref = refFor("void charge(int amount)")

        configure(
            """
            package demo;

            public class Payments {
                void chargeNow(int amount) { audit(); }
                void charge(String reason) { audit(); }
                void audit() { }
            }
            """.trimIndent(),
        )

        val resolution = runReadActionBlocking { FlowEntryResolver.resolve(project, ref) }
        assertEquals(
            "a pin that quietly moved to another function would make every mark untrustworthy",
            EntryResolution.NotFound,
            resolution,
        )
    }

    fun `test L a missing file is reported rather than throwing`() {
        configure()
        val ref = refFor("void charge(int amount)").copy(path = "gone/Missing.java")
        val resolution = runReadActionBlocking { FlowEntryResolver.resolve(project, ref) }
        assertEquals(EntryResolution.NotFound, resolution)
    }

    fun `test the stored path is project-relative`() {
        configure()
        val ref = refFor("void charge(int amount)")
        assertFalse(
            "an absolute path would leak the machine layout into a shared file",
            ref.path.startsWith("/") || ref.path.contains(":/"),
        )
    }

    fun `test E pinning twice unpins`() {
        configure()
        val ref = refFor("void charge(int amount)")
        val flows = FlowLensFlows.getInstance(project)

        assertTrue(flows.togglePin(ref))
        assertTrue(flows.isPinned(ref.key))
        assertFalse(flows.togglePin(ref))
        assertFalse(flows.isPinned(ref.key))
        assertTrue(flows.pins().isEmpty())
    }

    fun `test G a saved flow keeps the limits it was saved with`() {
        configure()
        val ref = refFor("void charge(int amount)")
        val flows = FlowLensFlows.getInstance(project)
        flows.save("deep charge", ref, FlowLimits(maxDepth = 5, maxNodes = 200))

        val saved = flows.savedFlows().single()
        assertEquals("deep charge", saved.name)
        assertEquals(5, saved.limits.maxDepth)
        assertEquals(200, saved.limits.maxNodes)
    }

    fun `test saving the same entry twice replaces it`() {
        configure()
        val ref = refFor("void charge(int amount)")
        val flows = FlowLensFlows.getInstance(project)
        flows.save("first", ref, FlowLimits())
        flows.save("second", ref, FlowLimits())

        assertEquals(1, flows.savedFlows().size)
        assertEquals("second", flows.savedFlows().single().name)
    }

    fun `test H re-analyzing moves a recent to the top without duplicating it`() {
        configure()
        val a = refFor("void charge(int amount)")
        val b = refFor("void charge(String reason)")
        val recents = FlowLensRecents.getInstance(project)

        recents.record(a, FlowLimits())
        recents.record(b, FlowLimits())
        recents.record(a, FlowLimits())

        assertEquals(listOf(a.key, b.key), recents.recents().map { it.entry.key })
    }

    fun `test J recents are capped`() {
        configure()
        val ref = refFor("void charge(int amount)")
        val recents = FlowLensRecents.getInstance(project)
        repeat(FlowLensRecents.MAX_RECENTS + 5) { index ->
            recents.record(ref.copy(key = "java:demo.Payments#m$index()"), FlowLimits())
        }
        assertEquals(FlowLensRecents.MAX_RECENTS, recents.recents().size)
        assertEquals(
            "the newest survives",
            "java:demo.Payments#m${FlowLensRecents.MAX_RECENTS + 4}()",
            recents.recents().first().entry.key,
        )
    }

    fun `test F stored state survives the serializer`() {
        configure()
        val ref = refFor("void charge(int amount)")
        val flows = FlowLensFlows.getInstance(project)
        flows.togglePin(ref)
        flows.save("charge deeply", ref, FlowLimits(maxDepth = 4, maxNodes = 150))

        // What a restart actually does: write the state out and read it back.
        val written = com.intellij.configurationStore.serialize(flows.state!!)!!
        val restored = com.intellij.util.xmlb.XmlSerializer
            .deserialize(written, FlowLensFlows.State::class.java)
        val reopened = FlowLensFlows().apply { loadState(restored) }

        assertEquals(listOf(ref.key), reopened.pins().map { it.key })
        val saved = reopened.savedFlows().single()
        assertEquals("charge deeply", saved.name)
        assertEquals(ref.path, saved.entry.path)
        assertEquals(4, saved.limits.maxDepth)
        assertEquals(150, saved.limits.maxNodes)
    }

    fun `test T stored state with no lists at all is still readable`() {
        // An older version, a hand edit, or a partial write: the component must
        // open the tool window rather than fail it.
        val flows = FlowLensFlows()
        val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(
            org.jdom.Element("state"),
            FlowLensFlows.State::class.java,
        )
        flows.loadState(restored)

        assertTrue(flows.pins().isEmpty())
        assertTrue(flows.savedFlows().isEmpty())
        assertFalse(flows.isPinned("anything"))
    }

    fun `test T a malformed stored entry is dropped rather than failing`() {
        val flows = FlowLensFlows.getInstance(project)
        val state = FlowLensFlows.State().apply {
            pins = mutableListOf(
                EntryState(),
                EntryState().apply {
                    key = "java:demo.Payments#audit()"
                    languageId = "java"
                    displayName = "audit()"
                    path = "Payments.java"
                },
            )
            saved = mutableListOf(SavedFlowState())
        }
        flows.loadState(state)

        assertEquals("only the readable pin survives", 1, flows.pins().size)
        assertTrue("a saved flow with no entry is dropped", flows.savedFlows().isEmpty())
    }

    override fun tearDown() {
        try {
            FlowLensFlows.getInstance(project).loadState(FlowLensFlows.State())
            FlowLensRecents.getInstance(project).clear()
        } finally {
            super.tearDown()
        }
    }
}
