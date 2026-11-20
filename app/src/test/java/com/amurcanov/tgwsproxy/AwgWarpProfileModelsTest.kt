package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgWarpProfileModelsTest {
    @Test
    fun generatedProfileNamesStartAtOneAndAdvancePastHighestExistingIndex() {
        assertEquals(1, AwgWarpProfileNames.nextGeneratedIndex("WARP", emptyList()))
        assertEquals(
            4,
            AwgWarpProfileNames.nextGeneratedIndex(
                "WARP",
                listOf("WARP 1", "Home", "warp 3", "WARP invalid"),
            ),
        )
    }

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
        assertEquals("6", parsed.deviceOptions["Jc"])
        assertEquals("10", parsed.deviceOptions["Jmin"])
        assertEquals("50", parsed.deviceOptions["Jmax"])
        assertTrue(parsed.deviceOptions["I1"].orEmpty().startsWith("<r 2><b 0x"))
        assertNull(parsed.deviceOptions["H1"])
        assertNull(parsed.deviceOptions["S1"])
    }

    @Test
    fun compatibilityPresetMatchesWarpscoutShape() {
        assertEquals(setOf("Jc", "Jmin", "Jmax", "I1"), AwgWarpCompatibilityPreset.options.keys)
        assertEquals("6", AwgWarpCompatibilityPreset.options["Jc"])
        assertEquals("10", AwgWarpCompatibilityPreset.options["Jmin"])
        assertEquals("50", AwgWarpCompatibilityPreset.options["Jmax"])
        assertTrue(AwgWarpCompatibilityPreset.options["I1"].orEmpty().contains("69636c6f756403636f6d"))
    }

    @Test
    fun transportCandidatesAreBoundedDeterministicAndDiverse() {
        val first = AwgWarpTransportCandidates.forProvisioning("seed-public-key")
        val second = AwgWarpTransportCandidates.forProvisioning("seed-public-key")

        assertEquals(first, second)
        assertTrue(first.size in 6..9)
        assertEquals("warpscout-default", first.first().id)
        assertTrue(first.any { "I1" !in it.deviceOptions })
        assertTrue(first.map { it.deviceOptions }.distinct().size > 4)

        first.forEach { candidate ->
            val jc = candidate.deviceOptions.getValue("Jc").toInt()
            val jmin = candidate.deviceOptions.getValue("Jmin").toInt()
            val jmax = candidate.deviceOptions.getValue("Jmax").toInt()
            assertTrue(jc in 1..128)
            assertTrue(jmin >= 0)
            assertTrue(jmax >= jmin)
            assertTrue(jmax <= 150)
        }
    }

    @Test
    fun transportCandidateSamplingChangesAcrossRegistrations() {
        val first = AwgWarpTransportCandidates.forProvisioning("registration-a")
        val second = AwgWarpTransportCandidates.forProvisioning("registration-b")

        assertTrue(first.map { it.deviceOptions } != second.map { it.deviceOptions })
    }

    @Test
    fun tunePlanIsBoundedAndStartsWithEndpointDiversity() {
        val endpoints = AwgWarpEndpointCandidates.forRegistration("engage.cloudflareclient.com:2408")
        val plan = AwgWarpTuneCandidates.forProvisioning(
            registrationEndpoint = "engage.cloudflareclient.com:2408",
            seedMaterial = "seed-public-key",
        )

        assertTrue(plan.isNotEmpty())
        assertTrue(plan.size <= AwgWarpTuneCandidates.MAX_ATTEMPTS)
        assertEquals(endpoints, plan.take(endpoints.size).map { it.endpoint })
        assertTrue(plan.take(endpoints.size).all { it.transport.id == "warpscout-default" })
        assertTrue(plan.drop(endpoints.size).any { it.transport.id != "warpscout-default" })
        assertEquals(
            plan.size,
            plan.distinctBy { it.endpoint to it.transport.deviceOptions }.size,
        )
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

    @Test
    fun parserAcceptsAwg31ConfigurationOptionsAndHeaderProtectionKey() {
        val headerKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
        val parsed = AwgWarpConfigParser.parse(awg31Config(headerKey)).getOrThrow()

        assertEquals(headerKey, parsed.deviceOptions["HeaderProtectionKey"])
        assertEquals("27-97", parsed.deviceOptions["ContentPaddingAddition"])
        assertEquals("116-131", parsed.deviceOptions["RekeyAfterTime"])
        assertEquals("6-9", parsed.deviceOptions["RekeyTimeout"])
        assertEquals("171-196", parsed.deviceOptions["RejectAfterTime"])
        assertEquals("9-16", parsed.deviceOptions["KeepaliveTimeout"])
        assertEquals("18-26", parsed.deviceOptions["MaxHandshakeAttempts"])
        assertEquals("on", parsed.deviceOptions["RandomTrailers"])
        assertEquals("0", parsed.deviceOptions["DisableCookies"])
    }

    @Test
    fun parserRejectsHeaderProtectionKeyUnlessEveryPaddingIsAtLeastTwelve() {
        for (key in listOf("S1", "S2", "S3", "S4")) {
            val config = awg31Config().replace("$key = 12", "$key = 11")
            assertTrue("$key below 12 must fail", AwgWarpConfigParser.parse(config).isFailure)
        }
    }

    @Test
    fun parserRejectsMalformedAwg31RangesBooleansAndHeaderKey() {
        val invalidConfigs = listOf(
            awg31Config().replace("ContentPaddingAddition = 27-97", "ContentPaddingAddition = 97-27"),
            awg31Config().replace("RekeyTimeout = 6-9", "RekeyTimeout = 1-2-3"),
            awg31Config().replace("RejectAfterTime = 171-196", "RejectAfterTime = 4294967296"),
            awg31Config().replace("RandomTrailers = on", "RandomTrailers = maybe"),
            awg31Config(headerKey = "not-a-key"),
        )
        invalidConfigs.forEachIndexed { index, config ->
            assertTrue("invalid AWG 3.1 configuration $index must fail", AwgWarpConfigParser.parse(config).isFailure)
        }
    }

    private fun awg31Config(
        headerKey: String = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
    ): String = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 172.16.0.2/32
        S1 = 12
        S2 = 12
        S3 = 12
        S4 = 12
        H1 = 1-10
        H2 = 11-20
        H3 = 21-30
        H4 = 31-40
        HeaderProtectionKey = $headerKey
        ContentPaddingAddition = 27-97
        RekeyAfterTime = 116-131
        RekeyTimeout = 6-9
        RejectAfterTime = 171-196
        KeepaliveTimeout = 9-16
        MaxHandshakeAttempts = 18-26
        RandomTrailers = on
        DisableCookies = 0

        [Peer]
        PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        Endpoint = engage.cloudflareclient.com:2408
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

}
