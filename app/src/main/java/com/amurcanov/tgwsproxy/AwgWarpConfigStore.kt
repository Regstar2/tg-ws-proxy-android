package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object AwgWarpConfigStore {
    private const val DIRECTORY_NAME = "awg-warp"
    private const val CONFIG_NAME = "active.conf"
    private const val TEMP_NAME = "active.conf.tmp"
    private const val MAX_CONFIG_BYTES = 64 * 1024L

    fun configPath(context: Context): String? {
        val file = configFile(context)
        return file.takeIf { it.isFile && it.length() in 1..MAX_CONFIG_BYTES }?.absolutePath
    }

    fun hasConfig(context: Context): Boolean = configPath(context) != null

    fun importConfig(context: Context, uri: Uri): Result<Unit> = runCatching {
        val directory = configDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create AWG/WARP config directory")
        }

        val temp = File(directory, TEMP_NAME)
        val target = File(directory, CONFIG_NAME)
        temp.delete()

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_CONFIG_BYTES) {
                        error("AWG/WARP config is larger than 64 KiB")
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
                if (total == 0L) {
                    error("AWG/WARP config is empty")
                }
            }
        } ?: error("Could not open selected AWG/WARP config")

        if (target.exists() && !target.delete()) {
            error("Could not replace existing AWG/WARP config")
        }
        if (!temp.renameTo(target)) {
            error("Could not activate AWG/WARP config")
        }
    }.onFailure {
        File(configDirectory(context), TEMP_NAME).delete()
    }

    fun remove(context: Context): Boolean {
        File(configDirectory(context), TEMP_NAME).delete()
        val target = configFile(context)
        return !target.exists() || target.delete()
    }

    private fun configDirectory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

    private fun configFile(context: Context): File = File(configDirectory(context), CONFIG_NAME)
}
