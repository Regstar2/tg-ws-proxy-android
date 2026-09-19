package com.amurcanov.tgwsproxy.cloudflare

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudflareApiException(
    message: String,
    val statusCode: Int? = null,
) : Exception(message)

class CloudflareOAuthClient(
    private val httpClient: OkHttpClient = defaultCloudflareHttpClient(),
) {
    fun buildAuthorizationUrl(
        config: CloudflareOAuthConfig,
        pkce: CloudflarePkcePair,
        state: String,
    ): String {
        require(config.isConfigured)
        return AUTHORIZATION_URL.toHttpUrl()
            .newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("scope", config.scopes.joinToString(" "))
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    suspend fun exchangeCode(
        config: CloudflareOAuthConfig,
        code: String,
        verifier: String,
    ): CloudflareOAuthToken = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", config.clientId)
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CloudflareApiException(
                    oauthError(payload, "OAuth token exchange failed"),
                    response.code,
                )
            }
            val json = JSONObject(payload)
            val token = json.optString("access_token").takeIf { it.isNotBlank() }
                ?: throw CloudflareApiException("OAuth response does not contain access_token")
            val expiresInSeconds = json.optLong("expires_in", -1L).takeIf { it > 0 }
            CloudflareOAuthToken(
                accessToken = token,
                expiresAtEpochMs = expiresInSeconds?.let {
                    System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(it)
                },
            )
        }
    }

    suspend fun listAccounts(accessToken: String): List<CloudflareAuthorizedAccount> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(API_BASE + "/accounts?per_page=50")
                .get()
                .bearer(accessToken)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                val result = requireCloudflareResult(response.code, response.isSuccessful, payload)
                val accounts = result as? JSONArray
                    ?: throw CloudflareApiException("Cloudflare accounts response has unexpected shape")
                buildList {
                    for (index in 0 until accounts.length()) {
                        val account = accounts.getJSONObject(index)
                        val id = account.optString("id")
                        val name = account.optString("name")
                        if (id.isNotBlank() && name.isNotBlank()) {
                            add(CloudflareAuthorizedAccount(id = id, name = name))
                        }
                    }
                }
            }
        }

    suspend fun revoke(accessToken: String, clientId: String) = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("token", accessToken)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder()
            .url(REVOKE_URL)
            .post(body)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CloudflareApiException("OAuth token revocation failed", response.code)
            }
        }
    }

    private fun oauthError(payload: String, fallback: String): String {
        return runCatching {
            val json = JSONObject(payload)
            json.optString("error_description")
                .ifBlank { json.optString("error") }
                .ifBlank { fallback }
        }.getOrDefault(fallback)
    }

    private companion object {
        const val AUTHORIZATION_URL = "https://dash.cloudflare.com/oauth2/auth"
        const val TOKEN_URL = "https://dash.cloudflare.com/oauth2/token"
        const val REVOKE_URL = "https://dash.cloudflare.com/oauth2/revoke"
    }
}

data class CloudflareWorkerDeploymentResult(
    val workerName: String,
    val workersDevUrl: String,
    val smokeStatusCode: Int,
)

