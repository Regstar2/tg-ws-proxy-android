package com.amurcanov.tgwsproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Proves that AndroidJUnitRunner can reach the target package without Compose. */
@RunWith(AndroidJUnit4::class)
class InstrumentationBootstrapIssue135Test {
    @Test
    fun runnerCanAccessTargetPackage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals(
            "io.github.regstar2.tgwsproxy",
            instrumentation.targetContext.packageName,
        )
    }
}
