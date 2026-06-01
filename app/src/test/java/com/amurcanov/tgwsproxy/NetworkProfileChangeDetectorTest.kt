package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProfileChangeDetectorTest {
    @Test
    fun firstProfileDoesNotNotify() {
        val detector = NetworkProfileChangeDetector()

        assertFalse(detector.shouldNotify(profile("wifi-a", NetworkProfileType.WIFI)))
    }

    @Test
    fun sameProfileDoesNotNotify() {
        val detector = NetworkProfileChangeDetector(profile("wifi-a", NetworkProfileType.WIFI))

        assertFalse(detector.shouldNotify(profile("wifi-a", NetworkProfileType.WIFI)))
    }

    @Test
    fun changedTypeNotifies() {
        val detector = NetworkProfileChangeDetector(profile("same", NetworkProfileType.WIFI))

        assertTrue(detector.shouldNotify(profile("same", NetworkProfileType.MOBILE)))
    }

    @Test
    fun changedIdNotifies() {
        val detector = NetworkProfileChangeDetector(profile("wifi-a", NetworkProfileType.WIFI))

        assertTrue(detector.shouldNotify(profile("wifi-b", NetworkProfileType.WIFI)))
    }

    @Test
    fun changedLabelOnlyDoesNotNotify() {
        val detector = NetworkProfileChangeDetector(NetworkProfile("wifi-a", NetworkProfileType.WIFI, "old"))

        assertFalse(detector.shouldNotify(NetworkProfile("wifi-a", NetworkProfileType.WIFI, "new")))
    }

    private fun profile(id: String, type: NetworkProfileType): NetworkProfile {
        return NetworkProfile(id = id, type = type, label = type.prefValue)
    }
}
