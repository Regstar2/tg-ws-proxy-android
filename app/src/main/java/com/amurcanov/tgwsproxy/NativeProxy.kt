package com.amurcanov.tgwsproxy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.json.JSONObject

interface ProxyLibrary : Library {
    companion object {
        val INSTANCE = Native.load("tgwsproxy", ProxyLibrary::class.java) as ProxyLibrary
    }
    
    fun StartProxy(host: String, port: Int, dcIps: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun GetStats(): Pointer?
    fun ResetCFDomainCooldowns()
    fun SetManualCFDomains(domains: String)
    fun SetCachedCFDomains(domains: String)
    fun GetAdaptiveRouteStats(): Pointer?
    fun GetProxyStatus(): Pointer?
    fun StartMtProtoProxy(host: String, port: Int, secret: String, runtimeConfig: String, verbose: Int): Int
    fun StopMtProtoProxy(): Int
    fun GetMtProtoProxyStatus(): Pointer?
    fun ConfigureAWGWarp(configPath: String, enabled: Int, preferred: Int, allowFallback: Int): Int
    fun ResetAWGWarp(): Int
    fun GenerateWireGuardKeyPair(): Pointer?
    fun ValidateAWGWarpConfig(configPath: String): Int
    fun ProbeAWGWarpConfig(configPath: String, target: String, timeoutMillis: Long): Pointer?
    fun ProbeConsumerWARPAPIWithAWG(configPath: String, timeoutMillis: Long): Pointer?
    fun RegisterConsumerWARPWithAWG(configPath: String, publicKey: String, timeoutMillis: Long): Pointer?
    fun ActivateConsumerWARPWithAWG(
        configPath: String,
        registrationId: String,
        token: String,
        timeoutMillis: Long,
    ): Pointer?
    fun ResetAdaptiveRouteStats(all: Int)
    fun ResetAdaptiveNetworkRouteStats(profileId: String)
    fun FreeString(p: Pointer)
}

data class WireGuardKeyPair(
    val privateKey: String,
    val publicKey: String,
)

data class AwgWarpProbeResult(
    val ok: Boolean,
    val code: String,
    val endpoint: String?,
    val lastHandshakeUnix: Long,
    val tunnelTxBytes: Long,
    val tunnelRxBytes: Long,
)

internal data class AwgWarpBootstrapHttpResult(
    val ok: Boolean,
    val code: String,
    val status: Int,
    val body: String?,
    val requestSent: Boolean,
)

object NativeProxy {
    fun startProxy(host: String, port: Int, dcIps: String, verbose: Int): Int {
        return ProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, verbose)
    }
    fun stopProxy(): Int {
        return ProxyLibrary.INSTANCE.StopProxy()
    }
    fun setPoolSize(size: Int) {
        ProxyLibrary.INSTANCE.SetPoolSize(size)
    }
    fun getStats(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetStats() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }
    fun resetCfDomainCooldowns() {
        ProxyLibrary.INSTANCE.ResetCFDomainCooldowns()
    }
    fun setManualCfDomains(domains: List<String>) {
        val payload = domains.mapNotNull(CfDomain::normalizeOrNull).distinct().joinToString("|")
        ProxyLibrary.INSTANCE.SetManualCFDomains(payload)
    }
    fun setCachedCfDomains(domains: List<String>) {
        val payload = domains.mapNotNull(CfDomain::normalizeOrNull).distinct().joinToString("|")
        ProxyLibrary.INSTANCE.SetCachedCFDomains(payload)
    }
    fun getProxyStatus(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetProxyStatus() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }
    fun startMtProtoProxy(
        host: String,
        port: Int,
        secret: String,
        runtimeConfig: String,
        verbose: Int,
    ): Int {
        return ProxyLibrary.INSTANCE.StartMtProtoProxy(host, port, secret, runtimeConfig, verbose)
    }
    fun stopMtProtoProxy(): Int {
        return ProxyLibrary.INSTANCE.StopMtProtoProxy()
    }
    fun getMtProtoProxyStatus(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetMtProtoProxyStatus() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }
    fun configureAwgWarp(
        configPath: String,
        enabled: Boolean,
        preferred: Boolean,
        allowFallback: Boolean,
    ): Int {
        return ProxyLibrary.INSTANCE.ConfigureAWGWarp(
            configPath,
            if (enabled) 1 else 0,
            if (preferred) 1 else 0,
            if (allowFallback) 1 else 0,
        )
    }
    fun resetAwgWarp(): Int {
        return ProxyLibrary.INSTANCE.ResetAWGWarp()
    }

    fun generateWireGuardKeyPair(): Result<WireGuardKeyPair> = runCatching {
        val ptr = ProxyLibrary.INSTANCE.GenerateWireGuardKeyPair()
            ?: error("native key generation returned no result")
        val payload = try {
            ptr.getString(0)
        } finally {
            ProxyLibrary.INSTANCE.FreeString(ptr)
        }
        val json = JSONObject(payload)
        if (!json.optBoolean("ok", false)) {
            error(json.optString("code", "key_generation_failed"))
        }
        val privateKey = json.getString("private_key")
        val publicKey = json.getString("public_key")
        require(privateKey.isNotBlank() && publicKey.isNotBlank()) {
            "native key generation returned an incomplete key pair"
        }
        WireGuardKeyPair(privateKey = privateKey, publicKey = publicKey)
    }

