package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProxyFrontendTest {
    private val fixedSecret = "0123456789abcdef0123456789abcdef"

    @Test
    fun socks5Frontend_startsThroughNativeProxyWrapper() {
        val nativeProxy = RecordingSocks5NativeProxy()
        val frontend = Socks5LocalProxyFrontend(nativeProxy)

        val result = frontend.start(
            LocalProxyFrontendConfig(
                host = "127.0.0.1",
                port = 1443,
                runtimeConfig = "1:149.154.175.50",
                poolSize = 4,
                verbose = 1,
            ),
        )

        assertEquals(LocalProxyFrontendType.MTPROTO_EXPERIMENTAL, LocalProxyFrontendType.DEFAULT)
        assertEquals(LocalProxyFrontendStatus.RUNNING, result.state.status)
        assertEquals(LocalProxyFrontendStatus.RUNNING, frontend.getState().status)
        assertEquals(4, nativeProxy.recordedPoolSize)
        assertEquals("127.0.0.1", nativeProxy.host)
        assertEquals(1443, nativeProxy.port)
        assertEquals("1:149.154.175.50", nativeProxy.runtimeConfig)
        assertEquals(1, nativeProxy.verbose)
        assertNull(result.errorCode)
    }

    @Test
    fun mtprotoExperimentalFrontend_doesNotStartWithoutRuntimeImplementation() {
        val frontend = MtProtoLocalProxyFrontend(
            runtimeAdapter = UnsupportedMtProtoRuntimeAdapter(),
            portAvailabilityChecker = FixedPortAvailabilityChecker(true),
        )

        val result = frontend.start(
            LocalProxyFrontendConfig(
                host = "127.0.0.1",
                port = 1443,
                runtimeConfig = "",
                poolSize = 4,
                verbose = 1,
                mtProtoConfig = MtProtoProxyConfig.default { fixedSecret },
            ),
        )

        assertEquals(LocalProxyFrontendType.MTPROTO_EXPERIMENTAL, result.state.type)
        assertEquals(LocalProxyFrontendStatus.UNSUPPORTED, result.state.status)
        assertEquals(LocalProxyFrontendStatus.UNSUPPORTED, frontend.getState().status)
        assertEquals(MtProtoRuntimeErrorCode.UNSUPPORTED_RUNTIME.name, result.errorCode)
        assertTrue(result.message.contains("no native implementation"))
    }

    @Test
    fun mtprotoExperimentalFrontend_invalidConfigDoesNotStartRuntime() {
        val adapter = RecordingMtProtoRuntimeAdapter()
        val frontend = MtProtoLocalProxyFrontend(
            runtimeAdapter = adapter,
            portAvailabilityChecker = FixedPortAvailabilityChecker(true),
        )

        val result = frontend.start(
            mtProtoStartConfig(
                MtProtoProxyConfig.default { fixedSecret }.copy(port = 0),
            ),
        )

        assertEquals(LocalProxyFrontendStatus.FAILED, result.state.status)
        assertEquals(MtProtoRuntimeErrorCode.INVALID_CONFIG.name, result.errorCode)
        assertTrue(result.message.contains(MtProtoProxyConfigValidationError.INVALID_PORT.name))
        assertEquals(0, adapter.startCalls)
    }

    @Test
    fun mtprotoExperimentalFrontend_portBusyMapsToError() {
        val adapter = RecordingMtProtoRuntimeAdapter()
        val frontend = MtProtoLocalProxyFrontend(
            runtimeAdapter = adapter,
            portAvailabilityChecker = FixedPortAvailabilityChecker(false),
        )

        val result = frontend.start(
            mtProtoStartConfig(MtProtoProxyConfig.default { fixedSecret }),
        )

        assertEquals(LocalProxyFrontendStatus.FAILED, result.state.status)
        assertEquals(MtProtoRuntimeErrorCode.PORT_BUSY.name, result.errorCode)
        assertTrue(result.message.contains("port is busy"))
        assertEquals(0, adapter.startCalls)
    }

    private fun mtProtoStartConfig(config: MtProtoProxyConfig): LocalProxyFrontendConfig {
        return LocalProxyFrontendConfig(
            host = config.host,
            port = config.port,
            runtimeConfig = "",
            poolSize = 4,
            verbose = 1,
            mtProtoConfig = config,
        )
    }

    private class FixedPortAvailabilityChecker(
        private val available: Boolean,
    ) : LocalPortAvailabilityChecker {
        override fun isAvailable(host: String, port: Int): Boolean = available
    }

    private class RecordingMtProtoRuntimeAdapter : MtProtoRuntimeAdapter {
        var startCalls = 0

        override fun start(config: MtProtoRuntimeConfig): MtProtoRuntimeStartResult {
            startCalls += 1
            return MtProtoRuntimeStartResult(
                state = MtProtoRuntimeState.RUNNING,
            )
        }

        override fun stop(): MtProtoRuntimeStopResult {
            return MtProtoRuntimeStopResult(
                state = MtProtoRuntimeState.STOPPED,
            )
        }

        override fun getState(): MtProtoRuntimeState = MtProtoRuntimeState.STOPPED
    }

    private class RecordingSocks5NativeProxy : Socks5NativeProxy {
        var recordedPoolSize = 0
        var host = ""
        var port = 0
        var runtimeConfig = ""
        var verbose = 0

        override fun setPoolSize(size: Int) {
            recordedPoolSize = size
        }

        override fun startProxy(host: String, port: Int, runtimeConfig: String, verbose: Int): Int {
            this.host = host
            this.port = port
            this.runtimeConfig = runtimeConfig
            this.verbose = verbose
            return 0
        }

        override fun stopProxy(): Int = 0

        override fun getAdaptiveRouteStats(): String? = null
    }
}
