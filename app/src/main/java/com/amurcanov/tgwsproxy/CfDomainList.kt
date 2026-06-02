package com.amurcanov.tgwsproxy

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException
import kotlin.random.Random

private const val AUTO_UPDATE_SUCCESS_INTERVAL_MS = 24L * 60L * 60L * 1000L
private const val AUTO_UPDATE_FAILURE_INTERVAL_MS = 60L * 60L * 1000L
private const val MAX_ATTEMPTS_MANUAL = 2
private const val MAX_ATTEMPTS_AUTO = 1
private const val RETRY_DELAY_MIN_MS = 500L
private const val RETRY_DELAY_MAX_MS = 1500L

@Deprecated("Use CfDomainUpdateConfig.PRIMARY_URL", ReplaceWith("CfDomainUpdateConfig.PRIMARY_URL"))
const val DEFAULT_CF_DOMAIN_SOURCE_URL: String = CfDomainUpdateConfig.PRIMARY_URL

data class CfDomainUpstreamState(
    val domains: List<String> = emptyList(),
    val lastUpdatedAtMs: Long = 0L,
    val lastAttemptAtMs: Long = 0L,
    val lastSuccessfulUpdateAtMs: Long = 0L,
    val lastError: String? = null,
    val lastErrorStage: CfDomainUpdateStage? = null,
    val lastSuccessfulSource: CfDomainUpdateSourceType? = null,
    val sourceUrl: String = CfDomainUpdateConfig.PRIMARY_URL,
    val etag: String? = null,
    val lastModified: String? = null,
    val autoUpdateEnabled: Boolean = true,
    val mirrorEnabled: Boolean = false,
    val mirrorUrl: String = "",
    val primaryStatus: CfDomainSourceStatus = CfDomainSourceStatus(
        sourceType = CfDomainUpdateSourceType.PRIMARY_GITHUB,
        url = CfDomainUpdateConfig.PRIMARY_URL,
        enabled = true,
    ),
    val mirrorStatus: CfDomainSourceStatus = CfDomainSourceStatus(
        sourceType = CfDomainUpdateSourceType.USER_MIRROR,
        enabled = false,
    ),
) {
    val sourceUrlCompat: String
        get() = sourceUrl
}

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
        val sourceType: CfDomainUpdateSourceType,
        val etag: String?,
        val lastModified: String?,
        val latencyMs: Long,
    ) : CfDomainListDownloadResult

    data class NotModified(
        val checkedAtMs: Long,
        val sourceUrl: String,
        val sourceType: CfDomainUpdateSourceType,
        val etag: String?,
        val lastModified: String?,
        val latencyMs: Long,
    ) : CfDomainListDownloadResult

    data class Failed(
        val sourceType: CfDomainUpdateSourceType,
        val sourceUrl: String,
        val stage: CfDomainUpdateStage,
        val message: String,
        val httpStatus: Int? = null,
        val retryable: Boolean = false,
        val latencyMs: Long = 0L,
    ) : CfDomainListDownloadResult
}

data class CfDomainHttpRequest(
    val url: String,
    val etag: String?,
    val lastModified: String?,
)

data class CfDomainHttpResponse(
    val statusCode: Int,
    val body: String?,
    val etag: String?,
    val lastModified: String?,
)

interface CfDomainHttpClient {
    suspend fun get(request: CfDomainHttpRequest): CfDomainHttpResponse
}

class HttpURLConnectionCfDomainHttpClient(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000,
) : CfDomainHttpClient {
    override suspend fun get(request: CfDomainHttpRequest): CfDomainHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                useCaches = false
                request.etag?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-None-Match", it) }
                request.lastModified?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("If-Modified-Since", it)
                }
            }
            connection.use { http ->
                val statusCode = http.responseCode
                val etag = http.getHeaderField("ETag")
                val lastModified = http.getHeaderField("Last-Modified")
                val body = when {
                    statusCode == HttpURLConnection.HTTP_NOT_MODIFIED -> null
                    statusCode in 200..299 -> BufferedReader(
                        InputStreamReader(http.inputStream, StandardCharsets.UTF_8),
                    ).use { it.readText() }

                    else -> null
                }
                CfDomainHttpResponse(
                    statusCode = statusCode,
                    body = body,
                    etag = etag,
                    lastModified = lastModified,
                )
            }
        }
}

