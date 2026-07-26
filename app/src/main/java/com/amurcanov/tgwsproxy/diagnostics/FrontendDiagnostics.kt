package com.amurcanov.tgwsproxy.diagnostics

import android.content.Context
import com.amurcanov.tgwsproxy.LocalProxyFrontendRepository
import com.amurcanov.tgwsproxy.LocalProxyFrontendType
import com.amurcanov.tgwsproxy.MtProtoNativeStatus
import com.amurcanov.tgwsproxy.MtProtoProxyConfig
import com.amurcanov.tgwsproxy.MtProtoProxyConfigRepository
import com.amurcanov.tgwsproxy.MtProtoProxyConfigValidator
import com.amurcanov.tgwsproxy.NativeProxy
import com.amurcanov.tgwsproxy.ProxyRuntimeMetrics
import com.amurcanov.tgwsproxy.ProxyRuntimeState
import com.amurcanov.tgwsproxy.ProxyServiceStatus
import com.amurcanov.tgwsproxy.worker.WorkerPoolReportSnapshot

enum class DiagnosticSupportStatus {
    READY,
    LIMITED,
    UNSUPPORTED,
    UNKNOWN,
}

data class FrontendDiagnosticsSnapshot(
    val configuredFrontendKind: String = "SOCKS5",
    val runtimeFrontendKind: String = "NONE",
    val socks5ListenerStatus: String = "STOPPED",
    val socks5RouteSupport: DiagnosticSupportStatus = DiagnosticSupportStatus.READY,
    val socks5SelectedRoute: String = "",
    val socks5ActiveRoute: String = "",
    val mtProtoEnabled: Boolean = false,
    val mtProtoHost: String = "",
    val mtProtoPort: Int = 0,
    val mtProtoSecretStatus: String = "NOT_CONFIGURED",
    val mtProtoSecretFingerprint: String = "",
    val mtProtoListenerStatus: String = "UNAVAILABLE",
    val mtProtoOutboundStatus: String = "UNAVAILABLE",
    val mtProtoRouteSupport: DiagnosticSupportStatus = DiagnosticSupportStatus.UNSUPPORTED,
    val mtProtoProxyLinkSupport: DiagnosticSupportStatus = DiagnosticSupportStatus.UNSUPPORTED,
    val mtProtoSelectedBackend: String = "",
    val mtProtoActualBackend: String = "",
    val mtProtoLastErrorCode: String = "NONE",
    val mtProtoFakeTlsEnabled: Boolean = false,
    val mtProtoMaskingPassthrough: Boolean = false,
    val mtProtoFakeTlsAccepted: Long = 0L,
    val mtProtoFakeTlsRejected: Long = 0L,
    val mtProtoFakeTlsRedirected: Long = 0L,
    val mtProtoFakeTlsProbe: Long = 0L,
    val mtProtoFakeTlsPassthrough: Long = 0L,
    val mtProtoFakeTlsLastError: String = "",
    val runtimeRouteSupport: DiagnosticSupportStatus = DiagnosticSupportStatus.UNKNOWN,
    val selectedBackend: String = "",
    val actualBackend: String = "",
    val fallbackUsed: Boolean? = null,
    val routeReason: String = "",
    val lastErrorCode: String = "NONE",
    val lastSuccessfulStartAtMs: Long? = null,
    val workerPoolEnabled: Boolean = false,
    val workerPoolWorkersCount: Int = 0,
    val workerPoolEnabledWorkersCount: Int = 0,
    val workerPoolSelectedWorkerName: String = "",
    val mtProtoWorkerPoolSupport: DiagnosticSupportStatus = DiagnosticSupportStatus.UNSUPPORTED,
)

internal data class FrontendDiagnosticsInput(
    val configuredFrontend: LocalProxyFrontendType,
    val serviceStatus: ProxyServiceStatus,
    val socks5Runtime: ProxyRuntimeMetrics,
    val mtProtoConfig: MtProtoProxyConfig,
    val mtProtoNativeStatus: MtProtoNativeStatus?,
    val workerPoolSnapshot: WorkerPoolReportSnapshot?,
)

