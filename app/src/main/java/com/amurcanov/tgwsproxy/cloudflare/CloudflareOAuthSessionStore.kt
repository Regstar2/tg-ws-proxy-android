package com.amurcanov.tgwsproxy.cloudflare

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CloudflareOAuthSessionStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = CloudflareTokenCipher()

    @Synchronized
    fun loadSessions(): List<CloudflareOAuthSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decodeSession(array.getJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun upsertSession(session: CloudflareOAuthSession) {
        val sessions = loadSessions().toMutableList()
        val existingIndex = sessions.indexOfFirst { it.id == session.id }
        if (existingIndex >= 0) {
            sessions[existingIndex] = session
        } else {
            sessions += session
        }
        saveSessions(sessions)
    }

    @Synchronized
    fun removeSession(sessionId: String) {
        val sessions = loadSessions().filterNot { it.id == sessionId }
        saveSessions(sessions)
        if (loadActiveAccount()?.sessionId == sessionId) {
            val fallback = sessions.firstNotNullOfOrNull { session ->
                session.accounts.firstOrNull()?.let { account ->
                    ActiveCloudflareAccountRef(session.id, account.id)
                }
            }
            saveActiveAccount(fallback)
        }
    }

    fun loadActiveAccount(): ActiveCloudflareAccountRef? {
        val sessionId = prefs.getString(KEY_ACTIVE_SESSION_ID, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val accountId = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return ActiveCloudflareAccountRef(sessionId, accountId)
    }

    fun saveActiveAccount(ref: ActiveCloudflareAccountRef?) {
        prefs.edit()
            .putString(KEY_ACTIVE_SESSION_ID, ref?.sessionId)
            .putString(KEY_ACTIVE_ACCOUNT_ID, ref?.accountId)
            .apply()
    }

    private fun saveSessions(sessions: List<CloudflareOAuthSession>) {
        val array = JSONArray()
        sessions.forEach { session -> array.put(encodeSession(session)) }
        prefs.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    private fun encodeSession(session: CloudflareOAuthSession): JSONObject {
        val accounts = JSONArray()
        session.accounts.forEach { account ->
            accounts.put(
                JSONObject()
                    .put("id", account.id)
                    .put("name", account.name),
            )
        }
        return JSONObject()
            .put("id", session.id)
            .put("label", session.label)
            .put("accessToken", cipher.encrypt(session.accessToken))
            .put("expiresAtEpochMs", session.expiresAtEpochMs ?: JSONObject.NULL)
            .put("createdAtEpochMs", session.createdAtEpochMs)
            .put("accounts", accounts)
    }

    private fun decodeSession(json: JSONObject): CloudflareOAuthSession? {
        return runCatching {
            val accountsJson = json.getJSONArray("accounts")
            val accounts = buildList {
                for (index in 0 until accountsJson.length()) {
                    val account = accountsJson.getJSONObject(index)
                    add(
                        CloudflareAuthorizedAccount(
                            id = account.getString("id"),
                            name = account.getString("name"),
                        ),
                    )
                }
            }
            CloudflareOAuthSession(
                id = json.getString("id"),
                label = json.optString("label").ifBlank { "Cloudflare" },
                accessToken = cipher.decrypt(json.getString("accessToken")),
                expiresAtEpochMs = if (json.isNull("expiresAtEpochMs")) {
                    null
                } else {
                    json.getLong("expiresAtEpochMs")
                },
                accounts = accounts,
                createdAtEpochMs = json.optLong("createdAtEpochMs", 0L),
            )
        }.getOrNull()
    }

    private fun encode(value: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

    private fun decode(value: String): ByteArray {
        return Base64.getUrlDecoder().decode(value)
    }

    private inner class CloudflareTokenCipher {
        fun encrypt(plainText: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return encode(cipher.iv) + "." + encode(encrypted)
        }

        fun decrypt(value: String): String {
            val separator = value.indexOf('.')
            require(separator > 0 && separator < value.lastIndex)
            val iv = decode(value.substring(0, separator))
            val encrypted = decode(value.substring(separator + 1))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }

        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                return existing
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "CloudflareOAuthSessions"
        const val KEY_SESSIONS = "sessions_v1"
        const val KEY_ACTIVE_SESSION_ID = "active_session_id_v1"
        const val KEY_ACTIVE_ACCOUNT_ID = "active_account_id_v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tgwsproxy_cloudflare_oauth_access_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
