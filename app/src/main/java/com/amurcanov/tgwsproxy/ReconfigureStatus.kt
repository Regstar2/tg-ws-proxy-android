package com.amurcanov.tgwsproxy

import android.content.SharedPreferences

enum class ReconfigureStatusType {
    NONE,
    SUCCESS,
    SKIPPED,
    FAILED,
}

data class ReconfigureStatus(
    val type: ReconfigureStatusType,
    val networkType: NetworkProfileType?,
    val message: String,
    val updatedAtMs: Long,
)

object ReconfigureStatusStore {
    fun load(prefs: SharedPreferences): ReconfigureStatus {
        val type = runCatching {
            ReconfigureStatusType.valueOf(prefs.getString(KEY_TYPE, null).orEmpty())
        }.getOrDefault(ReconfigureStatusType.NONE)
        val networkType = prefs.getString(KEY_NETWORK_TYPE, null)
            ?.let { raw -> NetworkProfileType.entries.firstOrNull { it.prefValue == raw } }
        return ReconfigureStatus(
            type = type,
            networkType = networkType,
            message = prefs.getString(KEY_MESSAGE, "").orEmpty(),
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    fun save(prefs: SharedPreferences, status: ReconfigureStatus) {
        prefs.edit()
            .putString(KEY_TYPE, status.type.name)
            .putString(KEY_NETWORK_TYPE, status.networkType?.prefValue.orEmpty())
            .putString(KEY_MESSAGE, status.message)
            .putLong(KEY_UPDATED_AT, status.updatedAtMs)
            .apply()
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_TYPE)
            .remove(KEY_NETWORK_TYPE)
            .remove(KEY_MESSAGE)
            .remove(KEY_UPDATED_AT)
            .apply()
    }

    private const val KEY_TYPE = "last_reconfigure_type_v1"
    private const val KEY_NETWORK_TYPE = "last_reconfigure_network_type_v1"
    private const val KEY_MESSAGE = "last_reconfigure_message_v1"
    private const val KEY_UPDATED_AT = "last_reconfigure_updated_at_v1"
}