class CfDomainSourceDownloader(
    private val httpClient: CfDomainHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun download(
        sourceType: CfDomainUpdateSourceType,
        url: String,
        etag: String?,
        lastModified: String?,
    ): CfDomainListDownloadResult {
        val startedAt = nowMs()
        val request = CfDomainHttpRequest(url = url, etag = etag, lastModified = lastModified)
        return try {
            val response = httpClient.get(request)
            val latencyMs = nowMs() - startedAt
            mapResponse(sourceType, url, response, latencyMs, etag, lastModified)
        } catch (e: UnknownHostException) {
            failed(sourceType, url, CfDomainUpdateStage.DNS, describeNetworkError(e), nowMs() - startedAt)
        } catch (e: SSLException) {
            failed(sourceType, url, CfDomainUpdateStage.TLS, describeNetworkError(e), nowMs() - startedAt)
        } catch (e: SocketTimeoutException) {
            failed(
                sourceType,
                url,
                CfDomainUpdateStage.TIMEOUT,
                describeNetworkError(e),
                nowMs() - startedAt,
                retryable = true,
            )
        } catch (e: IOException) {
            failed(sourceType, url, CfDomainUpdateStage.READ, describeNetworkError(e), nowMs() - startedAt)
        } catch (e: Exception) {
            failed(sourceType, url, CfDomainUpdateStage.TCP, describeNetworkError(e), nowMs() - startedAt)
        }
    }

    private fun mapResponse(
        sourceType: CfDomainUpdateSourceType,
        url: String,
        response: CfDomainHttpResponse,
        latencyMs: Long,
        previousEtag: String?,
        previousLastModified: String?,
    ): CfDomainListDownloadResult {
        val etag = response.etag ?: previousEtag
        val lastModified = response.lastModified ?: previousLastModified
        return when (response.statusCode) {
            HttpURLConnection.HTTP_NOT_MODIFIED -> CfDomainListDownloadResult.NotModified(
                checkedAtMs = nowMs(),
                sourceUrl = url,
                sourceType = sourceType,
                etag = etag,
                lastModified = lastModified,
                latencyMs = latencyMs,
            )

            in 200..299 -> {
                val body = response.body.orEmpty()
                when (val parsed = CfDomainListParser.parse(body)) {
                    is CfDomainListParseResult.Success -> CfDomainListDownloadResult.Success(
                        domains = parsed.domains,
                        fetchedAtMs = nowMs(),
                        sourceUrl = url,
                        sourceType = sourceType,
                        etag = response.etag,
                        lastModified = response.lastModified,
                        latencyMs = latencyMs,
                    )

                    CfDomainListParseResult.EmptyListError -> failed(
                        sourceType,
                        url,
                        CfDomainUpdateStage.PARSE,
                        "empty_list",
                        latencyMs,
                    )

                    is CfDomainListParseResult.ValidationError -> failed(
                        sourceType,
                        url,
                        CfDomainUpdateStage.VALIDATION,
                        parsed.message,
                        latencyMs,
                    )
                }
            }

            429 -> failed(
                sourceType,
                url,
                CfDomainUpdateStage.HTTP,
                "rate_limited",
                latencyMs,
                httpStatus = response.statusCode,
            )

            in 500..599 -> failed(
                sourceType,
                url,
                CfDomainUpdateStage.HTTP,
                "server_error",
                latencyMs,
                httpStatus = response.statusCode,
                retryable = true,
            )

            else -> failed(
                sourceType,
                url,
                CfDomainUpdateStage.HTTP,
                "status_${response.statusCode}",
                latencyMs,
                httpStatus = response.statusCode,
            )
        }
    }

    private fun failed(
        sourceType: CfDomainUpdateSourceType,
        url: String,
        stage: CfDomainUpdateStage,
        message: String,
        latencyMs: Long,
        httpStatus: Int? = null,
        retryable: Boolean = false,
    ): CfDomainListDownloadResult.Failed = CfDomainListDownloadResult.Failed(
        sourceType = sourceType,
        sourceUrl = url,
        stage = stage,
        message = message,
        httpStatus = httpStatus,
        retryable = retryable,
        latencyMs = latencyMs,
    )

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
        val primaryUrl = prefs.getString(KEY_SOURCE_URL, CfDomainUpdateConfig.PRIMARY_URL)
            ?: CfDomainUpdateConfig.PRIMARY_URL
        val mirrorUrl = prefs.getString(KEY_MIRROR_URL, "").orEmpty()
        val mirrorEnabled = prefs.getBoolean(KEY_MIRROR_ENABLED, false)
        return CfDomainUpstreamState(
            domains = decodeDomains(prefs.getString(KEY_DOMAINS, "").orEmpty()),
            lastUpdatedAtMs = prefs.getLong(KEY_LAST_UPDATED_AT, 0L),
            lastAttemptAtMs = prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L),
            lastSuccessfulUpdateAtMs = prefs.getLong(KEY_LAST_SUCCESSFUL_UPDATE_AT, 0L),
            lastError = prefs.getString(KEY_LAST_ERROR, null),
            lastErrorStage = prefs.getString(KEY_LAST_ERROR_STAGE, null)?.let(::parseStage),
            lastSuccessfulSource = prefs.getString(KEY_LAST_SUCCESSFUL_SOURCE, null)?.let(::parseSourceType),
            sourceUrl = primaryUrl,
            etag = prefs.getString(KEY_ETAG, null),
            lastModified = prefs.getString(KEY_LAST_MODIFIED, null),
            autoUpdateEnabled = prefs.getBoolean(KEY_AUTO_UPDATE, true),
            mirrorEnabled = mirrorEnabled,
            mirrorUrl = mirrorUrl,
            primaryStatus = loadSourceStatus(
                type = CfDomainUpdateSourceType.PRIMARY_GITHUB,
                url = primaryUrl,
                enabled = true,
                prefix = "primary",
            ),
            mirrorStatus = loadSourceStatus(
                type = CfDomainUpdateSourceType.USER_MIRROR,
                url = mirrorUrl,
                enabled = mirrorEnabled,
                prefix = "mirror",
            ),
        )
    }

    override fun save(state: CfDomainUpstreamState) {
        prefs.edit()
            .putString(KEY_DOMAINS, state.domains.joinToString("\n"))
            .putLong(KEY_LAST_UPDATED_AT, state.lastUpdatedAtMs)
            .putLong(KEY_LAST_ATTEMPT_AT, state.lastAttemptAtMs)
            .putLong(KEY_LAST_SUCCESSFUL_UPDATE_AT, state.lastSuccessfulUpdateAtMs)
            .putString(KEY_LAST_ERROR, state.lastError)
            .putString(KEY_LAST_ERROR_STAGE, state.lastErrorStage?.name)
            .putString(
                KEY_LAST_SUCCESSFUL_SOURCE,
                state.lastSuccessfulSource?.name,
            )
            .putString(KEY_SOURCE_URL, state.sourceUrl)
            .putString(KEY_ETAG, state.etag)
            .putString(KEY_LAST_MODIFIED, state.lastModified)
            .putBoolean(KEY_AUTO_UPDATE, state.autoUpdateEnabled)
            .putBoolean(KEY_MIRROR_ENABLED, state.mirrorEnabled)
            .putString(KEY_MIRROR_URL, state.mirrorUrl)
            .apply()
        saveSourceStatus(state.primaryStatus, "primary")
        saveSourceStatus(state.mirrorStatus, "mirror")
    }

    private fun loadSourceStatus(
        type: CfDomainUpdateSourceType,
        url: String,
        enabled: Boolean,
        prefix: String,
    ): CfDomainSourceStatus {
        return CfDomainSourceStatus(
            sourceType = type,
            url = url,
            enabled = enabled,
            lastAttemptAtMs = prefs.getLong("${prefix}_last_attempt_at", 0L),
            lastSuccessAtMs = prefs.getLong("${prefix}_last_success_at", 0L),
            lastError = prefs.getString("${prefix}_last_error", null),
            lastHttpStatus = prefs.getInt("${prefix}_last_http_status", -1).takeIf { it >= 0 },
            lastLatencyMs = prefs.getLong("${prefix}_last_latency_ms", -1L).takeIf { it >= 0 },
            lastStage = prefs.getString("${prefix}_last_stage", null)?.let(::parseStage),
            etag = prefs.getString("${prefix}_etag", null),
            lastModified = prefs.getString("${prefix}_last_modified", null),
        )
    }

    private fun saveSourceStatus(status: CfDomainSourceStatus, prefix: String) {
        prefs.edit()
            .putLong("${prefix}_last_attempt_at", status.lastAttemptAtMs)
            .putLong("${prefix}_last_success_at", status.lastSuccessAtMs)
            .putString("${prefix}_last_error", status.lastError)
            .putInt("${prefix}_last_http_status", status.lastHttpStatus ?: -1)
            .putLong("${prefix}_last_latency_ms", status.lastLatencyMs ?: -1L)
            .putString("${prefix}_last_stage", status.lastStage?.name)
            .putString("${prefix}_etag", status.etag)
            .putString("${prefix}_last_modified", status.lastModified)
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
        const val KEY_LAST_ATTEMPT_AT = "cf_upstream_last_attempt_at"
        const val KEY_LAST_SUCCESSFUL_UPDATE_AT = "cf_upstream_last_successful_update_at"
        const val KEY_LAST_ERROR = "cf_upstream_last_error"
        const val KEY_LAST_ERROR_STAGE = "cf_upstream_last_error_stage"
        const val KEY_LAST_SUCCESSFUL_SOURCE = "cf_upstream_last_successful_source"
        const val KEY_SOURCE_URL = "cf_upstream_source_url"
        const val KEY_ETAG = "cf_upstream_etag"
        const val KEY_LAST_MODIFIED = "cf_upstream_last_modified"
        const val KEY_AUTO_UPDATE = "cf_auto_update_domains"
        const val KEY_MIRROR_ENABLED = "cf_domain_mirror_enabled"
        const val KEY_MIRROR_URL = "cf_domain_mirror_url"
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
        const val DEFAULT_MANUAL_DOMAIN = ""
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

    fun setMirrorSettings(enabled: Boolean, url: String): CfDomainUpstreamState {
        val current = state()
        return save(
            current.copy(
                mirrorEnabled = enabled,
                mirrorUrl = url.trim(),
                mirrorStatus = current.mirrorStatus.copy(
                    enabled = enabled,
                    url = url.trim(),
                ),
            ),
        )
    }

    fun shouldAutoUpdate(nowMs: Long): Boolean {
        val current = state()
        if (!current.autoUpdateEnabled) {
            return false
        }
        val lastSuccess = current.lastSuccessfulUpdateAtMs
        val lastAttempt = maxOf(current.lastAttemptAtMs, current.lastUpdatedAtMs)
        if (lastSuccess == 0L && lastAttempt == 0L) {
            return true
        }
        if (lastSuccess >= lastAttempt && lastSuccess > 0L) {
            return nowMs - lastSuccess >= AUTO_UPDATE_SUCCESS_INTERVAL_MS
        }
        if (lastAttempt > 0L) {
            return nowMs - lastAttempt >= AUTO_UPDATE_FAILURE_INTERVAL_MS
        }
        return true
    }

    fun replaceCache(
        domains: List<String>,
        fetchedAtMs: Long,
        sourceUrl: String,
        sourceType: CfDomainUpdateSourceType,
        etag: String?,
        lastModified: String?,
        primaryStatus: CfDomainSourceStatus,
        mirrorStatus: CfDomainSourceStatus,
    ): CfDomainUpstreamState {
        return save(
            state().copy(
                domains = domains,
                lastUpdatedAtMs = fetchedAtMs,
                lastAttemptAtMs = fetchedAtMs,
                lastSuccessfulUpdateAtMs = fetchedAtMs,
                lastError = null,
                lastErrorStage = null,
                lastSuccessfulSource = sourceType,
                sourceUrl = sourceUrl,
                etag = etag,
                lastModified = lastModified,
                primaryStatus = primaryStatus,
                mirrorStatus = mirrorStatus,
            ),
        )
    }

    fun markNotModified(
        checkedAtMs: Long,
        sourceUrl: String,
        sourceType: CfDomainUpdateSourceType,
        etag: String?,
        lastModified: String?,
        primaryStatus: CfDomainSourceStatus,
        mirrorStatus: CfDomainSourceStatus,
    ): CfDomainUpstreamState {
        return save(
            state().copy(
                lastUpdatedAtMs = checkedAtMs,
                lastAttemptAtMs = checkedAtMs,
                lastSuccessfulUpdateAtMs = checkedAtMs,
                lastError = null,
                lastErrorStage = null,
                lastSuccessfulSource = sourceType,
                sourceUrl = sourceUrl,
                etag = etag,
                lastModified = lastModified,
                primaryStatus = primaryStatus,
                mirrorStatus = mirrorStatus,
            ),
        )
    }

    fun recordFailure(
        attemptedAtMs: Long,
        stage: CfDomainUpdateStage,
        message: String,
        primaryStatus: CfDomainSourceStatus,
        mirrorStatus: CfDomainSourceStatus,
    ): CfDomainUpstreamState {
        return save(
            state().copy(
                lastUpdatedAtMs = attemptedAtMs,
                lastAttemptAtMs = attemptedAtMs,
                lastError = message,
                lastErrorStage = stage,
                primaryStatus = primaryStatus,
                mirrorStatus = mirrorStatus,
            ),
        )
    }

    fun updateSourceStatuses(
        primaryStatus: CfDomainSourceStatus,
        mirrorStatus: CfDomainSourceStatus,
    ): CfDomainUpstreamState {
        return save(
            state().copy(
                primaryStatus = primaryStatus,
                mirrorStatus = mirrorStatus,
            ),
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

enum class CfDomainUpdateMode {
    MANUAL,
    AUTO,
    TEST_PRIMARY,
    TEST_MIRROR,
}

sealed interface CfDomainListUpdateResult {
    data class Success(
        val state: CfDomainUpstreamState,
        val elapsedMs: Long,
        val sourceType: CfDomainUpdateSourceType,
        val dryRun: Boolean,
        val domainCount: Int,
    ) : CfDomainListUpdateResult

    data class NotModified(
        val state: CfDomainUpstreamState,
        val elapsedMs: Long,
        val sourceType: CfDomainUpdateSourceType,
        val dryRun: Boolean,
        val domainCount: Int,
    ) : CfDomainListUpdateResult

    data class Failure(
        val state: CfDomainUpstreamState,
        val stage: CfDomainUpdateStage,
        val message: String,
        val dryRun: Boolean,
    ) : CfDomainListUpdateResult

    data class MirrorInvalid(val message: String) : CfDomainListUpdateResult

    object AlreadyRunning : CfDomainListUpdateResult
    object SkippedDisabled : CfDomainListUpdateResult
    object SkippedThrottled : CfDomainListUpdateResult
}

class CfDomainListUpdater(
    private val repository: CfDomainListRepository,
    private val sourceDownloader: CfDomainSourceDownloader,
    private val logger: CfDomainUpdateLogger,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()

    suspend fun manualUpdate(): CfDomainListUpdateResult = updateAllSources(
        mode = CfDomainUpdateMode.MANUAL,
        dryRun = false,
    )

    suspend fun testPrimarySource(): CfDomainListUpdateResult = updateAllSources(
        mode = CfDomainUpdateMode.TEST_PRIMARY,
        dryRun = true,
    )

    suspend fun testMirrorSource(): CfDomainListUpdateResult = updateAllSources(
        mode = CfDomainUpdateMode.TEST_MIRROR,
        dryRun = true,
    )

    suspend fun maybeAutoUpdate(): CfDomainListUpdateResult {
        val current = repository.state()
        if (!current.autoUpdateEnabled) {
            return CfDomainListUpdateResult.SkippedDisabled
        }
        if (!repository.shouldAutoUpdate(nowMs())) {
            val nextAllowed = nextAutoUpdateAtMs(current)
            logger.info("CF update skipped reason=throttle next_allowed_at=$nextAllowed")
            return CfDomainListUpdateResult.SkippedThrottled
        }
        return updateAllSources(mode = CfDomainUpdateMode.AUTO, dryRun = false)
    }

    private suspend fun updateAllSources(
        mode: CfDomainUpdateMode,
        dryRun: Boolean,
    ): CfDomainListUpdateResult {
        if (!mutex.tryLock()) {
            return CfDomainListUpdateResult.AlreadyRunning
        }

        val startedAt = nowMs()
        val logMode = when (mode) {
            CfDomainUpdateMode.MANUAL -> "manual"
            CfDomainUpdateMode.AUTO -> "auto"
            CfDomainUpdateMode.TEST_PRIMARY -> "test_primary"
            CfDomainUpdateMode.TEST_MIRROR -> "test_mirror"
        }
        logger.info("CF update started mode=$logMode dry_run=$dryRun")

        return try {
            var current = repository.state()
            val mirrorValidation = CfDomainMirrorUrlValidator.validate(
                enabled = current.mirrorEnabled,
                rawUrl = current.mirrorUrl,
            )
            if (
                mode == CfDomainUpdateMode.TEST_MIRROR &&
                current.mirrorEnabled &&
                mirrorValidation is CfDomainMirrorValidation.Invalid
            ) {
                return CfDomainListUpdateResult.MirrorInvalid(mirrorValidation.message)
            }

            val sources = buildSources(current, mode, mirrorValidation)
            if (sources.isEmpty()) {
                return failAll(
                    current = current,
                    stage = CfDomainUpdateStage.VALIDATION,
                    message = "no_sources",
                    dryRun = dryRun,
                )
            }

            val maxAttempts = when (mode) {
                CfDomainUpdateMode.AUTO -> MAX_ATTEMPTS_AUTO
                else -> MAX_ATTEMPTS_MANUAL
            }

            var lastFailure: CfDomainListDownloadResult.Failed? = null
            for (source in sources) {
                logger.info(
                    "CF update trying source=${source.type.logName} url=${safeUrlForLog(source.url)}",
                )
                var attempt = 0
                while (attempt < maxAttempts) {
                    attempt += 1
                    val result = sourceDownloader.download(
                        sourceType = source.type,
                        url = source.url,
                        etag = source.etag,
                        lastModified = source.lastModified,
                    )
                    current = recordSourceResult(current, source.type, result)

                    when (result) {
                        is CfDomainListDownloadResult.Success -> {
                            logger.info(
                                "CF update success source=${source.type.logName} " +
                                    "count=${result.domains.size} elapsed_ms=${result.latencyMs}",
                            )
                            if (!dryRun) {
                                current = applySuccess(current, result)
                            } else {
                                current = repository.updateSourceStatuses(
                                    current.primaryStatus,
                                    current.mirrorStatus,
                                )
                            }
                            val elapsed = nowMs() - startedAt
                            return CfDomainListUpdateResult.Success(
                                state = current,
                                elapsedMs = elapsed,
                                sourceType = result.sourceType,
                                dryRun = dryRun,
                                domainCount = result.domains.size,
                            )
                        }

                        is CfDomainListDownloadResult.NotModified -> {
                            logger.info(
                                "CF update not modified source=${source.type.logName} status=304",
                            )
                            if (!dryRun) {
                                current = applyNotModified(current, result)
                            }
                            val elapsed = nowMs() - startedAt
                            return CfDomainListUpdateResult.NotModified(
                                state = current,
                                elapsedMs = elapsed,
                                sourceType = result.sourceType,
                                dryRun = dryRun,
                                domainCount = current.domains.size,
                            )
                        }

                        is CfDomainListDownloadResult.Failed -> {
                            lastFailure = result
                            logger.warn(
                                "CF update source failed source=${source.type.logName} " +
                                    "stage=${result.stage.logName} reason=${result.message}" +
                                    (result.httpStatus?.let { " status=$it" } ?: ""),
                            )
                            val shouldRetry = result.retryable &&
                                attempt < maxAttempts &&
                                mode != CfDomainUpdateMode.AUTO
                            if (shouldRetry) {
                                val retryDelay = random.nextLong(RETRY_DELAY_MIN_MS, RETRY_DELAY_MAX_MS + 1)
                                logger.info(
                                    "CF update retry source=${source.type.logName} " +
                                        "attempt=${attempt + 1} delay_ms=$retryDelay",
                                )
                                sleeper(retryDelay)
                                continue
                            }
                            break
                        }
                    }
                }
            }

            failAll(
                current = current,
                stage = lastFailure?.stage ?: current.lastErrorStage ?: CfDomainUpdateStage.UNKNOWN,
                message = lastFailure?.let(::formatFailureMessage) ?: current.lastError ?: "all_sources_failed",
                dryRun = dryRun,
            )
        } finally {
            mutex.unlock()
        }
    }

    private data class ResolvedSource(
        val type: CfDomainUpdateSourceType,
        val url: String,
        val etag: String?,
        val lastModified: String?,
    )

    private fun buildSources(
        state: CfDomainUpstreamState,
        mode: CfDomainUpdateMode,
        mirrorValidation: CfDomainMirrorValidation,
    ): List<ResolvedSource> {
        val primary = ResolvedSource(
            type = CfDomainUpdateSourceType.PRIMARY_GITHUB,
            url = CfDomainUpdateConfig.PRIMARY_URL,
            etag = state.primaryStatus.etag ?: state.etag,
            lastModified = state.primaryStatus.lastModified ?: state.lastModified,
        )
        val mirror = when (mirrorValidation) {
            is CfDomainMirrorValidation.Valid -> ResolvedSource(
                type = CfDomainUpdateSourceType.USER_MIRROR,
                url = mirrorValidation.url,
                etag = state.mirrorStatus.etag,
                lastModified = state.mirrorStatus.lastModified,
            )

            else -> null
        }

        return when (mode) {
            CfDomainUpdateMode.TEST_PRIMARY -> listOf(primary)
            CfDomainUpdateMode.TEST_MIRROR -> mirror?.let { listOf(it) }.orEmpty()
            CfDomainUpdateMode.MANUAL, CfDomainUpdateMode.AUTO -> buildList {
                add(primary)
                if (mirror != null) {
                    add(mirror)
                }
            }
        }
    }

    private fun recordSourceResult(
        state: CfDomainUpstreamState,
        sourceType: CfDomainUpdateSourceType,
        result: CfDomainListDownloadResult,
    ): CfDomainUpstreamState {
        val attemptedAt = nowMs()
        val updatedStatus = when (result) {
            is CfDomainListDownloadResult.Success -> {
                CfDomainSourceStatus(
                    sourceType = sourceType,
                    url = result.sourceUrl,
                    enabled = true,
                    lastAttemptAtMs = attemptedAt,
                    lastSuccessAtMs = attemptedAt,
                    lastError = null,
                    lastHttpStatus = 200,
                    lastLatencyMs = result.latencyMs,
                    lastStage = null,
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
            }

            is CfDomainListDownloadResult.NotModified -> {
                (if (sourceType == CfDomainUpdateSourceType.PRIMARY_GITHUB) {
                    state.primaryStatus
                } else {
                    state.mirrorStatus
                }).copy(
                    url = result.sourceUrl,
                    lastAttemptAtMs = attemptedAt,
                    lastSuccessAtMs = attemptedAt,
                    lastError = null,
                    lastHttpStatus = 304,
                    lastLatencyMs = result.latencyMs,
                    lastStage = null,
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
            }

            is CfDomainListDownloadResult.Failed -> {
                (if (sourceType == CfDomainUpdateSourceType.PRIMARY_GITHUB) {
                    state.primaryStatus
                } else {
                    state.mirrorStatus
                }).copy(
                    url = result.sourceUrl,
                    lastAttemptAtMs = attemptedAt,
                    lastError = formatFailureMessage(result),
                    lastHttpStatus = result.httpStatus,
                    lastLatencyMs = result.latencyMs,
                    lastStage = result.stage,
                )
            }
        }

        val primary = if (sourceType == CfDomainUpdateSourceType.PRIMARY_GITHUB) {
            updatedStatus
        } else {
            state.primaryStatus
        }
        val mirror = if (sourceType == CfDomainUpdateSourceType.USER_MIRROR) {
            updatedStatus
        } else {
            state.mirrorStatus
        }
        return repository.updateSourceStatuses(primary, mirror)
    }

    private fun applySuccess(
        state: CfDomainUpstreamState,
        result: CfDomainListDownloadResult.Success,
    ): CfDomainUpstreamState {
        return repository.replaceCache(
            domains = result.domains,
            fetchedAtMs = result.fetchedAtMs,
            sourceUrl = result.sourceUrl,
            sourceType = result.sourceType,
            etag = result.etag,
            lastModified = result.lastModified,
            primaryStatus = if (result.sourceType == CfDomainUpdateSourceType.PRIMARY_GITHUB) {
                state.primaryStatus.copy(
                    etag = result.etag,
                    lastModified = result.lastModified,
                    lastSuccessAtMs = result.fetchedAtMs,
                )
            } else {
                state.primaryStatus
            },
            mirrorStatus = if (result.sourceType == CfDomainUpdateSourceType.USER_MIRROR) {
                state.mirrorStatus.copy(
                    etag = result.etag,
                    lastModified = result.lastModified,
                    lastSuccessAtMs = result.fetchedAtMs,
                )
            } else {
                state.mirrorStatus
            },
        )
    }

    private fun applyNotModified(
        state: CfDomainUpstreamState,
        result: CfDomainListDownloadResult.NotModified,
    ): CfDomainUpstreamState {
        return repository.markNotModified(
            checkedAtMs = result.checkedAtMs,
            sourceUrl = result.sourceUrl,
            sourceType = result.sourceType,
            etag = result.etag,
            lastModified = result.lastModified,
            primaryStatus = state.primaryStatus,
            mirrorStatus = state.mirrorStatus,
        )
    }

    private fun failAll(
        current: CfDomainUpstreamState,
        stage: CfDomainUpdateStage,
        message: String,
        dryRun: Boolean,
    ): CfDomainListUpdateResult.Failure {
        if (!dryRun) {
            logger.info("CF update cache kept reason=all_sources_failed")
        }
        val state = if (dryRun) {
            current
        } else {
            repository.recordFailure(
                attemptedAtMs = nowMs(),
                stage = stage,
                message = message,
                primaryStatus = current.primaryStatus,
                mirrorStatus = current.mirrorStatus,
            )
        }
        logger.warn("CF update failed stage=${stage.logName} reason=$message")
        return CfDomainListUpdateResult.Failure(
            state = state,
            stage = stage,
            message = message,
            dryRun = dryRun,
        )
    }

    private fun nextAutoUpdateAtMs(state: CfDomainUpstreamState): Long {
        val lastSuccess = state.lastSuccessfulUpdateAtMs
        val lastAttempt = maxOf(state.lastAttemptAtMs, state.lastUpdatedAtMs)
        return if (lastSuccess >= lastAttempt && lastSuccess > 0L) {
            lastSuccess + AUTO_UPDATE_SUCCESS_INTERVAL_MS
        } else {
            lastAttempt + AUTO_UPDATE_FAILURE_INTERVAL_MS
        }
    }
}

private fun formatFailureMessage(result: CfDomainListDownloadResult.Failed): String {
    return buildString {
        append(result.stage.logName)
        append(": ")
        append(result.message)
        result.httpStatus?.let { append(" (HTTP $it)") }
    }
}

private fun parseStage(raw: String): CfDomainUpdateStage? {
    return runCatching { CfDomainUpdateStage.valueOf(raw) }.getOrNull()
}

private fun parseSourceType(raw: String): CfDomainUpdateSourceType? {
    return runCatching { CfDomainUpdateSourceType.valueOf(raw) }.getOrNull()
}

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}
