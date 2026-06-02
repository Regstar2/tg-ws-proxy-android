package com.amurcanov.tgwsproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProxyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var metricsJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speedSampler = SpeedSampler()

    companion object {
        const val ACTION_START = "com.amurcanov.tgwsproxy.START"
        const val ACTION_STOP = "com.amurcanov.tgwsproxy.STOP"
        const val ACTION_RECONNECT = "com.amurcanov.tgwsproxy.RECONNECT"
        const val ACTION_RECONFIGURE = "com.amurcanov.tgwsproxy.RECONFIGURE"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_IPS = "EXTRA_IPS"
        const val EXTRA_POOL_SIZE = "EXTRA_POOL_SIZE"

        const val NOTIFICATION_ID = 3
        const val CHANNEL_STATUS_ID = "tgwsproxy_service_status_v3"
        const val CHANNEL_ALERTS_ID = "tgwsproxy_alerts_v3"

        private val LEGACY_CHANNEL_IDS = listOf(
            "proxy_status",
            "proxy_alerts",
            "tgwsproxy_service_status_v2",
            "tgwsproxy_alerts_v2",
        )
        private val LEGACY_NOTIFICATION_IDS = listOf(1, 2)

        private const val PREFS = "ProxyPrefs"
        private const val KEY_NOTIFICATION_CHANNELS_MIGRATED = "notification_channels_v3_migrated"
        private const val KEY_LAST_PORT = "last_proxy_port"
        private const val KEY_LAST_IPS = "last_runtime_ips"
        private const val KEY_LAST_POOL = "last_proxy_pool"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private var lastPort: Int = 1081
        private var lastIps: String = ""
        private var lastPoolSize: Int = 4
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        PersistentLogStore.initIfNeeded(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                loadLastConfig()
                logAction("start")
                val port = intent.getIntExtra(EXTRA_PORT, lastPort)
                val ips = intent.getStringExtra(EXTRA_IPS).orEmpty().ifBlank { lastIps }
                val poolSize = intent.getIntExtra(EXTRA_POOL_SIZE, lastPoolSize)
                if (ips.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                saveLastConfig(port, ips, poolSize)
                startProxy(port, ips, poolSize)
            }
            ACTION_STOP -> {
                logAction("stop")
                stopProxy()
            }
            ACTION_RECONNECT -> {
                logAction("reconnect")
                reconnectProxy()
            }
            ACTION_RECONFIGURE -> {
                loadLastConfig()
                logAction("reconfigure")
                val port = intent.getIntExtra(EXTRA_PORT, lastPort)
                val ips = intent.getStringExtra(EXTRA_IPS).orEmpty().ifBlank { lastIps }
                val poolSize = intent.getIntExtra(EXTRA_POOL_SIZE, lastPoolSize)
                if (ips.isBlank()) {
                    return START_STICKY
                }
                reconfigureProxy(port, ips, poolSize)
            }
        }
        return START_STICKY
    }

    private fun saveLastConfig(port: Int, ips: String, poolSize: Int) {
        lastPort = port
        lastIps = ips
        lastPoolSize = poolSize
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LAST_PORT, port)
            .putString(KEY_LAST_IPS, ips)
            .putInt(KEY_LAST_POOL, poolSize)
            .apply()
    }

    private fun loadLastConfig() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastPort = prefs.getInt(KEY_LAST_PORT, 1081)
        lastIps = prefs.getString(KEY_LAST_IPS, "").orEmpty()
        lastPoolSize = prefs.getInt(KEY_LAST_POOL, 4)
    }

    private fun startProxy(port: Int, ips: String, poolSize: Int) {
        if (_isRunning.value && ips == lastIps && port == lastPort && poolSize == lastPoolSize) {
            updateNotification()
            return
        }
        if (_isRunning.value) {
            stopNativeOnly()
        }
        saveLastConfig(port, ips, poolSize)
        ProxyRuntimeState.update {
            it.copy(serviceStatus = ProxyServiceStatus.STARTING)
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
        speedSampler.reset()
        Thread {
            NativeProxy.setPoolSize(poolSize)
            NativeProxy.startProxy("127.0.0.1", port, ips, 1)
        }.start()
        _isRunning.value = true
        ProxyRuntimeState.update {
            it.copy(serviceStatus = ProxyServiceStatus.RUNNING)
        }
        startMetricsLoop()
        updateNotification()
    }

    private fun reconnectProxy() {
        if (!_isRunning.value) {
            loadLastConfig()
            if (lastIps.isNotBlank()) {
                logAction("start")
                startProxy(lastPort, lastIps, lastPoolSize)
            }
            return
        }
        ProxyRuntimeState.update {
            it.copy(serviceStatus = ProxyServiceStatus.RECONNECTING)
        }
        updateNotification()
        serviceScope.launch(Dispatchers.IO) {
            stopNativeOnly()
            delay(400)
            NativeProxy.setPoolSize(lastPoolSize)
            NativeProxy.startProxy("127.0.0.1", lastPort, lastIps, 1)
            speedSampler.reset()
            ProxyRuntimeState.update {
                it.copy(serviceStatus = ProxyServiceStatus.RUNNING)
            }
            updateNotification()
        }
    }

    private fun reconfigureProxy(port: Int, ips: String, poolSize: Int) {
        if (!_isRunning.value) {
            saveLastConfig(port, ips, poolSize)
            startProxy(port, ips, poolSize)
            return
        }
        if (port == lastPort && ips == lastIps && poolSize == lastPoolSize) {
            updateNotification()
            return
        }
        ProxyRuntimeState.update {
            it.copy(serviceStatus = ProxyServiceStatus.RECONNECTING)
        }
        updateNotification()
        serviceScope.launch(Dispatchers.IO) {
            stopNativeOnly()
            delay(400)
            saveLastConfig(port, ips, poolSize)
            NativeProxy.setPoolSize(poolSize)
            NativeProxy.startProxy("127.0.0.1", port, ips, 1)
            speedSampler.reset()
            ProxyRuntimeState.update {
                it.copy(serviceStatus = ProxyServiceStatus.RUNNING)
            }
            launch(Dispatchers.Main) {
                updateNotification()
            }
        }
    }

    private fun stopNativeOnly() {
        val exported = NativeProxy.getAdaptiveRouteStats()
        NativeProxy.stopProxy()
        if (!exported.isNullOrBlank()) {
            AdaptiveRouteStatsRepository(
                getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            ).mergeFromNative(exported)
        }
    }

    private fun stopProxy() {
        metricsJob?.cancel()
        metricsJob = null
        serviceScope.launch(Dispatchers.IO) {
            stopNativeOnly()
            PersistentLogStore.flushAsync()
        }
        releaseWakeLock()
        _isRunning.value = false
        ProxyRuntimeState.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startMetricsLoop() {
        metricsJob?.cancel()
        metricsJob = serviceScope.launch(Dispatchers.IO) {
            val largeThresholdBytes = 10L * 1024L * 1024L
            var lastBytesUp: Long? = null
            var lastBytesDown: Long? = null
            var lastBytesAtMs: Long? = null
            while (isActive && _isRunning.value) {
                val prefs = NotificationPreferences.load(this@ProxyService)
                val interval = if (prefs.displayMode == NotificationDisplayMode.MINIMAL) 3000L else 2000L
                delay(interval)
                if (!_isRunning.value) {
                    continue
                }
                val status = NativeProxy.getProxyStatus()
                val runtime = ProxyRuntimeMetrics.parseStatus(status)
                    ?: ProxyRuntimeMetrics(running = true)
                // Best-effort large transfer detection (aggregate bytes counters).
                runCatching {
                    val now = System.currentTimeMillis()
                    val prevUp = lastBytesUp
                    val prevDown = lastBytesDown
                    val prevAt = lastBytesAtMs
                    if (prevUp != null && prevDown != null && prevAt != null) {
                        val dt = (now - prevAt).coerceAtLeast(1L)
                        val upDelta = (runtime.bytesUp - prevUp).coerceAtLeast(0L)
                        val downDelta = (runtime.bytesDown - prevDown).coerceAtLeast(0L)
                        if (upDelta >= largeThresholdBytes) {
                            AppLogger.i(
                                context = this@ProxyService,
                                category = AppLogCategory.TG,
                                message = "TG_LARGE_UPLOAD_DETECTED",
                                details = mapOf(
                                    "bytes" to upDelta.toString(),
                                    "durationMs" to dt.toString(),
                                ),
                            )
                        }
                        if (downDelta >= largeThresholdBytes) {
                            AppLogger.i(
                                context = this@ProxyService,
                                category = AppLogCategory.TG,
                                message = "TG_LARGE_DOWNLOAD_DETECTED",
                                details = mapOf(
                                    "bytes" to downDelta.toString(),
                                    "durationMs" to dt.toString(),
                                ),
                            )
                        }
                    }
                    lastBytesUp = runtime.bytesUp
                    lastBytesDown = runtime.bytesDown
                    lastBytesAtMs = now
                }
                val (downBps, upBps) = speedSampler.sample(runtime.bytesDown, runtime.bytesUp)
                ProxyRuntimeState.update {
                    it.copy(
                        runtime = runtime,
                        downloadBps = downBps,
                        uploadBps = upBps,
                        serviceStatus = when (it.serviceStatus) {
                            ProxyServiceStatus.RECONNECTING -> ProxyServiceStatus.RECONNECTING
                            else -> ProxyServiceStatus.RUNNING
                        },
                    )
                }
                launch(Dispatchers.Main) {
                    updateNotification()
                }
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val prefs = NotificationPreferences.load(this)
        val ui = ProxyRuntimeState.uiMetrics.value
        val title = getString(R.string.app_name)
        val content = notificationContentText(prefs, ui)
        val expanded = notificationExpandedText(prefs, ui)
        return ProxyNotificationBuilder.build(
            context = this,
            prefs = prefs,
            title = title,
            contentText = content,
            expandedText = expanded,
            contentIntent = openAppPendingIntent(),
            actions = buildActions(),
        )
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun notificationContentText(
        prefs: NotificationPreferences,
        ui: ProxyUiMetrics,
    ): String {
        val status = when (ui.serviceStatus) {
            ProxyServiceStatus.STOPPED -> getString(R.string.notification_status_stopped)
            ProxyServiceStatus.STARTING -> getString(R.string.notification_status_starting)
            ProxyServiceStatus.RECONNECTING -> getString(R.string.notification_status_reconnecting)
            ProxyServiceStatus.ERROR -> getString(R.string.notification_status_error)
            ProxyServiceStatus.RUNNING -> getString(
                R.string.notification_status_connected,
                ui.runtime.modeLabel(this),
                ui.runtime.routeLabel(this),
            )
        }
        if (!prefs.showMetricsInNotification || prefs.displayMode == NotificationDisplayMode.MINIMAL) {
            return status
        }
        if (ui.serviceStatus != ProxyServiceStatus.RUNNING) {
            return status
        }
        val speed = when (prefs.displayMode) {
            NotificationDisplayMode.COMPACT ->
                ConnectionMetricsFormatter.formatSpeedPairCompact(
                    ui.downloadBps,
                    ui.uploadBps,
                    getString(R.string.metrics_idle),
                )
            else ->
                ConnectionMetricsFormatter.formatSpeedPair(
                    ui.downloadBps,
                    ui.uploadBps,
                    getString(R.string.metrics_idle),
                )
        }
        return "$status · $speed"
    }

    private fun notificationExpandedText(
        prefs: NotificationPreferences,
        ui: ProxyUiMetrics,
    ): String? {
        if (prefs.displayMode != NotificationDisplayMode.NORMAL || !prefs.showMetricsInNotification) {
            return null
        }
        if (ui.serviceStatus != ProxyServiceStatus.RUNNING) {
            return null
        }
        val lines = mutableListOf<String>()
        lines += getString(R.string.notification_line_mode, ui.runtime.modeLabel(this))
        lines += getString(R.string.notification_line_route, ui.runtime.routeLabel(this))
        val speed = ConnectionMetricsFormatter.formatSpeedPair(
            ui.downloadBps,
            ui.uploadBps,
            getString(R.string.metrics_idle),
        )
        lines += getString(R.string.notification_line_speed, speed)
        lines += getString(
            R.string.notification_line_latency,
            ConnectionMetricsFormatter.formatLatency(ui.runtime.lastLatencyMs),
        )
        lines += getString(R.string.notification_line_connections, ui.runtime.activeConnections)
        if (ui.runtime.lastError.isNotBlank()) {
            lines += getString(R.string.notification_line_last_error, ui.runtime.lastError)
        }
        return lines.joinToString("\n")
    }

    private fun buildActions(): List<NotificationCompat.Action> {
        val actions = mutableListOf<NotificationCompat.Action>()
        if (_isRunning.value) {
            actions += action(
                requestCode = 10,
                title = getString(R.string.notification_action_stop),
                action = ACTION_STOP,
            )
            actions += action(
                requestCode = 11,
                title = getString(R.string.notification_action_reconnect),
                action = ACTION_RECONNECT,
            )
        } else {
            actions += action(
                requestCode = 12,
                title = getString(R.string.notification_action_start),
                action = ACTION_START,
            )
        }
        actions += openAppAction()
        return actions
    }

    private fun action(requestCode: Int, title: String, action: String): NotificationCompat.Action {
        val intent = Intent(this, ProxyService::class.java).apply { this.action = action }
        val pending = PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, title, pending).build()
    }

    private fun openAppAction(): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            0,
            getString(R.string.notification_action_open),
            openAppPendingIntent(),
        ).build()
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_STATUS, true)
        }
        return PendingIntent.getActivity(
            this,
            20,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun logAction(action: String) {
        AppLogger.i(
            context = this,
            category = AppLogCategory.APP,
            message = "NOTIFICATION_ACTION action=$action",
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        migrateLegacyNotificationChannels(manager)
        val status = NotificationChannel(
            CHANNEL_STATUS_ID,
            getString(R.string.notification_channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_status_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS_ID,
            getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_alerts_desc)
        }
        manager.createNotificationChannel(status)
        manager.createNotificationChannel(alerts)
    }

    private fun migrateLegacyNotificationChannels(manager: NotificationManager) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_CHANNELS_MIGRATED, false)) {
            return
        }
        LEGACY_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
        LEGACY_NOTIFICATION_IDS.forEach { manager.cancel(it) }
        manager.cancelAll()
        prefs.edit().putBoolean(KEY_NOTIFICATION_CHANNELS_MIGRATED, true).apply()
        Log.i(
            "TgWsProxy",
            "Migrated notification channels to $CHANNEL_STATUS_ID (removed legacy MIUI cache)",
        )
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TgWsProxy::ServiceWakeLock",
        )
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        if (_isRunning.value) {
            stopProxy()
        }
        PersistentLogStore.flushAsync()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
