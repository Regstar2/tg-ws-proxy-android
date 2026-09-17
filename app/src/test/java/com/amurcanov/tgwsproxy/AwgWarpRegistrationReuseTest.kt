package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgWarpRegistrationReuseTest {
    private val details = AwgWarpConfigDetails(
        privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        addresses = listOf("172.16.0.2/32", "2606:4700:110:1234::2/128"),
        mtu = 1280,
        deviceOptions = mapOf(
            "Jc" to "4",
            "Jmin" to "40",
            "Jmax" to "70",
        ),
        peer = AwgWarpPeerDetails(
            publicKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            endpoint = "engage.cloudflareclient.com:2408",
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            persistentKeepalive = 25,
        ),
    )

    @Test
    fun storedConsumerProfileReconstructsRegistrationSeed() {
        val metadata = AwgWarpProfileMetadata(
            id = "00000000-0000-0000-0000-000000000001",
            name = "WARP",
            source = AwgWarpProfileSource.CONSUMER_WARP,
            createdAtMs = 1L,
            localPublicKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
        )

        val reused = reusableConsumerWarpRegistration(metadata, details)

        requireNotNull(reused)
        assertEquals(details.privateKey, reused.privateKey)
        assertEquals(metadata.localPublicKey, reused.publicKey)
        assertEquals("172.16.0.2/32", reused.assignedIpv4)
        assertEquals("2606:4700:110:1234::2/128", reused.assignedIpv6)
        assertEquals(details.peer.publicKey, reused.peerPublicKey)
        assertEquals(details.peer.endpoint, reused.endpoint)
        assertEquals(details.peer.allowedIps, reused.allowedIps)
        assertEquals(details.mtu, reused.mtu)
        assertEquals(25, reused.persistentKeepalive)
        assertTrue(reused.deviceOptions.containsKey("Jc"))
    }

    @Test
    fun importedProfileIsNeverUsedAsConsumerRegistrationSeed() {
        val metadata = AwgWarpProfileMetadata(
            id = "00000000-0000-0000-0000-000000000002",
            name = "Imported",
            source = AwgWarpProfileSource.IMPORTED,
            createdAtMs = 1L,
            localPublicKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
        )

        assertNull(reusableConsumerWarpRegistration(metadata, details))
    }

    @Test
    fun consumerProfileWithoutLocalPublicKeyIsNotReusable() {
        val metadata = AwgWarpProfileMetadata(
            id = "00000000-0000-0000-0000-000000000003",
            name = "WARP",
            source = AwgWarpProfileSource.CONSUMER_WARP,
            createdAtMs = 1L,
            localPublicKey = null,
        )

        assertNull(reusableConsumerWarpRegistration(metadata, details))
    }
}
