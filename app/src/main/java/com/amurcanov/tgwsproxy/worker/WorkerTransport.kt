package com.amurcanov.tgwsproxy.worker

/**
 * Protocol deployed at a Telegram relay Worker. This is unrelated to the
 * standalone WARP/AWG provisioning Worker.
 *
 * Missing/unknown persisted values intentionally stay on Chunk Relay: upgrades
 * must never silently switch an existing user's Worker to Legacy WebSocket.
 */
enum class WorkerTransport(val prefValue: String) {
    CHUNK_RELAY("chunk_relay"),
    LEGACY_WEBSOCKET("legacy_websocket");

    companion object {
        fun fromPref(raw: String?): WorkerTransport =
            entries.firstOrNull { it.prefValue == raw?.trim()?.lowercase() } ?: CHUNK_RELAY
    }
}
