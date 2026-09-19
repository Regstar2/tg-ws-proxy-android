package com.amurcanov.tgwsproxy.cloudflare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.WorkerDomain
import com.amurcanov.tgwsproxy.worker.SharedPreferencesWorkerPoolPersistence
import com.amurcanov.tgwsproxy.worker.WorkerEndpoint
import com.amurcanov.tgwsproxy.worker.WorkerPoolRepository
import kotlinx.coroutines.launch
import java.util.UUID

class CloudflareOAuthActivity : ComponentActivity() {
    private lateinit var config: CloudflareOAuthConfig
    private lateinit var sessionStore: CloudflareOAuthSessionStore
    private lateinit var oauthClient: CloudflareOAuthClient
    private lateinit var deploymentClient: CloudflareWorkerDeploymentClient

    private var uiState by mutableStateOf(CloudflareProvisioningUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = CloudflareOAuthConfig.fromBuildConfig()
        sessionStore = CloudflareOAuthSessionStore(this)
        oauthClient = CloudflareOAuthClient()
        deploymentClient = CloudflareWorkerDeploymentClient(this)
        refreshState()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CloudflareOAuthScreen(
                        configured = config.isConfigured,
                        state = uiState,
                        onBack = ::finish,
                        onAddLogin = ::startLogin,
                        onSelectAccount = ::selectAccount,
                        onRemoveLogin = ::removeLogin,
                        onDeploy = ::deployWorker,
                        onDelete = ::deleteWorker,
                    )
                }
            }
        }

        handleOAuthCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun startLogin() {
        if (!config.isConfigured || uiState.busy) {
            uiState = uiState.copy(message = getString(R.string.cf_oauth_not_configured))
            return
        }
        val pkce = CloudflarePkce.create()
        val state = UUID.randomUUID().toString()
        pendingPrefs().edit()
            .putString(KEY_PENDING_STATE, state)
            .putString(KEY_PENDING_VERIFIER, pkce.verifier)
            .apply()

        val authorizationUrl = oauthClient.buildAuthorizationUrl(config, pkce, state)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)))
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val callback = intent?.data ?: return
        val expected = Uri.parse(config.redirectUri)
        if (callback.scheme != expected.scheme ||
            callback.host != expected.host ||
            callback.path != expected.path
        ) {
            return
        }

        val pendingState = pendingPrefs().getString(KEY_PENDING_STATE, null)
        val verifier = pendingPrefs().getString(KEY_PENDING_VERIFIER, null)
        val returnedState = callback.getQueryParameter("state")
        val error = callback.getQueryParameter("error")
        val code = callback.getQueryParameter("code")

        if (pendingState.isNullOrBlank() || verifier.isNullOrBlank() || returnedState != pendingState) {
            clearPendingLogin()
            uiState = uiState.copy(message = getString(R.string.cf_oauth_state_mismatch))
            return
        }
        clearPendingLogin()

        if (!error.isNullOrBlank()) {
            uiState = uiState.copy(
                message = getString(
                    R.string.cf_oauth_authorization_failed,
                    callback.getQueryParameter("error_description") ?: error,
                ),
            )
            return
        }
        if (code.isNullOrBlank()) {
            uiState = uiState.copy(message = getString(R.string.cf_oauth_missing_code))
            return
        }

        lifecycleScope.launch {
            runOperation {
                val token = oauthClient.exchangeCode(config, code, verifier)
                val accounts = oauthClient.listAccounts(token.accessToken)
                if (accounts.isEmpty()) {
                    throw CloudflareApiException(getString(R.string.cf_oauth_no_accounts))
                }
                val session = CloudflareOAuthSession.create(
                    accessToken = token.accessToken,
                    expiresAtEpochMs = token.expiresAtEpochMs,
                    accounts = accounts,
                )
                sessionStore.upsertSession(session)
                sessionStore.saveActiveAccount(
                    ActiveCloudflareAccountRef(session.id, accounts.first().id),
                )
                refreshState(getString(R.string.cf_oauth_login_added, accounts.size))
            }
        }
    }

    private fun selectAccount(ref: ActiveCloudflareAccountRef) {
        sessionStore.saveActiveAccount(ref)
        refreshState()
    }

    private fun removeLogin(sessionId: String) {
        val session = sessionStore.loadSessions().firstOrNull { it.id == sessionId } ?: return
        lifecycleScope.launch {
            uiState = uiState.copy(busy = true, message = null)
            runCatching { oauthClient.revoke(session.accessToken, config.clientId) }
            sessionStore.removeSession(sessionId)
            refreshState(getString(R.string.cf_oauth_login_removed))
        }
    }

    private fun deployWorker(rawWorkerName: String) {
        val selection = selectedSessionAndAccount() ?: return
        val workerName = CloudflareWorkerName.normalize(rawWorkerName)
        if (!CloudflareWorkerName.isValid(workerName)) {
            uiState = uiState.copy(message = getString(R.string.cf_oauth_worker_name_invalid))
            return
        }
        val (session, account) = selection
        if (session.isExpired()) {
            uiState = uiState.copy(message = getString(R.string.cf_oauth_session_expired))
            return
        }

        lifecycleScope.launch {
            runOperation {
                val result = deploymentClient.deploy(
                    accessToken = session.accessToken,
                    accountId = account.id,
                    workerName = workerName,
                )
                addWorkerToPool(result)
                refreshState(
                    getString(
                        R.string.cf_oauth_deploy_success,
                        result.workersDevUrl,
                        result.smokeStatusCode,
                    ),
                )
            }
        }
    }

    private fun deleteWorker(rawWorkerName: String) {
        val selection = selectedSessionAndAccount() ?: return
        val workerName = CloudflareWorkerName.normalize(rawWorkerName)
        if (!CloudflareWorkerName.isValid(workerName)) {
            uiState = uiState.copy(message = getString(R.string.cf_oauth_worker_name_invalid))
            return
        }
        val (session, account) = selection
        if (session.isExpired()) {
            uiState = uiState.copy(message = getString(R.string.cf_oauth_session_expired))
            return
        }

        lifecycleScope.launch {
            runOperation {
                deploymentClient.delete(
                    accessToken = session.accessToken,
                    accountId = account.id,
                    workerName = workerName,
                )
                refreshState(getString(R.string.cf_oauth_delete_success, workerName))
            }
        }
    }

    private fun selectedSessionAndAccount(): Pair<CloudflareOAuthSession, CloudflareAuthorizedAccount>? {
        val active = sessionStore.loadActiveAccount()
        val sessions = sessionStore.loadSessions()
        val session = sessions.firstOrNull { it.id == active?.sessionId }
        val account = session?.accounts?.firstOrNull { it.id == active?.accountId }
        if (session == null || account == null) {
            uiState = uiState.copy(message = getString(R.string.cf_oauth_select_account_first))
            return null
        }
        return session to account
    }

    private suspend fun runOperation(block: suspend () -> Unit) {
        uiState = uiState.copy(busy = true, message = null)
        try {
            block()
        } catch (error: Exception) {
            uiState = uiState.copy(
                busy = false,
                message = getString(
                    R.string.cf_oauth_operation_failed,
                    error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    private fun refreshState(message: String? = uiState.message) {
        val sessions = sessionStore.loadSessions()
        val savedActive = sessionStore.loadActiveAccount()
        val active = savedActive?.takeIf { ref ->
            sessions.any { session ->
                session.id == ref.sessionId && session.accounts.any { it.id == ref.accountId }
            }
        } ?: sessions.firstNotNullOfOrNull { session ->
            session.accounts.firstOrNull()?.let { account ->
                ActiveCloudflareAccountRef(session.id, account.id)
            }
        }
        if (active != savedActive) {
            sessionStore.saveActiveAccount(active)
        }
        uiState = CloudflareProvisioningUiState(
            sessions = sessions,
            activeAccount = active,
            busy = false,
            message = message,
        )
    }

    private fun addWorkerToPool(result: CloudflareWorkerDeploymentResult) {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val repository = WorkerPoolRepository(SharedPreferencesWorkerPoolPersistence(prefs))
        val normalizedDomain = WorkerDomain.normalize(result.workersDevUrl)
        val existing = repository.getWorkers().firstOrNull {
            WorkerDomain.normalize(it.url).equals(normalizedDomain, ignoreCase = true)
        }
        val worker = if (existing == null) {
            repository.addWorker(
                WorkerEndpoint.create(
                    name = "Cloudflare / " + result.workerName,
                    url = result.workersDevUrl,
                    enabled = true,
                ),
            ).getOrThrow()
        } else if (!existing.enabled) {
            repository.setWorkerEnabled(existing.id, true).getOrThrow()
        } else {
            existing
        }
        repository.setPoolEnabled(true)
        repository.selectWorker(worker.id).getOrThrow()
    }

    private fun pendingPrefs() = getSharedPreferences(PENDING_PREFS_NAME, Context.MODE_PRIVATE)

    private fun clearPendingLogin() {
        pendingPrefs().edit()
            .remove(KEY_PENDING_STATE)
            .remove(KEY_PENDING_VERIFIER)
            .apply()
    }

    private companion object {
        const val PENDING_PREFS_NAME = "CloudflareOAuthPending"
        const val KEY_PENDING_STATE = "state"
        const val KEY_PENDING_VERIFIER = "verifier"
    }
}

private data class CloudflareProvisioningUiState(
    val sessions: List<CloudflareOAuthSession> = emptyList(),
    val activeAccount: ActiveCloudflareAccountRef? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

@Composable
private fun CloudflareOAuthScreen(
    configured: Boolean,
    state: CloudflareProvisioningUiState,
    onBack: () -> Unit,
    onAddLogin: () -> Unit,
    onSelectAccount: (ActiveCloudflareAccountRef) -> Unit,
    onRemoveLogin: (String) -> Unit,
    onDeploy: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var workerName by rememberSaveable { mutableStateOf("tgwsproxy") }
    val normalizedWorkerName = remember(workerName) { CloudflareWorkerName.normalize(workerName) }
    val hasActiveAccount = state.activeAccount != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.cf_oauth_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (!configured) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.cf_oauth_not_configured),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.cf_oauth_accounts_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.cf_oauth_accounts_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onAddLogin,
                enabled = configured && !state.busy,
            ) {
                Text(stringResource(R.string.cf_oauth_add_login))
            }
        }

        if (state.sessions.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.cf_oauth_no_logins),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.sessions, key = { it.id }) { session ->
            CloudflareSessionCard(
                session = session,
                activeAccount = state.activeAccount,
                busy = state.busy,
                onSelectAccount = onSelectAccount,
                onRemoveLogin = { onRemoveLogin(session.id) },
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.cf_oauth_deploy_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.cf_oauth_deploy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = workerName,
                        onValueChange = { workerName = it },
                        label = { Text(stringResource(R.string.cf_oauth_worker_name)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.cf_oauth_worker_name_normalized,
                                    normalizedWorkerName.ifBlank { "-" },
                                ),
                            )
                        },
                        enabled = !state.busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onDeploy(normalizedWorkerName) },
                            enabled = configured &&
                                hasActiveAccount &&
                                normalizedWorkerName.isNotBlank() &&
                                !state.busy,
                        ) {
                            Text(stringResource(R.string.cf_oauth_deploy_or_update))
                        }
                        OutlinedButton(
                            onClick = { onDelete(normalizedWorkerName) },
                            enabled = configured &&
                                hasActiveAccount &&
                                normalizedWorkerName.isNotBlank() &&
                                !state.busy,
                        ) {
                            Text(stringResource(R.string.cf_oauth_delete_worker))
                        }
                    }
                }
            }
        }

        if (state.busy) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.cf_oauth_operation_running))
                }
            }
        }

        state.message?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.cf_oauth_security_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CloudflareSessionCard(
    session: CloudflareOAuthSession,
    activeAccount: ActiveCloudflareAccountRef?,
    busy: Boolean,
    onSelectAccount: (ActiveCloudflareAccountRef) -> Unit,
    onRemoveLogin: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = session.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (session.isExpired()) {
                Text(
                    text = stringResource(R.string.cf_oauth_session_expired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            session.accounts.forEach { account ->
                val ref = ActiveCloudflareAccountRef(session.id, account.id)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = activeAccount == ref,
                        enabled = !busy && !session.isExpired(),
                        onClick = { onSelectAccount(ref) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(account.name)
                        Text(
                            account.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(
                onClick = onRemoveLogin,
                enabled = !busy,
            ) {
                Text(stringResource(R.string.cf_oauth_remove_login))
            }
        }
    }
}
