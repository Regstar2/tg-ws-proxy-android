package com.amurcanov.tgwsproxy

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * On MIUI the instrumentation process starts in the background even if the launcher
 * was opened before `am instrument`: Android restarts that process for instrumentation.
 * Bring the debug-only Compose host to the foreground via the shell AFTER the runner
 * starts, but BEFORE ActivityScenario launches its own host instance.
 */
internal class ForegroundComposeHostRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val targetPackage = instrumentation.targetContext.packageName
                val command = "am start -n $targetPackage/androidx.activity.ComponentActivity"
                val output = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)
                ).bufferedReader().use { it.readText() }

                Log.i("Issue135UiTest", "foreground Compose host: $output")
                if (output.contains("Error:", ignoreCase = true) ||
                    output.contains("SecurityException", ignoreCase = true) ||
                    output.contains("Exception occurred", ignoreCase = true)
                ) {
                    throw AssertionError("Could not foreground the debug Compose host: $output")
                }
                Thread.sleep(250)
                base.evaluate()
            }
        }
}
