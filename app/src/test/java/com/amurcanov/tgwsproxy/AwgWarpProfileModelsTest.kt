package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgWarpProfileModelsTest {
    @Test
    fun generatedProfileRoundTripsThroughParser() {
        val profile = WarpProvisionedProfile(
            privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            publicKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
            assignedIpv4 = "172.16.0.2",
            assignedIpv6 = "2606:4700:110:1234::2",
            peerPublicKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            endpoint = "engage.cloudflareclient.com:2408",
        )

        val text = AwgWarpProfileSerializer.serialize(profile)
        val parsed = AwgWarpConfigParser.parse(text).getOrThrow()

        assertEquals(listOf("172.16.0.2/32", "2606:4700:110:1234::2/128"), parsed.addresses)
        assertEquals(1280, parsed.mtu)
        assertEquals("engage.cloudflareclient.com:2408", parsed.peer.endpoint)
        assertEquals(listOf("0.0.0.0/0", "::/0"), parsed.peer.allowedIps)
        assertEquals("4", parsed.deviceOptions["Jc"])
        assertEquals("1", parsed.deviceOptions["H1"])
        assertEquals("4", parsed.deviceOptions["H4"])
        assertEquals("0", parsed.deviceOptions["S4"])
    }

    @Test
    fun compatibilityPresetKeepsWireGuardMessageHeaders() {
        assertEquals("1", AwgWarpCompatibilityPreset.options["H1"])
        assertEquals("2", AwgWarpCompatibilityPreset.options["H2"])
        assertEquals("3", AwgWarpCompatibilityPreset.options["H3"])
        assertEquals("4", AwgWarpCompatibilityPreset.options["H4"])
        assertEquals("0", AwgWarpCompatibilityPreset.options["S1"])
        assertEquals("0", AwgWarpCompatibilityPreset.options["S4"])
    }

    @Test
    fun endpointCandidatesKeepRegistrationEndpointFirstAndStayBounded() {
        val candidates = AwgWarpEndpointCandidates.forRegistration(" 162.159.192.1:2408 ")

        assertEquals("162.159.192.1:2408", candidates.first())
        assertTrue(candidates.contains("188.114.98.1:7559"))
        assertTrue(candidates.size <= 4)
        assertEquals(candidates.size, candidates.distinctBy { it.lowercase() }.size)
    }

    @Test
    fun endpointCandidatesDoNotDuplicateRegistrationFallback() {
        val candidates = AwgWarpEndpointCandidates.forRegistration("188.114.98.1:7559")

        assertEquals("188.114.98.1:7559", candidates.first())
        assertEquals(1, candidates.count { it.equals("188.114.98.1:7559", ignoreCase = true) })
    }

    @Test
    fun parserRejectsInvalidPrivateKeyWithoutEchoingIt() {
        val secret = "not-a-real-private-key"
        val text = """
            [Interface]
            PrivateKey = $secret
            Address = 172.16.0.2/32
            [Peer]
            PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
            Endpoint = engage.cloudflareclient.com:2408
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val failure = AwgWarpConfigParser.parse(text).exceptionOrNull()
        assertTrue(failure != null)
        assertFalse(failure?.message.orEmpty().contains(secret))
    }
}