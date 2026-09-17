package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

interface WarpProfileProvisioner {
    suspend fun provision(request: WarpProvisionRequest): Result<WarpProvisionedProfile>
}

enum class WarpProvisioningStage {
    PREPARING_KEYS,
    REGISTERING_WARP,
    FETCHING_PARAMETERS,
    BUILDING_PROFILE,
    VALIDATING_CONFIG,
    CHECKING_CONNECTION,
    SAVING,
}

class WarpProvisioningException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)

class ConsumerWarpProfileProvisioner(
    private val bootstrapWorkerUrls: List<String> = emptyList(),
    private val onStage: (WarpProvisioningStage) -> Unit = {},
    private val onDiagnostic: (String, Map<String, String>) -> Unit = { _, _ -> },
) : WarpProfileProvisioner {
    companion object {
        // Consumer registration is intentionally isolated here because it is not a stable public API.
        // This profile follows the current direct WireGuard registration shape used by warpscout:
        // POST only the public key, then enable WARP on the returned registration.
        private const val API_BASE_URL = "https://api.cloudflareclient.com"
        private const val API_HOST = "api.cloudflareclient.com"
        private const val API_VERSION = "v0a4005"
        private const val REGISTER_PATH = "/$API_VERSION/reg"
        private const val BOOTSTRAP_PREFIX = "/warp-bootstrap"
        private const val BOOTSTRAP_REVISION = "warp-bootstrap-v1"
        private const val API_REACH_CALL_TIMEOUT_MS = 12_000L
        private const val API_REACH_CONNECT_TIMEOUT_MS = 3_000L
        private const val BOOTSTRAP_HEALTH_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_ATTEMPTS = 3
        private const val USER_AGENT = "okhttp/3.12.1"
        private const val CLIENT_VERSION = "a-6.11-2223"
    }

    @Volatile
    private var preferredApiAddress: InetAddress? = null

    @Volatile
    private var directRegistrationMayHaveBeenSent = false

    @Volatile
    private var activeBootstrapBaseUrl: String? = null

    private val httpEvents = object : EventListener() {
        override fun dnsStart(call: Call, domainName: String) {
            diagnostic("WARP HTTP dns start", mapOf("op" to operation(call)))
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            diagnostic(
                "WARP HTTP dns end",
                mapOf("op" to operation(call), "addresses" to inetAddressList.size.toString()),
            )
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            diagnostic(
                "WARP HTTP connect start",
                mapOf(
                    "op" to operation(call),
                    "family" to if (inetSocketAddress.address is java.net.Inet6Address) "ipv6" else "ipv4",
                ),
            )
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            diagnostic(
                "WARP HTTP TLS established",
                mapOf(
                    "op" to operation(call),
                    "tls" to (handshake?.tlsVersion?.javaName ?: "none"),
                ),
            )
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
        ) {
            if (call.request().url.host == API_HOST) {
                preferredApiAddress = inetSocketAddress.address
            }
            diagnostic(
                "WARP HTTP connect end",
                mapOf(
                    "op" to operation(call),
                    "family" to if (inetSocketAddress.address is java.net.Inet6Address) "ipv6" else "ipv4",
                    "pinned" to (call.request().url.host == API_HOST).toString(),
                ),
            )
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            diagnostic(
                "WARP HTTP connect failed",
                mapOf(
                    "op" to operation(call),
                    "family" to if (inetSocketAddress.address is java.net.Inet6Address) "ipv6" else "ipv4",
                    "cause" to safeCauseName(ioe),
                ),
            )
        }

        override fun requestHeadersStart(call: Call) {
            val request = call.request()
            if (request.url.host == API_HOST &&
                request.method == "POST" &&
                request.url.encodedPath == REGISTER_PATH
            ) {
                directRegistrationMayHaveBeenSent = true
            }
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            diagnostic(
                "WARP HTTP request sent",
                mapOf("op" to operation(call), "bytes" to byteCount.toString()),
            )
        }

        override fun responseHeadersStart(call: Call) {
            diagnostic("WARP HTTP response headers started", mapOf("op" to operation(call)))
        }

        override fun callFailed(call: Call, ioe: IOException) {
            diagnostic(
                "WARP HTTP call failed",
                mapOf("op" to operation(call), "cause" to safeCauseName(ioe)),
            )
        }
    }

    // Some Android/Wi-Fi combinations advertise IPv6 DNS answers while the actual IPv6 path is
    // black-holed. Preserve system DNS, prefer IPv4 initially, and pin the address that actually
    // completed a bootstrap connection so the side-effecting registration POST does not retry.
    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val preferred = preferredApiAddress.takeIf { hostname == API_HOST }
            val ipv4Count = addresses.count { it is Inet4Address }
            diagnostic(
                "WARP HTTP dns preference",
                mapOf(
                    "ipv4" to ipv4Count.toString(),
                    "ipv6" to (addresses.size - ipv4Count).toString(),
                    "preferred" to if (preferred != null) "pinned" else if (ipv4Count > 0) "ipv4" else "system",
                ),
            )
            return addresses.sortedBy { address ->
                when {
                    preferred != null && address == preferred -> 0
                    address is Inet4Address -> 1
                    else -> 2
                }
            }
        }
    }

    private val registrationClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .eventListener(httpEvents)
            // Registration is side-effecting. Do not let OkHttp replay it after an ambiguous failure.
            .retryOnConnectionFailure(false)
            .build()
    }

    private val reachabilityClient: OkHttpClient by lazy {
        registrationClient.newBuilder()
            .callTimeout(API_REACH_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(API_REACH_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(API_REACH_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(API_REACH_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // GET / is side-effect free, so it may safely fail over across the DNS route set.
            .retryOnConnectionFailure(true)
            .build()
    }

    private val bootstrapHealthClient: OkHttpClient by lazy {
        registrationClient.newBuilder()
            .callTimeout(BOOTSTRAP_HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(BOOTSTRAP_HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(BOOTSTRAP_HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(BOOTSTRAP_HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun provision(request: WarpProvisionRequest): Result<WarpProvisionedProfile> = runCatching {
        coroutineContext.ensureActive()
        onStage(WarpProvisioningStage.PREPARING_KEYS)
        val keyPair = NativeProxy.generateWireGuardKeyPair().getOrElse {
            throw WarpProvisioningException("key_generation_failed")
        }
        coroutineContext.ensureActive()
        onStage(WarpProvisioningStage.REGISTERING_WARP)
        diagnostic(
            "WARP registration started",
            mapOf(
                "api_version" to API_VERSION,
                "client_version" to CLIENT_VERSION,
                "bootstrap_workers" to bootstrapWorkerUrls.size.toString(),
            ),
        )
        probeApiReachability()
        val response = registerDevice(publicKey = keyPair.publicKey)
        coroutineContext.ensureActive()
        onStage(WarpProvisioningStage.FETCHING_PARAMETERS)
        normalizeRegistration(response, keyPair)
    }.recoverCatching { throwable ->
        if (throwable is CancellationException) throw throwable
        if (throwable is WarpProvisioningException) {
            diagnostic(
                "WARP provisioning failed",
                mapOf("code" to throwable.code, "cause" to safeCauseName(throwable.cause)),
            )
            throw throwable
        }
        diagnostic(
            "WARP provisioning failed",
            mapOf("code" to "provisioning_failed", "cause" to safeCauseName(throwable)),
        )
        throw WarpProvisioningException("provisioning_failed", throwable)
    }

    private suspend fun probeApiReachability() {
        try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$API_BASE_URL/")
                    .header("User-Agent", USER_AGENT)
                    .header("CF-Client-Version", CLIENT_VERSION)
                    .get()
                    .build()
                reachabilityClient.newCall(request).execute().use { response ->
                    diagnostic(
                        "WARP API reachability response",
                        mapOf(
                            "status" to response.code.toString(),
                            "preferred_address" to if (preferredApiAddress != null) "pinned" else "none",
                        ),
                    )
                }
            }
        } catch (e: IOException) {
            // This is only a hint. The registration POST still starts directly. Worker bootstrap is
            // allowed only when that direct request fails before request headers begin, which avoids
            // creating a duplicate device after an ambiguous side-effecting failure.
            diagnostic(
                "WARP API reachability probe failed",
                mapOf("cause" to safeCauseName(e), "continuing" to "true"),
            )
        }
    }

    private suspend fun registerDevice(publicKey: String): JSONObject {
        var lastCode = "registration_failed"

        repeat(MAX_ATTEMPTS) { index ->
            coroutineContext.ensureActive()
            val attempt = index + 1
            try {
                diagnostic("WARP registration attempt", mapOf("attempt" to attempt.toString()))
                return withContext(Dispatchers.IO) {
                    val registration = performRegistration(publicKey)
                    enableWarp(registration)
                    registration
                }
            } catch (e: WarpProvisioningException) {
                lastCode = e.code
                diagnostic(
                    "WARP registration attempt failed",
                    mapOf("attempt" to attempt.toString(), "code" to e.code),
                )
                // A 429 is a definite pre-registration rejection and is safe to retry. Other errors
                // may happen after the registration has been persisted, so do not retry automatically.
                val retryable = e.code == "registration_rate_limited"
                if (!retryable || index == MAX_ATTEMPTS - 1) throw e
            } catch (e: IOException) {
                diagnostic(
                    "WARP registration network failure",
                    mapOf("attempt" to attempt.toString(), "cause" to safeCauseName(e)),
                )
                throw WarpProvisioningException("registration_network_error", e)
            }
            delay(500L shl index)
        }
        throw WarpProvisioningException(lastCode)
    }

    private fun performRegistration(publicKey: String): JSONObject {
        val body = JSONObject()
            .put("key", publicKey)
            .toString()

        directRegistrationMayHaveBeenSent = false
        activeBootstrapBaseUrl = null
        try {
            return executeJsonRequest(
                method = "POST",
                url = API_BASE_URL + REGISTER_PATH,
                body = body,
                bearerToken = null,
                operationName = "registration",
            )
        } catch (e: IOException) {
            if (directRegistrationMayHaveBeenSent) {
                diagnostic(
                    "WARP Worker bootstrap skipped after ambiguous direct registration failure",
                    mapOf("cause" to safeCauseName(e)),
                )
                throw e
            }

            val bootstrapBaseUrl = selectBootstrapWorker() ?: run {
                diagnostic("WARP Worker bootstrap unavailable", mapOf("candidates" to bootstrapWorkerUrls.size.toString()))
                throw e
            }
            diagnostic("WARP registration switching to Worker bootstrap")
            val registration = executeJsonRequest(
                method = "POST",
                url = "$bootstrapBaseUrl$BOOTSTRAP_PREFIX$REGISTER_PATH",
                body = body,
                bearerToken = null,
                operationName = "bootstrap registration",
            )
            activeBootstrapBaseUrl = bootstrapBaseUrl
            return registration
        }
    }

    private fun enableWarp(registration: JSONObject) {
        val registrationId = registration.optString("id").trim()
        val token = registration.optString("token").trim()
        if (registrationId.isBlank()) throw WarpProvisioningException("registration_missing_id")
        if (token.isBlank()) throw WarpProvisioningException("registration_missing_token")

        val body = JSONObject().put("warp_enabled", true).toString()
        val bootstrapBaseUrl = activeBootstrapBaseUrl
        if (bootstrapBaseUrl != null) {
            executeJsonRequest(
                method = "PATCH",
                url = "$bootstrapBaseUrl$BOOTSTRAP_PREFIX$REGISTER_PATH/$registrationId",
                body = body,
                bearerToken = token,
                operationName = "bootstrap activation",
            )
            diagnostic("WARP registration activated", mapOf("transport" to "worker_bootstrap"))
            return
        }

        try {
            executeJsonRequest(
                method = "PATCH",
                url = "$API_BASE_URL$REGISTER_PATH/$registrationId",
                body = body,
                bearerToken = token,
                operationName = "activation",
            )
            diagnostic("WARP registration activated", mapOf("transport" to "direct"))
        } catch (e: IOException) {
            // Setting warp_enabled=true is idempotent for this already-created registration, so a
            // network failure can safely retry the same PATCH through the Worker bootstrap.
            val fallback = selectBootstrapWorker() ?: throw e
            diagnostic("WARP activation switching to Worker bootstrap")
            executeJsonRequest(
                method = "PATCH",
                url = "$fallback$BOOTSTRAP_PREFIX$REGISTER_PATH/$registrationId",
                body = body,
                bearerToken = token,
                operationName = "bootstrap activation",
            )
            activeBootstrapBaseUrl = fallback
            diagnostic("WARP registration activated", mapOf("transport" to "worker_bootstrap"))
        }
    }

    private fun selectBootstrapWorker(): String? {
        val candidates = bootstrapWorkerUrls
            .asSequence()
            .map(String::trim)
            .filter { it.startsWith("https://", ignoreCase = true) }
            .map { it.trimEnd('/') }
            .distinct()
            .toList()

        for ((index, baseUrl) in candidates.withIndex()) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl$BOOTSTRAP_PREFIX/health")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                bootstrapHealthClient.newCall(request).execute().use { response ->
                    val revision = response.header("X-Tgws-Warp-Bootstrap-Revision").orEmpty()
                    diagnostic(
                        "WARP Worker bootstrap health response",
                        mapOf(
                            "candidate" to (index + 1).toString(),
                            "status" to response.code.toString(),
                            "revision_ok" to (revision == BOOTSTRAP_REVISION).toString(),
                        ),
                    )
                    if (response.isSuccessful && revision == BOOTSTRAP_REVISION) {
                        return baseUrl
                    }
                }
            } catch (e: IOException) {
                diagnostic(
                    "WARP Worker bootstrap health failed",
                    mapOf("candidate" to (index + 1).toString(), "cause" to safeCauseName(e)),
                )
            }
        }
        return null
    }

    private fun executeJsonRequest(
        method: String,
        url: String,
        body: String,
        bearerToken: String?,
        operationName: String,
    ): JSONObject {
        val requestBody = body.toRequestBody("application/json; charset=UTF-8".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("CF-Client-Version", CLIENT_VERSION)
            .header("Accept", "application/json")
        if (!bearerToken.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerToken")
        }
        when (method) {
            "POST" -> requestBuilder.post(requestBody)
            "PATCH" -> requestBuilder.patch(requestBody)
            else -> throw IllegalArgumentException("unsupported_method")
        }

        registrationClient.newCall(requestBuilder.build()).execute().use { response ->
            val status = response.code
            diagnostic(
                "WARP $operationName response",
                mapOf("status" to status.toString()),
            )
            if (status == 429) throw WarpProvisioningException("registration_rate_limited")
            if (status in 500..599) throw WarpProvisioningException("registration_server_error")
            if (status !in 200..299) throw WarpProvisioningException("registration_http_$status")

            val responseBody = response.body ?: throw WarpProvisioningException("registration_empty_response")
            val declaredLength = responseBody.contentLength()
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw WarpProvisioningException("registration_response_too_large")
            }
            val bytes = responseBody.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw WarpProvisioningException("registration_response_too_large")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            if (bytes.isEmpty()) throw WarpProvisioningException("registration_empty_response")
            return runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                .getOrElse { throw WarpProvisioningException("registration_response_invalid") }
        }
    }

    private fun normalizeRegistration(response: JSONObject, keyPair: WireGuardKeyPair): WarpProvisionedProfile {
        val config = response.optJSONObject("config")
            ?: throw WarpProvisioningException("registration_missing_config")
        val interfaceObject = config.optJSONObject("interface")
            ?: throw WarpProvisioningException("registration_missing_interface")
        val addresses = interfaceObject.optJSONObject("addresses")
            ?: throw WarpProvisioningException("registration_missing_addresses")
        val peers = config.optJSONArray("peers")
            ?: throw WarpProvisioningException("registration_missing_peers")
        if (peers.length() == 0) throw WarpProvisioningException("registration_missing_peer")
        val peer = peers.optJSONObject(0)
            ?: throw WarpProvisioningException("registration_peer_invalid")
        val endpointObject = peer.optJSONObject("endpoint")
            ?: throw WarpProvisioningException("registration_missing_endpoint")

        val ipv4 = addresses.optString("v4").trim()
        val ipv6 = addresses.optString("v6").trim()
        val peerPublicKey = peer.optString("public_key").trim()
        val endpoint = endpointObject.optString("host").trim()
            .ifBlank { endpointObject.optString("v4").trim() }

        if (ipv4.isBlank() && ipv6.isBlank()) throw WarpProvisioningException("registration_addresses_empty")
        if (peerPublicKey.isBlank()) throw WarpProvisioningException("registration_peer_key_empty")
        if (endpoint.isBlank()) throw WarpProvisioningException("registration_endpoint_empty")

        diagnostic("WARP registration normalized")
        return WarpProvisionedProfile(
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
            assignedIpv4 = ipv4,
            assignedIpv6 = ipv6,
            peerPublicKey = peerPublicKey,
            endpoint = endpoint,
        )
    }

    private fun operation(call: Call): String {
        val request = call.request()
        val bootstrap = request.url.host != API_HOST
        return when {
            request.url.encodedPath.endsWith("$BOOTSTRAP_PREFIX/health") -> "bootstrap_health"
            bootstrap && request.method == "PATCH" -> "bootstrap_activation"
            bootstrap && request.method == "POST" -> "bootstrap_registration"
            request.url.encodedPath == "/" -> "reachability"
            request.method == "PATCH" -> "activation"
            else -> "registration"
        }
    }

    private fun diagnostic(message: String, details: Map<String, String> = emptyMap()) {
        runCatching { onDiagnostic(message, details) }
    }

    private fun safeCauseName(throwable: Throwable?): String = throwable?.javaClass?.simpleName ?: "none"
}

data class AwgWarpProfileCheckResult(
    val health: AwgWarpProfileHealth,
    val errorCode: String?,
    val probe: AwgWarpProbeResult? = null,
)

class AwgWarpProfileManager(
    context: Context,
    private val repository: AwgWarpProfileRepository = AwgWarpProfileRepository(context.applicationContext),
) {
    companion object {
        private const val TELEGRAM_PROBE_TARGET = "149.154.175.50:443"
    }

    private val appContext = context.applicationContext

    fun listProfiles(): List<AwgWarpProfileSummary> = repository.listProfiles()

    fun selectedProfileId(): String? = repository.selectedProfileId()

    fun loadDetails(profileId: String): Result<AwgWarpConfigDetails> = repository.loadDetails(profileId)

    fun loadMetadata(profileId: String): AwgWarpProfileMetadata? = repository.loadMetadata(profileId)

    fun selectProfile(profileId: String): Result<Unit> = repository.selectProfile(profileId)

    fun deleteProfile(profileId: String): Result<Unit> = repository.deleteProfile(profileId)

    suspend fun provisionProfile(
        name: String,
        onStage: (WarpProvisioningStage) -> Unit,
    ): Result<AwgWarpProfileMetadata> = runCatching {
        val provider = ConsumerWarpProfileProvisioner(
            bootstrapWorkerUrls = WarpBootstrapWorkerCandidates.load(appContext),
            onStage = onStage,
            onDiagnostic = { message, details ->
                AppLogger.i(appContext, AppLogCategory.NETWORK, message, details)
            },
        )
        val provisioned = provider.provision(
            WarpProvisionRequest(
                profileName = name,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            ),
        ).getOrThrow()

        coroutineContext.ensureActive()
        onStage(WarpProvisioningStage.BUILDING_PROFILE)
        val configText = AwgWarpProfileSerializer.serialize(provisioned)
        val staging = repository.createStagingConfig(configText).getOrThrow()
        try {
            coroutineContext.ensureActive()
            onStage(WarpProvisioningStage.VALIDATING_CONFIG)
            if (!NativeProxy.validateAwgWarpConfig(staging.absolutePath)) {
                throw WarpProvisioningException("native_config_validation_failed")
            }

            coroutineContext.ensureActive()
            onStage(WarpProvisioningStage.CHECKING_CONNECTION)
            val probe = withContext(Dispatchers.IO) {
                NativeProxy.probeAwgWarpConfig(staging.absolutePath, TELEGRAM_PROBE_TARGET)
            }
            if (!probe.ok) throw WarpProvisioningException(probe.code)

            coroutineContext.ensureActive()
            onStage(WarpProvisioningStage.SAVING)
            repository.saveProfile(
                name = name,
                source = AwgWarpProfileSource.CONSUMER_WARP,
                configText = configText,
                localPublicKey = provisioned.publicKey,
                health = AwgWarpProfileHealth.WORKING,
                lastCheckedAtMs = System.currentTimeMillis(),
            ).getOrThrow()
        } finally {
            repository.removeStagingConfig(staging)
        }
    }.recoverCatching { throwable ->
        if (throwable is CancellationException) throw throwable
        if (throwable is WarpProvisioningException) throw throwable
        throw WarpProvisioningException("profile_creation_failed", throwable)
    }

    suspend fun importProfile(uri: Uri, name: String): Result<AwgWarpProfileMetadata> = runCatching {
        val text = repository.readImportedConfig(uri).getOrThrow()
        val staging = repository.createStagingConfig(text).getOrThrow()
        try {
            if (!NativeProxy.validateAwgWarpConfig(staging.absolutePath)) {
                throw WarpProvisioningException("native_config_validation_failed")
            }
            repository.saveProfile(
                name = name,
                source = AwgWarpProfileSource.IMPORTED,
                configText = text,
                health = AwgWarpProfileHealth.NOT_CHECKED,
            ).getOrThrow()
        } finally {
            repository.removeStagingConfig(staging)
        }
    }

    suspend fun checkProfile(profileId: String): Result<AwgWarpProfileCheckResult> = runCatching {
        val config = repository.loadConfig(profileId) ?: throw WarpProvisioningException("profile_not_found")
        val staging = repository.createStagingConfig(config).getOrThrow()
        try {
            if (!NativeProxy.validateAwgWarpConfig(staging.absolutePath)) {
                repository.updateValidation(
                    profileId,
                    AwgWarpProfileHealth.CONFIG_ERROR,
                    errorCode = "native_config_validation_failed",
                )
                return@runCatching AwgWarpProfileCheckResult(
                    AwgWarpProfileHealth.CONFIG_ERROR,
                    "native_config_validation_failed",
                )
            }
            val probe = withContext(Dispatchers.IO) {
                NativeProxy.probeAwgWarpConfig(staging.absolutePath, TELEGRAM_PROBE_TARGET)
            }
            val health = when (probe.code) {
                "ok" -> AwgWarpProfileHealth.WORKING
                "no_handshake" -> AwgWarpProfileHealth.NO_HANDSHAKE
                "config_or_tunnel_init_failed" -> AwgWarpProfileHealth.CONFIG_ERROR
                else -> AwgWarpProfileHealth.NETWORK_ERROR
            }
            val errorCode = probe.code.takeUnless { it == "ok" }
            repository.updateValidation(profileId, health, errorCode = errorCode)
            AwgWarpProfileCheckResult(health, errorCode, probe)
        } finally {
            repository.removeStagingConfig(staging)
        }
    }
}