class CloudflareWorkerDeploymentClient(
    context: Context,
    private val httpClient: OkHttpClient = defaultCloudflareHttpClient(),
) {
    private val appContext = context.applicationContext

    suspend fun deploy(
        accessToken: String,
        accountId: String,
        workerName: String,
    ): CloudflareWorkerDeploymentResult = withContext(Dispatchers.IO) {
        val normalizedName = CloudflareWorkerName.normalize(workerName)
        if (!CloudflareWorkerName.isValid(normalizedName)) {
            throw CloudflareApiException("Invalid Worker name")
        }

        uploadWorker(accessToken, accountId, normalizedName)
        enableWorkersDev(accessToken, accountId, normalizedName)
        val subdomain = getAccountWorkersSubdomain(accessToken, accountId)
        val url = "https://$normalizedName.$subdomain.workers.dev"
        val smokeStatus = smokeTest(url)

        CloudflareWorkerDeploymentResult(
            workerName = normalizedName,
            workersDevUrl = url,
            smokeStatusCode = smokeStatus,
        )
    }

    suspend fun delete(
        accessToken: String,
        accountId: String,
        workerName: String,
    ) = withContext(Dispatchers.IO) {
        val normalizedName = CloudflareWorkerName.normalize(workerName)
        if (!CloudflareWorkerName.isValid(normalizedName)) {
            throw CloudflareApiException("Invalid Worker name")
        }
        val request = Request.Builder()
            .url(API_BASE + "/accounts/" + accountId + "/workers/scripts/" + normalizedName)
            .delete()
            .bearer(accessToken)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (response.code == 404 || (response.isSuccessful && payload.isBlank())) {
                return@use
            }
            requireCloudflareResult(response.code, response.isSuccessful, payload)
        }
    }

    private fun uploadWorker(
        accessToken: String,
        accountId: String,
        workerName: String,
    ) {
        val metadata = JSONObject()
            .put("main_module", MAIN_MODULE)
            .put("compatibility_date", COMPATIBILITY_DATE)
            .put(
                "bindings",
                JSONArray().put(
                    JSONObject()
                        .put("type", "durable_object_namespace")
                        .put("name", "CHUNK_RELAY")
                        .put("class_name", "ChunkRelaySession"),
                ),
            )
            .put(
                "exports",
                JSONObject().put(
                    "ChunkRelaySession",
                    JSONObject()
                        .put("type", "durable-object")
                        .put("storage", "sqlite"),
                ),
            )

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "metadata",
                "metadata.json",
                metadata.toString().toRequestBody(JSON_MEDIA_TYPE),
            )
        MODULES.forEach { moduleName ->
            multipart.addFormDataPart(
                moduleName,
                moduleName,
                readAsset(moduleName).toRequestBody(JAVASCRIPT_MODULE_MEDIA_TYPE),
            )
        }

        val request = Request.Builder()
            .url(API_BASE + "/accounts/" + accountId + "/workers/scripts/" + workerName)
            .put(multipart.build())
            .bearer(accessToken)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            requireCloudflareResult(response.code, response.isSuccessful, payload)
        }
    }

    private fun enableWorkersDev(
        accessToken: String,
        accountId: String,
        workerName: String,
    ) {
        val body = JSONObject()
            .put("enabled", true)
            .put("previews_enabled", false)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(API_BASE + "/accounts/" + accountId + "/workers/scripts/" + workerName + "/subdomain")
            .post(body)
            .bearer(accessToken)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            requireCloudflareResult(response.code, response.isSuccessful, payload)
        }
    }

    private fun getAccountWorkersSubdomain(
        accessToken: String,
        accountId: String,
    ): String {
        val request = Request.Builder()
            .url(API_BASE + "/accounts/" + accountId + "/workers/subdomain")
            .get()
            .bearer(accessToken)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            val result = requireCloudflareResult(response.code, response.isSuccessful, payload)
            val subdomain = (result as? JSONObject)?.optString("subdomain").orEmpty()
            if (subdomain.isBlank()) {
                throw CloudflareApiException("Cloudflare account does not have a workers.dev subdomain")
            }
            return subdomain
        }
    }

    private fun smokeTest(url: String): Int {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Cache-Control", "no-cache")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code >= 500) {
                throw CloudflareApiException(
                    "Worker smoke test returned HTTP " + response.code,
                    response.code,
                )
            }
            return response.code
        }
    }

    private fun readAsset(name: String): String {
        return appContext.assets.open("cloudflare-worker/$name")
            .bufferedReader()
            .use { it.readText() }
    }

    private companion object {
        const val MAIN_MODULE = "chunk-relay-status-worker.js"
        const val COMPATIBILITY_DATE = "2026-09-12"
        val MODULES = listOf(
            "chunk-relay-status-worker.js",
            "chunk-relay-worker.js",
            "worker.js",
        )
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val JAVASCRIPT_MODULE_MEDIA_TYPE = "application/javascript+module".toMediaType()
    }
}

private fun Request.Builder.bearer(accessToken: String): Request.Builder {
    return header("Authorization", "Bearer $accessToken")
        .header("Accept", "application/json")
}

private fun requireCloudflareResult(
    statusCode: Int,
    transportSuccess: Boolean,
    payload: String,
): Any? {
    val json = runCatching { JSONObject(payload) }.getOrNull()
    val success = json?.optBoolean("success", false) == true
    if (!transportSuccess || !success) {
        val errorMessage = json
            ?.optJSONArray("errors")
            ?.optJSONObject(0)
            ?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: "Cloudflare API request failed"
        throw CloudflareApiException(errorMessage, statusCode)
    }
    return json?.opt("result")
}

private const val API_BASE = "https://api.cloudflare.com/client/v4"

private fun defaultCloudflareHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
}
