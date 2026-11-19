package com.amurcanov.tgwsproxy

internal object UpdateApkAbiValidator {
    fun hasExpectedNativeLibraries(
        entries: Iterable<String>,
        expectedAbiNames: Set<String>,
    ): Boolean {
        if (expectedAbiNames.isEmpty()) return false

        val nativeEntries = entries.filter { entry ->
            val parts = entry.split('/')
            parts.size == 3 &&
                parts[0] == "lib" &&
                parts[2].endsWith(".so")
        }.toSet()

        val actualAbiNames = nativeEntries.map { it.split('/')[1] }.toSet()
        if (actualAbiNames != expectedAbiNames) return false

        return expectedAbiNames.all { abiName ->
            "lib/$abiName/libtgwsproxy.so" in nativeEntries
        }
    }
}