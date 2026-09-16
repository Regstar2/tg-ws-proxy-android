package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
    private val onStage: (WarpProvisioningStage) -> Unit = {},
    private val onDiagnostic: (String, Map<String, String>) -> Unit = { _, _ -> },
) : WarpProfileProvisioner {
    companion object {
        // Consumer registration is intentionally isolated here because it is not a stable public API.
        // v0a2158 / a-6.10-2158 matches the newer Android consumer registration shape used by
        // currently maintained community clients. The older v0a1922 profile can stall without a
        // response on some networks even after the TLS connection and POST have completed.
        private const val API_BASE_URL = "https://api.cloudflareclient.com"
        private const val API_VERSION = "v0a2158"
        private const val REGISTER_PATH = "/$API_VERSION/reg"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 20_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_ATTEMPTS = 3
        private const val USER_AGENT = "okhttp/3.12.1"
        private const val CLIENT_VERSION = "a-6.10-2158"
        private const val INSTALL_ID_LENGTH = 22
        private const val FCM_SUFFIX_LENGTH = 134
        private const val FCM_PREFIX = "APA91b"
        private const val RANDOM_ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        private val tosFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }

    private val secureRandom = SecureRandom()

    private val registrationClient: OkHttpClient by lazy {
        val modernConsumerTls = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionSpecs(listOf(modernConsumerTls))
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
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
        diagnostic("WARP registration started", mapOf("api_version" to API_VERSION))
        val response = registerDevice(
            publicKey = keyPair.publicKey,
            deviceModel = request.deviceModel,
        )
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

    private suspend fun registerDevice(publicKey: String, deviceModel: String): JSONObject {
        val installId = randomAlphaNumeric(INSTALL_ID_LENGTH)
        val fcmToken = "$installId:$FCM_PREFIX${randomAlphaNumeric(FCM_SUFFIX_LENGTH)}"
        var lastCode = "registration_failed"

        repeat(MAX_ATTEMPTS) { index ->
            coroutineContext.ensureActive()
            val attempt = index + 1
            try {
                diagnostic("WARP registration attempt", mapOf("attempt" to attempt.toString()))
                return withContext(Dispatchers.IO) {
                    performRegistration(
                        publicKey = publicKey,
                        deviceModel = deviceModel,
                        installId = installId,
                        fcmToken = fcmToken,
                    )
                }
            } catch (e: WarpProvisioningException) {
                lastCode = e.code
                diagnostic(
                    "WARP registration attempt failed",
                    mapOf("attempt" to attempt.toString(), "code" to e.code),
                )
                // 429 is a definite pre-registration rejection and is safe to retry. A 5xx response
                // is not retried because the server may have persisted the registration already.
                val retryable = e.code == "registration_rate_limited"
                if (!retryable || index == MAX_ATTEMPTS - 1) throw e
            } catch (e: IOException) {
                // The request may already have reached the server. Retrying an ambiguous I/O failure can
                // create duplicate consumer registrations, so fail deterministically and let the user retry.
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

    private fun performRegistration(
        publicKey: String,
        deviceModel: String,
        installId: String,
        fcmToken: String,
    ): JSONObject {
        val body = JSONObject()
            .put("key", publicKey)
            .put("install_id", installId)
            .put("fcm_token", fcmToken)
            .put("tos", tosFormatter.format(Instant.now()))
            .put("model", deviceModel.take(128))
            .put("serial_number", installId)
            .put("locale", "en_US")
            .toString()

        val request = Request.Builder()
            .url(API_BASE_URL + REGISTER_PATH)
            .header("User-Agent", USER_AGENT)
            .header("CF-Client-Version", CLIENT_VERSION)
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        registrationClient.newCall(request).execute().use { response ->
            val status = response.code
            diagnostic("WARP registration response", mapOf("status" to status.toString()))
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

    private fun randomAlphaNumeric(length: Int): String = buildString(length) {
        repeat(length) {
            append(RANDOM_ALPHANUMERIC[secureRandom.nextInt(RANDOM_ALPHANUMERIC.length)])
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
