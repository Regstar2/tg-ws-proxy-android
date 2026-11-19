package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateApkAbiValidatorTest {
    private val universalAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    private fun proxyLibraries(vararg abis: String): List<String> =
        abis.map { "lib/$it/libtgwsproxy.so" }

    @Test
    fun acceptsAllFourExpectedAbis() {
        val entries = proxyLibraries(*universalAbis.toTypedArray())
        assertTrue(UpdateApkAbiValidator.hasExpectedNativeLibraries(entries, universalAbis))
    }

    @Test
    fun acceptsLegacyArm64OnlyApk() {
        val expected = setOf("arm64-v8a")
        assertTrue(
            UpdateApkAbiValidator.hasExpectedNativeLibraries(
                proxyLibraries("arm64-v8a"),
                expected,
            ),
        )
    }

    @Test
    fun rejectsMissingExpectedAbi() {
        val entries = proxyLibraries("arm64-v8a", "armeabi-v7a", "x86")
        assertFalse(UpdateApkAbiValidator.hasExpectedNativeLibraries(entries, universalAbis))
    }

    @Test
    fun rejectsUnexpectedAdditionalAbi() {
        val entries = proxyLibraries(*universalAbis.toTypedArray(), "mips")
        assertFalse(UpdateApkAbiValidator.hasExpectedNativeLibraries(entries, universalAbis))
    }

    @Test
    fun rejectsAbiWithoutProxyLibrary() {
        val entries = proxyLibraries("arm64-v8a", "armeabi-v7a", "x86")
            .plus("lib/x86_64/libjnidispatch.so")
        assertFalse(UpdateApkAbiValidator.hasExpectedNativeLibraries(entries, universalAbis))
    }

    @Test
    fun allowsAdditionalLibrariesWithinExpectedAbis() {
        val entries = proxyLibraries(*universalAbis.toTypedArray()) +
            universalAbis.map { "lib/$it/libjnidispatch.so" }
        assertTrue(UpdateApkAbiValidator.hasExpectedNativeLibraries(entries, universalAbis))
    }

    @Test
    fun rejectsEmptyExpectedSet() {
        assertFalse(UpdateApkAbiValidator.hasExpectedNativeLibraries(emptyList(), emptySet()))
    }
}