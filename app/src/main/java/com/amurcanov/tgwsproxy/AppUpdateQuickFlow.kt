package com.amurcanov.tgwsproxy

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

internal enum class QuickUpdateDestination {
    WAIT_FOR_FOREGROUND,
    OPEN_SYSTEM_INSTALLER,
    OPEN_UPDATES_FOR_PERMISSION,
}

internal object QuickUpdateDeliveryPolicy {
    fun destination(isForeground: Boolean, canInstall: Boolean): QuickUpdateDestination = when {
        !isForeground -> QuickUpdateDestination.WAIT_FOR_FOREGROUND
        canInstall -> QuickUpdateDestination.OPEN_SYSTEM_INSTALLER
        else -> QuickUpdateDestination.OPEN_UPDATES_FOR_PERMISSION
    }
}

internal data class AppUpdateQuickHandoff(
    val release: GitHubReleaseInfo,
    val verifiedFile: File? = null,
    val errorMessage: Int? = null,
)

/**
 * An explicit tap in the startup dialog starts this best-effort, process-scoped
 * download. It never starts an Android installer from a background activity.
 * If Android kills the process, the next run clears any stale temporary APK.
 */
internal object AppUpdateQuickFlow {
    private const val TAG = "TgWsProxyUpdate"
    private const val CHANNEL = "tgwsproxy_update_downloads"
    private const val NOTIFICATION_ID = 139
    private const val NOTIFICATION_REQUEST = 139
    private const val CANCEL_REQUEST = 140
    internal const val ACTION_CANCEL_QUICK_UPDATE = "io.github.regstar2.tgwsproxy.CANCEL_QUICK_UPDATE"

    private sealed interface State {
        data object Idle : State
        data object Downloading : State
        data class Verified(val release: GitHubReleaseInfo, val file: File) : State
        data class Failed(val release: GitHubReleaseInfo, val message: Int) : State
        data object HandedOff : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var state: State = State.Idle
    private var downloadJob: Job? = null
    private var foreground: WeakReference<Activity>? = null
    private var subscribedApplication: Application? = null
    private var pendingHandoff: AppUpdateQuickHandoff? = null

    val isDownloading: Boolean get() = state is State.Downloading

    fun takeHandoff(): AppUpdateQuickHandoff? =
        pendingHandoff.also { pendingHandoff = null }

    /**
     * Called only after the user explicitly selects "Download and install".
     * Does not navigate to Updates, and ignores duplicate taps while working.
     */
    fun start(activity: Activity, release: GitHubReleaseInfo): Boolean {
        if (state is State.Downloading || state is State.Verified) return false
        if (AppUpdateAssetSelector.select(release) == null) return false

        val app = activity.application
        observeActivityLifecycle(app)
        foreground = WeakReference(activity)
        pendingHandoff = null
        state = State.Downloading

        Toast.makeText(app, R.string.update_quick_started, Toast.LENGTH_SHORT).show()
        showNotification(app, R.string.update_quick_downloading, 0, 100)

        downloadJob = scope.launch {
            var lastProgress = 0L
            try {
                val file = AppUpdateInstallerStore.get(app).download(release) { received, total ->
                    if (received - lastProgress >= 1024 * 1024 || received == total) {
                        lastProgress = received
                        withContext(Dispatchers.Main.immediate) {
                            val percentage = ((received * 100) / total.coerceAtLeast(1L))
                                .toInt().coerceIn(0, 100)
                            showNotification(app, R.string.update_quick_downloading, percentage, 100)
                        }
                    }
                }
                state = State.Verified(release, file)
                dispatchOrNotify(app)
            } catch (cancelled: CancellationException) {
                state = State.Idle
                dismissNotification(app)
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Quick update failed: ${error.javaClass.simpleName}")
                state = State.Failed(release, R.string.update_download_failed)
                dispatchOrNotify(app)
            } finally {
                downloadJob = null
            }
        }
        return true
    }

    fun cancel(context: Context) {
        if (state !is State.Downloading) return
        downloadJob?.cancel()
        dismissNotification(context.applicationContext)
    }

