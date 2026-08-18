package com.kanicream.flowlens.testutil

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel

/**
 * A light project with a real test source root, so `inTestSource` is a fact the
 * IDE reports rather than a guess from a directory name.
 *
 * The default light descriptor has one source root, and a file added under a
 * path that merely starts with `test/` is still production source. A test that
 * relied on the name would pass whatever the code did.
 */
class TestSourceRootDescriptor : RealJdkProjectDescriptor() {

    override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
        super.configureModule(module, model, contentEntry)
        // The folder has to live under the content entry, which is the light
        // fixture's single source root; a sibling of it is rejected outright.
        val root = contentEntry.file?.createChildDirectory(this, TEST_ROOT) ?: return
        contentEntry.addSourceFolder(root, true)
    }

    companion object {
        /** Files under this directory are test sources. */
        const val TEST_ROOT = "testSrc"

        val INSTANCE = TestSourceRootDescriptor()
    }
}
