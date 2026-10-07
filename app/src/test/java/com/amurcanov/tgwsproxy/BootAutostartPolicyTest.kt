package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class BootAutostartPolicyTest {
    @Test
    fun disabled_skipsStart() {
        assertEquals(
            BootAutostartDecision.DISABLED,
            BootAutostartPolicy.evaluate(
                enabled = false,
                savedConfig = validSocksConfig(),
                alreadyRunning = false,
            ),
        )
    }

    @Test
    fun enabledAndValid_starts() {
        assertEquals(
            BootAutostartDecision.START,
            BootAutostartPolicy.evaluate(
                enabled = true,
                savedConfig = validSocksConfig(),
                alreadyRunning = false,
            ),
        )
    }

    @Test
    fun enabledAndInvalidSocksConfig_skipsStart() {
        assertEquals(
            BootAutostartDecision.INVALID_SAVED_CONFIG,
            BootAutostartPolicy.evaluate(
                enabled = true,
                savedConfig = validSocksConfig().copy(runtimeConfig = ""),
                alreadyRunning = false,
            ),
        )
    }

    @Test
    fun mtProto_doesNotRequireRuntimeIps() {
        assertEquals(
            BootAutostartDecision.START,
            BootAutostartPolicy.evaluate(
                enabled = true,
                savedConfig = ProxySavedConfig(
                    port = DEFAULT_LOCAL_PROXY_PORT,
                    runtimeConfig = "",
                    poolSize = 4,
                    frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
                ),
                alreadyRunning = false,
            ),
        )
    }

    @Test
    fun repeatedDelivery_whenServiceAlreadyRunning_skipsSecondStart() {
        assertEquals(
            BootAutostartDecision.ALREADY_RUNNING,
            BootAutostartPolicy.evaluate(
                enabled = true,
                savedConfig = validSocksConfig(),
                alreadyRunning = true,
            ),
        )
    }

    private fun validSocksConfig(): ProxySavedConfig {
        return ProxySavedConfig(
            port = DEFAULT_LOCAL_PROXY_PORT,
            runtimeConfig = "runtime-config",
            poolSize = 4,
            frontendType = LocalProxyFrontendType.SOCKS5,
        )
    }
}
