package com.amurcanov.tgwsproxy

import java.util.Base64
import java.util.Random

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

internal object AwgWarpProfileNames {
    fun nextGeneratedIndex(prefix: String, existingNames: Iterable<String>): Int {
        val normalizedPrefix = prefix.trim()
        require(normalizedPrefix.isNotBlank()) { "profile_name_prefix_empty" }
        val pattern = Regex(
            "^" + Regex.escape(normalizedPrefix) + "\\s+(\\d+)$",
            RegexOption.IGNORE_CASE,
        )
        val highest = existingNames
            .mapNotNull { name -> pattern.matchEntire(name.trim())?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
            ?: 0
        return highest + 1
    }
}

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
     * Seed the bounded autotuner with the conservative WARP/AWG shape used by
     * warpscout. The final generated profile is not forced to this preset: the
     * provisioning search may select another bounded candidate for the network.
     */
    const val DEFAULT_I1 =
        "<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>"

    val options: Map<String, String> = linkedMapOf(
        "Jc" to "6",
        "Jmin" to "10",
        "Jmax" to "50",
        "I1" to DEFAULT_I1,
    )
}

data class AwgWarpTransportCandidate(
    val id: String,
    val deviceOptions: Map<String, String>,
)

object AwgWarpTransportCandidates {
    private const val RANDOM_CANDIDATE_COUNT = 3
    private const val MAX_CANDIDATES = 9

    /**
     * Produce a small, reproducible search space. The public key is random per
     * registration, so each new registration samples a different bounded subset
     * while repeated evaluation of the same registration stays deterministic.
     */
    fun forProvisioning(seedMaterial: String): List<AwgWarpTransportCandidate> {
        val candidates = mutableListOf(
            candidate("warpscout-default", 6, 10, 50, includeI1 = true),
            candidate("warpscout-no-i1", 6, 10, 50, includeI1 = false),
            candidate("compact", 4, 10, 40, includeI1 = true),
            candidate("balanced", 5, 20, 70, includeI1 = true),
            candidate("wide", 6, 30, 110, includeI1 = true),
            candidate("legacy-junk", 4, 40, 70, includeI1 = false),
        )

        val random = Random(stableSeed(seedMaterial))
        repeat(RANDOM_CANDIDATE_COUNT) { index ->
            val jc = 4 + random.nextInt(3)
            val jmin = 10 + random.nextInt(41)
            val span = 20 + random.nextInt(81)
            val jmax = (jmin + span).coerceAtMost(150)
            candidates += candidate(
                id = "sample-${index + 1}",
                jc = jc,
                jmin = jmin,
                jmax = jmax,
                includeI1 = random.nextBoolean(),
            )
        }

        return candidates
            .distinctBy { candidate -> candidate.deviceOptions.entries.joinToString("|") { "${it.key}=${it.value}" } }
            .take(MAX_CANDIDATES)
    }

    private fun candidate(
        id: String,
        jc: Int,
        jmin: Int,
        jmax: Int,
        includeI1: Boolean,
    ): AwgWarpTransportCandidate {
        require(jc in 1..128)
        require(jmin >= 0 && jmax >= jmin)
        return AwgWarpTransportCandidate(
            id = id,
            deviceOptions = linkedMapOf<String, String>().apply {
                put("Jc", jc.toString())
                put("Jmin", jmin.toString())
                put("Jmax", jmax.toString())
                if (includeI1) put("I1", AwgWarpCompatibilityPreset.DEFAULT_I1)
            },
        )
    }

    private fun stableSeed(value: String): Long {
        var hash = 1_469_598_103_934_665_603L
        value.forEach { char ->
            hash = (hash xor char.code.toLong()) * 1_099_511_628_211L
        }
        return hash
    }
}

data class AwgWarpTuneCandidate(
    val endpoint: String,
    val transport: AwgWarpTransportCandidate,
)

object AwgWarpTuneCandidates {
    const val MAX_ATTEMPTS = 16

    /**
     * Wave 1 checks endpoint diversity with the strongest seed. Wave 2 tunes the
     * transport on the registration endpoint. Wave 3 combines alternate endpoints
     * with a bounded subset of the remaining transport candidates.
     */
    fun forProvisioning(registrationEndpoint: String, seedMaterial: String): List<AwgWarpTuneCandidate> {
        val endpoints = AwgWarpEndpointCandidates.forRegistration(registrationEndpoint)
        val transports = AwgWarpTransportCandidates.forProvisioning(seedMaterial)
        if (endpoints.isEmpty() || transports.isEmpty()) return emptyList()

        val result = mutableListOf<AwgWarpTuneCandidate>()
        val seen = mutableSetOf<String>()
        fun add(endpoint: String, transport: AwgWarpTransportCandidate) {
            if (result.size >= MAX_ATTEMPTS) return
            val key = "$endpoint|${transport.deviceOptions.entries.joinToString("|") { "${it.key}=${it.value}" }}"
            if (seen.add(key)) result += AwgWarpTuneCandidate(endpoint, transport)
        }

        val baseline = transports.first()
        endpoints.forEach { endpoint -> add(endpoint, baseline) }

        val primaryEndpoint = endpoints.first()
        transports.drop(1).forEach { transport -> add(primaryEndpoint, transport) }

        val alternates = endpoints.drop(1)
        var offset = 0
        while (result.size < MAX_ATTEMPTS && alternates.isNotEmpty() && transports.size > 1) {
            var added = false
            alternates.forEachIndexed { endpointIndex, endpoint ->
                if (result.size >= MAX_ATTEMPTS) return@forEachIndexed
                val transportIndex = 1 + ((offset + endpointIndex) % (transports.size - 1))
                val before = result.size
                add(endpoint, transports[transportIndex])
                if (result.size > before) added = true
            }
            if (!added) break
            offset++
        }

        return result
    }
}

object AwgWarpEndpointCandidates {
    /**
     * Consumer registration returns one usable WARP endpoint, but a single endpoint
     * can be degraded on a particular network. Keep that provider endpoint first,
     * then try a very small deterministic set from Cloudflare's public WARP pools.
     * The list is intentionally bounded: this is provisioning validation, not a
     * background endpoint scanner.
     */
    private val fallbackEndpoints = listOf(
        "188.114.98.1:7559",
        "188.114.98.1:2408",
        "162.159.195.1:2408",
    )

    fun forRegistration(registrationEndpoint: String): List<String> {
        return buildList {
            registrationEndpoint.trim().takeIf(String::isNotBlank)?.let(::add)
            addAll(fallbackEndpoints)
        }.distinctBy { it.lowercase() }
    }
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