internal object FrontendDiagnosticsMapper {
    fun map(input: FrontendDiagnosticsInput): FrontendDiagnosticsSnapshot {
        val native = input.mtProtoNativeStatus
        val mtConfigValid = MtProtoProxyConfigValidator.validate(input.mtProtoConfig.normalized()).isValid
        val mtRouteSupport = mtProtoRouteSupport(native)
        val workerPool = input.workerPoolSnapshot
        val runtimeFrontend = when {
            native?.status in MTPROTO_LISTENING_STATES -> "MTPROTO"
            input.serviceStatus == ProxyServiceStatus.RUNNING &&
                input.configuredFrontend == LocalProxyFrontendType.SOCKS5 -> "SOCKS5"
            else -> "NONE"
        }
        val socksRoute = input.socks5Runtime.routeRuntime

        return FrontendDiagnosticsSnapshot(
            configuredFrontendKind = when (input.configuredFrontend) {
                LocalProxyFrontendType.SOCKS5 -> "SOCKS5"
                LocalProxyFrontendType.MTPROTO_EXPERIMENTAL -> "MTPROTO"
            },
            runtimeFrontendKind = runtimeFrontend,
            socks5ListenerStatus = if (
                input.serviceStatus == ProxyServiceStatus.RUNNING &&
                input.configuredFrontend == LocalProxyFrontendType.SOCKS5
            ) {
                "RUNNING"
            } else {
                "STOPPED"
            },
            socks5RouteSupport = DiagnosticSupportStatus.READY,
            socks5SelectedRoute = socksRoute.selectedRoute,
            socks5ActiveRoute = socksRoute.activeRoute,
            mtProtoEnabled = input.mtProtoConfig.enabled,
            mtProtoHost = input.mtProtoConfig.host,
            mtProtoPort = input.mtProtoConfig.port,
            mtProtoSecretStatus = when {
                input.mtProtoConfig.secret.isBlank() -> "NOT_CONFIGURED"
                mtConfigValid -> "CONFIGURED_MASKED"
                else -> "INVALID"
            },
            mtProtoSecretFingerprint = native?.secretFingerprint.orEmpty(),
            mtProtoListenerStatus = native?.status.orEmpty().ifBlank { "UNAVAILABLE" },
            mtProtoOutboundStatus = native?.outbound.orEmpty().ifBlank { "UNAVAILABLE" },
            mtProtoRouteSupport = mtRouteSupport,
            mtProtoProxyLinkSupport = if (mtConfigValid) {
                DiagnosticSupportStatus.READY
            } else {
                DiagnosticSupportStatus.UNSUPPORTED
            },
            mtProtoSelectedBackend = native?.selectedBackend.orEmpty().noneAsBlank(),
            mtProtoActualBackend = native?.actualBackend.orEmpty().noneAsBlank(),
            mtProtoLastErrorCode = mtProtoLastErrorCode(native),
            mtProtoFakeTlsEnabled = native?.fakeTls ?: input.mtProtoConfig.fakeTlsDomain.isNotBlank(),
            mtProtoMaskingPassthrough = native?.maskingPassthrough ?: input.mtProtoConfig.fakeTlsPassthrough,
            mtProtoFakeTlsAccepted = native?.fakeTlsAccepted ?: 0L,
            mtProtoFakeTlsRejected = native?.fakeTlsRejected ?: 0L,
            mtProtoFakeTlsRedirected = native?.fakeTlsRedirected ?: 0L,
            mtProtoFakeTlsProbe = native?.fakeTlsProbe ?: 0L,
            mtProtoFakeTlsPassthrough = native?.fakeTlsPassthrough ?: 0L,
            mtProtoFakeTlsLastError = native?.fakeTlsLastError.orEmpty().noneAsBlank(),
            runtimeRouteSupport = when (runtimeFrontend) {
                "MTPROTO" -> mtRouteSupport
                "SOCKS5" -> DiagnosticSupportStatus.READY
                else -> DiagnosticSupportStatus.UNKNOWN
            },
            selectedBackend = when (runtimeFrontend) {
                "MTPROTO" -> native?.selectedBackend.orEmpty().noneAsBlank()
                "SOCKS5" -> socksRoute.selectedRoute
                else -> ""
            },
            actualBackend = when (runtimeFrontend) {
                "MTPROTO" -> native?.actualBackend.orEmpty().noneAsBlank()
                "SOCKS5" -> socksRoute.activeRoute
                else -> ""
            },
            fallbackUsed = when (runtimeFrontend) {
                "MTPROTO" -> native?.fallbackUsed
                "SOCKS5" -> socksRoute.fallbackReason.takeIf { it.isNotBlank() }?.let { true }
                else -> null
            },
            routeReason = when (runtimeFrontend) {
                "MTPROTO" -> native?.routeReason.orEmpty().noneAsBlank()
                "SOCKS5" -> socksRoute.fallbackReason
                else -> ""
            },
            lastErrorCode = when (runtimeFrontend) {
                "MTPROTO" -> mtProtoLastErrorCode(native)
                "SOCKS5" -> input.socks5Runtime.lastError.ifBlank { "NONE" }
                else -> "NONE"
            },
            workerPoolEnabled = workerPool?.enabled == true,
            workerPoolWorkersCount = workerPool?.workers?.size ?: 0,
            workerPoolEnabledWorkersCount = workerPool?.workers?.count { it.enabled } ?: 0,
            workerPoolSelectedWorkerName = workerPool?.selectedWorker?.name.orEmpty(),
            mtProtoWorkerPoolSupport = when {
                native?.selectedBackend.noneAsBlank() == "cf_worker_ws" ->
                    DiagnosticSupportStatus.LIMITED
                else -> DiagnosticSupportStatus.UNSUPPORTED
            },
        )
    }

