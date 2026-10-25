package com.amurcanov.tgwsproxy

internal data class ProxyNotificationSnapshot(
    val title: String,
    val contentText: String,
    val expandedText: String?,
    val displayMode: NotificationDisplayMode,
    val actionsEnabled: Boolean,
    val proxyRunning: Boolean,
)

internal class ProxyNotificationUpdateGate(
    private val minimumUpdateIntervalMs: Long = 3_000L,
) {
    init {
        require(minimumUpdateIntervalMs >= 0L)
    }

    private var lastPublishedSnapshot: ProxyNotificationSnapshot? = null
    private var lastPublishedElapsedMs: Long = 0L

    var startedAtMillis: Long? = null
        private set

    fun onForegroundStarted(
        snapshot: ProxyNotificationSnapshot,
        wallTimeMillis: Long,
        elapsedTimeMillis: Long,
    ) {
        startedAtMillis = wallTimeMillis
        onPublished(snapshot, elapsedTimeMillis)
    }

    fun shouldPublish(
        snapshot: ProxyNotificationSnapshot,
        elapsedTimeMillis: Long,
        force: Boolean = false,
    ): Boolean {
        if (startedAtMillis == null || snapshot == lastPublishedSnapshot) return false
        return force || elapsedTimeMillis - lastPublishedElapsedMs >= minimumUpdateIntervalMs
    }

    fun onPublished(snapshot: ProxyNotificationSnapshot, elapsedTimeMillis: Long) {
        lastPublishedSnapshot = snapshot
        lastPublishedElapsedMs = elapsedTimeMillis
    }

    fun onForegroundStopped() {
        startedAtMillis = null
        lastPublishedSnapshot = null
        lastPublishedElapsedMs = 0L
    }
}
