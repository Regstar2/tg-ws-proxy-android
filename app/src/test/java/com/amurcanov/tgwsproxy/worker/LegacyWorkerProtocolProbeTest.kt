package com.amurcanov.tgwsproxy.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyWorkerProtocolProbeTest {
    private val key = "dGhlIHNhbXBsZSBub25jZQ=="
    private val accept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="

    @Test
    fun validRfc6455HandshakeIsRecognized() {
        assertTrue(LegacyWorkerProtocolProbe.verifyUpgrade(listOf(
            "HTTP/1.1 101 Switching Protocols",
            "Upgrade: websocket",
            "Connection: keep-alive, Upgrade",
            "Sec-WebSocket-Accept: $accept",
        ), key))
    }

    @Test
    fun wrongStatusOrMissingAcceptCannotProduceFalseSuccess() {
        assertFalse(LegacyWorkerProtocolProbe.verifyUpgrade(listOf(
            "HTTP/1.1 404 Not Found",
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Accept: $accept",
        ), key))
        assertFalse(LegacyWorkerProtocolProbe.verifyUpgrade(listOf(
            "HTTP/1.1 101 Switching Protocols",
            "Upgrade: websocket",
            "Connection: Upgrade",
        ), key))
        assertFalse(LegacyWorkerProtocolProbe.verifyUpgrade(listOf(
            "HTTP/1.1 101 Switching Protocols",
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Accept: incorrect",
        ), key))
    }
}
