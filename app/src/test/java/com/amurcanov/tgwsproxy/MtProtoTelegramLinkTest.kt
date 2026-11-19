package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MtProtoTelegramLinkTest {
    private val fixedSecret = "0123456789abcdef0123456789abcdef"

    @Test
    fun httpsLink_usesOfficialProxyParametersAndDdSecretPrefix() {
        val config = MtProtoProxyConfig.default { fixedSecret }

        val link = MtProtoTelegramLinkBuilder.httpsLink(config)

        assertEquals(
            "https://t.me/proxy?server=127.0.0.1&port=1443&secret=dd$fixedSecret",
            link,
        )
    }

    @Test
    fun httpsLink_usesEeSecretWhenFakeTlsDomainConfigured() {
        val config = MtProtoProxyConfig.default { fixedSecret }
            .copy(fakeTlsDomain = "www.google.com")

        val link = MtProtoTelegramLinkBuilder.httpsLink(config)

        assertEquals(
            "https://t.me/proxy?server=127.0.0.1&port=1443&secret=ee" +
                fixedSecret + "7777772e676f6f676c652e636f6d",
            link,
        )
    }

    @Test
    fun homeCopy_mtProtoLinkMatchesConnectionSettingsForDefaultAndFakeTls() {
        val config = MtProtoProxyConfig.default { fixedSecret }
        for (candidate in listOf(config, config.copy(fakeTlsDomain = "www.google.com"))) {
            assertEquals(
                MtProtoTelegramLinkBuilder.httpsLink(candidate),
                ProxyTelegramLinkBuilder.httpsLink(
                    LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
                    candidate,
                    socks5Port = 0,
                ),
            )
        }
    }

    @Test
    fun homeCopy_socks5LinkContainsLocalHostAndPort() {
        assertEquals(
            "https://t.me/socks?server=127.0.0.1&port=1443",
            ProxyTelegramLinkBuilder.httpsLink(
                LocalProxyFrontendType.SOCKS5,
                MtProtoProxyConfig.default { fixedSecret },
                socks5Port = 1443,
            ),
        )
    }

    @Test
    fun homeCopy_socks5LinkRejectsInvalidPorts() {
        val config = MtProtoProxyConfig.default { fixedSecret }
        for (port in listOf(-1, 0, 65536)) {
            assertNull(
                ProxyTelegramLinkBuilder.httpsLink(
                    LocalProxyFrontendType.SOCKS5,
                    config,
                    socks5Port = port,
                ),
            )
        }
    }

    @Test
    fun homeCopy_mtProtoLinkRejectsInvalidConfig() {
        val config = MtProtoProxyConfig.default { fixedSecret }.copy(port = 0)
        assertNull(
            ProxyTelegramLinkBuilder.httpsLink(
                LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
                config,
                socks5Port = 1443,
            ),
        )
    }

    @Test
    fun tgUri_usesOfficialProxyParametersAndDdSecretPrefix() {
        val config = MtProtoProxyConfig.default { fixedSecret }

        val link = MtProtoTelegramLinkBuilder.tgUri(config)

        assertEquals(
            "tg://proxy?server=127.0.0.1&port=1443&secret=dd$fixedSecret",
            link,
        )
    }

    @Test
    fun link_returnsNullForInvalidConfig() {
        val config = MtProtoProxyConfig.default { fixedSecret }.copy(port = 0)

        assertNull(MtProtoTelegramLinkBuilder.httpsLink(config))
        assertNull(MtProtoTelegramLinkBuilder.tgUri(config))
    }

    @Test
    fun uiStatus_keepsSocks5StableByDefault() {
        val status = MtProtoUiStatusResolver.resolve(
            frontendType = LocalProxyFrontendType.SOCKS5,
            config = MtProtoProxyConfig.default { fixedSecret },
            serviceRunning = false,
            runtimeState = MtProtoRuntimeState.STOPPED,
        )

        assertEquals(MtProtoUiStatus.SOCKS5_WS_STABLE, status)
    }

    @Test
    fun uiStatus_reportsLocalOnlyLimitedForLocalMtProtoListener() {
        val status = MtProtoUiStatusResolver.resolve(
            frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
            config = MtProtoProxyConfig.default { fixedSecret }.copy(enabled = true),
            serviceRunning = true,
            runtimeState = MtProtoRuntimeState.LISTENING_LOCAL_ONLY,
        )

        assertEquals(MtProtoUiStatus.MTPROTO_LOCAL_ONLY_LIMITED, status)
    }

    @Test
    fun uiStatus_reportsDirectRouteReadyForRunningMtProtoBackend() {
        val status = MtProtoUiStatusResolver.resolve(
            frontendType = LocalProxyFrontendType.MTPROTO_EXPERIMENTAL,
            config = MtProtoProxyConfig.default { fixedSecret }.copy(enabled = true),
            serviceRunning = true,
            runtimeState = MtProtoRuntimeState.RUNNING,
        )

        assertEquals(MtProtoUiStatus.MTPROTO_ROUTE_READY, status)
    }
}
