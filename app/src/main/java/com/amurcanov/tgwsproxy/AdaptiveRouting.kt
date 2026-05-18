package com.amurcanov.tgwsproxy

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import java.security.MessageDigest

enum class NetworkProfileType(val prefValue: String) {
    WIFI("wifi"),
    MOBILE("mobile"),
    UNKNOWN("unknown"),
}

data class NetworkProfile(
    val id: String,
    val type: NetworkProfileType,
    val label: String,
)

data class RouteStatSnapshot(
    val profileId: String,
    val routeType: String,
    val dcId: Int,
    val isMedia: Boolean,
    val successCount: Int,
    val failureCount: Int,
    val lastFailureReason: String?,
    val averageLatencyMs: Long,
    val cooldownUntilMs: Long,
)

object NetworkProfileProvider {
    @SuppressLint("MissingPermission")
    fun current(context: Context): NetworkProfile {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        return when {
            caps == null -> unknownProfile()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiProfile(context)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> mobileProfile(context)
            else -> unknownProfile()
        }
    }

    private fun wifiProfile(context: Context): NetworkProfile {
        val rawId = "wifi_unknown"
        val label = context.getString(R.string.adaptive_network_wifi)
        return NetworkProfile(
            id = hashId("wifi", rawId),
            type = NetworkProfileType.WIFI,
            label = label,
        )
    }

