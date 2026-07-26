package com.amurcanov.tgwsproxy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.UnknownHostException

class CfDomainListTest {
    @Test
    fun parser_normalizesPlainDomainList() {
        val parsed = CfDomainListParser.parse(
            """
            # comment
            EXAMPLE.com

            https://Example.com/apiws
            second.example/
            third.example
            // another comment
            """.trimIndent()
        )

        assertEquals(
            CfDomainListParseResult.Success(listOf("example.com", "second.example", "third.example")),
            parsed,
        )
    }

    @Test
    fun parser_decodesFlowsealEncodedUpstreamList() {
        val parsed = CfDomainListParser.parse(
            """
            virkgj.com
            vmmzovy.com
            mkuosckvso.com
            """.trimIndent()
        )

        assertEquals(
            CfDomainListParseResult.Success(
                listOf(
                    CfDomain.decodeFlowseal("virkgj.com"),
                    CfDomain.decodeFlowseal("vmmzovy.com"),
                    CfDomain.decodeFlowseal("mkuosckvso.com"),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun parser_rejectsInvalidEntriesAndEmptyResult() {
        val mixed = CfDomainListParser.parse(
            """
            valid.example
            domain with spaces.com
            *.wildcard.example
            127.0.0.1
            """.trimIndent()
        )
        assertTrue(mixed is CfDomainListParseResult.ValidationError)

        val enoughValid = CfDomainListParser.parse(
            """
            valid.example
            second.example
            third.example
            domain with spaces.com
            """.trimIndent()
        )
        assertEquals(
            CfDomainListParseResult.Success(listOf("valid.example", "second.example", "third.example")),
            enoughValid,
        )

        val invalidOnly = CfDomainListParser.parse(
            """
            domain with spaces.com
            localhost
            https://127.0.0.1/
            """.trimIndent()
        )
        assertTrue(invalidOnly is CfDomainListParseResult.ValidationError)

        val empty = CfDomainListParser.parse(
            """
            # comment
            // comment
            """.trimIndent()
        )
        assertEquals(CfDomainListParseResult.EmptyListError, empty)
    }

    @Test
    fun repository_persistsAutoUpdateMirrorAndStatus() {
        val persistence = FakePersistence()
        val repository = CfDomainListRepository(persistence)

        repository.setMirrorSettings(enabled = true, url = "https://mirror.example/list.txt")
        repository.setAutoUpdateEnabled(false)
        repository.recordFailure(
            attemptedAtMs = 1000L,
            stage = CfDomainUpdateStage.DNS,
            message = "offline",
            primaryStatus = CfDomainSourceStatus(
                sourceType = CfDomainUpdateSourceType.PRIMARY_GITHUB,
                lastAttemptAtMs = 1000L,
                lastError = "dns: offline",
                lastStage = CfDomainUpdateStage.DNS,
            ),
            mirrorStatus = CfDomainSourceStatus(
                sourceType = CfDomainUpdateSourceType.USER_MIRROR,
                enabled = true,
            ),
        )

        val reloaded = CfDomainListRepository(persistence).state()
        assertFalse(reloaded.autoUpdateEnabled)
        assertTrue(reloaded.mirrorEnabled)
        assertEquals("https://mirror.example/list.txt", reloaded.mirrorUrl)
        assertEquals("offline", reloaded.lastError)
        assertEquals(CfDomainUpdateStage.DNS, reloaded.lastErrorStage)
    }

    @Test
    fun updater_primary200SuccessReplacesCache() = runBlocking {
        val persistence = FakePersistence(
            CfDomainUpstreamState(domains = listOf("old.example")),
        )
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to { _ ->
                    okBody("new.example\nsecond.example\nthird.example", etag = "\"abc\"")
                },
            ),
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.Success)
        assertEquals(listOf("new.example", "second.example", "third.example"), repository.state().domains)
        assertEquals("\"abc\"", repository.state().etag)
        assertEquals(CfDomainUpdateSourceType.PRIMARY_GITHUB, repository.state().lastSuccessfulSource)
    }

    @Test
    fun updater_primary304KeepsCache() = runBlocking {
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                domains = listOf("cached.example"),
                etag = "\"keep\"",
            ),
        )
        val repository = CfDomainListRepository(persistence)
        var sentEtag: String? = null
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to { request ->
                    sentEtag = request.etag
                    CfDomainHttpResponse(
                        statusCode = HttpURLConnection.HTTP_NOT_MODIFIED,
                        body = null,
                        etag = "\"keep\"",
                        lastModified = null,
                    )
                },
            ),
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.NotModified)
        assertEquals(listOf("cached.example"), repository.state().domains)
        assertEquals("\"keep\"", sentEtag)
    }

    @Test
    fun updater_primaryFailsMirrorSucceeds() = runBlocking {
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                domains = listOf("old.example"),
                mirrorEnabled = true,
                mirrorUrl = MIRROR_URL,
            ),
        )
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to { _ ->
                    throw UnknownHostException("dns down")
                },
                MIRROR_URL to { _ -> okBody("mirror.example\nsecond.example\nthird.example") },
            ),
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.Success)
        assertEquals(listOf("mirror.example", "second.example", "third.example"), repository.state().domains)
        assertEquals(CfDomainUpdateSourceType.USER_MIRROR, repository.state().lastSuccessfulSource)
    }

    @Test
    fun updater_primary500RetriesThenMirrorSucceeds() = runBlocking {
        var primaryCalls = 0
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                domains = listOf("old.example"),
                mirrorEnabled = true,
                mirrorUrl = MIRROR_URL,
            ),
        )
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to {
                    primaryCalls += 1
                    CfDomainHttpResponse(500, null, null, null)
                },
                MIRROR_URL to { _ -> okBody("mirror.example\nsecond.example\nthird.example") },
            ),
            sleeper = {},
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.Success)
        assertEquals(2, primaryCalls)
        assertEquals(listOf("mirror.example", "second.example", "third.example"), repository.state().domains)
    }

    @Test
    fun updater_primary429FallsBackToMirror() = runBlocking {
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                domains = listOf("old.example"),
                mirrorEnabled = true,
                mirrorUrl = MIRROR_URL,
            ),
        )
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to { _ ->
                    CfDomainHttpResponse(429, null, null, null)
                },
                MIRROR_URL to { _ -> okBody("mirror.example\nsecond.example\nthird.example") },
            ),
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.Success)
        assertEquals(listOf("mirror.example", "second.example", "third.example"), repository.state().domains)
    }

    @Test
    fun updater_invalidMirrorSkippedWhenBothFailCacheKept() = runBlocking {
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                domains = listOf("old.example"),
                mirrorEnabled = true,
                mirrorUrl = "http://localhost/list.txt",
            ),
        )
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to { _ ->
                    CfDomainHttpResponse(404, null, null, null)
                },
            ),
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.Failure)
        assertEquals(listOf("old.example"), repository.state().domains)
    }

    @Test
    fun updater_invalidBodyParseErrorKeepsCache() = runBlocking {
        val persistence = FakePersistence(CfDomainUpstreamState(domains = listOf("old.example")))
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to { _ ->
                    okBody("not a domain\n###")
                },
            ),
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.Failure)
        assertEquals(listOf("old.example"), repository.state().domains)
        assertEquals(CfDomainUpdateStage.VALIDATION, (result as CfDomainListUpdateResult.Failure).stage)
    }

    @Test
    fun updater_emptyBodyKeepsCache() = runBlocking {
        val persistence = FakePersistence(CfDomainUpstreamState(domains = listOf("old.example")))
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to { _ -> okBody("# only comments") },
            ),
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.Failure)
        assertEquals(listOf("old.example"), repository.state().domains)
    }

    @Test
    fun updater_shortValidBodyFailsAndKeepsCache() = runBlocking {
        val persistence = FakePersistence(CfDomainUpstreamState(domains = listOf("old.example")))
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to { _ -> okBody("new.example\nsecond.example") },
            ),
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.Failure)
        assertEquals(CfDomainUpdateStage.VALIDATION, (result as CfDomainListUpdateResult.Failure).stage)
        assertEquals(listOf("old.example"), repository.state().domains)
    }

    @Test
    fun updater_testPrimaryDoesNotReplaceCache() = runBlocking {
        val persistence = FakePersistence(CfDomainUpstreamState(domains = listOf("old.example")))
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(
            repository = repository,
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to {
                    okBody("new.example\nsecond.example\nthird.example")
                },
            ),
        )

        val result = updater.testPrimarySource()
        assertTrue(result is CfDomainListUpdateResult.Success)
        val success = result as CfDomainListUpdateResult.Success
        assertTrue(success.dryRun)
        assertEquals(listOf("old.example"), repository.state().domains)
        assertEquals(3, success.domainCount)
    }

    @Test
    fun updater_testMirrorWithInvalidMirrorReturnsMirrorInvalid() = runBlocking {
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                mirrorEnabled = true,
                mirrorUrl = "http://localhost/list.txt",
            ),
        )
        val repository = CfDomainListRepository(persistence)
        val updater = buildUpdater(repository = repository, handlers = emptyMap())

        val result = updater.testMirrorSource()
        assertTrue(result is CfDomainListUpdateResult.MirrorInvalid)
    }

    @Test
    fun updater_autoUpdateThrottledAfterSuccess() = runBlocking {
        val now = 10_000L
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                domains = listOf("cached.example"),
                lastUpdatedAtMs = now,
                lastAttemptAtMs = now,
                lastSuccessfulUpdateAtMs = now,
            ),
        )
        val updater = buildUpdater(
            repository = CfDomainListRepository(persistence),
            handlers = emptyMap(),
            nowMs = { now + 1000L },
        )

        assertEquals(CfDomainListUpdateResult.SkippedThrottled, updater.maybeAutoUpdate())
    }

    @Test
    fun updater_autoUpdateAllowedAfterFailureBackoff() = runBlocking {
        val now = 10_000L
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                domains = listOf("cached.example"),
                lastUpdatedAtMs = now,
                lastAttemptAtMs = now,
                lastSuccessfulUpdateAtMs = 0L,
            ),
        )
        val updater = buildUpdater(
            repository = CfDomainListRepository(persistence),
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to {
                    okBody("fresh.example\nsecond.example\nthird.example")
                },
            ),
            nowMs = { now + (60L * 60L * 1000L) + 1L },
        )

        val result = updater.maybeAutoUpdate()
        assertTrue(result is CfDomainListUpdateResult.Success)
    }

    @Test
    fun httpClient_sendsIfNoneMatchWhenEtagExists() = runBlocking {
        var capturedEtag: String? = null
        val client = FakeCfDomainHttpClient(
            handlers = mapOf(
                CfDomainUpdateConfig.PRIMARY_URL to { request ->
                    capturedEtag = request.etag
                    CfDomainHttpResponse(HttpURLConnection.HTTP_NOT_MODIFIED, null, "\"etag\"", null)
                },
            ),
        )
        val downloader = CfDomainSourceDownloader(client)
        val result = downloader.download(
            sourceType = CfDomainUpdateSourceType.PRIMARY_GITHUB,
            url = CfDomainUpdateConfig.PRIMARY_URL,
            etag = "\"etag\"",
            lastModified = null,
        )
        assertTrue(result is CfDomainListDownloadResult.NotModified)
        assertEquals("\"etag\"", capturedEtag)
    }

    private fun buildUpdater(
        repository: CfDomainListRepository,
        handlers: Map<String, (CfDomainHttpRequest) -> CfDomainHttpResponse>,
        nowMs: () -> Long = { 1000L },
        sleeper: suspend (Long) -> Unit = {},
    ): CfDomainListUpdater {
        return CfDomainListUpdater(
            repository = repository,
            sourceDownloader = CfDomainSourceDownloader(FakeCfDomainHttpClient(handlers)),
            logger = NoOpCfDomainUpdateLogger,
            nowMs = nowMs,
            sleeper = sleeper,
        )
    }

    private fun okBody(body: String, etag: String? = null): CfDomainHttpResponse {
        return CfDomainHttpResponse(
            statusCode = 200,
            body = body,
            etag = etag,
            lastModified = "Mon, 01 Jan 2024 00:00:00 GMT",
        )
    }

    private class FakePersistence(
        private var state: CfDomainUpstreamState = CfDomainUpstreamState(),
    ) : CfDomainListPersistence {
        override fun load(): CfDomainUpstreamState = state

        override fun save(state: CfDomainUpstreamState) {
            this.state = state
        }
    }

    private class FakeCfDomainHttpClient(
        private val handlers: Map<String, (CfDomainHttpRequest) -> CfDomainHttpResponse>,
    ) : CfDomainHttpClient {
        override suspend fun get(request: CfDomainHttpRequest): CfDomainHttpResponse {
            val handler = handlers[request.url]
            if (handler != null) {
                return handler(request)
            }
            throw UnknownHostException("no handler for ${request.url}")
        }
    }

    private companion object {
        const val MIRROR_URL = "https://mirror.example/cfproxy-domains.txt"
    }
}
