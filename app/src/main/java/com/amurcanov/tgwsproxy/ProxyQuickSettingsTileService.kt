package com.amurcanov.tgwsproxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal object ProxyTileStateMapper {
    fun isActive(status: ProxyServiceStatus, running: Boolean): Boolean {
        return running && status == ProxyServiceStatus.RUNNING
    }

    fun isTransitioning(status: ProxyServiceStatus, preparingStart: Boolean = false): Boolean {
        return status == ProxyServiceStatus.STARTING ||
            status == ProxyServiceStatus.RECONNECTING ||
            (preparingStart && status == ProxyServiceStatus.STOPPED)
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
    private var preparingStart = false

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
        if (ProxyTileStateMapper.isTransitioning(status)) {
            showFeedback(R.string.quick_tile_transitioning)
            return
        }
        if (!ProxyTileCommandGate.tryAcquire()) {
            showFeedback(R.string.quick_tile_transitioning)
            return
        }

        val running = ProxyService.isRunning.value
        Log.i(TAG, "TILE_TAP running=$running status=${status.name}")
        if (running) {
            try {
                startService(Intent(this, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_STOP
                })
                Log.i(TAG, "TILE_STOP_REQUESTED")
            } catch (error: RuntimeException) {
                ProxyTileCommandGate.release()
                Log.e(TAG, "TILE_COMMAND_REJECTED error=${error.javaClass.simpleName}")
                showFeedback(R.string.quick_tile_error)
            }
            refreshTile()
            return
        }

        // Show pending state immediately; building the current network/route config
        // may involve I/O and must not block Android SystemUI's tile click callback.
        preparingStart = true
        refreshTile()
        val tappedAt = SystemClock.elapsedRealtime()
        scope.launch {
            var requested = false
            try {
                val check = withContext(Dispatchers.IO) {
                    ProxyTileStartPolicy.startCheck(applicationContext)
                }
                Log.i(TAG, "TILE_START_PREPARED elapsed_ms=${SystemClock.elapsedRealtime() - tappedAt}")
                val saved = check.saved
                if (check.decision != ProxyTileStartDecision.READY ||
                    saved == null || !saved.isSufficientForStart()
                ) {
                    val reason = if (check.decision == ProxyTileStartDecision.READY) {
                        ProxyTileStartDecision.VALIDATION_FAILED
                    } else {
                        check.decision
                    }
                    Log.w(TAG, "TILE_START_REJECTED reason=${reason.name}")
                    showFeedback(feedbackFor(reason))
                    return@launch
                }

                ContextCompat.startForegroundService(
                    this@ProxyQuickSettingsTileService,
                    Intent(this@ProxyQuickSettingsTileService, ProxyService::class.java).apply {
                        action = ProxyService.ACTION_START
                        putExtra(ProxyService.EXTRA_PORT, saved.port)
                        putExtra(ProxyService.EXTRA_IPS, saved.runtimeConfig)
                        putExtra(ProxyService.EXTRA_POOL_SIZE, saved.poolSize)
                    },
                )
                requested = true
                Log.i(TAG, "TILE_START_REQUESTED")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                Log.e(TAG, "TILE_COMMAND_REJECTED error=${error.javaClass.simpleName}")
                showFeedback(R.string.quick_tile_error)
            } finally {
                if (!requested) ProxyTileCommandGate.release()
                preparingStart = false
                refreshTile()
            }
        }
    }

    private fun feedbackFor(decision: ProxyTileStartDecision): Int = when (decision) {
        ProxyTileStartDecision.SETUP_REQUIRED,
        ProxyTileStartDecision.FRONTEND_INVALID -> R.string.quick_tile_setup
        ProxyTileStartDecision.CONFIRMATION_MISSING -> R.string.quick_tile_confirm_required
        ProxyTileStartDecision.VALIDATION_FAILED -> R.string.quick_tile_error
        ProxyTileStartDecision.NETWORK_CHANGED,
        ProxyTileStartDecision.ROUTE_POLICY_CHANGED,
        ProxyTileStartDecision.AWG_CHANGED,
        ProxyTileStartDecision.CONFIG_CHANGED -> R.string.quick_tile_config_changed
        ProxyTileStartDecision.READY -> R.string.quick_tile_off
    }

    private fun showFeedback(messageRes: Int) {
        Toast.makeText(applicationContext, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val status = ProxyRuntimeState.uiMetrics.value.serviceStatus
        val running = ProxyService.isRunning.value
        val decision = if (!preparingStart && !running && status == ProxyServiceStatus.STOPPED) {
            ProxyTileStartPolicy.startDecision(this)
        } else {
            null
        }
        val subtitle = when {
            ProxyTileStateMapper.isTransitioning(status, preparingStart) -> R.string.quick_tile_transitioning
            ProxyTileStateMapper.isActive(status, running) -> R.string.quick_tile_on
            status == ProxyServiceStatus.ERROR -> R.string.quick_tile_error
            decision != null -> feedbackFor(decision)
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
        private const val TAG = "ProxyQuickSettingsTile"

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
