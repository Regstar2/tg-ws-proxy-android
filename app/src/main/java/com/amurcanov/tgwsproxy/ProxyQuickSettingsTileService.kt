package com.amurcanov.tgwsproxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal object ProxyTileStateMapper {
    fun isActive(status: ProxyServiceStatus, running: Boolean): Boolean {
        return running && status == ProxyServiceStatus.RUNNING
    }

    fun isTransitioning(status: ProxyServiceStatus): Boolean {
        return status == ProxyServiceStatus.STARTING ||
            status == ProxyServiceStatus.RECONNECTING
    }
}

internal object ProxyTileCommandGate {
    private val inFlight = AtomicBoolean(false)
    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)
    fun release() { inFlight.set(false) }
}

class ProxyQuickSettingsTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            ProxyRuntimeState.uiMetrics.collect { refreshTile() }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val status = ProxyRuntimeState.uiMetrics.value.serviceStatus
        if (ProxyTileStateMapper.isTransitioning(status) || !ProxyTileCommandGate.tryAcquire()) return

        val running = ProxyService.isRunning.value
        val saved = if (!running && ProxyTileStartPolicy.canStart(this)) {
            runCatching {
                ProxySavedConfigStore(
                    getSharedPreferences(PROXY_PREFS_NAME, Context.MODE_PRIVATE),
                ).load()
            }.getOrNull()
        } else {
            null
        }
        if (!running && (saved == null || !saved.isSufficientForStart())) {
            ProxyTileCommandGate.release()
            refreshTile()
            return
        }

        val intent = Intent(this, ProxyService::class.java).apply {
            action = if (running) ProxyService.ACTION_STOP else ProxyService.ACTION_START
            // Explicit, validated extras let ProxyService persist the confirmation on
            // successful startup, including the first start after upgrading the app.
            saved?.let {
                putExtra(ProxyService.EXTRA_PORT, it.port)
                putExtra(ProxyService.EXTRA_IPS, it.runtimeConfig)
                putExtra(ProxyService.EXTRA_POOL_SIZE, it.poolSize)
            }
        }
        try {
            if (running) {
                startService(intent)
            } else {
                ContextCompat.startForegroundService(this, intent)
            }
        } catch (_: RuntimeException) {
            ProxyTileCommandGate.release()
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val status = ProxyRuntimeState.uiMetrics.value.serviceStatus
        val running = ProxyService.isRunning.value
        val subtitle = when {
            ProxyTileStateMapper.isTransitioning(status) -> R.string.quick_tile_transitioning
            ProxyTileStateMapper.isActive(status, running) -> R.string.quick_tile_on
            status == ProxyServiceStatus.ERROR -> R.string.quick_tile_error
            !ProxyTileStartPolicy.canStart(this) -> R.string.quick_tile_setup
            else -> R.string.quick_tile_off
        }
        tile.state = if (ProxyTileStateMapper.isActive(status, running)) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.quick_tile_title)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_quick_settings_tile)
        tile.contentDescription = getString(R.string.quick_tile_title) + ": " + getString(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(subtitle)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = getString(subtitle)
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        listeningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun requestRefresh(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, ProxyQuickSettingsTileService::class.java),
                )
            }
        }
    }
}
