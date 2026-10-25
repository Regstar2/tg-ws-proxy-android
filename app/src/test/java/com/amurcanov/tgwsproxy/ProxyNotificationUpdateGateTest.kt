package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyNotificationUpdateGateTest {
    private val connected = ProxyNotificationSnapshot(
        title = "TgWsProxy",
        contentText = "Connected",
        expandedText = null,
        displayMode = NotificationDisplayMode.NORMAL,
        actionsEnabled = true,
        proxyRunning = true,
    )

    @Test
    fun unchangedNotificationIsNeverRepublishedEvenWhenForced() {
        val gate = ProxyNotificationUpdateGate()
        gate.onForegroundStarted(connected, wallTimeMillis = 1_000L, elapsedTimeMillis = 500L)

        assertFalse(gate.shouldPublish(connected, elapsedTimeMillis = 5_000L))
        assertFalse(gate.shouldPublish(connected, elapsedTimeMillis = 5_000L, force = true))
        assertEquals(1_000L, gate.startedAtMillis)
    }

    @Test
    fun metricsChangeIsThrottledWithoutDroppingLaterUpdate() {
        val gate = ProxyNotificationUpdateGate()
        gate.onForegroundStarted(connected, wallTimeMillis = 1_000L, elapsedTimeMillis = 500L)
        val changedSpeed = connected.copy(contentText = "Connected · 12 KB/s")

        assertFalse(gate.shouldPublish(changedSpeed, elapsedTimeMillis = 2_500L))
        assertTrue(gate.shouldPublish(changedSpeed, elapsedTimeMillis = 3_500L))
        gate.onPublished(changedSpeed, elapsedTimeMillis = 3_500L)
        assertFalse(gate.shouldPublish(changedSpeed, elapsedTimeMillis = 8_000L))
        assertEquals(1_000L, gate.startedAtMillis)
    }

    @Test
    fun statusChangeCanBypassThrottleButNotDuplicateContent() {
        val gate = ProxyNotificationUpdateGate()
        gate.onForegroundStarted(connected, wallTimeMillis = 1_000L, elapsedTimeMillis = 500L)
        val reconnecting = connected.copy(contentText = "Reconnecting")

        assertTrue(gate.shouldPublish(reconnecting, elapsedTimeMillis = 700L, force = true))
        gate.onPublished(reconnecting, elapsedTimeMillis = 700L)
        assertFalse(gate.shouldPublish(reconnecting, elapsedTimeMillis = 750L, force = true))
        assertTrue(gate.shouldPublish(connected, elapsedTimeMillis = 900L, force = true))
    }

    @Test
    fun unchangedVisibleTextDoesNotPublishWhenMetricsAreDisabled() {
        val gate = ProxyNotificationUpdateGate()
        gate.onForegroundStarted(connected, wallTimeMillis = 1_000L, elapsedTimeMillis = 500L)

        // Runtime metrics are intentionally absent from the notification snapshot.
        assertFalse(gate.shouldPublish(connected.copy(), elapsedTimeMillis = 5_000L))
        assertTrue(
            gate.shouldPublish(
                connected.copy(actionsEnabled = false),
                elapsedTimeMillis = 5_000L,
            ),
        )
    }

    @Test
    fun displayModeAndExpandedTextArePartOfPublishedContent() {
        val gate = ProxyNotificationUpdateGate()
        gate.onForegroundStarted(connected, wallTimeMillis = 1_000L, elapsedTimeMillis = 500L)

        assertTrue(
            gate.shouldPublish(
                connected.copy(displayMode = NotificationDisplayMode.MINIMAL),
                elapsedTimeMillis = 5_000L,
            ),
        )
        assertTrue(
            gate.shouldPublish(
                connected.copy(expandedText = "Connections: 3"),
                elapsedTimeMillis = 5_000L,
            ),
        )
    }

    @Test
    fun stoppingClearsSessionAndNextStartUsesNewTimestamp() {
        val gate = ProxyNotificationUpdateGate()
        gate.onForegroundStarted(connected, wallTimeMillis = 1_000L, elapsedTimeMillis = 500L)
        gate.onForegroundStopped()

        assertNull(gate.startedAtMillis)
        assertFalse(gate.shouldPublish(connected.copy(contentText = "Changed"), 5_000L))

        gate.onForegroundStarted(connected, wallTimeMillis = 10_000L, elapsedTimeMillis = 9_000L)
        assertEquals(10_000L, gate.startedAtMillis)
        assertFalse(gate.shouldPublish(connected, elapsedTimeMillis = 9_100L))
    }
}
