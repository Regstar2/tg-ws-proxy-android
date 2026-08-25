package com.amurcanov.tgwsproxy

import org.json.JSONArray
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

private const val UPDATE_API_URL =
    "https://api.github.com/repos/Regstar2/tg-ws-proxy-android/releases?per_page=20"
private const val RELEASE_PAGE_PREFIX =
    "https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/"
private const val MAX_RELEASE_RESPONSE_BYTES = 1024 * 1024
private const val MAX_RELEASE_NOTES_CHARS = 6000

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList(),
    val buildMetadata: List<String> = emptyList(),
) : Comparable<SemanticVersion> {
    val isPrerelease: Boolean
        get() = prerelease.isNotEmpty()

    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        val shared = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until shared) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            if (left == right) continue

            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            return when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex(
            "^[vV]?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
        )

        fun parse(raw: String?): SemanticVersion? {
            val value = raw?.trim().orEmpty()
            val match = pattern.matchEntire(value) ?: return null
            val prerelease = match.groupValues[4]
                .takeIf { it.isNotEmpty() }
                ?.split('.')
                .orEmpty()
            if (prerelease.any { identifier ->
                    identifier.all(Char::isDigit) && identifier.length > 1 && identifier.startsWith('0')
                }) {
                return null
            }
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                prerelease = prerelease,
                buildMetadata = match.groupValues[5]
                    .takeIf { it.isNotEmpty() }
                    ?.split('.')
                    .orEmpty(),
            )
        }
    }
}

data class GitHubReleaseInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val draft: Boolean,
    val prerelease: Boolean,
)

sealed interface UpdateDecision {
    data class Available(
        val release: GitHubReleaseInfo,
        val version: SemanticVersion,
    ) : UpdateDecision

    data object UpToDate : UpdateDecision
    data object UnsupportedCurrentVersion : UpdateDecision
}

object AppUpdateSelector {
    fun select(currentVersionName: String, releases: List<GitHubReleaseInfo>): UpdateDecision {
        val current = SemanticVersion.parse(currentVersionName)
            ?: return UpdateDecision.UnsupportedCurrentVersion
        val allowPrerelease = current.isPrerelease

        val candidate = releases.asSequence()
            .filterNot { it.draft }
            .mapNotNull { release ->
                val version = SemanticVersion.parse(release.tagName) ?: return@mapNotNull null
                if (!allowPrerelease && (release.prerelease || version.isPrerelease)) {
                    return@mapNotNull null
                }
                if (version <= current) return@mapNotNull null
                release to version
            }
            .maxWithOrNull(compareBy<Pair<GitHubReleaseInfo, SemanticVersion>> { it.second })
            ?: return UpdateDecision.UpToDate

        return UpdateDecision.Available(candidate.first, candidate.second)
    }

    fun officialReleaseUrl(tagName: String): String? {
        val version = SemanticVersion.parse(tagName) ?: return null
        val canonicalTag = buildString {
            append('v')
            append(version.major)
            append('.')
            append(version.minor)
            append('.')
            append(version.patch)
            if (version.prerelease.isNotEmpty()) {
                append('-')
                append(version.prerelease.joinToString("."))
            }
            if (version.buildMetadata.isNotEmpty()) {
                append('+')
                append(version.buildMetadata.joinToString("."))
            }
        }
        return RELEASE_PAGE_PREFIX + canonicalTag
    }
}

enum class UpdateFailureKind {
    NETWORK,
    TIMEOUT,
    API,
    MALFORMED,
}

class UpdateCheckException(
    val kind: UpdateFailureKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class GitHubReleasesClient(
    private val endpoint: String = UPDATE_API_URL,
) {
    fun fetchReleases(): List<GitHubReleaseInfo> {
        val connection = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (error: IOException) {
            throw UpdateCheckException(UpdateFailureKind.NETWORK, "Unable to open releases endpoint", error)
        }

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "tg-ws-proxy-android-update-check")

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw UpdateCheckException(UpdateFailureKind.API, "GitHub Releases returned HTTP $code")
            }

            val body = connection.inputStream.use(::readBoundedUtf8)
            parseReleaseList(body)
        } catch (error: SocketTimeoutException) {
            throw UpdateCheckException(UpdateFailureKind.TIMEOUT, "GitHub Releases request timed out", error)
        } catch (error: UnknownHostException) {
            throw UpdateCheckException(UpdateFailureKind.NETWORK, "GitHub Releases host is unavailable", error)
        } catch (error: UpdateCheckException) {
            throw error
        } catch (error: JSONException) {
            throw UpdateCheckException(UpdateFailureKind.MALFORMED, "Malformed GitHub Releases metadata", error)
        } catch (error: IOException) {
            throw UpdateCheckException(UpdateFailureKind.NETWORK, "Unable to read GitHub Releases metadata", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseReleaseList(rawJson: String): List<GitHubReleaseInfo> {
        val array = JSONArray(rawJson)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val tagName = item.optString("tag_name").trim()
                if (tagName.isEmpty()) continue
                add(
                    GitHubReleaseInfo(
                        tagName = tagName,
                        title = item.optString("name").trim().ifBlank { tagName },
                        notes = item.optString("body").trim().take(MAX_RELEASE_NOTES_CHARS),
                        draft = item.optBoolean("draft", false),
                        prerelease = item.optBoolean("prerelease", false),
                    ),
                )
            }
        }
    }

    private fun readBoundedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RELEASE_RESPONSE_BYTES) {
                throw UpdateCheckException(
                    UpdateFailureKind.MALFORMED,
                    "GitHub Releases response exceeds size limit",
                )
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
