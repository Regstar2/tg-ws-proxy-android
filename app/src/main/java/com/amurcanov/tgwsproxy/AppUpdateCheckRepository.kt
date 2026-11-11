package com.amurcanov.tgwsproxy

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One source of update-check results for startup and the Updates screen.
 * A successful response is cached across process restarts. A manual check
 * always bypasses the cache; downloads still verify every APK independently.
 */
internal object AppUpdateCheckRepository {
    private const val PREFS = "AppUpdateCheckCache"
    private const val ENTRY = "latest_result"
    private val mutex = Mutex()

    suspend fun check(
        context: Context,
        installedVersion: String,
        installedCode: Long,
        force: Boolean = false,
        client: GitHubReleasesClient = GitHubReleasesClient(),
    ): UpdateDecision = mutex.withLock {
        if (!force) {
            cached(context, installedVersion, installedCode)?.let { return@withLock it }
            if (recentFailure(context, installedVersion, installedCode)) {
                throw UpdateCheckException(
                    UpdateFailureKind.NETWORK, "Recent GitHub check failure; retry manually",
                )
            }
        }
        try {
            val result = withContext(Dispatchers.IO) {
                AppUpdateSelector.select(installedVersion, client.fetchReleases())
            }
            save(context, installedVersion, installedCode, result)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            saveFailure(context, installedVersion, installedCode)
            throw error
        }
    }

    fun cached(context: Context, installedVersion: String, installedCode: Long): UpdateDecision? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ENTRY, null) ?: return null
        return runCatching {
            val data = JSONObject(raw)
            if (data.optString("installed") != installedVersion ||
                data.optLong("installedCode", -1L) != installedCode ||
                !AppUpdateCheckPolicy.isFresh(data.optLong("checkedAt", -1L), System.currentTimeMillis())
            ) return@runCatching null

            when (data.optString("state")) {
                "current" -> UpdateDecision.UpToDate
                "unsupported" -> UpdateDecision.UnsupportedCurrentVersion
                "available" -> {
                    val item = data.optJSONObject("release") ?: return@runCatching null
                    val release = GitHubReleaseInfo(
                        tagName = item.optString("tag"),
                        title = item.optString("title"),
                        notes = item.optString("notes").take(6000),
                        draft = item.optBoolean("draft", false),
                        prerelease = item.optBoolean("prerelease", false),
                        assets = buildList {
                            val items = item.optJSONArray("assets") ?: JSONArray()
                            for (index in 0 until minOf(items.length(), 8)) {
                                val asset = items.optJSONObject(index) ?: return@runCatching null
                                add(
                                    GitHubReleaseAsset(
                                        name = asset.optString("name"),
                                        downloadUrl = asset.optString("url"),
                                        size = asset.optLong("size", -1L),
                                        contentType = asset.optString("mime"),
                                        digest = if (asset.isNull("digest")) null else asset.optString("digest"),
                                    ),
                                )
                            }
                        },
                    )
                    val decision = AppUpdateSelector.select(installedVersion, listOf(release))
                    decision as? UpdateDecision.Available
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun recentFailure(context: Context, version: String, code: Long): Boolean =
        runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ENTRY, null) ?: return@runCatching false
            val data = JSONObject(raw)
            data.optString("state") == "failure" &&
                data.optString("installed") == version &&
                data.optLong("installedCode", -1L) == code &&
                AppUpdateCheckPolicy.isFresh(
                    data.optLong("checkedAt", -1L),
                    System.currentTimeMillis(),
                    AppUpdateCheckPolicy.ERROR_BACKOFF_MS,
                )
        }.getOrDefault(false)

    private fun saveFailure(context: Context, version: String, code: Long) {
        val data = JSONObject()
            .put("installed", version)
            .put("installedCode", code)
            .put("checkedAt", System.currentTimeMillis())
            .put("state", "failure")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ENTRY, data.toString()).apply()
    }

    private fun save(
        context: Context,
        installedVersion: String,
        installedCode: Long,
        decision: UpdateDecision,
    ) {
        val data = JSONObject()
            .put("installed", installedVersion)
            .put("installedCode", installedCode)
            .put("checkedAt", System.currentTimeMillis())
        when (decision) {
            UpdateDecision.UpToDate -> data.put("state", "current")
            UpdateDecision.UnsupportedCurrentVersion -> data.put("state", "unsupported")
            is UpdateDecision.Available -> {
                val release = decision.release
                val items = JSONArray()
                val matching = AppUpdateAssetSelector.select(release)
                val selectedAssets = if (matching != null) {
                    listOfNotNull(matching.apk, matching.checksum)
                } else release.assets.take(8)
                selectedAssets.forEach { asset ->
                    items.put(
                        JSONObject()
                            .put("name", asset.name)
                            .put("url", asset.downloadUrl)
                            .put("size", asset.size)
                            .put("mime", asset.contentType)
                            .put("digest", asset.digest ?: JSONObject.NULL),
                    )
                }
                data.put("state", "available")
                    .put("release",
                        JSONObject()
                            .put("tag", release.tagName)
                            .put("title", release.title)
                            .put("notes", release.notes)
                            .put("draft", release.draft)
                            .put("prerelease", release.prerelease)
                            .put("assets", items),
                    )
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ENTRY, data.toString()).apply()
    }
}

internal object AppUpdateCheckPolicy {
    const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    const val ERROR_BACKOFF_MS = 5L * 60L * 1000L

    fun isFresh(
        checkedAt: Long,
        now: Long,
        maxAge: Long = CACHE_TTL_MS,
    ): Boolean =
        checkedAt >= 0 && now >= checkedAt && now - checkedAt < maxAge
}
