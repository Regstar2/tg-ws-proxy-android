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

internal object NoOpAwgWarpRuntime : AwgWarpNativeRuntime {
    override fun configure(config: AwgWarpRuntimeConfig): Int = 0
    override fun reset(): Int = 0
}

internal class MtProtoLocalProxyFrontend(
    private val runtimeAdapter: MtProtoRuntimeAdapter = NativeMtProtoRuntimeAdapter(),
    private val portAvailabilityChecker: LocalPortAvailabilityChecker = DefaultLocalPortAvailabilityChecker,
    private val awgRuntime: AwgWarpNativeRuntime = NoOpAwgWarpRuntime,
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

        val awgConfig = AwgWarpRuntimeConfig.fromRuntimeTokens(config.runtimeConfig)
        if (awgConfig.enabled && awgConfig.configPath.isBlank()) {
            return fail(
                errorCode = MtProtoRuntimeErrorCode.INVALID_CONFIG,
                message = "AWG/WARP route is enabled but no config has been imported.",
            )
        }
        if (awgRuntime.configure(awgConfig) != 0) {
            return fail(
                errorCode = MtProtoRuntimeErrorCode.INVALID_CONFIG,
                message = "AWG/WARP route configuration was rejected by native runtime.",
            )
        }

        val mapping = MtProtoRuntimeConfigMapper.fromProxyConfig(
            config = proxyConfig,
            dcIps = config.runtimeConfig,
            verbose = config.verbose,
        )
        if (!mapping.isSuccess) {
            awgRuntime.reset()
            val errors = mapping.validationErrors.joinToString(",") { it.name }
            return fail(
                errorCode = mapping.errorCode ?: MtProtoRuntimeErrorCode.INVALID_CONFIG,
                message = "MTProto runtime config is invalid: ${errors.ifBlank { "UNKNOWN" }}.",
            )
        }

        val runtimeConfig = mapping.config!!
        if (!portAvailabilityChecker.isAvailable(runtimeConfig.host, runtimeConfig.port)) {
            awgRuntime.reset()
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
        if (state.status == LocalProxyFrontendStatus.FAILED ||
            state.status == LocalProxyFrontendStatus.UNSUPPORTED
        ) {
            awgRuntime.reset()
        }
        return LocalProxyFrontendStartResult(
            state = state,
            message = result.message,
            errorCode = result.errorCode?.name,
        )
    }

    override fun stop(): String? {
        val result = runtimeAdapter.stop()
        awgRuntime.reset()
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
