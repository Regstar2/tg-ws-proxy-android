package com.amurcanov.tgwsproxy.cloudflare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflarePkceTest {
    @Test
    fun rfc7636ChallengeMatchesKnownVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            CloudflarePkce.challenge(verifier),
        )
    }

    @Test
    fun generatedVerifierIsUrlSafeAndUnpadded() {
        val pair = CloudflarePkce.create()

        assertTrue(pair.verifier.length in 43..128)
        assertTrue(pair.verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(pair.challenge.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(pair.verifier.contains('='))
        assertFalse(pair.challenge.contains('='))
    }

    @Test
    fun workerNameNormalizationIsDeterministic() {
        assertEquals("my-worker-1", CloudflareWorkerName.normalize(" My Worker #1 "))
        assertTrue(CloudflareWorkerName.isValid("my-worker-1"))
        assertFalse(CloudflareWorkerName.isValid("My Worker #1"))
    }
}