    internal fun mtProtoRouteSupport(status: MtProtoNativeStatus?): DiagnosticSupportStatus {
        if (status == null) return DiagnosticSupportStatus.UNSUPPORTED
        if (
            status.status == "LISTENING_LOCAL_ONLY" ||
            status.outbound == "OUTBOUND_UNSUPPORTED"
        ) {
            return DiagnosticSupportStatus.UNSUPPORTED
        }
        if (
            status.outbound.startsWith("MTPROTO_ROUTE_") &&
            status.selectedBackend.noneAsBlank() in MTPROTO_PARTIAL_BACKENDS
        ) {
            return DiagnosticSupportStatus.LIMITED
        }
        return DiagnosticSupportStatus.UNKNOWN
    }

    private fun mtProtoLastErrorCode(status: MtProtoNativeStatus?): String {
        if (status == null) return "STATUS_UNAVAILABLE"
        if (status.status.startsWith("FAILED_")) return status.status
        val reason = status.routeReason.noneAsBlank()
        return if (status.lastError.isNotBlank()) {
            reason.ifBlank { "RUNTIME_ERROR" }.uppercase()
        } else {
            "NONE"
        }
    }

    private val MTPROTO_LISTENING_STATES = setOf(
        "LISTENING_LOCAL_ONLY",
        "LISTENING_ROUTE_READY",
    )

    private val MTPROTO_PARTIAL_BACKENDS = setOf(
        "direct_ws",
        "direct_tcp",
        "cf_worker_ws",
        "cf_proxy_ws",
    )
}

internal object FrontendDiagnosticsSource {
    fun read(
        context: Context,
        workerPoolSnapshot: WorkerPoolReportSnapshot? = null,
    ): FrontendDiagnosticsSnapshot {
        val prefs = context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val nativeStatus = runCatching {
            NativeProxy.getMtProtoProxyStatus()?.let(MtProtoNativeStatus::parse)
        }.getOrNull()
        return FrontendDiagnosticsMapper.map(
            FrontendDiagnosticsInput(
                configuredFrontend = LocalProxyFrontendRepository(prefs).load(),
                serviceStatus = ProxyRuntimeState.uiMetrics.value.serviceStatus,
                socks5Runtime = ProxyRuntimeState.uiMetrics.value.runtime,
                mtProtoConfig = MtProtoProxyConfigRepository(prefs).loadForDiagnostics(),
                mtProtoNativeStatus = nativeStatus,
                workerPoolSnapshot = workerPoolSnapshot,
            ),
        )
    }
}

private fun String?.noneAsBlank(): String {
    val normalized = this?.trim().orEmpty()
    return if (normalized.equals("none", ignoreCase = true)) "" else normalized
}
