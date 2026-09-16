package com.amurcanov.tgwsproxy

import java.util.Base64

enum class AwgWarpProfileSource(val wireValue: String) {
    CONSUMER_WARP("consumer_warp"),
    IMPORTED("imported");

    companion object {
        fun fromWireValue(value: String?): AwgWarpProfileSource {
            return entries.firstOrNull { it.wireValue == value } ?: IMPORTED
        }
    }
}

enum class AwgWarpProfileHealth(val wireValue: String) {
    NOT_CHECKED("not_checked"),
    WORKING("working"),
    CONFIG_ERROR("config_error"),
    NO_HANDSHAKE("no_handshake"),
    NETWORK_ERROR("network_error");

    companion object {
        fun fromWireValue(value: String?): AwgWarpProfileHealth {
            return entries.firstOrNull { it.wireValue == value } ?: NOT_CHECKED
        }
    }
}

data class AwgWarpProfileMetadata(
    val id: String,
    val name: String,
    val source: AwgWarpProfileSource,
    val createdAtMs: Long,
    val localPublicKey: String? = null,
    val health: AwgWarpProfileHealth = AwgWarpProfileHealth.NOT_CHECKED,
    val lastCheckedAtMs: Long? = null,
    val lastErrorCode: String? = null,
)

data class AwgWarpProfileSummary(
    val metadata: AwgWarpProfileMetadata,
    val selected: Boolean,
    val endpoint: String?,
)

data class AwgWarpPeerDetails(
    val publicKey: String,
    val endpoint: String,
    val allowedIps: List<String>,
    val persistentKeepalive: Int?,
)

data class AwgWarpConfigDetails(
    val privateKey: String,
    val addresses: List<String>,
    val mtu: Int,
    val deviceOptions: Map<String, String>,
    val peer: AwgWarpPeerDetails,
)

data class WarpProvisionRequest(
    val profileName: String,
    val deviceModel: String,
)

data class WarpProvisionedProfile(
    val privateKey: String,
    val publicKey: String,
    val assignedIpv4: String,
    val assignedIpv6: String,
    val peerPublicKey: String,
    val endpoint: String,
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val mtu: Int = 1280,
    val persistentKeepalive: Int = 25,
    val deviceOptions: Map<String, String> = AwgWarpCompatibilityPreset.options,
)

object AwgWarpCompatibilityPreset {
    /**
     * WARP is a WireGuard peer. Keep the real WireGuard packet shape intact while
     * only adding client-side junk packets. AmneziaWG treats omitted/zero values
     * as standard WireGuard behaviour; H1-H4 explicitly retain WG message types.
     */
    val options: Map<String, String> = linkedMapOf(
        "Jc" to "4",
        "Jmin" to "40",
        "Jmax" to "70",
        "S1" to "0",
        "S2" to "0",
        "S3" to "0",
        "S4" to "0",
        "H1" to "1",
        "H2" to "2",
        "H3" to "3",
        "H4" to "4",
    )
}

object AwgWarpProfileSerializer {
    fun serialize(profile: WarpProvisionedProfile): String {
        require(profile.assignedIpv4.isNotBlank() || profile.assignedIpv6.isNotBlank()) {
            "WARP profile has no assigned address"
        }
        require(profile.peerPublicKey.isNotBlank()) { "WARP profile has no peer public key" }
        require(profile.endpoint.isNotBlank()) { "WARP profile has no endpoint" }
        require(profile.allowedIps.isNotEmpty()) { "WARP profile has no AllowedIPs" }

        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${profile.privateKey}")
            val addresses = buildList {
                profile.assignedIpv4.takeIf { it.isNotBlank() }?.let { add(withPrefix(it, 32)) }
                profile.assignedIpv6.takeIf { it.isNotBlank() }?.let { add(withPrefix(it, 128)) }
            }
            appendLine("Address = ${addresses.joinToString(", ")}")
            appendLine("MTU = ${profile.mtu}")
            profile.deviceOptions.forEach { (key, value) ->
                if (value.isNotBlank()) appendLine("$key = $value")
            }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${profile.peerPublicKey}")
            appendLine("Endpoint = ${profile.endpoint}")
            appendLine("AllowedIPs = ${profile.allowedIps.joinToString(", ")}")
            if (profile.persistentKeepalive >= 0) {
                appendLine("PersistentKeepalive = ${profile.persistentKeepalive}")
            }
        }
    }

    private fun withPrefix(value: String, prefix: Int): String {
        val trimmed = value.trim()
        return if ('/' in trimmed) trimmed else "$trimmed/$prefix"
    }
}

