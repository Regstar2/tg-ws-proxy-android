package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class MtProtoAwgRuntimeOwnershipTest {
    private val fixedSecret = "0123456789abcdef0123456789abcdef"
    private val runtimeConfig = listOf(
        "1:149.154.175.50",
        "@route_awg_warp=1",
        "@preferred_route=awg_warp",
        "@route_fallback=0",
        "@awg_warp_config_path=/private/active.conf",
    ).joinToString(",")

    @Test
    fun staleFrontendStop_doesNotResetAwgRuntimeOwnedByReplacement() {
        val awgRuntime = RecordingAwgRuntime()
        val first = frontend(awgRuntime)
        val replacement = frontend(awgRuntime)

        assertEquals(LocalProxyFrontendStatus.RUNNING, first.start(startConfig()).state.status)
        assertEquals(LocalProxyFrontendStatus.RUNNING, replacement.start(startConfig()).state.status)
        assertEquals(2, awgRuntime.configureCalls)
        assertEquals(0, awgRuntime.resetCalls)

        first.stop()
        assertEquals(0, awgRuntime.resetCalls)

        replacement.stop()
        assertEquals(1, awgRuntime.resetCalls)
    }

    private fun frontend(awgRuntime: RecordingAwgRuntime): MtProtoLocalProxyFrontend {
        return MtProtoLocalProxyFrontend(
            runtimeAdapter = RunningMtProtoRuntimeAdapter(),
            portAvailabilityChecker = AlwaysAvailablePortChecker,
            awgRuntime = awgRuntime,
        )
    }

    private fun startConfig(): LocalProxyFrontendConfig {
        val mtProto = MtProtoProxyConfig.default { fixedSecret }
        return LocalProxyFrontendConfig(
            host = mtProto.host,
            port = mtProto.port,
            runtimeConfig = runtimeConfig,
            poolSize = 4,
            verbose = 1,
            mtProtoConfig = mtProto,
        )
    }

    private object AlwaysAvailablePortChecker : LocalPortAvailabilityChecker {
        override fun isAvailable(host: String, port: Int): Boolean = true
    }

    private class RecordingAwgRuntime : AwgWarpNativeRuntime {
        var configureCalls = 0
        var resetCalls = 0

        override fun configure(config: AwgWarpRuntimeConfig): Int {
            configureCalls += 1
            return 0
        }

        override fun reset(): Int {
            resetCalls += 1
            return 0
        }
    }

    private class RunningMtProtoRuntimeAdapter : MtProtoRuntimeAdapter {
        override fun start(config: MtProtoRuntimeConfig): MtProtoRuntimeStartResult {
            return MtProtoRuntimeStartResult(MtProtoRuntimeState.RUNNING)
        }

        override fun stop(): MtProtoRuntimeStopResult {
            return MtProtoRuntimeStopResult(MtProtoRuntimeState.STOPPED)
        }

        override fun getState(): MtProtoRuntimeState = MtProtoRuntimeState.RUNNING
    }
}
