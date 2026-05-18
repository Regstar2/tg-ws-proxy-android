package com.amurcanov.tgwsproxy

enum class ConnectionMode(val prefValue: String) {
    Auto("auto"),
    DirectWithFallback("direct_with_fallback"),
    WorkerFirst("worker_first"),
    CFFirst("cf_first"),
    WorkerOnly("worker_only"),
    CFOnly("cf_only"),
    DirectOnly("direct_only");

    companion object {
        fun fromPref(value: String?): ConnectionMode {
            return entries.firstOrNull { it.prefValue == value } ?: DirectWithFallback
        }

        /** Maps legacy three-switch UI to a mode when connection_mode pref is absent. */
        fun fromLegacy(cfEnabled: Boolean, cfPriority: Boolean, cfOnly: Boolean): ConnectionMode {
            return when {
                cfOnly -> CFOnly
                cfPriority && cfEnabled -> CFFirst
                cfEnabled -> DirectWithFallback
                else -> DirectOnly
            }
        }
    }

    fun displayLabelRes(): Int = when (this) {
        Auto -> R.string.connection_mode_auto
        DirectWithFallback -> R.string.connection_mode_direct_cf_fallback
        WorkerFirst -> R.string.connection_mode_worker_first
        CFFirst -> R.string.connection_mode_cf_first
        WorkerOnly -> R.string.connection_mode_worker_only
        CFOnly -> R.string.connection_mode_cf_only
        DirectOnly -> R.string.connection_mode_direct_only
    }

    fun usesCfProxy(): Boolean = this != DirectOnly

    fun isRestrictedDirect(): Boolean = this == CFOnly || this == WorkerOnly
}
