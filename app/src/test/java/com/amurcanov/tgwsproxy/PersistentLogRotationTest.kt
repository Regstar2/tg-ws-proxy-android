package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersistentLogRotationTest {
    @Test
    fun readTailLinesReturnsLastLinesWithoutReadingWholeFile() {
        val file = File.createTempFile("app-current", ".log")
        file.writeText(
            buildString {
                repeat(50) { index ->
                    appendLine("line-$index")
                }
            },
        )
        val tail = com.amurcanov.tgwsproxy.diagnostics.DiagnosticReportContextFactory.readTailLines(file, 5)
        assertTrue(tail.last().contains("line-49"))
        assertTrue(tail.first().contains("line-45"))
        assertFalse(tail.any { it.contains("line-0") })
        file.delete()
    }
}
