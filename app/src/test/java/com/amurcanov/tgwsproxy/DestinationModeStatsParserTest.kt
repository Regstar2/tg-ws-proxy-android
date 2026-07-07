package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationModeStatsParserTest {
    @Test
    fun parse_extendedDestinationProfile() {
        val entries = DestinationModeStatsParser.parse(
            "PRESERVE_ORIGINAL_DST:destination_mode=PRESERVE_ORIGINAL_DST:original_parsed_dst=149.154.167.41:worker_dst=149.154.167.41:mapped_dc=2:is_media=0:flowseal_media_fix_applied=0:sessions_total=3:sessions_zero_down=1:sessions_bidirectional=2:sessions_with_down_bytes=2:zero_down_sessions=1:up_bytes_total=524288:down_bytes_total=1024:avg_duration_ms=900:media_fix_applied=0:close_reason=ws_read_EOF=2,client_read_EOF=1",
        )
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("PRESERVE_ORIGINAL_DST", entry.destinationMode)
        assertEquals("149.154.167.41", entry.originalParsedDst)
        assertEquals("149.154.167.41", entry.workerDst)
        assertEquals(2, entry.mappedDc)
        assertEquals(false, entry.isMedia)
        assertEquals(3, entry.sessionsTotal)
        assertEquals(1, entry.sessionsZeroDown)
        assertEquals(2, entry.sessionsBidirectional)
        assertEquals(524288, entry.upBytesTotal)
        assertEquals(1024, entry.downBytesTotal)
    }

    @Test
    fun parse_singleBucket() {
        val entries = DestinationModeStatsParser.parse(
            "PRESERVE_ORIGINAL_DST:sessions_total=5:sessions_with_down_bytes=3:zero_down_sessions=2:avg_duration_ms=1200:media_fix_applied=0:close_reason=ws_read_EOF=3,client_read_EOF=2",
        )
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("PRESERVE_ORIGINAL_DST", entry.destinationMode)
        assertEquals(5, entry.sessionsTotal)
        assertEquals(3, entry.sessionsWithDownBytes)
        assertEquals(2, entry.zeroDownSessions)
        assertEquals(1200, entry.avgDurationMs)
        assertEquals(0, entry.mediaFixApplied)
        assertEquals("ws_read_EOF=3,client_read_EOF=2", entry.closeReason)
    }

    @Test
    fun parse_multipleBuckets() {
        val entries = DestinationModeStatsParser.parse(
            "FLOWSEAL_DC_MAP:sessions_total=2:sessions_with_down_bytes=2:zero_down_sessions=0:avg_duration_ms=5000:media_fix_applied=0:close_reason=ws_read_EOF=2|" +
                "EXPERIMENTAL_FORCE_MEDIA_DC4:sessions_total=1:sessions_with_down_bytes=0:zero_down_sessions=1:avg_duration_ms=90:media_fix_applied=1:close_reason=ws_read_EOF=1",
        )
        assertEquals(2, entries.size)
        assertEquals("FLOWSEAL_DC_MAP", entries[0].destinationMode)
        assertEquals("EXPERIMENTAL_FORCE_MEDIA_DC4", entries[1].destinationMode)
        assertEquals(1, entries[1].mediaFixApplied)
    }
}
