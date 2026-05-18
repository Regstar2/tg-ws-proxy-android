package com.amurcanov.tgwsproxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
