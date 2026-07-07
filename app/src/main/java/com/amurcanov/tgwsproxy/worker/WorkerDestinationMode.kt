package com.amurcanov.tgwsproxy.worker

enum class WorkerDestinationMode(val prefValue: String) {
    PRESERVE_ORIGINAL_DST("preserve_original_dst"),
    FLOWSEAL_DC_MAP("flowseal_dc_map"),
    FLOWSEAL_MEDIA_DC4_FIX("flowseal_media_dc4_fix"),
    ;

    val wireLabel: String
        get() = when (this) {
            PRESERVE_ORIGINAL_DST -> "PRESERVE_ORIGINAL_DST"
            FLOWSEAL_DC_MAP -> "FLOWSEAL_DC_MAP"
            FLOWSEAL_MEDIA_DC4_FIX -> "EXPERIMENTAL_FORCE_MEDIA_DC4"
        }

    companion object {
        const val DEFAULT_MEDIA_FIX_DC = 4
        const val DEFAULT_MEDIA_FIX_IP = "149.154.167.220"

        fun fromPref(raw: String?): WorkerDestinationMode {
            return entries.firstOrNull { it.prefValue == raw?.trim() } ?: PRESERVE_ORIGINAL_DST
        }
    }
}
