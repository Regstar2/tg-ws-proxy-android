package com.amurcanov.tgwsproxy

import java.io.File
import java.util.zip.ZipFile

internal object AppUpdateApkAbi {
    private val architectures = mapOf(
        "armeabi-v7a" to (1 to 40),
        "arm64-v8a" to (2 to 183),
        "x86" to (1 to 3),
        "x86_64" to (2 to 62),
    )
    private val libraries = setOf("libtgwsproxy.so", "libjnidispatch.so")

    fun isCompatible(file: File, supportedAbis: List<String>): Boolean = runCatching {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            if (entries.map { it.name }.distinct().size != entries.size) return false
            val native = entries.filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
            val abis = native.mapNotNull { it.name.split('/').getOrNull(1) }.toSet()
            if (abis.isEmpty() || !architectures.keys.containsAll(abis) || supportedAbis.none { it in abis }) return false
            val expected = abis.flatMap { abi -> libraries.map { "lib/$abi/$it" } }.toSet()
            if (native.map { it.name }.toSet() != expected) return false
            native.all { entry ->
                val (elfClass, machine) = architectures.getValue(entry.name.split('/')[1])
                val header = ByteArray(20)
                zip.getInputStream(entry).use { java.io.DataInputStream(it).readFully(header) }
                header.take(4) == listOf(0x7f.toByte(), 69.toByte(), 76.toByte(), 70.toByte()) &&
                    header[4].toInt() == elfClass && header[5].toInt() == 1 &&
                    header[16].toInt() == 3 && header[17].toInt() == 0 &&
                    (header[18].toInt() and 255) == machine && header[19].toInt() == 0
            }
        }
    }.getOrDefault(false)
}
