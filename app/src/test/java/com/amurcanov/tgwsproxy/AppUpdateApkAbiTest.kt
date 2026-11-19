package com.amurcanov.tgwsproxy

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppUpdateApkAbiTest {
    private fun apk(abis: List<String>, missingJna: Boolean = false, wrongElf: Boolean = false): File {
        val file = File.createTempFile("abi-test-", ".apk")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            for (abi in abis) for (lib in listOf("libtgwsproxy.so", "libjnidispatch.so")) {
                if (missingJna && lib == "libjnidispatch.so") continue
                val header = ByteArray(20)
                header[0] = 0x7f; header[1] = 69; header[2] = 76; header[3] = 70
                header[4] = if (wrongElf || abi == "arm64-v8a") 2 else 1
                header[5] = 1; header[16] = 3
                header[18] = if (abi == "arm64-v8a") 183.toByte() else 40
                zip.putNextEntry(ZipEntry("lib/$abi/$lib")); zip.write(header); zip.closeEntry()
            }
        }
        return file
    }

    @Test fun acceptsEachCompatibleVariantAndUniversal() {
        val both = listOf("arm64-v8a", "armeabi-v7a")
        for (abis in listOf(listOf("arm64-v8a"), listOf("armeabi-v7a"), both)) {
            val file = apk(abis)
            try { assertTrue(AppUpdateApkAbi.isCompatible(file, abis)) } finally { file.delete() }
        }
        val universal = apk(both)
        try { assertTrue(AppUpdateApkAbi.isCompatible(universal, listOf("armeabi-v7a"))) } finally { universal.delete() }
    }

    @Test fun rejectsIncompatibleMissingJnaAndMislabeledElf() {
        val cases = listOf(apk(listOf("arm64-v8a")), apk(listOf("armeabi-v7a"), missingJna = true), apk(listOf("armeabi-v7a"), wrongElf = true), apk(emptyList()))
        for (file in cases) {
            try { assertFalse(AppUpdateApkAbi.isCompatible(file, listOf("armeabi-v7a"))) } finally { file.delete() }
        }
        val file = apk(listOf("armeabi-v7a"))
        try { assertFalse(AppUpdateApkAbi.isCompatible(file, listOf("x86"))) } finally { file.delete() }
    }
}
