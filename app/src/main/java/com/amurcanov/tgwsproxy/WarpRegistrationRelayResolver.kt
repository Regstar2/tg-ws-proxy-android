package com.amurcanov.tgwsproxy

import android.content.Context
import com.amurcanov.tgwsproxy.worker.SharedPreferencesWorkerPoolPersistence
import com.amurcanov.tgwsproxy.worker.WorkerPoolRepository
import com.amurcanov.tgwsproxy.worker.WorkerRouteResolver

internal object WarpRegistrationRelayResolver {
    private const val PREFS_NAME = "ProxyPrefs"
    private const val LEGACY_WORKER_DOMAIN_KEY = "worker_domain"

    fun resolveBaseUrl(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val repository = WorkerPoolRepository(SharedPreferencesWorkerPoolPersistence(prefs))
        val legacyWorkerDomain = prefs.getString(LEGACY_WORKER_DOMAIN_KEY, "").orEmpty()
        val domain = WorkerRouteResolver.resolveDomain(repository, legacyWorkerDomain).trim()
        if (domain.isBlank()) return null
        return "https://$domain"
    }
}
