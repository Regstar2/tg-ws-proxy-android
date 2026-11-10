package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateAssetSelectorTest {
    private val tag = "v1.11.8"
    private val filename = "TgWsProxy-Android-v1.11.8-arm64-v8a.apk"
    private val digest = "sha256:" + "a".repeat(64)

    private fun asset(name: String, size: Long = 20_000_000L, digest: String? = null) =
        GitHubReleaseAsset(
            name = name,
            downloadUrl = "https://github.com/Regstar2/tg-ws-proxy-android/releases/download/$tag/$name",
            size = size,
            contentType = "application/octet-stream",
            digest = digest,
        )

    private fun release(vararg assets: GitHubReleaseAsset) =
        GitHubReleaseInfo(tag, tag, "", false, false, assets.toList())

    @Test
    fun selectsOfficialArm64ReleaseWithDigest() {
        val selected = AppUpdateAssetSelector.select(release(asset(filename, digest = digest)))
        assertNotNull(selected)
        assertEquals(filename, selected!!.apk.name)
        assertEquals("a".repeat(64), selected.metadataSha256)
    }

    @Test
    fun acceptsShaSidecarWithoutApiDigest() {
        val selected = AppUpdateAssetSelector.select(
            release(asset(filename), asset("$filename.sha256", size = 107L)),
        )
        assertNotNull(selected)
        assertNotNull(selected!!.checksum)
    }

    @Test
    fun rejectsDownloadsWithoutChecksumEvidence() {
        assertNull(AppUpdateAssetSelector.select(release(asset(filename))))
    }

    @Test
    fun rejectsDuplicateOfficialApkAssets() {
        assertNull(AppUpdateAssetSelector.select(
            release(asset(filename, digest = digest), asset(filename, digest = digest))
        ))
    }

    @Test
    fun rejectsAlteredUrlAndOtherArchitecture() {
        assertNull(AppUpdateAssetSelector.select(release(
            asset(filename, digest = digest).copy(downloadUrl = "https://evil.example/update.apk")
        )))
        assertNull(AppUpdateAssetSelector.select(
            release(asset(filename.replace("arm64-v8a", "x86"), digest = digest))
        ))
    }

    @Test
    fun rejectsInvalidMetadataAndExcessiveSize() {
        assertNull(AppUpdateAssetSelector.select(release(
            asset(filename, digest = "sha256:INVALID")
        )))
        assertNull(AppUpdateAssetSelector.select(release(
            asset(filename, size = 120L * 1024L * 1024L, digest = digest)
        )))
    }

    @Test
    fun checksumParserRequiresMatchingFilename() {
        val valid = "B".repeat(64) + "  " + filename
        assertEquals("b".repeat(64), AppUpdateAssetSelector.parseChecksum(valid, filename))
        assertNull(AppUpdateAssetSelector.parseChecksum(valid, "different.apk"))
        assertNull(AppUpdateAssetSelector.parseChecksum("b".repeat(64), filename))
        assertNull(AppUpdateAssetSelector.parseChecksum(valid + "\nextra", filename))
    }
}