object AwgWarpConfigParser {
    private val interfaceOptions = setOf(
        "jc", "jmin", "jmax",
        "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5",
    )

    fun parse(text: String): Result<AwgWarpConfigDetails> = runCatching {
        var section = ""
        var peerCount = 0
        var privateKey: String? = null
        val addresses = mutableListOf<String>()
        var mtu = 1280
        val options = linkedMapOf<String, String>()
        var peerPublicKey: String? = null
        var endpoint: String? = null
        val allowedIps = mutableListOf<String>()
        var keepalive: Int? = null

        text.lineSequence().forEachIndexed { index, raw ->
            val line = stripComment(raw).trim()
            if (line.isBlank()) return@forEachIndexed
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                when (section) {
                    "interface" -> Unit
                    "peer" -> {
                        peerCount++
                        require(peerCount == 1) { "Multiple Peer sections are not supported" }
                    }
                    else -> error("Unsupported section on line ${index + 1}")
                }
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            require(separator > 0) { "Invalid config line ${index + 1}" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(key.isNotBlank() && value.isNotBlank()) { "Empty config field on line ${index + 1}" }
            val normalized = key.lowercase()

            when (section) {
                "interface" -> when (normalized) {
                    "privatekey" -> {
                        validateKey(value)
                        require(privateKey == null) { "Duplicate PrivateKey" }
                        privateKey = value
                    }
                    "address" -> addresses += value.split(',').map(String::trim).filter(String::isNotBlank)
                    "mtu" -> {
                        mtu = value.toIntOrNull() ?: error("Invalid MTU")
                        require(mtu in 576..65535) { "Invalid MTU" }
                    }
                    "dns", "table" -> Unit
                    in interfaceOptions -> options[keyCanonical(normalized)] = value
                    else -> error("Unsupported Interface field")
                }
                "peer" -> when (normalized) {
                    "publickey" -> {
                        validateKey(value)
                        require(peerPublicKey == null) { "Duplicate PublicKey" }
                        peerPublicKey = value
                    }
                    "endpoint" -> endpoint = value
                    "allowedips" -> allowedIps += value.split(',').map(String::trim).filter(String::isNotBlank)
                    "persistentkeepalive" -> {
                        keepalive = value.toIntOrNull() ?: error("Invalid PersistentKeepalive")
                        require(keepalive in 0..65535) { "Invalid PersistentKeepalive" }
                    }
                    else -> error("Unsupported Peer field")
                }
                else -> error("Fields must be inside Interface or Peer section")
            }
        }

        val private = requireNotNull(privateKey) { "Missing Interface.PrivateKey" }
        require(addresses.isNotEmpty()) { "Missing Interface.Address" }
        val peerKey = requireNotNull(peerPublicKey) { "Missing Peer.PublicKey" }
        val peerEndpoint = requireNotNull(endpoint) { "Missing Peer.Endpoint" }
        require(peerEndpoint.isNotBlank()) { "Missing Peer.Endpoint" }
        require(allowedIps.isNotEmpty()) { "Missing Peer.AllowedIPs" }
        val jmin = options["Jmin"]?.toLongOrNull()
        val jmax = options["Jmax"]?.toLongOrNull()
        if (jmin != null && jmax != null) require(jmin <= jmax) { "Jmin exceeds Jmax" }

        AwgWarpConfigDetails(
            privateKey = private,
            addresses = addresses.toList(),
            mtu = mtu,
            deviceOptions = options.toMap(),
            peer = AwgWarpPeerDetails(
                publicKey = peerKey,
                endpoint = peerEndpoint,
                allowedIps = allowedIps.toList(),
                persistentKeepalive = keepalive,
            ),
        )
    }

    private fun validateKey(value: String) {
        val decoded = runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull()
        require(decoded?.size == 32) { "Invalid WireGuard key" }
    }

    private fun keyCanonical(normalized: String): String = when {
        normalized.startsWith("jc") -> if (normalized == "jc") "Jc" else normalized.replaceFirstChar(Char::uppercase)
        else -> normalized.replaceFirstChar(Char::uppercase)
    }

    private fun stripComment(line: String): String {
        for (i in line.indices) {
            val c = line[i]
            if ((c == '#' || c == ';') && (i == 0 || line[i - 1].isWhitespace())) {
                return line.substring(0, i)
            }
        }
        return line
    }
}
