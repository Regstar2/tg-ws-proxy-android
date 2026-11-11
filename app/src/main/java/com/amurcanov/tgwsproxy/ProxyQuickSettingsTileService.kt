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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ProxyTileClickAction {
    START,
    STOP,
    NONE,
}

internal data class ProxyTileState(
    val active: Boolean,
    val transitioning: Boolean,
    val clickAction: ProxyTileClickAction,
)

internal object ProxyTileStateMapper {
    fun resolve(
        status: ProxyServiceStatus,
        running: Boolean,
        preparingStart: Boolean = false,
        stopping: Boolean = false,
    ): ProxyTileState {
        val active = running && status == ProxyServiceStatus.RUNNING
        val inconsistent = running != (status == ProxyServiceStatus.RUNNING)
        val transitioning = preparingStart || stopping ||
            status == ProxyServiceStatus.STARTING ||
            status == ProxyServiceStatus.RECONNECTING ||
            inconsistent
        val action = when {
            transitioning -> ProxyTileClickAction.NONE
            active -> ProxyTileClickAction.STOP
            status == ProxyServiceStatus.STOPPED || status == ProxyServiceStatus.ERROR ->
                ProxyTileClickAction.START
            else -> ProxyTileClickAction.NONE
        }
        return ProxyTileState(active && !transitioning, transitioning, action)
    }

    fun actionForTap(state: ProxyTileState, publishedActive: Boolean?): ProxyTileClickAction {
        return if (publishedActive != null && publishedActive == state.active) {
            state.clickAction
        } else {
            ProxyTileClickAction.NONE
        }
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
    private var stopping = false
    private var lastLoggedTileState: String? = null

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            combine(ProxyRuntimeState.uiMetrics, ProxyService.isRunning) { _, _ -> Unit }
                .collect { refreshTile() }
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
        val running = ProxyService.isRunning.value
        val state = ProxyTileStateMapper.resolve(status, running, preparingStart, stopping)
        val publishedActive = when (qsTile?.state) {
            Tile.STATE_ACTIVE -> true
            Tile.STATE_INACTIVE -> false
            else -> null
        }
        val action = ProxyTileStateMapper.actionForTap(state, publishedActive)
        if (action == ProxyTileClickAction.NONE) {
            Log.i(TAG, "TILE_CLICK_IGNORED running=$running status=${status.name} tile_state=${qsTile?.state} action=${state.clickAction.name}")
            refreshTile()
            showFeedback(R.string.quick_tile_transitioning)
            return
        }
        if (!ProxyTileCommandGate.tryAcquire()) {
            showFeedback(R.string.quick_tile_transitioning)
            return
        }

        Log.i(TAG, "TILE_TAP running=$running status=${status.name} action=${action.name}")
        if (action == ProxyTileClickAction.STOP) {
            val latestStatus = ProxyRuntimeState.uiMetrics.value.serviceStatus
            val latestRunning = ProxyService.isRunning.value
            if (ProxyTileStateMapper.resolve(latestStatus, latestRunning).clickAction !=
                ProxyTileClickAction.STOP
            ) {
                Log.i(TAG, "TILE_STOP_SUPERSEDED running=$latestRunning status=${latestStatus.name}")
                ProxyTileCommandGate.release()
                refreshTile()
                return
            }
            stopping = true
            refreshTile()
            try {
                startService(Intent(this, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_STOP
                })
                Log.i(TAG, "TILE_STOP_REQUESTED")
            } catch (error: RuntimeException) {
                stopping = false
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

                val currentStatus = ProxyRuntimeState.uiMetrics.value.serviceStatus
                val currentRunning = ProxyService.isRunning.value
                if (ProxyTileStateMapper.resolve(currentStatus, currentRunning).clickAction !=
                    ProxyTileClickAction.START
                ) {
                    Log.i(TAG, "TILE_START_SUPERSEDED running=$currentRunning status=${currentStatus.name}")
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

    private fun androidTileState(state: ProxyTileState): Int = when {
        state.transitioning -> Tile.STATE_UNAVAILABLE
        state.active -> Tile.STATE_ACTIVE
        else -> Tile.STATE_INACTIVE
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val status = ProxyRuntimeState.uiMetrics.value.serviceStatus
        val running = ProxyService.isRunning.value
        if (stopping && !running &&
            (status == ProxyServiceStatus.STOPPED || status == ProxyServiceStatus.ERROR)
        ) {
            stopping = false
        }
        val state = ProxyTileStateMapper.resolve(status, running, preparingStart, stopping)
        val decision = if (state.clickAction == ProxyTileClickAction.START &&
            status == ProxyServiceStatus.STOPPED
        ) {
            ProxyTileStartPolicy.startDecision(this)
        } else {
            null
        }
        val subtitle = when {
            state.transitioning -> R.string.quick_tile_transitioning
            state.active -> R.string.quick_tile_on
            status == ProxyServiceStatus.ERROR -> R.string.quick_tile_error
            decision != null -> feedbackFor(decision)
            else -> R.string.quick_tile_off
        }
        tile.state = androidTileState(state)
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

        val stateKey = "${status.name}/$running/${state.active}/${state.transitioning}"
        if (stateKey != lastLoggedTileState) {
            Log.i(TAG, "TILE_STATE status=${status.name} running=$running active=${state.active} transitioning=${state.transitioning}")
            lastLoggedTileState = stateKey
        }
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
            }.onFailure { error ->
                Log.w(TAG, "TILE_REFRESH_REQUEST_FAILED exception=${error.javaClass.simpleName}")
            }
        }
    }
}
