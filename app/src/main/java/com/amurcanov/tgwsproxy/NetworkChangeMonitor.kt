package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NetworkChangeMonitor(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onNetworkChanged: (NetworkProfile) -> Unit,
) {
    private val appContext = context.applicationContext
    private val detector = NetworkProfileChangeDetector(NetworkProfileProvider.current(appContext))
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var debounceJob: Job? = null

    fun start() {
        if (callback != null) {
            return
        }
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleCheck()
            }

            override fun onLost(network: Network) {
                scheduleCheck()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scheduleCheck()
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(networkCallback)
            callback = networkCallback
        }
    }

    fun stop() {
        debounceJob?.cancel()
        debounceJob = null
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val registered = callback ?: return
        callback = null
        runCatching {
            cm?.unregisterNetworkCallback(registered)
        }
    }

    private fun scheduleCheck() {
        debounceJob?.cancel()
        debounceJob = coroutineScope.launch {
            delay(DEBOUNCE_MS)
            val profile = NetworkProfileProvider.current(appContext)
            if (detector.shouldNotify(profile)) {
                onNetworkChanged(profile)
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 1000L
    }
}
