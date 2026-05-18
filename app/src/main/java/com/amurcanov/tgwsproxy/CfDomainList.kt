package com.amurcanov.tgwsproxy

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException

const val DEFAULT_CF_DOMAIN_SOURCE_URL =
    "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

private const val AUTO_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L

data class CfDomainUpstreamState(
    val domains: List<String> = emptyList(),
    val lastUpdatedAtMs: Long = 0L,
    val lastSuccessfulUpdateAtMs: Long = 0L,
    val lastError: String? = null,
    val sourceUrl: String = DEFAULT_CF_DOMAIN_SOURCE_URL,
    val etag: String? = null,
    val lastModified: String? = null,
    val autoUpdateEnabled: Boolean = true,
)

sealed interface CfDomainListParseResult {
    data class Success(val domains: List<String>) : CfDomainListParseResult
    object EmptyListError : CfDomainListParseResult
    data class ValidationError(val message: String) : CfDomainListParseResult
}

object CfDomainListParser {
    fun parse(text: String): CfDomainListParseResult {
        val domains = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        var candidateCount = 0

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                return@forEach
            }

            candidateCount += 1
            val normalized = CfDomain.normalizeOrNull(line) ?: return@forEach
            if (seen.add(normalized)) {
                domains += normalized
            }
        }

        return when {
            domains.isNotEmpty() -> CfDomainListParseResult.Success(domains)
            candidateCount == 0 -> CfDomainListParseResult.EmptyListError
            else -> CfDomainListParseResult.ValidationError("no valid Cloudflare domains")
        }
    }
}

sealed interface CfDomainListDownloadResult {
    data class Success(
        val domains: List<String>,
        val fetchedAtMs: Long,
        val sourceUrl: String,
        val etag: String?,
        val lastModified: String?,
    ) : CfDomainListDownloadResult

    data class NotModified(
        val checkedAtMs: Long,
        val sourceUrl: String,
        val etag: String?,
        val lastModified: String?,
    ) : CfDomainListDownloadResult

    data class NetworkError(val stage: String, val message: String) : CfDomainListDownloadResult
    data class HttpError(val statusCode: Int, val message: String) : CfDomainListDownloadResult
    data class ParseError(val message: String) : CfDomainListDownloadResult
    object EmptyListError : CfDomainListDownloadResult
    data class ValidationError(val message: String) : CfDomainListDownloadResult
}

interface CfDomainListDownloader {
    suspend fun download(previous: CfDomainUpstreamState): CfDomainListDownloadResult
}

class HttpCfDomainListDownloader(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000,
) : CfDomainListDownloader {
    override suspend fun download(previous: CfDomainUpstreamState): CfDomainListDownloadResult =
        withContext(Dispatchers.IO) {
            try {
                val connection = (URL(previous.sourceUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    requestMethod = "GET"
                    useCaches = false
                    previous.etag?.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("If-None-Match", it)
                    }
                    previous.lastModified?.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("If-Modified-Since", it)
                    }
                }

                connection.use { http ->
                    val checkedAt = nowMs()
                    val etag = http.getHeaderField("ETag")
                    val lastModified = http.getHeaderField("Last-Modified")
                    when (http.responseCode) {
                        HttpURLConnection.HTTP_NOT_MODIFIED -> {
                            CfDomainListDownloadResult.NotModified(
                                checkedAtMs = checkedAt,
                                sourceUrl = previous.sourceUrl,
                                etag = etag ?: previous.etag,
                                lastModified = lastModified ?: previous.lastModified,
                            )
                        }

                        in 200..299 -> {
                            val body = BufferedReader(
                                InputStreamReader(http.inputStream, StandardCharsets.UTF_8)
                            ).use { it.readText() }
                            when (val parsed = CfDomainListParser.parse(body)) {
                                is CfDomainListParseResult.Success -> CfDomainListDownloadResult.Success(
                                    domains = parsed.domains,
                                    fetchedAtMs = checkedAt,
                                    sourceUrl = previous.sourceUrl,
                                    etag = etag,
                                    lastModified = lastModified,
                                )

                                CfDomainListParseResult.EmptyListError -> CfDomainListDownloadResult.EmptyListError
                                is CfDomainListParseResult.ValidationError ->
                                    CfDomainListDownloadResult.ValidationError(parsed.message)
                            }
                        }

                        else -> CfDomainListDownloadResult.HttpError(
                            statusCode = http.responseCode,
                            message = http.responseMessage.orEmpty().ifBlank { "HTTP ${http.responseCode}" },
                        )
                    }
                }
            } catch (e: UnknownHostException) {
                CfDomainListDownloadResult.NetworkError("dns", describeNetworkError(e))
            } catch (e: SSLException) {
                CfDomainListDownloadResult.NetworkError("tls", describeNetworkError(e))
            } catch (e: SocketTimeoutException) {
                CfDomainListDownloadResult.NetworkError("tcp", describeNetworkError(e))
            } catch (e: Exception) {
                CfDomainListDownloadResult.NetworkError("tcp", describeNetworkError(e))
            }
        }

    private fun describeNetworkError(error: Exception): String {
        return error.message?.replace("\n", " ")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
    }
}

