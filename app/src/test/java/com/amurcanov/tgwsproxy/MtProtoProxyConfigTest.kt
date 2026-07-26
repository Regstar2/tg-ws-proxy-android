package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtProtoProxyConfigTest {
    private val fixedSecret = "0123456789abcdef0123456789abcdef"

    @Test
    fun defaultConfig_isEnabledLocalhostAndValid() {
        val config = MtProtoProxyConfig.default { fixedSecret }

        assertEquals(MtProtoProxyConfig.DEFAULT_HOST, config.host)
        assertEquals(MtProtoProxyConfig.DEFAULT_PORT, config.port)
        assertEquals(fixedSecret, config.secret)
        assertFalse(config.fakeTlsPassthrough)
        assertTrue(config.enabled)
        assertTrue(config.experimentalAcknowledged)
        assertTrue(MtProtoProxyConfigValidator.validate(config).isValid)
    }

    @Test
    fun portValidation_rejectsOutOfRangeValues() {
        val low = MtProtoProxyConfig.default { fixedSecret }.copy(port = 0)
        val high = MtProtoProxyConfig.default { fixedSecret }.copy(port = 65536)
        val valid = MtProtoProxyConfig.default { fixedSecret }.copy(port = 1443)

        assertTrue(
            MtProtoProxyConfigValidator.validate(low)
                .errors
                .contains(MtProtoProxyConfigValidationError.INVALID_PORT),
        )
        assertTrue(
            MtProtoProxyConfigValidator.validate(high)
                .errors
                .contains(MtProtoProxyConfigValidationError.INVALID_PORT),
        )
        assertTrue(MtProtoProxyConfigValidator.validate(valid).isValid)
    }

    @Test
    fun secretGeneration_returnsValidHexSecret() {
        val secret = MtProtoSecretGenerator.generate()

        assertTrue(secret.isNotBlank())
        assertEquals(32, secret.length)
        assertTrue(MtProtoProxyConfigValidator.isValidRawSecret(secret))
    }

    @Test
    fun repository_savesAndLoadsConfig() {
        val prefs = InMemorySharedPreferences()
        val repository = MtProtoProxyConfigRepository(prefs) { fixedSecret }
        val saved = MtProtoProxyConfig(
            host = " 127.0.0.1 ",
            port = 1443,
            secret = fixedSecret.uppercase(),
            enabled = true,
            experimentalAcknowledged = true,
        )

        repository.save(saved)
        val loaded = repository.load()

        assertEquals(MtProtoProxyConfig.DEFAULT_HOST, loaded.host)
        assertEquals(1443, loaded.port)
        assertEquals(fixedSecret, loaded.secret)
        assertEquals("", loaded.fakeTlsDomain)
        assertFalse(loaded.fakeTlsPassthrough)
        assertTrue(loaded.enabled)
        assertTrue(loaded.experimentalAcknowledged)
    }

    @Test
    fun repository_emptyPrefsUseEnabledDefaults() {
        val prefs = InMemorySharedPreferences()
        val repository = MtProtoProxyConfigRepository(prefs) { fixedSecret }

        val loaded = repository.load()

        assertTrue(loaded.enabled)
        assertTrue(loaded.experimentalAcknowledged)
        assertEquals(fixedSecret, loaded.secret)
    }

    @Test
    fun fakeTlsDomainValidation_acceptsPublicDomainsOnly() {
        val valid = MtProtoProxyConfig.default { fixedSecret }.copy(fakeTlsDomain = "HTTPS://WWW.Google.COM/path")
        val invalid = MtProtoProxyConfig.default { fixedSecret }.copy(fakeTlsDomain = "localhost")

        assertTrue(MtProtoProxyConfigValidator.validate(valid).isValid)
        assertEquals("www.google.com", valid.normalized().fakeTlsDomain)
        assertTrue(
            MtProtoProxyConfigValidator.validate(invalid)
                .errors
                .contains(MtProtoProxyConfigValidationError.INVALID_FAKE_TLS_DOMAIN),
        )
    }

    @Test
    fun repository_persistsFakeTlsDomain() {
        val prefs = InMemorySharedPreferences()
        val repository = MtProtoProxyConfigRepository(prefs) { fixedSecret }

        repository.save(
            MtProtoProxyConfig.default { fixedSecret }
                .copy(fakeTlsDomain = "WWW.Google.COM"),
        )
        val loaded = repository.load()

        assertEquals("www.google.com", loaded.fakeTlsDomain)
    }

    @Test
    fun repository_persistsFakeTlsPassthroughOnlyWithValidDomain() {
        val prefs = InMemorySharedPreferences()
        val repository = MtProtoProxyConfigRepository(prefs) { fixedSecret }

        repository.save(
            MtProtoProxyConfig.default { fixedSecret }
                .copy(fakeTlsDomain = "WWW.Google.COM", fakeTlsPassthrough = true),
        )
        val loaded = repository.load()

        assertEquals("www.google.com", loaded.fakeTlsDomain)
        assertTrue(loaded.fakeTlsPassthrough)

        repository.save(loaded.copy(fakeTlsDomain = "", fakeTlsPassthrough = true))

        assertFalse(repository.load().fakeTlsPassthrough)
    }

    @Test
    fun repository_corruptedConfigFallsBackToSafeDefaults() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString(MtProtoProxyConfigRepository.KEY_HOST, "")
            .putInt(MtProtoProxyConfigRepository.KEY_PORT, 70000)
            .putString(MtProtoProxyConfigRepository.KEY_SECRET, "not-a-secret")
            .putBoolean(MtProtoProxyConfigRepository.KEY_ENABLED, true)
            .putBoolean(MtProtoProxyConfigRepository.KEY_EXPERIMENTAL_ACKNOWLEDGED, true)
            .apply()
        val fallbackSecret = "fedcba9876543210fedcba9876543210"
        val repository = MtProtoProxyConfigRepository(prefs) { fallbackSecret }

        val loaded = repository.load()

        assertEquals(MtProtoProxyConfig.DEFAULT_HOST, loaded.host)
        assertEquals(MtProtoProxyConfig.DEFAULT_PORT, loaded.port)
        assertEquals(fallbackSecret, loaded.secret)
        assertTrue(loaded.enabled)
        assertTrue(loaded.experimentalAcknowledged)
        assertEquals(fallbackSecret, prefs.getString(MtProtoProxyConfigRepository.KEY_SECRET, null))
    }

    @Test
    fun diagnosticsReportLines_maskSecret() {
        val config = MtProtoProxyConfig.default { fixedSecret }

        val report = MtProtoProxyConfigDiagnostics.reportLines(config).joinToString("\n")

        assertFalse(report.contains(fixedSecret))
        assertTrue(report.contains("MTProto secret: ${MtProtoSecretMasking.MASKED}"))
    }

    @Test
    fun appLogSanitizer_masksMtProtoSecretKey() {
        val sanitized = AppLogSanitizer.sanitizeText("mtproto_secret=$fixedSecret secret_key=$fixedSecret")

        assertFalse(sanitized.contains(fixedSecret))
        assertTrue(sanitized.contains("mtproto_secret=***"))
        assertTrue(sanitized.contains("secret_key=***"))
    }
}
