package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateAssetSelectorTest {
    private fun select(release: GitHubReleaseInfo, abis: List<String> = listOf("arm64-v8a")) =
        AppUpdateAssetSelector.select(release, abis)
    @Test
    fun selectsArmv7On32BitAndroid() {
        val arm = filename.replace("arm64-v8a", "armeabi-v7a")
        val selected = select(
            release(asset(filename, digest = digest), asset(arm, digest = digest)),
            listOf("armeabi-v7a", "armeabi"),
        )
        assertEquals(arm, selected?.apk?.name)
    }

    @Test
    fun selectsX86AndX86_64ReleaseAssets() {
        val x86 = filename.replace("arm64-v8a", "x86")
        val x86_64 = filename.replace("arm64-v8a", "x86_64")
        val release = release(
            asset(x86, digest = digest),
            asset(x86_64, digest = digest),
        )

        assertEquals(x86, select(release, listOf("x86"))?.apk?.name)
        assertEquals(x86_64, select(release, listOf("x86_64"))?.apk?.name)
        assertEquals(x86_64, select(release, listOf("x86_64", "x86"))?.apk?.name)
        assertEquals(x86, select(release, listOf("x86", "x86_64"))?.apk?.name)
    }

    @Test
    fun fallsBackToUniversalForNewArchitectures() {
        val universal = filename.replace("arm64-v8a", "universal")
        val release = release(asset(universal, digest = digest))

        assertEquals(universal, select(release, listOf("x86"))?.apk?.name)
        assertEquals(universal, select(release, listOf("x86_64"))?.apk?.name)
    }

    @Test
    fun prefersDeviceAbiOrderThenUniversal() {
        val arm = filename.replace("arm64-v8a", "armeabi-v7a")
        val universal = filename.replace("arm64-v8a", "universal")
        val all = release(asset(arm, digest = digest), asset(universal, digest = digest), asset(filename, digest = digest))
        assertEquals(filename, select(all, listOf("arm64-v8a", "armeabi-v7a"))?.apk?.name)
        assertEquals(universal, select(release(asset(universal, digest = digest)), listOf("armeabi-v7a"))?.apk?.name)
        assertEquals(universal, select(all, listOf("x86_64", "x86"))?.apk?.name)
        assertNull(select(all, emptyList()))
        assertNull(select(release(asset(filename, digest = digest)), listOf("armeabi-v7a")))
    }

    @Test
    fun universalStillRequiresOfficialSourceAndIntegrity() {
        val name = filename.replace("arm64-v8a", "universal")
        assertNull(select(release(asset(name)), listOf("armeabi-v7a")))
        assertNull(select(release(asset(name, digest = digest).copy(downloadUrl = "https://evil.example/$name")), listOf("armeabi-v7a")))
        assertNull(select(release(asset(name, digest = digest), asset(name, digest = digest)), listOf("arm64-v8a")))
    }
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
        val selected = select(release(asset(filename, digest = digest)))
        assertNotNull(selected)
        assertEquals(filename, selected!!.apk.name)
        assertEquals("a".repeat(64), selected.metadataSha256)
    }

    @Test
    fun acceptsShaSidecarWithoutApiDigest() {
        val selected = select(
            release(asset(filename), asset("$filename.sha256", size = 107L)),
        )
        assertNotNull(selected)
        assertNotNull(selected!!.checksum)
    }

    @Test
    fun rejectsDownloadsWithoutChecksumEvidence() {
        assertNull(select(release(asset(filename))))
    }

    @Test
    fun rejectsDuplicateOfficialApkAssets() {
        assertNull(select(
            release(asset(filename, digest = digest), asset(filename, digest = digest))
        ))
    }

    @Test
    fun rejectsAlteredUrlAndOtherArchitecture() {
        assertNull(select(release(
            asset(filename, digest = digest).copy(downloadUrl = "https://evil.example/update.apk")
        )))
        assertNull(select(
            release(asset(filename.replace("arm64-v8a", "x86"), digest = digest))
        ))
    }

    @Test
    fun rejectsInvalidMetadataAndExcessiveSize() {
        assertNull(select(release(
            asset(filename, digest = "sha256:INVALID")
        )))
        assertNull(select(release(
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
