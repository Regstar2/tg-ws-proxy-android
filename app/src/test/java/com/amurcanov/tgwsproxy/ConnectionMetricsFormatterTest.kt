package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionMetricsFormatterTest {
    @Test
    fun formatSpeed_zeroIsEmpty() {
        assertEquals("", ConnectionMetricsFormatter.formatSpeed(0.0))
    }

    @Test
    fun formatSpeed_bytesAndKilobytes() {
        assertEquals("512 B/s", ConnectionMetricsFormatter.formatSpeed(512.0))
        assertTrue(ConnectionMetricsFormatter.formatSpeed(1536.0).contains("KB/s"))
    }

    @Test
    fun formatSpeed_megabytes() {
        assertTrue(ConnectionMetricsFormatter.formatSpeed(1048576.0).contains("MB/s"))
    }

    @Test
    fun formatLatency_unknownAndValue() {
        assertEquals("—", ConnectionMetricsFormatter.formatLatency(0))
        assertEquals("210 ms", ConnectionMetricsFormatter.formatLatency(210))
    }

    @Test
    fun formatSpeedPair_idleWhenNoTraffic() {
        assertEquals("Idle", ConnectionMetricsFormatter.formatSpeedPair(0.0, 0.0, "Idle"))
    }
}