interface CfDomainListPersistence {
    fun load(): CfDomainUpstreamState
    fun save(state: CfDomainUpstreamState)
}

class SharedPreferencesCfDomainListPersistence(
    private val prefs: SharedPreferences,
) : CfDomainListPersistence {
    override fun load(): CfDomainUpstreamState {
        return CfDomainUpstreamState(
            domains = decodeDomains(prefs.getString(KEY_DOMAINS, "").orEmpty()),
            lastUpdatedAtMs = prefs.getLong(KEY_LAST_UPDATED_AT, 0L),
            lastSuccessfulUpdateAtMs = prefs.getLong(KEY_LAST_SUCCESSFUL_UPDATE_AT, 0L),
            lastError = prefs.getString(KEY_LAST_ERROR, null),
            sourceUrl = prefs.getString(KEY_SOURCE_URL, DEFAULT_CF_DOMAIN_SOURCE_URL)
                ?: DEFAULT_CF_DOMAIN_SOURCE_URL,
            etag = prefs.getString(KEY_ETAG, null),
            lastModified = prefs.getString(KEY_LAST_MODIFIED, null),
            autoUpdateEnabled = prefs.getBoolean(KEY_AUTO_UPDATE, true),
        )
    }

    override fun save(state: CfDomainUpstreamState) {
        prefs.edit()
            .putString(KEY_DOMAINS, state.domains.joinToString("\n"))
            .putLong(KEY_LAST_UPDATED_AT, state.lastUpdatedAtMs)
            .putLong(KEY_LAST_SUCCESSFUL_UPDATE_AT, state.lastSuccessfulUpdateAtMs)
            .putString(KEY_LAST_ERROR, state.lastError)
            .putString(KEY_SOURCE_URL, state.sourceUrl)
            .putString(KEY_ETAG, state.etag)
            .putString(KEY_LAST_MODIFIED, state.lastModified)
            .putBoolean(KEY_AUTO_UPDATE, state.autoUpdateEnabled)
            .apply()
    }

    private fun decodeDomains(raw: String): List<String> {
        return raw.lineSequence()
            .mapNotNull(CfDomain::normalizeOrNull)
            .distinct()
            .toList()
    }

    private companion object {
        const val KEY_DOMAINS = "cf_upstream_domains"
        const val KEY_LAST_UPDATED_AT = "cf_upstream_last_updated_at"
        const val KEY_LAST_SUCCESSFUL_UPDATE_AT = "cf_upstream_last_successful_update_at"
        const val KEY_LAST_ERROR = "cf_upstream_last_error"
        const val KEY_SOURCE_URL = "cf_upstream_source_url"
        const val KEY_ETAG = "cf_upstream_etag"
        const val KEY_LAST_MODIFIED = "cf_upstream_last_modified"
        const val KEY_AUTO_UPDATE = "cf_auto_update_domains"
    }
}

