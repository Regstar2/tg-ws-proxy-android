package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgWarpBootstrapSelectionTest {
    @Test
    fun selectedWorkingProfileIsPreferredBeforeNewerProfiles() {
        val profiles = listOf(
            summary(id = "newer", createdAtMs = 30L, selected = false),
            summary(id = "selected", createdAtMs = 10L, selected = true),
            summary(id = "older", createdAtMs = 20L, selected = false),
        )

        val ordered = orderedExistingAwgBootstrapProfiles(profiles)

        assertEquals(listOf("selected", "newer", "older"), ordered.map { it.metadata.id })
    }

    @Test
    fun onlyFullDuplexValidatedWorkingProfilesAreCandidates() {
        val profiles = listOf(
            summary(id = "working-import", source = AwgWarpProfileSource.IMPORTED),
            summary(id = "unchecked", health = AwgWarpProfileHealth.NOT_CHECKED),
            summary(id = "network-error", health = AwgWarpProfileHealth.NETWORK_ERROR),
        )

        val ordered = orderedExistingAwgBootstrapProfiles(profiles)

        assertEquals(listOf("working-import"), ordered.map { it.metadata.id })
        assertTrue(ordered.single().metadata.source == AwgWarpProfileSource.IMPORTED)
    }

    @Test
    fun selectedButUnhealthyProfileDoesNotBypassHealthGate() {
        val profiles = listOf(
            summary(id = "selected-bad", selected = true, health = AwgWarpProfileHealth.NO_HANDSHAKE),
            summary(id = "other-good", selected = false, health = AwgWarpProfileHealth.WORKING),
        )

        val ordered = orderedExistingAwgBootstrapProfiles(profiles)

        assertEquals("other-good", ordered.single().metadata.id)
        assertFalse(ordered.single().selected)
    }

    private fun summary(
        id: String,
        createdAtMs: Long = 1L,
        selected: Boolean = false,
        source: AwgWarpProfileSource = AwgWarpProfileSource.CONSUMER_WARP,
        health: AwgWarpProfileHealth = AwgWarpProfileHealth.WORKING,
    ): AwgWarpProfileSummary {
        return AwgWarpProfileSummary(
            metadata = AwgWarpProfileMetadata(
                id = id,
                name = id,
                source = source,
                createdAtMs = createdAtMs,
                health = health,
            ),
            selected = selected,
            endpoint = "engage.cloudflareclient.com:2408",
        )
    }
}
