package com.amurcanov.tgwsproxy.worker

import com.amurcanov.tgwsproxy.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerTransportCompatibilityTest {
    @Test
    fun oldWorkersDefaultToChunkRelay() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(
            "worker_pool_workers_v1",
            "id=old;name=Worker;url=old.workers.dev;enabled=1;createdAt=1;updatedAt=1",
        ).commit()
        val persistence = SharedPreferencesWorkerPoolPersistence(prefs)
        assertEquals(WorkerTransport.CHUNK_RELAY, persistence.loadWorkers().single().transport)
    }

    @Test
    fun transportSurvivesPersistenceAndSelectionEncoding() {
        val prefs = InMemorySharedPreferences()
        val persistence = SharedPreferencesWorkerPoolPersistence(prefs)
        val first = WorkerEndpoint.create("WS", "ws.workers.dev", transport = WorkerTransport.LEGACY_WEBSOCKET)
        val second = WorkerEndpoint.create("Relay", "relay.workers.dev")
        persistence.saveWorkers(listOf(first, second))
        val restored = persistence.loadWorkers()
        assertEquals(WorkerTransport.LEGACY_WEBSOCKET, restored.first().transport)
        assertEquals(WorkerTransport.CHUNK_RELAY, restored.last().transport)
        val candidates = restored.map {
            WorkerFailoverCandidate(
                workerId = it.id, workerName = it.name, url = it.url,
                domain = it.normalizedDomain(), state = it.state, enabled = it.enabled,
                priority = it.priority, latencyMs = null, failureCount = 0,
                lastSuccessAt = null, lastFailureAt = null,
                isSelectedWorker = false, isBackoffActive = false, transport = it.transport,
            )
        }
        val encoded = WorkerFailoverPayload(
            enabled = true, selectedWorkerId = first.id, candidates = candidates,
            maxAttempts = 2, primaryDomain = first.normalizedDomain(),
        ).encodeCandidatesToken()
        assertTrue(encoded.contains(":ws.workers.dev:legacy_websocket"))
        assertTrue(encoded.contains(":relay.workers.dev:chunk_relay"))
    }

    @Test
    fun protocolParserDoesNotInterpretUnknownAsLegacy() {
        assertEquals(WorkerTransport.CHUNK_RELAY, WorkerTransport.fromPref(null))
        assertEquals(WorkerTransport.CHUNK_RELAY, WorkerTransport.fromPref("unknown"))
        assertEquals(WorkerTransport.LEGACY_WEBSOCKET, WorkerTransport.fromPref("legacy_websocket"))
        assertFalse(WorkerTransport.fromPref("unknown") == WorkerTransport.LEGACY_WEBSOCKET)
    }
}
