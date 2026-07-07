package com.amurcanov.tgwsproxy.worker

import android.content.SharedPreferences

data class DcIpTexts(
    val dc1: String,
    val dc2: String,
    val dc4: String,
    val dc203: String,
) {
    fun persist(prefs: SharedPreferences) {
        prefs.edit()
            .putString(KEY_DC1, dc1)
            .putString(KEY_DC2, dc2)
            .putString(KEY_DC4, dc4)
            .putString(KEY_DC203, dc203)
            .apply()
    }

    companion object {
        const val KEY_DC1 = "dc1"
        const val KEY_DC2 = "dc2"
        const val KEY_DC4 = "dc4"
        const val KEY_DC203 = "dc203"
    }
}

object FlowsealDcPreset {
    const val PREF_ENABLED = "flowseal_dc_only_enabled"
    const val FLOWSEAL_DC = 4
    const val FLOWSEAL_IP = "149.154.167.220"

    private const val BACKUP_DC1 = "flowseal_dc_backup_dc1"
    private const val BACKUP_DC2 = "flowseal_dc_backup_dc2"
    private const val BACKUP_DC4 = "flowseal_dc_backup_dc4"
    private const val BACKUP_DC203 = "flowseal_dc_backup_dc203"

    private val standardIps = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        4 to "149.154.167.91",
        203 to "91.105.192.100",
    )

    fun standardTexts(): DcIpTexts = DcIpTexts(
        dc1 = standardIps.getValue(1),
        dc2 = standardIps.getValue(2),
        dc4 = standardIps.getValue(4),
        dc203 = standardIps.getValue(203),
    )

    fun flowsealTexts(): DcIpTexts = DcIpTexts(
        dc1 = "",
        dc2 = "",
        dc4 = FLOWSEAL_IP,
        dc203 = "",
    )

    fun loadTexts(prefs: SharedPreferences): DcIpTexts {
        val defaults = standardTexts()
        return DcIpTexts(
            dc1 = prefs.getString(DcIpTexts.KEY_DC1, defaults.dc1) ?: defaults.dc1,
            dc2 = prefs.getString(DcIpTexts.KEY_DC2, defaults.dc2) ?: defaults.dc2,
            dc4 = prefs.getString(DcIpTexts.KEY_DC4, defaults.dc4) ?: defaults.dc4,
            dc203 = prefs.getString(DcIpTexts.KEY_DC203, defaults.dc203) ?: defaults.dc203,
        )
    }

    fun dcEntries(texts: DcIpTexts, flowsealOnly: Boolean): List<String> {
        if (flowsealOnly) {
            return listOf("$FLOWSEAL_DC:$FLOWSEAL_IP")
        }
        return buildList {
            if (texts.dc1.isNotBlank()) add("1:${texts.dc1.trim()}")
            if (texts.dc2.isNotBlank()) add("2:${texts.dc2.trim()}")
            if (texts.dc4.isNotBlank()) add("4:${texts.dc4.trim()}")
            if (texts.dc203.isNotBlank()) add("203:${texts.dc203.trim()}")
        }
    }

    fun enable(prefs: SharedPreferences, current: DcIpTexts): DcIpTexts {
        val applied = flowsealTexts()
        prefs.edit()
            .putString(BACKUP_DC1, current.dc1)
            .putString(BACKUP_DC2, current.dc2)
            .putString(BACKUP_DC4, current.dc4)
            .putString(BACKUP_DC203, current.dc203)
            .putBoolean(PREF_ENABLED, true)
            .putString(DcIpTexts.KEY_DC1, applied.dc1)
            .putString(DcIpTexts.KEY_DC2, applied.dc2)
            .putString(DcIpTexts.KEY_DC4, applied.dc4)
            .putString(DcIpTexts.KEY_DC203, applied.dc203)
            .apply()
        return applied
    }

    fun disable(prefs: SharedPreferences): DcIpTexts {
        val defaults = standardTexts()
        val restored = DcIpTexts(
            dc1 = prefs.getString(BACKUP_DC1, null)?.takeIf { it.isNotBlank() } ?: defaults.dc1,
            dc2 = prefs.getString(BACKUP_DC2, null)?.takeIf { it.isNotBlank() } ?: defaults.dc2,
            dc4 = prefs.getString(BACKUP_DC4, null)?.takeIf { it.isNotBlank() } ?: defaults.dc4,
            dc203 = prefs.getString(BACKUP_DC203, null)?.takeIf { it.isNotBlank() } ?: defaults.dc203,
        )
        prefs.edit()
            .putBoolean(PREF_ENABLED, false)
            .putString(DcIpTexts.KEY_DC1, restored.dc1)
            .putString(DcIpTexts.KEY_DC2, restored.dc2)
            .putString(DcIpTexts.KEY_DC4, restored.dc4)
            .putString(DcIpTexts.KEY_DC203, restored.dc203)
            .apply()
        return restored
    }
}
