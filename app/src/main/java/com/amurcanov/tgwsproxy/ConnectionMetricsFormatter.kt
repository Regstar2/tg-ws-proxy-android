package com.amurcanov.tgwsproxy

import java.util.Locale
import kotlin.math.roundToInt

object ConnectionMetricsFormatter {
    fun formatSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0.5) {
            return ""
        }
        return when {
            bytesPerSecond < 1024 -> "${bytesPerSecond.roundToInt()} B/s"
            bytesPerSecond < 1024 * 1024 -> {
                val kb = bytesPerSecond / 1024.0
                String.format(Locale.US, "%.1f KB/s", kb)
            }
            else -> {
                val mb = bytesPerSecond / (1024.0 * 1024.0)
                String.format(Locale.US, "%.1f MB/s", mb)
            }
        }
    }

    fun formatSpeedPair(downloadBps: Double, uploadBps: Double, idleLabel: String): String {
        if (downloadBps <= 0.5 && uploadBps <= 0.5) {
            return idleLabel
        }
        val down = formatSpeed(downloadBps).ifBlank { "0 B/s" }
        val up = formatSpeed(uploadBps).ifBlank { "0 B/s" }
        return "↓ $down · ↑ $up"
    }

    fun formatSpeedPairCompact(downloadBps: Double, uploadBps: Double, idleLabel: String): String {
        if (downloadBps <= 0.5 && uploadBps <= 0.5) {
            return idleLabel
        }
        fun compact(bps: Double): String {
            return when {
                bps < 1024 -> "${bps.roundToInt()}B"
                bps < 1024 * 1024 -> "${(bps / 1024).roundToInt()}K"
                else -> String.format(Locale.US, "%.1fM", bps / (1024.0 * 1024.0))
            }
        }
        return "↓${compact(downloadBps)} ↑${compact(uploadBps)}"
    }

    fun formatLatency(latencyMs: Long): String {
        if (latencyMs <= 0) {
            return "—"
        }
        return "$latencyMs ms"
    }
}
