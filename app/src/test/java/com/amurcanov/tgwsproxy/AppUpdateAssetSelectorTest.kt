package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateAssetSelectorTest {
    private val tag = "v1.11.8"
    private val legacyFilename = "TgWsProxy-Android-v1.11.8-arm64-v8a.apk"
    private val universalFilename = "TgWsProxy-Android-v1.11.8-universal.apk"
    private val universalAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    private val legacyAbis = setOf("arm64-v8a")
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
    fun selectsOfficialUniversalReleaseWithDigest() {
        val selected = AppUpdateAssetSelector.select(
            release(asset(universalFilename, digest = digest)),
        )
        assertNotNull(selected)
        assertEquals(universalFilename, selected!!.apk.name)
        assertEquals("a".repeat(64), selected.metadataSha256)
        assertEquals(universalAbis, selected.nativeAbis)
    }

    @Test
    fun retainsSupportForOfficialLegacyArm64Release() {
        val selected = AppUpdateAssetSelector.select(
            release(asset(legacyFilename, digest = digest)),
        )
        assertNotNull(selected)
        assertEquals(legacyFilename, selected!!.apk.name)
        assertEquals(legacyAbis, selected.nativeAbis)
    }

    @Test
    fun prefersUniversalAssetWhenBothFormatsArePresent() {
        val selected = AppUpdateAssetSelector.select(
            release(
                asset(legacyFilename, digest = digest),
                asset(universalFilename, digest = digest),
            ),
        )
        assertNotNull(selected)
        assertEquals(universalFilename, selected!!.apk.name)
        assertEquals(universalAbis, selected.nativeAbis)
    }

    @Test
    fun acceptsUniversalShaSidecarWithoutApiDigest() {
        val selected = AppUpdateAssetSelector.select(
            release(
                asset(universalFilename),
                asset("$universalFilename.sha256", size = 107L),
            ),
        )
        assertNotNull(selected)
        assertNotNull(selected!!.checksum)
        assertEquals(universalAbis, selected.nativeAbis)
    }

    @Test
    fun acceptsLegacyShaSidecarWithoutApiDigest() {
        val selected = AppUpdateAssetSelector.select(
            release(
                asset(legacyFilename),
                asset("$legacyFilename.sha256", size = 107L),
            ),
        )
        assertNotNull(selected)
        assertNotNull(selected!!.checksum)
        assertEquals(legacyAbis, selected.nativeAbis)
    }

    @Test
    fun rejectsDownloadsWithoutChecksumEvidence() {
        assertNull(AppUpdateAssetSelector.select(release(asset(universalFilename))))
        assertNull(AppUpdateAssetSelector.select(release(asset(legacyFilename))))
    }

    @Test
    fun rejectsDuplicateOfficialApkAssets() {
        assertNull(
            AppUpdateAssetSelector.select(
                release(
                    asset(universalFilename, digest = digest),
                    asset(universalFilename, digest = digest),
                ),
            ),
        )
    }

    @Test
    fun rejectsAlteredUrlAndUnsupportedArchitectureAsset() {
        assertNull(
            AppUpdateAssetSelector.select(
                release(
                    asset(universalFilename, digest = digest)
                        .copy(downloadUrl = "https://evil.example/update.apk"),
                ),
            ),
        )
        assertNull(
            AppUpdateAssetSelector.select(
                release(
                    asset("TgWsProxy-Android-$tag-x86.apk", digest = digest),
                ),
            ),
        )
    }

    @Test
    fun rejectsInvalidMetadataAndExcessiveSize() {
        assertNull(
            AppUpdateAssetSelector.select(
                release(asset(universalFilename, digest = "sha256:INVALID")),
            ),
        )
        assertNull(
            AppUpdateAssetSelector.select(
                release(asset(universalFilename, size = 120L * 1024L * 1024L, digest = digest)),
            ),
        )
    }

    @Test
    fun rejectsDuplicateChecksumSidecars() {
        assertNull(
            AppUpdateAssetSelector.select(
                release(
                    asset(universalFilename, digest = digest),
                    asset("$universalFilename.sha256", size = 107L),
                    asset("$universalFilename.sha256", size = 107L),
                ),
            ),
        )
    }

    @Test
    fun checksumParserRequiresMatchingFilename() {
        val valid = "B".repeat(64) + "  " + universalFilename
        assertEquals(
            "b".repeat(64),
            AppUpdateAssetSelector.parseChecksum(valid, universalFilename),
        )
        assertNull(AppUpdateAssetSelector.parseChecksum(valid, "different.apk"))
        assertNull(AppUpdateAssetSelector.parseChecksum("b".repeat(64), universalFilename))
        assertNull(AppUpdateAssetSelector.parseChecksum(valid + "\nextra", universalFilename))
    }
}