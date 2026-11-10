package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

private const val MAX_UPDATE_BYTES = 100L * 1024L * 1024L
private const val MAX_CHECKSUM_BYTES = 4096
private const val MAX_REDIRECTS = 5
private const val APK_MIME = "application/vnd.android.package-archive"

class UpdateInstallException(message: String) : IOException(message)

/** Never pass an APK to Android until its hash, signature and identity pass validation. */
class AppUpdateInstaller(private val context: Context) {
    private val directory: File get() = File(context.cacheDir, "verified-updates")
    private val pendingFile: File get() = File(directory, "release.apk.part")
    private val verifiedFile: File get() = File(directory, "release.apk")

    init {
        // Never reuse APKs left from an earlier process or release.
        discard()
    }

    suspend fun download(
        release: GitHubReleaseInfo,
        onProgress: suspend (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val selection = AppUpdateAssetSelector.select(release)
            ?: throw UpdateInstallException("No verifiable official APK for this release")
        directory.mkdirs()
        pendingFile.delete()
        verifiedFile.delete()
        try {
            val officialChecksum = selection.checksum?.let { asset ->
                val rawBytes = downloadBytes(asset.downloadUrl)
                asset.digest?.let { metadata ->
                    val expected = metadata.takeIf {
                        it.matches(Regex("^sha256:[0-9a-fA-F]{64}$"))
                    }?.substringAfter(':')?.lowercase()
                        ?: throw UpdateInstallException("Invalid checksum asset digest")
                    if (sha256(rawBytes) != expected) {
                        throw UpdateInstallException("Checksum asset digest mismatch")
                    }
                }
                AppUpdateAssetSelector.parseChecksum(
                    String(rawBytes, Charsets.US_ASCII), selection.apk.name,
                )
                    ?: throw UpdateInstallException("Invalid release checksum")
            }
            val expectedHash = selection.metadataSha256 ?: officialChecksum
                ?: throw UpdateInstallException("Missing official SHA-256")
            if (officialChecksum != null && selection.metadataSha256 != null &&
                officialChecksum != selection.metadataSha256
            ) throw UpdateInstallException("Release checksum disagreement")
            val digest = MessageDigest.getInstance("SHA-256")
            val response = openVerifiedConnection(selection.apk.downloadUrl)
            try {
                if (response.responseCode != HttpURLConnection.HTTP_OK) {
                    throw UpdateInstallException("APK download HTTP " + response.responseCode)
                }
                val length = response.contentLengthLong
                val mime = response.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (mime !in setOf("application/octet-stream", "application/vnd.android.package-archive")) {
                    throw UpdateInstallException("Unexpected APK MIME type")
                }
                if (length > MAX_UPDATE_BYTES || (length >= 0 && length != selection.apk.size)) {
                    throw UpdateInstallException("Unexpected APK content length")
                }
                val buffer = ByteArray(32 * 1024)
                var received = 0L
                response.inputStream.use { input ->
                    pendingFile.outputStream().buffered().use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            received += count
                            if (received > MAX_UPDATE_BYTES || received > selection.apk.size) {
                                throw UpdateInstallException("APK size limit exceeded")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress(received, selection.apk.size)
                        }
                    }
                }
                if (received != selection.apk.size) throw UpdateInstallException("Incomplete APK")
            } finally {
                response.disconnect()
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHash != expectedHash) throw UpdateInstallException("APK SHA-256 mismatch")
            verifySignedPackage(pendingFile)
            if (!pendingFile.renameTo(verifiedFile)) throw UpdateInstallException("Unable to finalize APK")
            verifiedFile
        } catch (error: Exception) {
            pendingFile.delete()
            verifiedFile.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verifySignedPackage(file: File) {
        val result = ApkVerifier.Builder(file)
            .setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .build()
            .verify()
        if (!result.isVerified || result.signerCertificates.isEmpty()) {
            throw UpdateInstallException("APK signature validation failed")
        }
        val pm = context.packageManager
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, signingFlags)
            ?: throw UpdateInstallException("Invalid APK metadata")
        if (archive.packageName != context.packageName) {
            throw UpdateInstallException("APK belongs to another package")
        }
        val installed = pm.getPackageInfo(context.packageName, signingFlags)
        val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.longVersionCode
        } else installed.versionCode.toLong()
        val candidateCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else archive.versionCode.toLong()
        if (candidateCode <= installedCode) {
            throw UpdateInstallException("APK versionCode is not newer")
        }
        if ((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) {
            throw UpdateInstallException("APK requires newer Android")
        }
        ZipFile(file).use { zip ->
            val abiNames = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .mapNotNull { it.name.split('/').getOrNull(1) }
                .toSet()
            if (abiNames != setOf("arm64-v8a")) {
                throw UpdateInstallException("APK native libraries do not match arm64-v8a")
            }
        }
        val installedSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else installed.signatures?.toList().orEmpty()
        val trusted = installedSigners.map { sha256(it.toByteArray()) }.toSet()
        val candidate = result.signerCertificates.map { sha256(it.encoded) }.toSet()
        if (trusted.isEmpty() || trusted != candidate) {
            // Key rotation is deliberately rejected until validated lineage support is added.
            throw UpdateInstallException("APK signer differs from installed application")
        }
    }

    fun installIntent(file: File): Intent {
        if (file.canonicalFile != verifiedFile.canonicalFile || !file.isFile) {
            throw UpdateInstallException("No verified update APK")
        }
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun discard() {
        pendingFile.delete()
        verifiedFile.delete()
    }

    private fun downloadBytes(url: String): ByteArray {
        val connection = openVerifiedConnection(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                connection.contentLengthLong > MAX_CHECKSUM_BYTES
            ) throw UpdateInstallException("Checksum download failed")
            connection.inputStream.use { input ->
                val bytes = ByteArray(MAX_CHECKSUM_BYTES + 1)
                var total = 0
                while (total < bytes.size) {
                    val count = input.read(bytes, total, bytes.size - total)
                    if (count < 0) break
                    total += count
                }
                if (total > MAX_CHECKSUM_BYTES) throw UpdateInstallException("Checksum too large")
                return bytes.copyOf(total)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openVerifiedConnection(url: String): HttpURLConnection {
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) { redirects ->
            if (target.protocol != "https" || target.host.lowercase() !in TRUSTED_HOSTS ||
                target.userInfo != null || target.port !in setOf(-1, 443)
            ) throw UpdateInstallException("Untrusted download URL")
            val connection = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "tg-ws-proxy-android-update")
            }
            val status = try { connection.responseCode } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (status !in setOf(301, 302, 303, 307, 308)) return connection
            val location = connection.getHeaderField("Location")
                ?: throw UpdateInstallException("Redirect without Location")
            val next = URL(target, location)
            connection.disconnect()
            if (redirects == MAX_REDIRECTS) throw UpdateInstallException("Too many redirects")
            target = next
        }
        throw UpdateInstallException("Unable to follow redirects")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    companion object {
        private val TRUSTED_HOSTS = setOf(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )
    }
}
