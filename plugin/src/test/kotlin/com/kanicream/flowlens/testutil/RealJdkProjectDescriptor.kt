package com.kanicream.flowlens.testutil

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor

/**
 * Light project descriptor backed by the test JVM's real JDK, so JDK calls
 * (String.trim etc.) resolve to compiled library targets like in a real project.
 * The default light descriptor has no SDK in this harness.
 */
open class RealJdkProjectDescriptor : DefaultLightProjectDescriptor() {
    override fun getSdk(): Sdk =
        JavaSdk.getInstance().createJdk("flow-lens-test-jdk", System.getProperty("java.home"), false)

    companion object {
        val INSTANCE = RealJdkProjectDescriptor()
    }
}