class SharedPreferencesManualCfDomainRepository(
    private val prefs: SharedPreferences,
) {
    fun load(): List<String> {
        if (prefs.contains(KEY_MANUAL_DOMAINS)) {
            return decodeDomains(prefs.getString(KEY_MANUAL_DOMAINS, "").orEmpty())
        }

        val migrated = CfManualDomainList.normalize(
            listOf(prefs.getString(KEY_LEGACY_MANUAL_DOMAIN, DEFAULT_MANUAL_DOMAIN) ?: DEFAULT_MANUAL_DOMAIN)
        )
        save(migrated)
        return migrated
    }

    fun save(domains: List<String>): List<String> {
        val normalized = CfManualDomainList.normalize(domains)
        prefs.edit()
            .putString(KEY_MANUAL_DOMAINS, normalized.joinToString("\n"))
            .putString(KEY_LEGACY_MANUAL_DOMAIN, normalized.firstOrNull().orEmpty())
            .apply()
        return normalized
    }

    private fun decodeDomains(raw: String): List<String> {
        return CfManualDomainList.parse(raw).domains
    }

    private companion object {
        const val DEFAULT_MANUAL_DOMAIN = "pclead.co.uk"
        const val KEY_MANUAL_DOMAINS = "cf_manual_domains"
        const val KEY_LEGACY_MANUAL_DOMAIN = "cfproxy_domain"
    }
}

class CfDomainListRepository(
    private val persistence: CfDomainListPersistence,
) {
    fun state(): CfDomainUpstreamState = persistence.load()

    fun setAutoUpdateEnabled(enabled: Boolean): CfDomainUpstreamState {
        return save(state().copy(autoUpdateEnabled = enabled))
    }

    fun shouldAutoUpdate(nowMs: Long): Boolean {
        val current = state()
        if (!current.autoUpdateEnabled) {
            return false
        }
        val lastAttemptOrSuccess = maxOf(current.lastUpdatedAtMs, current.lastSuccessfulUpdateAtMs)
        return lastAttemptOrSuccess == 0L || nowMs - lastAttemptOrSuccess >= AUTO_UPDATE_INTERVAL_MS
    }

    fun replaceCache(
        domains: List<String>,
        fetchedAtMs: Long,
        sourceUrl: String,
        etag: String?,
        lastModified: String?,
    ): CfDomainUpstreamState {
        return save(
            state().copy(
                domains = domains,
                lastUpdatedAtMs = fetchedAtMs,
                lastSuccessfulUpdateAtMs = fetchedAtMs,
                lastError = null,
                sourceUrl = sourceUrl,
                etag = etag,
                lastModified = lastModified,
            )
        )
    }

    fun markNotModified(
        checkedAtMs: Long,
        sourceUrl: String,
        etag: String?,
        lastModified: String?,
    ): CfDomainUpstreamState {
        return save(
            state().copy(
                lastUpdatedAtMs = checkedAtMs,
                lastSuccessfulUpdateAtMs = checkedAtMs,
                lastError = null,
                sourceUrl = sourceUrl,
                etag = etag,
                lastModified = lastModified,
            )
        )
    }

    fun recordFailure(attemptedAtMs: Long, message: String): CfDomainUpstreamState {
        return save(
            state().copy(
                lastUpdatedAtMs = attemptedAtMs,
                lastError = message,
            )
        )
    }

    private fun save(state: CfDomainUpstreamState): CfDomainUpstreamState {
        persistence.save(state)
        return state
    }
}

interface CfDomainUpdateLogger {
    fun info(message: String)
    fun warn(message: String)
}

object AndroidCfDomainUpdateLogger : CfDomainUpdateLogger {
    private const val TAG = "TgWsProxy"

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }
}

object NoOpCfDomainUpdateLogger : CfDomainUpdateLogger {
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
}

