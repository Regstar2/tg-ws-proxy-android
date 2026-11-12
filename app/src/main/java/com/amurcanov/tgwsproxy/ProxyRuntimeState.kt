package com.amurcanov.tgwsproxy

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProxyServiceStatus {
    STOPPED,
    STARTING,
    RUNNING,
    RECONNECTING,
    ERROR,
}

data class ProxyUiMetrics(
    val serviceStatus: ProxyServiceStatus = ProxyServiceStatus.STOPPED,
    val runtime: ProxyRuntimeMetrics = ProxyRuntimeMetrics(),
    val downloadBps: Double = 0.0,
    val uploadBps: Double = 0.0,
    /** Planned first route after hot apply; separate from last observed actual backend. */
    val configuredRouteKind: String = "",
)

object ProxyRuntimeState {
    private val _uiMetrics = MutableStateFlow(ProxyUiMetrics())
    val uiMetrics: StateFlow<ProxyUiMetrics> = _uiMetrics.asStateFlow()

    internal fun update(block: (ProxyUiMetrics) -> ProxyUiMetrics) {
        _uiMetrics.value = block(_uiMetrics.value)
    }

    internal fun reset() {
        _uiMetrics.value = ProxyUiMetrics()
    }
}

/** One-shot UI feedback; failure events are not replayed after Activity recreation. */
internal object ProxyStartFeedback {
    private val _busyPortEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val busyPortEvents = _busyPortEvents.asSharedFlow()

    fun busyPortFrom(errorCode: String?, port: Int): Int? =
        port.takeIf { it in 1..65535 && errorCode == MtProtoRuntimeErrorCode.PORT_BUSY.name }

    fun report(errorCode: String?, port: Int) {
        busyPortFrom(errorCode, port)?.let { _busyPortEvents.tryEmit(it) }
    }
}
