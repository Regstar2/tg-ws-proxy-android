package com.amurcanov.tgwsproxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class ProvisioningWorkerHealthCheckResult(
    val ok: Boolean,
    val errorCode: String?,
    val httpStatus: Int? = null,
)

internal object WarpBootstrapProtocol {
    const val SERVICE = "warp-bootstrap"
    const val REVISION = "warp-bootstrap-v1"
    const val PREFIX = "/warp-bootstrap"
    const val HEALTH_PATH = "$PREFIX/health"

    private const val MAX_HEALTH_RESPONSE_BYTES = 8 * 1024

    fun validateHealthResponse(
        httpStatus: Int,
        headerRevision: String?,
        bodyText: String,
    ): ProvisioningWorkerHealthCheckResult {
        if (httpStatus !in 200..299) {
            return ProvisioningWorkerHealthCheckResult(
                ok = false,
                errorCode = "health_http_$httpStatus",
                httpStatus = httpStatus,
            )
        }

        val payload = parseHealthPayload(bodyText)
            ?: return ProvisioningWorkerHealthCheckResult(false, "health_response_invalid", httpStatus)
        if (payload.service != SERVICE) {
            return ProvisioningWorkerHealthCheckResult(false, "health_service_mismatch", httpStatus)
        }
        if (payload.revision != REVISION) {
            return ProvisioningWorkerHealthCheckResult(false, "health_revision_unsupported", httpStatus)
        }
        if (!headerRevision.isNullOrBlank() && headerRevision != REVISION) {
            return ProvisioningWorkerHealthCheckResult(false, "health_header_revision_mismatch", httpStatus)
        }
        return ProvisioningWorkerHealthCheckResult(true, null, httpStatus)
    }

    private data class HealthPayload(
        val service: String,
        val revision: String,
    )

    private fun parseHealthPayload(bodyText: String): HealthPayload? {
        val trimmed = bodyText.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        val service = extractSingleJsonStringField(trimmed, "service") ?: return null
        val revision = extractSingleJsonStringField(trimmed, "revision") ?: return null
        return HealthPayload(service = service, revision = revision)
    }

    private fun extractSingleJsonStringField(json: String, field: String): String? {
        val pattern = Regex(
            "\\\"${Regex.escape(field)}\\\"\\s*:\\s*\\\"([^\\\"\\\\]*)\\\"",
        )
        val matches = pattern.findAll(json).toList()
        if (matches.size != 1) return null
        return matches.single().groupValues[1]
    }

    fun readBoundedHealthBody(body: ResponseBody?): String? {
        if (body == null) return null
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_HEALTH_RESPONSE_BYTES) return null

        val bytes = body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_HEALTH_RESPONSE_BYTES) return null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }
}

internal class WarpProvisioningWorkerHealthChecker {
    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val USER_AGENT = "okhttp/3.12.1"
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun check(rawBaseUrl: String): ProvisioningWorkerHealthCheckResult = withContext(Dispatchers.IO) {
        val baseUrl = try {
            ProvisioningWorkerUrlValidator.normalize(rawBaseUrl)
        } catch (e: WarpProvisioningBootstrapException) {
            return@withContext ProvisioningWorkerHealthCheckResult(false, e.code)
        }

        try {
            val request = Request.Builder()
                .url("$baseUrl${WarpBootstrapProtocol.HEALTH_PATH}")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = WarpBootstrapProtocol.readBoundedHealthBody(response.body)
                    ?: return@withContext ProvisioningWorkerHealthCheckResult(
                        false,
                        "health_response_too_large_or_empty",
                        response.code,
                    )
                WarpBootstrapProtocol.validateHealthResponse(
                    httpStatus = response.code,
                    headerRevision = response.header("X-Tgws-Warp-Bootstrap-Revision"),
                    bodyText = body,
                )
            }
        } catch (_: IOException) {
            ProvisioningWorkerHealthCheckResult(false, "health_network_error")
        } catch (_: IllegalArgumentException) {
            ProvisioningWorkerHealthCheckResult(false, "worker_url_invalid")
        }
    }
}
