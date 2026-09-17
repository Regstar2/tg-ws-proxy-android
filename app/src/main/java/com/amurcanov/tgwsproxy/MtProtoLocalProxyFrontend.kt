package com.amurcanov.tgwsproxy

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.IdentityHashMap

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
        val awgConfigure = runCatching { awgRuntime.configure(awgConfig) }
        if (awgConfigure.isFailure) {
            resetAwgRuntimeAfterFailedConfigure()
            val errorType = awgConfigure.exceptionOrNull()?.javaClass?.simpleName.orEmpty()
            return fail(
                errorCode = MtProtoRuntimeErrorCode.INVALID_CONFIG,
                message = if (errorType.isBlank()) {
                    "AWG/WARP native runtime initialization failed unexpectedly."
                } else {
                    "AWG/WARP native runtime initialization failed ($errorType)."
                },
            )
        }
        if (awgConfigure.getOrThrow() != 0) {
            resetAwgRuntimeAfterFailedConfigure()
            return fail(
                errorCode = MtProtoRuntimeErrorCode.INVALID_CONFIG,
                message = "AWG/WARP route configuration was rejected by native runtime.",
            )
        }
        claimAwgRuntimeOwnership()

        val mapping = MtProtoRuntimeConfigMapper.fromProxyConfig(
            config = proxyConfig,
            dcIps = config.runtimeConfig,
            verbose = config.verbose,
        )
        if (!mapping.isSuccess) {
            safeResetAwgRuntime()
            val errors = mapping.validationErrors.joinToString(",") { it.name }
            return fail(
                errorCode = mapping.errorCode ?: MtProtoRuntimeErrorCode.INVALID_CONFIG,
                message = "MTProto runtime config is invalid: ${errors.ifBlank { "UNKNOWN" }}.",
            )
        }

        val runtimeConfig = mapping.config!!
        if (!portAvailabilityChecker.isAvailable(runtimeConfig.host, runtimeConfig.port)) {
            safeResetAwgRuntime()
            return fail(
                errorCode = MtProtoRuntimeErrorCode.PORT_BUSY,
                message = "MTProto local port is busy: host=${runtimeConfig.host} port=${runtimeConfig.port}.",
            )
        }

        val runtimeStart = runCatching { runtimeAdapter.start(runtimeConfig) }
        if (runtimeStart.isFailure) {
            safeResetAwgRuntime()
            val errorType = runtimeStart.exceptionOrNull()?.javaClass?.simpleName.orEmpty()
            return fail(
                errorCode = MtProtoRuntimeErrorCode.START_FAILED,
                message = if (errorType.isBlank()) {
                    "MTProto native runtime start failed unexpectedly."
                } else {
                    "MTProto native runtime start failed ($errorType)."
                },
            )
        }

        val result = runtimeStart.getOrThrow()
        state = LocalProxyFrontendState(
            type = type,
            status = result.state.toLocalStatus(),
        )
        if (state.status == LocalProxyFrontendStatus.FAILED ||
            state.status == LocalProxyFrontendStatus.UNSUPPORTED
        ) {
            safeResetAwgRuntime()
        }
        return LocalProxyFrontendStartResult(
            state = state,
            message = result.message,
            errorCode = result.errorCode?.name,
        )
    }

    override fun stop(): String? {
        val result = runtimeAdapter.stop()
        safeResetAwgRuntime()
        state = LocalProxyFrontendState(
            type = type,
            status = result.state.toLocalStatus(),
        )
        return null
    }

    override fun getState(): LocalProxyFrontendState = state

    private fun claimAwgRuntimeOwnership() {
        synchronized(awgRuntimeOwnerLock) {
            awgRuntimeOwners[awgRuntime] = this
        }
    }

    private fun safeResetAwgRuntime() {
        val ownsRuntime = synchronized(awgRuntimeOwnerLock) {
            if (awgRuntimeOwners[awgRuntime] !== this) {
                false
            } else {
                awgRuntimeOwners.remove(awgRuntime)
                true
            }
        }
        if (ownsRuntime) {
            runCatching { awgRuntime.reset() }
        }
    }

    private fun resetAwgRuntimeAfterFailedConfigure() {
        synchronized(awgRuntimeOwnerLock) {
            awgRuntimeOwners.remove(awgRuntime)
        }
        runCatching { awgRuntime.reset() }
    }

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

    companion object {
        private val awgRuntimeOwnerLock = Any()
        private val awgRuntimeOwners = IdentityHashMap<AwgWarpNativeRuntime, MtProtoLocalProxyFrontend>()
    }
}