    private fun observeActivityLifecycle(app: Application) {
        if (subscribedApplication === app) return
        subscribedApplication = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                foreground = WeakReference(activity)
                dispatchOrNotify(app)
            }
            override fun onActivityPaused(activity: Activity) {
                if (foreground?.get() === activity) foreground = null
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (foreground?.get() === activity) foreground = null
            }
        })
    }

    private fun dispatchOrNotify(app: Application) {
        val activity = foreground?.get()
        // Resumed activities are recorded on explicit UI taps and lifecycle
        // callbacks, and removed before they can be used from the background.
        val isForeground = activity != null && !activity.isFinishing && !activity.isDestroyed

        when (val result = state) {
            is State.Verified -> when (QuickUpdateDeliveryPolicy.destination(
                isForeground = isForeground,
                canInstall = app.packageManager.canRequestPackageInstalls(),
            )) {
                QuickUpdateDestination.WAIT_FOR_FOREGROUND ->
                    showNotification(app, R.string.update_quick_ready)

                QuickUpdateDestination.OPEN_SYSTEM_INSTALLER -> {
                    val host = activity ?: return
                    state = State.HandedOff
                    dismissNotification(app)
                    try {
                        host.startActivity(AppUpdateInstallerStore.get(app).installIntent(result.file))
                    } catch (error: Exception) {
                        Log.w(TAG, "Quick installer failed: ${error.javaClass.simpleName}")
                        openUpdates(host, AppUpdateQuickHandoff(
                            result.release, errorMessage = R.string.update_installer_failed,
                        ))
                    }
                }

                QuickUpdateDestination.OPEN_UPDATES_FOR_PERMISSION -> {
                    val host = activity ?: return
                    state = State.HandedOff
                    dismissNotification(app)
                    openUpdates(host, AppUpdateQuickHandoff(
                        result.release, verifiedFile = result.file,
                    ))
                }
            }

            is State.Failed -> {
                if (isForeground && activity != null) {
                    state = State.HandedOff
                    dismissNotification(app)
                    openUpdates(activity, AppUpdateQuickHandoff(
                        result.release, errorMessage = result.message,
                    ))
                } else {
                    showNotification(app, R.string.update_quick_failed)
                }
            }

            else -> Unit
        }
    }

    private fun openUpdates(activity: Activity, handoff: AppUpdateQuickHandoff) {
        pendingHandoff = handoff
        try {
            activity.startActivity(
                Intent(activity, UpdateActivity::class.java)
                    .putExtra(UpdateActivity.EXTRA_QUICK_HANDOFF, true),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Unable to open update screen: ${error.javaClass.simpleName}")
            Toast.makeText(activity, R.string.update_installer_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showNotification(
        app: Application,
        label: Int,
        progress: Int = -1,
        maximum: Int = 100,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        try {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, app.getString(R.string.update_quick_channel),
                NotificationManager.IMPORTANCE_LOW,
            ))
            val launch = Intent(app, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            // The system sends this PendingIntent when the user taps the notification.
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                }.toBundle()
            } else null
            val pending = PendingIntent.getActivity(
                app, NOTIFICATION_REQUEST, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                options,
            )
            val isDownloading = progress >= 0
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_quick_settings_tile)
                .setContentTitle(app.getString(R.string.update_title))
                .setContentText(app.getString(label))
                .setOnlyAlertOnce(true)
                .setAutoCancel(!isDownloading)
                .setOngoing(isDownloading)
                .setContentIntent(pending)
                .apply {
                    if (isDownloading) {
                        setProgress(maximum, progress, false)
                        val cancelIntent = Intent(app, AppUpdateQuickCancelReceiver::class.java)
                            .setAction(ACTION_CANCEL_QUICK_UPDATE)
                        val cancelPending = PendingIntent.getBroadcast(
                            app, CANCEL_REQUEST, cancelIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        addAction(
                            R.drawable.ic_quick_settings_tile,
                            app.getString(R.string.update_cancel_download),
                            cancelPending,
                        )
                    }
                }
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            Log.w(TAG, "Update notification unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun dismissNotification(app: Context) {
        runCatching {
            app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}

/** Explicit internal action from the progress notification; no exported receiver. */
internal class AppUpdateQuickCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppUpdateQuickFlow.ACTION_CANCEL_QUICK_UPDATE) {
            AppUpdateQuickFlow.cancel(context)
        }
    }
}
