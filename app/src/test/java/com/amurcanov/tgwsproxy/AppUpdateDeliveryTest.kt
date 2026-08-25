package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateDeliveryTest {
    @Test
    fun `semver follows stable and prerelease precedence`() {
        val alpha = SemanticVersion.parse("1.10.13-alpha.1")!!
        val beta = SemanticVersion.parse("v1.10.13-beta.1")!!
        val rc = SemanticVersion.parse("1.10.13-rc.1")!!
        val stable = SemanticVersion.parse("1.10.13")!!

        assertTrue(alpha < beta)
        assertTrue(beta < rc)
        assertTrue(rc < stable)
    }

    @Test
    fun `semver compares numeric prerelease identifiers numerically`() {
        val rc2 = SemanticVersion.parse("1.10.13-rc.2")!!
        val rc10 = SemanticVersion.parse("1.10.13-rc.10")!!

        assertTrue(rc2 < rc10)
    }

    @Test
    fun `semver ignores build metadata for precedence`() {
        val left = SemanticVersion.parse("1.10.13+build.1")!!
        val right = SemanticVersion.parse("1.10.13+build.99")!!

        assertEquals(0, left.compareTo(right))
    }

    @Test
    fun `invalid semver is rejected`() {
        assertNull(SemanticVersion.parse("1.10"))
        assertNull(SemanticVersion.parse("1.10.13-rc.01"))
        assertNull(SemanticVersion.parse("latest"))
    }

    @Test
    fun `stable install ignores prerelease and selects newer stable`() {
        val decision = AppUpdateSelector.select(
            currentVersionName = "1.10.12",
            releases = listOf(
                release("v1.10.14-rc.1", prerelease = true),
                release("v1.10.13"),
            ),
        )

        assertTrue(decision is UpdateDecision.Available)
        assertEquals("v1.10.13", (decision as UpdateDecision.Available).release.tagName)
    }

    @Test
    fun `stable install reports up to date when only newer prerelease exists`() {
        val decision = AppUpdateSelector.select(
            currentVersionName = "1.10.12",
            releases = listOf(release("v1.10.13-rc.1", prerelease = true)),
        )

        assertEquals(UpdateDecision.UpToDate, decision)
    }

    @Test
    fun `prerelease install may advance to newer prerelease`() {
        val decision = AppUpdateSelector.select(
            currentVersionName = "1.10.13-beta.1",
            releases = listOf(
                release("v1.10.13-rc.1", prerelease = true),
                release("v1.10.13-beta.2", prerelease = true),
            ),
        )

        assertTrue(decision is UpdateDecision.Available)
        assertEquals("v1.10.13-rc.1", (decision as UpdateDecision.Available).release.tagName)
    }

    @Test
    fun `prerelease install prefers stable of same version`() {
        val decision = AppUpdateSelector.select(
            currentVersionName = "1.10.13-rc.1",
            releases = listOf(
                release("v1.10.13"),
                release("v1.10.14-beta.1", prerelease = true),
            ),
        )

        assertTrue(decision is UpdateDecision.Available)
        assertEquals("v1.10.14-beta.1", (decision as UpdateDecision.Available).release.tagName)
    }

    @Test
    fun `draft malformed and older releases are ignored`() {
        val decision = AppUpdateSelector.select(
            currentVersionName = "1.10.13",
            releases = listOf(
                release("v2.0.0", draft = true),
                release("not-a-version"),
                release("v1.10.12"),
            ),
        )

        assertEquals(UpdateDecision.UpToDate, decision)
    }

    @Test
    fun `unsupported installed version is explicit`() {
        val decision = AppUpdateSelector.select(
            currentVersionName = "dev-build",
            releases = listOf(release("v1.10.13")),
        )

        assertEquals(UpdateDecision.UnsupportedCurrentVersion, decision)
    }

    @Test
    fun `release URL accepts only semver tags and stays on official repository`() {
        assertEquals(
            "https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.10.13",
            AppUpdateSelector.officialReleaseUrl("v1.10.13"),
        )
        assertNull(AppUpdateSelector.officialReleaseUrl("../../issues/1"))
        assertNull(AppUpdateSelector.officialReleaseUrl("latest"))
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = GitHubReleaseInfo(
        tagName = tag,
        title = tag,
        notes = "notes",
        draft = draft,
        prerelease = prerelease,
    )
}