    private fun mobileProfile(context: Context): NetworkProfile {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val operator = when {
            telephony == null -> "mobile_unknown"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                val name = telephony.simOperatorName?.trim().orEmpty()
                val mccMnc = telephony.simOperator?.trim().orEmpty()
                when {
                    mccMnc.isNotBlank() -> mccMnc
                    name.isNotBlank() -> name
                    else -> "mobile_unknown"
                }
            }
            else -> telephony.simOperator?.trim().orEmpty().ifBlank { "mobile_unknown" }
        }
        return NetworkProfile(
            id = hashId("mobile", operator),
            type = NetworkProfileType.MOBILE,
            label = context.getString(R.string.adaptive_network_mobile),
        )
    }

    private fun unknownProfile(): NetworkProfile {
        return NetworkProfile(
            id = hashId("unknown", "unknown"),
            type = NetworkProfileType.UNKNOWN,
            label = "",
        )
    }

    private fun hashId(prefix: String, raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$prefix:$raw".toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}

class AdaptiveRouteStatsRepository(
    private val prefs: SharedPreferences,
) {
    fun loadEncodedStats(): String = prefs.getString(KEY_STATS, "").orEmpty()

    fun saveEncodedStats(blob: String) {
        val cleaned = cleanupOldProfiles(blob, maxProfiles = MAX_PROFILES)
        prefs.edit().putString(KEY_STATS, cleaned).apply()
    }

    fun mergeFromNative(exported: String?) {
        if (exported.isNullOrBlank()) {
            return
        }
        saveEncodedStats(exported)
    }

    fun resetAll() {
        prefs.edit().remove(KEY_STATS).apply()
    }

    fun resetCurrentNetwork(profileId: String) {
        val (stats, lastGoods) = decode(loadEncodedStats())
        val filteredStats = stats.filter { it.profileId != profileId }
        val filteredLG = lastGoods.filter { it.profileId != profileId }
        saveEncodedStats(encode(filteredStats, filteredLG))
    }

    fun snapshotForDisplay(profileId: String): List<RouteStatSnapshot> {
        val (stats, _) = decode(loadEncodedStats())
        return stats
            .filter { it.profileId == profileId }
            .groupBy { it.routeType }
            .map { (route, entries) ->
                RouteStatSnapshot(
                    profileId = profileId,
                    routeType = route,
                    dcId = entries.first().dcId,
                    isMedia = entries.first().isMedia,
                    successCount = entries.sumOf { it.successCount },
                    failureCount = entries.sumOf { it.failureCount },
                    lastFailureReason = entries.mapNotNull { it.lastFailureReason }.maxByOrNull { it.length },
                    averageLatencyMs = entries.map { it.averageLatencyMs }.filter { it > 0 }.average().toLong(),
                    cooldownUntilMs = entries.maxOf { it.cooldownUntilMs },
                )
            }
            .sortedBy { it.routeType }
    }

    private data class StatEntry(
        val profileId: String,
        val routeType: String,
        val dcId: Int,
        val isMedia: Boolean,
        val successCount: Int,
        val failureCount: Int,
        val lastFailureReason: String?,
        val averageLatencyMs: Long,
        val cooldownUntilMs: Long,
    )

    private data class LastGoodEntry(
        val profileId: String,
        val dcId: Int,
        val isMedia: Boolean,
        val routeType: String,
        val lastGoodAtMs: Long,
    )

    private fun decode(raw: String): Pair<List<StatEntry>, List<LastGoodEntry>> {
        if (raw.isBlank()) {
            return emptyList<StatEntry>() to emptyList()
        }
        val stats = mutableListOf<StatEntry>()
        val lastGoods = mutableListOf<LastGoodEntry>()
        raw.split(';').forEach { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) {
                return@forEach
            }
            if (trimmed.startsWith("lg:")) {
                val parts = trimmed.removePrefix("lg:").split(':')
                if (parts.size >= 5) {
                    lastGoods += LastGoodEntry(
                        profileId = parts[0],
                        dcId = parts[1].toIntOrNull() ?: 0,
                        isMedia = parts[2] == "1",
                        routeType = parts[3],
                        lastGoodAtMs = parts[4].toLongOrNull() ?: 0L,
                    )
                }
            } else {
                val parts = trimmed.split(':')
                if (parts.size >= 14) {
                    stats += StatEntry(
                        profileId = parts[13],
                        routeType = parts[0],
                        dcId = parts[1].toIntOrNull() ?: 0,
                        isMedia = parts[2] == "1",
                        successCount = parts[3].toIntOrNull() ?: 0,
                        failureCount = parts[4].toIntOrNull() ?: 0,
                        lastFailureReason = parts[7].ifBlank { null },
                        averageLatencyMs = parts[9].toLongOrNull() ?: 0L,
                        cooldownUntilMs = parts[10].toLongOrNull() ?: 0L,
                    )
                }
            }
        }
        return stats to lastGoods
    }

    private fun encode(stats: List<StatEntry>, lastGoods: List<LastGoodEntry>): String {
        val parts = mutableListOf<String>()
        stats.forEach { st ->
            val media = if (st.isMedia) 1 else 0
            parts += "${st.routeType}:${st.dcId}:$media:${st.successCount}:${st.failureCount}:0:0:" +
                "${st.lastFailureReason.orEmpty()}:0:${st.averageLatencyMs}:${st.cooldownUntilMs}:0:0:${st.profileId}"
        }
        lastGoods.forEach { lg ->
            val media = if (lg.isMedia) 1 else 0
            parts += "lg:${lg.profileId}:${lg.dcId}:$media:${lg.routeType}:${lg.lastGoodAtMs}"
        }
        return parts.joinToString(";")
    }

    private fun cleanupOldProfiles(blob: String, maxProfiles: Int): String {
        val (stats, lastGoods) = decode(blob)
        val profileLastSeen = mutableMapOf<String, Long>()
        stats.forEach { st ->
            val prev = profileLastSeen[st.profileId] ?: 0L
            profileLastSeen[st.profileId] = maxOf(prev, st.cooldownUntilMs)
        }
        if (profileLastSeen.size <= maxProfiles) {
            return blob
        }
        val keep = profileLastSeen.entries
            .sortedByDescending { it.value }
            .take(maxProfiles)
            .map { it.key }
            .toSet()
        val filteredStats = stats.filter { it.profileId in keep }
        val filteredLG = lastGoods.filter { it.profileId in keep }
        return encode(filteredStats, filteredLG)
    }

    private companion object {
        const val KEY_STATS = "adaptive_route_stats"
        const val MAX_PROFILES = 20
    }
}
