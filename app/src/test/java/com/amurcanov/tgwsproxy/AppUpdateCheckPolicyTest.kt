package com.amurcanov.tgwsproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckPolicyTest {
    @Test
    fun reusesRecentSuccessfulResult() {
        val now = 1_000_000_000L
        assertTrue(AppUpdateCheckPolicy.isFresh(now - 60_000L, now))
        assertTrue(AppUpdateCheckPolicy.isFresh(now - AppUpdateCheckPolicy.CACHE_TTL_MS + 1, now))
    }

    @Test
    fun expiresOldResultsAndRejectsFutureTimestamps() {
        val now = 1_000_000_000L
        assertFalse(AppUpdateCheckPolicy.isFresh(now - AppUpdateCheckPolicy.CACHE_TTL_MS, now))
        assertFalse(AppUpdateCheckPolicy.isFresh(now + 1, now))
        assertFalse(AppUpdateCheckPolicy.isFresh(-1L, now))
    }

    @Test
    fun retriesFailedCheckAfterShortBackoff() {
        val now = 1_000_000_000L
        assertTrue(AppUpdateCheckPolicy.isFresh(
            now - AppUpdateCheckPolicy.ERROR_BACKOFF_MS + 1,
            now,
            AppUpdateCheckPolicy.ERROR_BACKOFF_MS,
        ))
        assertFalse(AppUpdateCheckPolicy.isFresh(
            now - AppUpdateCheckPolicy.ERROR_BACKOFF_MS,
            now,
            AppUpdateCheckPolicy.ERROR_BACKOFF_MS,
        ))
    }
}
