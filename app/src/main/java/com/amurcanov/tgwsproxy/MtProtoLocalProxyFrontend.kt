package com.amurcanov.tgwsproxy

import java.net.InetSocketAddress
import java.net.ServerSocket

internal interface LocalPortAvailabilityChecker {
    fun isAvailable(host: String, port: Int): Boolean
}

internal object DefaultLocalPortAvailabilityChecker : LocalPortAvailabilityChecker {
    override fun isAvailable(host: String, port: Int): Boolean {
        return runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(host, port))
            }
            true
        }.getOrDefault(false)
    }
}

internal class MtProtoLocalProxyFrontend(
    private val runtimeAdapter: MtProtoRuntimeAdapter = NativeMtProtoRuntimeAdapter(),
    private val portAvailabilityChecker: LocalPortAvailabilityChecker = DefaultLocalPortAvailabilityChecker,
) : LocalProxyFrontend {
    private var state = LocalProxyFrontendState(
        type = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
        status = LocalProxyFrontendStatus.STOPPED,
    )

    override val type: LocalProxyFrontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL

    override fun start(config: LocalProxyFrontendConfig): LocalProxyFrontendStartResult {
        val proxyConfig = config.mtProtoConfig
            ?: return fail(
                errorCode = MtProtoRuntimeErrorCode.INVALID_CONFIG,
                message = "MTProto proxy config is missing.",
            )

        val mapping = MtProtoRuntimeConfigMapper.fromProxyConfig(
            config = proxyConfig,
            dcIps = config.runtimeConfig,
            verbose = config.verbose,
        )
        if (!mapping.isSuccess) {
            val errors = mapping.validationErrors.joinToString(",") { it.name }
            return fail(
                errorCode = mapping.errorCode ?: MtProtoRuntimeErrorCode.INVALID_CONFIG,
                message = "MTProto runtime config is invalid: ${errors.ifBlank { "UNKNOWN" }}.",
            )
        }

        val runtimeConfig = mapping.config!!
        if (!portAvailabilityChecker.isAvailable(runtimeConfig.host, runtimeConfig.port)) {
            return fail(
                errorCode = MtProtoRuntimeErrorCode.PORT_BUSY,
                message = "MTProto local port is busy: host=${runtimeConfig.host} port=${runtimeConfig.port}.",
            )
        }

        val result = runtimeAdapter.start(runtimeConfig)
        state = LocalProxyFrontendState(
            type = type,
            status = result.state.toLocalStatus(),
        )
        return LocalProxyFrontendStartResult(
            state = state,
            message = result.message,
            errorCode = result.errorCode?.name,
        )
    }

    override fun stop(): String? {
        val result = runtimeAdapter.stop()
        state = LocalProxyFrontendState(
            type = type,
            status = result.state.toLocalStatus(),
        )
        return null
    }

    override fun getState(): LocalProxyFrontendState = state

    private fun fail(
        errorCode: MtProtoRuntimeErrorCode,
        message: String,
    ): LocalProxyFrontendStartResult {
        state = LocalProxyFrontendState(
            type = type,
            status = LocalProxyFrontendStatus.FAILED,
        )
        return LocalProxyFrontendStartResult(
            state = state,
            message = message,
            errorCode = errorCode.name,
        )
    }

    private fun MtProtoRuntimeState.toLocalStatus(): LocalProxyFrontendStatus {
        return when (this) {
            MtProtoRuntimeState.STOPPED -> LocalProxyFrontendStatus.STOPPED
            MtProtoRuntimeState.STARTING,
            MtProtoRuntimeState.RUNNING,
            MtProtoRuntimeState.LISTENING_LOCAL_ONLY -> LocalProxyFrontendStatus.RUNNING
            MtProtoRuntimeState.FAILED -> LocalProxyFrontendStatus.FAILED
            MtProtoRuntimeState.UNSUPPORTED -> LocalProxyFrontendStatus.UNSUPPORTED
        }
    }
}
