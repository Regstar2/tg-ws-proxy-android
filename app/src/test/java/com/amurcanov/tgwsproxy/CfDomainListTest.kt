package com.amurcanov.tgwsproxy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfDomainListTest {
    @Test
    fun parser_normalizesPlainDomainList() {
        val parsed = CfDomainListParser.parse(
            """
            # comment
            EXAMPLE.com

            https://Example.com/apiws
            second.example/
            // another comment
            """.trimIndent()
        )

        assertEquals(
            CfDomainListParseResult.Success(listOf("example.com", "second.example")),
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
        assertEquals(CfDomainListParseResult.Success(listOf("valid.example")), mixed)

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
    fun repository_persistsAutoUpdateAndStatus() {
        val persistence = FakePersistence()
        val repository = CfDomainListRepository(persistence)

        assertTrue(repository.state().autoUpdateEnabled)
        repository.setAutoUpdateEnabled(false)
        repository.recordFailure(1000L, "dns: offline")

        val reloaded = CfDomainListRepository(persistence).state()
        assertFalse(reloaded.autoUpdateEnabled)
        assertEquals(1000L, reloaded.lastUpdatedAtMs)
        assertEquals("dns: offline", reloaded.lastError)
    }

    @Test
    fun updater_successReplacesCacheAndFailureKeepsOldCache() = runBlocking {
        var now = 1000L
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                domains = listOf("old.example"),
                lastSuccessfulUpdateAtMs = 10L,
            )
        )
        val repository = CfDomainListRepository(persistence)
        val successUpdater = CfDomainListUpdater(
            repository = repository,
            downloader = FakeDownloader(
                CfDomainListDownloadResult.Success(
                    domains = listOf("new.example"),
                    fetchedAtMs = now,
                    sourceUrl = DEFAULT_CF_DOMAIN_SOURCE_URL,
                    etag = null,
                    lastModified = null,
                )
            ),
            logger = NoOpCfDomainUpdateLogger,
            nowMs = { now },
        )

        val success = successUpdater.manualUpdate()
        assertTrue(success is CfDomainListUpdateResult.Success)
        assertEquals(listOf("new.example"), repository.state().domains)

        now = 2000L
        val failureUpdater = CfDomainListUpdater(
            repository = repository,
            downloader = FakeDownloader(CfDomainListDownloadResult.ValidationError("no valid Cloudflare domains")),
            logger = NoOpCfDomainUpdateLogger,
            nowMs = { now },
        )

        val failure = failureUpdater.manualUpdate()
        assertTrue(failure is CfDomainListUpdateResult.Failure)
        assertEquals(listOf("new.example"), repository.state().domains)
        assertEquals("parse: no valid Cloudflare domains", repository.state().lastError)
    }

    @Test
    fun updater_emptyDownloadedListKeepsOldCache() = runBlocking {
        val repository = CfDomainListRepository(
            FakePersistence(CfDomainUpstreamState(domains = listOf("old.example")))
        )
        val updater = CfDomainListUpdater(
            repository = repository,
            downloader = FakeDownloader(CfDomainListDownloadResult.EmptyListError),
            logger = NoOpCfDomainUpdateLogger,
            nowMs = { 3000L },
        )

        val result = updater.manualUpdate()
        assertTrue(result is CfDomainListUpdateResult.Failure)
        assertEquals(listOf("old.example"), repository.state().domains)
    }

    @Test
    fun updater_autoUpdateIsThrottledFor24Hours() = runBlocking {
        val now = 1000L
        val persistence = FakePersistence(
            CfDomainUpstreamState(
                domains = listOf("cached.example"),
                lastUpdatedAtMs = now,
                lastSuccessfulUpdateAtMs = now,
            )
        )
        val updater = CfDomainListUpdater(
            repository = CfDomainListRepository(persistence),
            downloader = FakeDownloader(CfDomainListDownloadResult.EmptyListError),
            logger = NoOpCfDomainUpdateLogger,
            nowMs = { now + 1000L },
        )

        assertEquals(CfDomainListUpdateResult.SkippedThrottled, updater.maybeAutoUpdate())
    }

    private class FakePersistence(
        private var state: CfDomainUpstreamState = CfDomainUpstreamState(),
    ) : CfDomainListPersistence {
        override fun load(): CfDomainUpstreamState = state

        override fun save(state: CfDomainUpstreamState) {
            this.state = state
        }
    }

    private class FakeDownloader(
        private val result: CfDomainListDownloadResult,
    ) : CfDomainListDownloader {
        override suspend fun download(previous: CfDomainUpstreamState): CfDomainListDownloadResult = result
    }
}