    fun validateAwgWarpConfig(configPath: String): Boolean {
        if (configPath.isBlank()) return false
        return ProxyLibrary.INSTANCE.ValidateAWGWarpConfig(configPath) == 0
    }

    fun probeAwgWarpConfig(
        configPath: String,
        target: String,
        timeoutMillis: Long = 12_000L,
    ): AwgWarpProbeResult {
        if (configPath.isBlank()) {
            return AwgWarpProbeResult(false, "config_path_empty", null, 0L, 0L, 0L)
        }
        val ptr = ProxyLibrary.INSTANCE.ProbeAWGWarpConfig(configPath, target, timeoutMillis)
            ?: return AwgWarpProbeResult(false, "native_probe_empty", null, 0L, 0L, 0L)
        val payload = try {
            ptr.getString(0)
        } finally {
            ProxyLibrary.INSTANCE.FreeString(ptr)
        }
        return runCatching {
            val json = JSONObject(payload)
            AwgWarpProbeResult(
                ok = json.optBoolean("ok", false),
                code = json.optString("code", "unknown"),
                endpoint = json.optString("endpoint").takeIf { it.isNotBlank() },
                lastHandshakeUnix = json.optLong("last_handshake_unix", 0L),
                tunnelTxBytes = json.optLong("tunnel_tx_bytes", 0L),
                tunnelRxBytes = json.optLong("tunnel_rx_bytes", 0L),
            )
        }.getOrElse {
            AwgWarpProbeResult(false, "native_probe_parse_failed", null, 0L, 0L, 0L)
        }
    }

    internal fun probeConsumerWarpApiViaAwg(
        configPath: String,
        timeoutMillis: Long = 10_000L,
    ): AwgWarpBootstrapHttpResult {
        if (configPath.isBlank()) {
            return AwgWarpBootstrapHttpResult(false, "config_path_empty", 0, null, false)
        }
        return parseAwgWarpBootstrapHttpResult(
            ProxyLibrary.INSTANCE.ProbeConsumerWARPAPIWithAWG(configPath, timeoutMillis),
        )
    }

    internal fun registerConsumerWarpViaAwg(
        configPath: String,
        publicKey: String,
        timeoutMillis: Long = 20_000L,
    ): AwgWarpBootstrapHttpResult {
        if (configPath.isBlank() || publicKey.isBlank()) {
            return AwgWarpBootstrapHttpResult(false, "registration_invalid_input", 0, null, false)
        }
        return parseAwgWarpBootstrapHttpResult(
            ProxyLibrary.INSTANCE.RegisterConsumerWARPWithAWG(configPath, publicKey, timeoutMillis),
        )
    }

    internal fun activateConsumerWarpViaAwg(
        configPath: String,
        registrationId: String,
        token: String,
        timeoutMillis: Long = 20_000L,
    ): AwgWarpBootstrapHttpResult {
        if (configPath.isBlank() || registrationId.isBlank() || token.isBlank()) {
            return AwgWarpBootstrapHttpResult(false, "registration_invalid_input", 0, null, false)
        }
        return parseAwgWarpBootstrapHttpResult(
            ProxyLibrary.INSTANCE.ActivateConsumerWARPWithAWG(
                configPath,
                registrationId,
                token,
                timeoutMillis,
            ),
        )
    }

    private fun parseAwgWarpBootstrapHttpResult(ptr: Pointer?): AwgWarpBootstrapHttpResult {
        if (ptr == null) {
            return AwgWarpBootstrapHttpResult(false, "native_bootstrap_empty", 0, null, false)
        }
        val payload = try {
            ptr.getString(0)
        } finally {
            ProxyLibrary.INSTANCE.FreeString(ptr)
        }
        return runCatching {
            val json = JSONObject(payload)
            AwgWarpBootstrapHttpResult(
                ok = json.optBoolean("ok", false),
                code = json.optString("code", "unknown"),
                status = json.optInt("status", 0),
                body = json.optString("body").takeIf { it.isNotBlank() },
                requestSent = json.optBoolean("request_sent", false),
            )
        }.getOrElse {
            AwgWarpBootstrapHttpResult(false, "native_bootstrap_parse_failed", 0, null, false)
        }
    }

    fun getAdaptiveRouteStats(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetAdaptiveRouteStats() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }
    fun resetAdaptiveRouteStats(all: Boolean) {
        ProxyLibrary.INSTANCE.ResetAdaptiveRouteStats(if (all) 1 else 0)
    }
    fun resetAdaptiveNetworkRouteStats(profileId: String) {
        ProxyLibrary.INSTANCE.ResetAdaptiveNetworkRouteStats(profileId)
    }
}