sealed interface CfDomainListUpdateResult {
    data class Success(val state: CfDomainUpstreamState, val elapsedMs: Long) : CfDomainListUpdateResult
    data class NotModified(val state: CfDomainUpstreamState, val elapsedMs: Long) : CfDomainListUpdateResult
    data class Failure(val state: CfDomainUpstreamState, val stage: String, val message: String) :
        CfDomainListUpdateResult

    object AlreadyRunning : CfDomainListUpdateResult
    object SkippedDisabled : CfDomainListUpdateResult
    object SkippedThrottled : CfDomainListUpdateResult
}

class CfDomainListUpdater(
    private val repository: CfDomainListRepository,
    private val downloader: CfDomainListDownloader,
    private val logger: CfDomainUpdateLogger,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun manualUpdate(): CfDomainListUpdateResult = update()

    suspend fun maybeAutoUpdate(): CfDomainListUpdateResult {
        val current = repository.state()
        if (!current.autoUpdateEnabled) {
            return CfDomainListUpdateResult.SkippedDisabled
        }
        if (!repository.shouldAutoUpdate(nowMs())) {
            return CfDomainListUpdateResult.SkippedThrottled
        }
        return update()
    }

    private suspend fun update(): CfDomainListUpdateResult {
        if (!mutex.tryLock()) {
            return CfDomainListUpdateResult.AlreadyRunning
        }

        val startedAt = nowMs()
        val current = repository.state()
        logger.info("CF domain upstream update started source=${current.sourceUrl}")

        return try {
            when (val downloaded = downloader.download(current)) {
                is CfDomainListDownloadResult.Success -> {
                    val state = repository.replaceCache(
                        domains = downloaded.domains,
                        fetchedAtMs = downloaded.fetchedAtMs,
                        sourceUrl = downloaded.sourceUrl,
                        etag = downloaded.etag,
                        lastModified = downloaded.lastModified,
                    )
                    val elapsed = nowMs() - startedAt
                    logger.info("CF domain upstream update success count=${state.domains.size} elapsed_ms=$elapsed")
                    logger.info("CF domain upstream cache replaced count=${state.domains.size}")
                    CfDomainListUpdateResult.Success(state, elapsed)
                }

                is CfDomainListDownloadResult.NotModified -> {
                    val state = repository.markNotModified(
                        checkedAtMs = downloaded.checkedAtMs,
                        sourceUrl = downloaded.sourceUrl,
                        etag = downloaded.etag,
                        lastModified = downloaded.lastModified,
                    )
                    val elapsed = nowMs() - startedAt
                    logger.info("CF domain upstream update success count=${state.domains.size} elapsed_ms=$elapsed")
                    CfDomainListUpdateResult.NotModified(state, elapsed)
                }

                is CfDomainListDownloadResult.NetworkError -> fail(
                    attemptedAtMs = startedAt,
                    stage = downloaded.stage,
                    message = downloaded.message,
                )

                is CfDomainListDownloadResult.HttpError -> fail(
                    attemptedAtMs = startedAt,
                    stage = "http",
                    message = "${downloaded.statusCode} ${downloaded.message}".trim(),
                )

                is CfDomainListDownloadResult.ParseError -> fail(
                    attemptedAtMs = startedAt,
                    stage = "parse",
                    message = downloaded.message,
                )

                CfDomainListDownloadResult.EmptyListError -> fail(
                    attemptedAtMs = startedAt,
                    stage = "parse",
                    message = "empty list",
                )

                is CfDomainListDownloadResult.ValidationError -> fail(
                    attemptedAtMs = startedAt,
                    stage = "parse",
                    message = downloaded.message,
                )
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun fail(attemptedAtMs: Long, stage: String, message: String): CfDomainListUpdateResult.Failure {
        val state = repository.recordFailure(attemptedAtMs, "$stage: $message")
        logger.warn("CF domain upstream update failed stage=$stage reason=$message")
        logger.info("CF domain upstream cache kept reason=download_failed")
        return CfDomainListUpdateResult.Failure(state = state, stage = stage, message = message)
    }
}

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}
