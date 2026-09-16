package com.amurcanov.tgwsproxy

import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal class WarpRegistrationRelayClient(
    relayBaseUrl: String,
    private val onDiagnostic: (String, Map<String, String>) -> Unit = { _, _ -> },
) {
    companion object {
        private const val BOOTSTRAP_REVISION = "warp-bootstrap-v1"
        private const val BOOTSTRAP_REVISION_HEADER = "X-Tgws-Warp-Bootstrap-Revision"
        private const val STATUS_PATH = "/warp-bootstrap/status"
        private const val REGISTER_PATH = "/warp-bootstrap/v0a4005/reg"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 20_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val CAPABILITY_TIMEOUT_MS = 12_000L
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val USER_AGENT = "okhttp/3.12.1"
        private const val CLIENT_VERSION = "a-6.11-2223"
    }

    private val baseUrl = relayBaseUrl.trim().trimEnd('/').also {
        require(it.startsWith("https://") && it.length > "https://".length) { "invalid relay base URL" }
    }

    private val events = object : EventListener() {
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            diagnostic(
                "WARP relay connect start",
                mapOf("op" to operation(call)),
            )
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            diagnostic(
                "WARP relay TLS established",
                mapOf("op" to operation(call), "tls" to (handshake?.tlsVersion?.javaName ?: "none")),
            )
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            diagnostic(
                "WARP relay request sent",
                mapOf("op" to operation(call), "bytes" to byteCount.toString()),
            )
        }

        override fun responseHeadersStart(call: Call) {
            diagnostic("WARP relay response headers started", mapOf("op" to operation(call)))
        }

        override fun callFailed(call: Call, ioe: IOException) {
            diagnostic(
                "WARP relay call failed",
                mapOf("op" to operation(call), "cause" to safeCauseName(ioe)),
            )
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .eventListener(events)
            // POST /reg and PATCH activation are side-effecting. Never replay them automatically.
            .retryOnConnectionFailure(false)
            .build()
    }

    private val capabilityClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(CAPABILITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun register(publicKey: String): JSONObject {
        probeCapability()
        diagnostic("WARP registration transport", mapOf("mode" to "relay"))

        val registration = executeJsonRequest(
            method = "POST",
            path = REGISTER_PATH,
            body = JSONObject().put("key", publicKey).toString(),
            bearerToken = null,
            operationName = "registration",
        )

        val registrationId = registration.optString("id").trim()
        val token = registration.optString("token").trim()
        if (registrationId.isBlank()) throw WarpProvisioningException("registration_missing_id")
        if (token.isBlank()) throw WarpProvisioningException("registration_missing_token")
        if (!registrationId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            throw WarpProvisioningException("registration_invalid_id")
        }

        executeJsonRequest(
            method = "PATCH",
            path = "$REGISTER_PATH/$registrationId",
            body = JSONObject().put("warp_enabled", true).toString(),
            bearerToken = token,
            operationName = "activation",
        )
        diagnostic("WARP registration activated", mapOf("transport" to "relay"))
        return registration
    }

    private fun probeCapability() {
        val request = try {
            Request.Builder()
                .url(baseUrl + STATUS_PATH)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            throw WarpProvisioningException("registration_relay_invalid", e)
        }

        try {
            capabilityClient.newCall(request).execute().use { response ->
                val supported = response.isSuccessful &&
                    response.header(BOOTSTRAP_REVISION_HEADER) == BOOTSTRAP_REVISION
                diagnostic(
                    "WARP relay capability response",
                    mapOf(
                        "status" to response.code.toString(),
                        "supported" to supported.toString(),
                    ),
                )
                if (!supported) throw WarpProvisioningException("registration_relay_unsupported")
            }
        } catch (e: WarpProvisioningException) {
            throw e
        } catch (e: IOException) {
            diagnostic("WARP relay capability failed", mapOf("cause" to safeCauseName(e)))
            throw WarpProvisioningException("registration_relay_unavailable", e)
        }
    }

    private fun executeJsonRequest(
        method: String,
        path: String,
        body: String,
        bearerToken: String?,
        operationName: String,
    ): JSONObject {
        val requestBody = body.toRequestBody("application/json; charset=UTF-8".toMediaType())
        val requestBuilder = try {
            Request.Builder()
                .url(baseUrl + path)
                .header("User-Agent", USER_AGENT)
                .header("CF-Client-Version", CLIENT_VERSION)
                .header("Accept", "application/json")
        } catch (e: IllegalArgumentException) {
            throw WarpProvisioningException("registration_relay_invalid", e)
        }
        if (!bearerToken.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerToken")
        }
        when (method) {
            "POST" -> requestBuilder.post(requestBody)
            "PATCH" -> requestBuilder.patch(requestBody)
            else -> throw IllegalArgumentException("unsupported_method")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val status = response.code
                diagnostic(
                    "WARP relay $operationName response",
                    mapOf("status" to status.toString()),
                )
                if (status == 429) throw WarpProvisioningException("registration_rate_limited")
                if (status in 500..599) throw WarpProvisioningException("registration_relay_upstream_error")
                if (status !in 200..299) throw WarpProvisioningException("registration_relay_http_$status")
                return readJsonResponse(response.body)
            }
        } catch (e: WarpProvisioningException) {
            throw e
        } catch (e: IOException) {
            diagnostic(
                "WARP relay $operationName network failure",
                mapOf("cause" to safeCauseName(e)),
            )
            throw WarpProvisioningException("registration_relay_network_error", e)
        }
    }

    private fun readJsonResponse(responseBody: okhttp3.ResponseBody?): JSONObject {
        responseBody ?: throw WarpProvisioningException("registration_empty_response")
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
                if (total > MAX_RESPONSE_BYTES) {
                    throw WarpProvisioningException("registration_response_too_large")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        if (bytes.isEmpty()) throw WarpProvisioningException("registration_empty_response")
        return runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
            .getOrElse { throw WarpProvisioningException("registration_response_invalid") }
    }

    private fun operation(call: Call): String = when {
        call.request().url.encodedPath == STATUS_PATH -> "relay_probe"
        call.request().method == "PATCH" -> "activation"
        else -> "registration"
    }

    private fun diagnostic(message: String, details: Map<String, String> = emptyMap()) {
        runCatching { onDiagnostic(message, details) }
    }

    private fun safeCauseName(throwable: Throwable?): String = throwable?.javaClass?.simpleName ?: "none"
}
