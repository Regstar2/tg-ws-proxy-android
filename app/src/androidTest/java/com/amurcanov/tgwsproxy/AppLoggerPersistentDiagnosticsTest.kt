package com.amurcanov.tgwsproxy

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppLoggerPersistentDiagnosticsTest {
    @Test
    fun debugThrowableIsSanitizedAndWrittenToPersistentLogFile() {
        assertTrue("This instrumentation test requires the Debug build", BuildConfig.DEBUG)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)

        val enabledKey = "persistent_logs_enabled"
        val levelKey = "persistent_logs_level"

        val hadEnabled = preferences.contains(enabledKey)
        val oldEnabled = preferences.getBoolean(enabledKey, false)
        val hadLevel = preferences.contains(levelKey)
        val oldLevel = preferences.getString(levelKey, null)

        val marker = "persistent-stacktrace-test-${System.nanoTime()}"
        val detailSecret = "detail-secret-${System.nanoTime()}"
        val stackSecret = "stack-secret-${System.nanoTime()}"
        val bearerSecret = "bearer-secret-${System.nanoTime()}"

        try {
            preferences.edit()
                .putBoolean(enabledKey, true)
                .putString(levelKey, PersistentLogVerbosity.DEBUG.name)
                .commit()

            AppLogger.e(
                context = context,
                category = AppLogCategory.APP,
                message = marker,
                details = mapOf("token" to detailSecret),
                tr = IllegalStateException(
                    "token=$stackSecret Authorization: Bearer $bearerSecret",
                ),
            )

            PersistentLogStore.flushAsync()

            val logFile = File(context.filesDir, "logs/app-current.log")
            val deadline = System.nanoTime() + 15_000_000_000L
            var content = ""

            while (System.nanoTime() < deadline) {
                if (logFile.isFile) {
                    content = runCatching { logFile.readText(Charsets.UTF_8) }
                        .getOrDefault("")

                    if (content.contains(marker) && content.contains("stacktrace=")) {
                        break
                    }
                }
                Thread.sleep(50)
            }

            assertTrue("The event was not written to app-current.log", content.contains(marker))
            assertTrue("Stack trace field is missing", content.contains("stacktrace="))
            assertTrue("Token value was not redacted", content.contains("token=***"))
            assertTrue("Bearer value was not redacted", content.contains("Authorization: ***"))

            assertFalse(content.contains(detailSecret))
            assertFalse(content.contains(stackSecret))
            assertFalse(content.contains(bearerSecret))
        } finally {
            val editor = preferences.edit()

            if (hadEnabled) {
                editor.putBoolean(enabledKey, oldEnabled)
            } else {
                editor.remove(enabledKey)
            }

            if (hadLevel) {
                editor.putString(levelKey, oldLevel)
            } else {
                editor.remove(levelKey)
            }

            editor.commit()
        }
    }
}
