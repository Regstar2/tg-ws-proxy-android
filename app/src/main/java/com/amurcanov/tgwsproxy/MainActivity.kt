package com.amurcanov.tgwsproxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.diagnostics.DiagnosticReportEvent
import com.amurcanov.tgwsproxy.diagnostics.DiagnosticReportUiContext
import com.amurcanov.tgwsproxy.diagnostics.DiagnosticsScreen
import com.amurcanov.tgwsproxy.diagnostics.DiagnosticsViewModel
import com.amurcanov.tgwsproxy.diagnostics.RuntimeRouteUiModel
import com.amurcanov.tgwsproxy.routeprobe.RouteDiagnosticsRepository
import com.amurcanov.tgwsproxy.worker.FlowsealDcPreset
import com.amurcanov.tgwsproxy.worker.DcIpTexts
import com.amurcanov.tgwsproxy.worker.WorkerDestinationMode
import com.amurcanov.tgwsproxy.worker.SharedPreferencesWorkerPoolPersistence
import com.amurcanov.tgwsproxy.worker.WorkerEndpoint
import com.amurcanov.tgwsproxy.worker.WorkerFailoverConfigBuilder
import com.amurcanov.tgwsproxy.worker.WorkerRuntimeFailureRecorder
import com.amurcanov.tgwsproxy.worker.WorkerHealthRepository
import com.amurcanov.tgwsproxy.worker.WorkerPoolMigration
import com.amurcanov.tgwsproxy.worker.WorkerPoolRepository
import com.amurcanov.tgwsproxy.worker.WorkerRouteResolver
import com.amurcanov.tgwsproxy.worker.WorkerRuntimeTruth
import com.amurcanov.tgwsproxy.worker.WorkerPoolUiInput
import com.amurcanov.tgwsproxy.worker.WorkerPoolUiLogger
import com.amurcanov.tgwsproxy.worker.WorkerPoolUiState
import com.amurcanov.tgwsproxy.worker.WorkerPoolUiStateMapper
import com.amurcanov.tgwsproxy.worker.WorkerPoolOperationException
import com.amurcanov.tgwsproxy.worker.WorkerPoolError
import com.amurcanov.tgwsproxy.worker.WorkerValidationError
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeRequest
import com.amurcanov.tgwsproxy.routeprobe.RouteProbeTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

// DataCenters list removed

val telegramApps = listOf(
    "org.telegram.messenger",
    "org.thunderdog.challegram",
    "com.radolyn.ayugram",
    "app.exteragram.messenger",
    "ir.ilmili.telegraph",
    "org.telegram.plus",
    "tw.nekomimi.nekogram",
    "tw.nekomimi.nekogramx",
    "org.telegram.mdgram",
    "com.iMe.android",
    "app.nicegram",
    "org.telegram.bgram",
    "cc.modery.cherrygram",
    "io.github.nextalone.nagram"
)

private enum class PendingFolderAction {
    SaveRuntimeLogs,
}

private enum class ProxyScreenPage {
    Main,
    Settings,
    Diagnostics,
}

private const val MIN_RECONFIGURE_INTERVAL_MS = 3000L

private enum class ThemeMode {
    System,
    Light,
    Dark,
}

private enum class LanguageMode {
    System,
    Russian,
    English,
}

private fun parseLanguageMode(value: String?): LanguageMode {
    return runCatching { LanguageMode.valueOf(value ?: LanguageMode.System.name) }.getOrDefault(LanguageMode.System)
}

@Suppress("DEPRECATION")
private fun Context.applyLanguageMode(mode: LanguageMode) {
    val locale = when (mode) {
        LanguageMode.System -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                LocaleList.getDefault().get(0)
            } else {
                Locale.getDefault()
            }
        }
        LanguageMode.Russian -> Locale("ru")
        LanguageMode.English -> Locale.ENGLISH
    }
    Locale.setDefault(locale)
    val config = Configuration(resources.configuration)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.setLocales(LocaleList(locale))
    } else {
        @Suppress("DEPRECATION")
        config.setLocale(locale)
    }
    resources.updateConfiguration(config, resources.displayMetrics)
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_STATUS = "extra_open_status"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Ignored in this example, but handles Tiramisu+ notifications
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activityPrefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        applyLanguageMode(parseLanguageMode(activityPrefs.getString("language_mode", LanguageMode.System.name)))

        PersistentLogStore.initIfNeeded(this)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        checkBatteryOptimizations()
        
        setContent {
            val prefs = remember { getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE) }
            val context = LocalContext.current
            var themeModeName by rememberSaveable {
                mutableStateOf(prefs.getString("theme_mode", ThemeMode.System.name) ?: ThemeMode.System.name)
            }
            var languageModeName by rememberSaveable {
                mutableStateOf(prefs.getString("language_mode", LanguageMode.System.name) ?: LanguageMode.System.name)
            }
            val themeMode = runCatching { ThemeMode.valueOf(themeModeName) }.getOrDefault(ThemeMode.System)
            val languageMode = parseLanguageMode(languageModeName)
            val systemDarkTheme = isSystemInDarkTheme()
            val isDarkTheme = when (themeMode) {
                ThemeMode.System -> systemDarkTheme
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            // Dynamic colors logic for Android 12+ (Material You)
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }
                isDarkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ProxyScreen(
                        themeMode = themeMode,
                        onThemeModeChange = {
                            themeModeName = it.name
                            prefs.edit().putString("theme_mode", it.name).apply()
                        },
                        languageMode = languageMode,
                        onLanguageModeChange = {
                            if (it != languageMode) {
                                languageModeName = it.name
                                prefs.edit().putString("language_mode", it.name).apply()
                                applyLanguageMode(it)
                                recreate()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.background_permission_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    languageMode: LanguageMode,
    onLanguageModeChange: (LanguageMode) -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
    val localProxyFrontendRepository = remember {
        LocalProxyFrontendRepository(prefs)
    }
    val mtProtoProxyConfigRepository = remember {
        MtProtoProxyConfigRepository(prefs)
    }
    val mtProtoRuntimeAdapter = remember {
        NativeMtProtoRuntimeAdapter()
    }
    val cfDomainListRepository = remember {
        CfDomainListRepository(SharedPreferencesCfDomainListPersistence(prefs))
    }
    val manualCfDomainRepository = remember {
        SharedPreferencesManualCfDomainRepository(prefs)
    }
    val adaptiveRouteStatsRepository = remember {
        AdaptiveRouteStatsRepository(prefs)
    }
    val routePolicyRepository = remember {
        NetworkRoutePolicyRepository(prefs)
    }
    val workerPoolRepository = remember {
        WorkerPoolRepository(SharedPreferencesWorkerPoolPersistence(prefs))
    }
    val workerHealthRepository = remember(workerPoolRepository) {
        WorkerHealthRepository(workerPoolRepository)
    }
    val workerRuntimeFailureRecorder = remember(workerPoolRepository) {
        WorkerRuntimeFailureRecorder(workerPoolRepository)
    }

    LaunchedEffect(Unit) {
        RouteDefaultsMigration.applyIfNeeded(
            prefs = prefs,
            repository = routePolicyRepository,
        )
        WorkerPoolMigration.applyIfNeeded(prefs, workerPoolRepository)
    }
    val cfDomainListUpdater = remember {
        CfDomainListUpdater(
            repository = cfDomainListRepository,
            sourceDownloader = CfDomainSourceDownloader(HttpURLConnectionCfDomainHttpClient()),
            logger = AndroidCfDomainUpdateLogger,
        )
    }
    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    val uiMetrics by ProxyRuntimeState.uiMetrics.collectAsStateWithLifecycle()
    var localProxyFrontendType by remember {
        mutableStateOf(localProxyFrontendRepository.load())
    }
    var mtProtoProxyConfig by remember {
        mutableStateOf(mtProtoProxyConfigRepository.load())
    }
    var mtProtoRuntimeState by remember {
        mutableStateOf(mtProtoRuntimeAdapter.getState())
    }
    val routeDiagnosticsRepository = remember { RouteDiagnosticsRepository() }
    val diagnosticsViewModel = remember(routeDiagnosticsRepository) {
        DiagnosticsViewModel(routeDiagnosticsRepository)
    }
    val diagnosticsScreenState by diagnosticsViewModel.state.collectAsStateWithLifecycle()
    var notificationPrefs by remember {
        mutableStateOf(NotificationPreferences.load(context))
    }
    var showOnboarding by remember {
        mutableStateOf(!prefs.getBoolean("onboarding_completed", false))
    }
    var currentPage by rememberSaveable { mutableStateOf(ProxyScreenPage.Main) }
    var flowsealDcOnlyEnabled by remember {
        mutableStateOf(prefs.getBoolean(FlowsealDcPreset.PREF_ENABLED, false))
    }
    val initialDcTexts = remember {
        if (flowsealDcOnlyEnabled) {
            FlowsealDcPreset.flowsealTexts()
        } else {
            FlowsealDcPreset.loadTexts(prefs)
        }
    }
    var dc1Text by remember { mutableStateOf(initialDcTexts.dc1) }
    var dc2Text by remember { mutableStateOf(initialDcTexts.dc2) }
    var dc4Text by remember { mutableStateOf(initialDcTexts.dc4) }
    var dc203Text by remember { mutableStateOf(initialDcTexts.dc203) }
    val initialProxyPortText = remember {
        val saved = prefs.getString("port", null)?.takeIf { it.isNotBlank() }
        val migrationDone = prefs.getBoolean(LOCAL_PROXY_PORT_DEFAULT_MIGRATION_KEY, false)
        val shouldMigrateLegacyDefault = !migrationDone &&
            (saved == null || saved == LEGACY_DEFAULT_LOCAL_PROXY_PORT.toString())
        val normalized = if (shouldMigrateLegacyDefault) {
            DEFAULT_LOCAL_PROXY_PORT.toString()
        } else {
            saved ?: DEFAULT_LOCAL_PROXY_PORT.toString()
        }
        if (shouldMigrateLegacyDefault || !migrationDone || saved == null) {
            prefs.edit()
                .putString("port", normalized)
                .putBoolean(LOCAL_PROXY_PORT_DEFAULT_MIGRATION_KEY, true)
                .apply()
        }
        normalized
    }
    var portText by remember { mutableStateOf(initialProxyPortText) }
    var selectedPoolSize by remember { mutableStateOf(prefs.getInt("pool", 4)) }
    var cfProxyEnabled by remember { mutableStateOf(prefs.getBoolean("cfproxy_enabled", true)) }
    var cfProxyPriority by remember { mutableStateOf(prefs.getBoolean("cfproxy_priority", true)) }
    var cfProxyOnly by remember { mutableStateOf(prefs.getBoolean("cfproxy_only", false)) }
    var manualCfDomains by remember { mutableStateOf(manualCfDomainRepository.load()) }
    var manualCfDomainsText by remember { mutableStateOf(manualCfDomains.joinToString("\n")) }
    var invalidManualCfDomains by remember { mutableStateOf(emptyList<String>()) }
    var connectionMode by remember {
        mutableStateOf(
            prefs.getString("connection_mode", null)?.let { ConnectionMode.fromPref(it) }
                ?: ConnectionMode.fromLegacy(
                    prefs.getBoolean("cfproxy_enabled", true),
                    prefs.getBoolean("cfproxy_priority", true),
                    prefs.getBoolean("cfproxy_only", false),
                )
        )
    }
    var wifiRoutePolicy by remember {
        mutableStateOf(routePolicyRepository.load(NetworkProfileType.WIFI))
    }
    var mobileRoutePolicy by remember {
        mutableStateOf(routePolicyRepository.load(NetworkProfileType.MOBILE))
    }
    var hasSavedWifiRoutePolicy by remember {
        mutableStateOf(routePolicyRepository.hasSavedPolicy(NetworkProfileType.WIFI))
    }
    var hasSavedMobileRoutePolicy by remember {
        mutableStateOf(routePolicyRepository.hasSavedPolicy(NetworkProfileType.MOBILE))
    }
    var workerEnabled by remember { mutableStateOf(prefs.getBoolean("worker_enabled", false)) }
    var workerDomainText by remember { mutableStateOf(prefs.getString("worker_domain", "") ?: "") }
    var workerDestinationMode by remember {
        mutableStateOf(WorkerDestinationMode.fromPref(prefs.getString(ProxyRuntimeConfigFactory.KEY_WORKER_DESTINATION_MODE, null)))
    }
    var flowsealMediaFixEnabled by remember {
        mutableStateOf(prefs.getBoolean(ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_ENABLED, false))
    }
    var flowsealMediaFixDcText by remember {
        mutableStateOf(
            prefs.getInt(
                ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_DC,
                WorkerDestinationMode.DEFAULT_MEDIA_FIX_DC,
            ).toString(),
        )
    }
    var flowsealMediaFixIpText by remember {
        mutableStateOf(
            prefs.getString(
                ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_IP,
                WorkerDestinationMode.DEFAULT_MEDIA_FIX_IP,
            ) ?: WorkerDestinationMode.DEFAULT_MEDIA_FIX_IP,
        )
    }
    var workerPoolEnabled by remember {
        mutableStateOf(workerPoolRepository.getWorkerPoolConfig().enabled)
    }
    var workerPoolWorkers by remember {
        mutableStateOf(workerPoolRepository.getWorkers())
    }
    var workerPoolSelectedWorker by remember {
        mutableStateOf(workerPoolRepository.getSelectedWorker())
    }
    var workerPoolSelectionStrategy by remember {
        mutableStateOf(workerPoolRepository.getWorkerPoolConfig().selectionStrategy)
    }
    var checkingWorkerIds by remember { mutableStateOf(setOf<String>()) }
    var isCheckingAllWorkers by remember { mutableStateOf(false) }
    var diagStatusText by remember { mutableStateOf(prefs.getString("diag_last_status", "") ?: "") }
    var isDiagRunning by remember { mutableStateOf(false) }
    var cfUpstreamState by remember { mutableStateOf(cfDomainListRepository.state()) }
    var autoUpdateCfDomains by remember { mutableStateOf(cfUpstreamState.autoUpdateEnabled) }
    var cfMirrorEnabled by remember { mutableStateOf(cfUpstreamState.mirrorEnabled) }
    var cfMirrorUrlText by remember { mutableStateOf(cfUpstreamState.mirrorUrl) }
    var cfMirrorValidationError by remember { mutableStateOf<String?>(null) }
    var isCfDomainUpdateRunning by remember { mutableStateOf(false) }
    var cfDomainRows by remember {
        mutableStateOf(CfDomainDiagnosticsState.snapshot(manualCfDomains, cfUpstreamState.domains))
    }
    var cfDomainLastCheckAtMs by remember { mutableStateOf(CfDomainDiagnosticsState.lastCheckAtMs) }
    var adaptiveDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var routesSettingsPage by rememberSaveable { mutableStateOf(RoutesSettingsPage.OVERVIEW) }
    var cloudflareSettingsPage by rememberSaveable { mutableStateOf(CloudflareSettingsPage.OVERVIEW) }
    var autoStrategy by remember {
        mutableStateOf(AutoStrategy.fromPref(prefs.getString("auto_strategy", null)))
    }
    var maskDomainsInReport by remember {
        mutableStateOf(prefs.getBoolean("mask_domains_in_export", true))
    }
    var includeDomainsInLogExport by remember {
        mutableStateOf(prefs.getBoolean("include_domains_in_log_export", false))
    }
    var logsEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean("logs_enabled", false)) }
    var showRuntimeLogsDialog by remember { mutableStateOf(false) }
    var workerNormalizeHint by remember { mutableStateOf<String?>(null) }
    var showInfoModal by remember { mutableStateOf(false) }
    var showTipsModal by remember { mutableStateOf(false) }
    var showIpSetupModal by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var reportFolderUriText by remember { mutableStateOf(prefs.getString("report_folder_uri", "") ?: "") }
    var lastLogStatus by remember { mutableStateOf(prefs.getString("last_log_status", "") ?: "") }
    var isSavingLogs by remember { mutableStateOf(false) }
    var pendingFolderAction by remember { mutableStateOf<PendingFolderAction?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val logs by LogManager.logs.collectAsStateWithLifecycle()
    val screenScroll = rememberScrollState()
    val hasOpenModal = showInfoModal || showTipsModal || showIpSetupModal || showExitConfirm || showOnboarding || showRuntimeLogsDialog
    val currentNetworkProfile = NetworkProfileProvider.current(context)
    var routePolicySnapshot by remember {
        mutableStateOf(
            RoutePolicyDiagnostics.buildSnapshot(
                context = context,
                prefs = prefs,
                repository = routePolicyRepository,
            ),
        )
    }
    var reconfigureStatus by remember {
        mutableStateOf(ReconfigureStatusStore.load(prefs))
    }
    var routeProbeReport by remember { mutableStateOf<EffectiveRouteProbeReport?>(null) }
    var isRouteProbeRunning by remember { mutableStateOf(false) }
    var lastRuntimeConfigKey by remember { mutableStateOf("") }
    var lastReconfigureAtMs by remember { mutableStateOf(0L) }
    var pendingReconfigureJob by remember { mutableStateOf<Job?>(null) }
    val syncCfDomainsToRuntimeHolder = remember { mutableStateOf<() -> Unit>({}) }

    fun refreshRoutePolicySnapshot() {
        routePolicySnapshot = RoutePolicyDiagnostics.buildSnapshot(
            context = context,
            prefs = prefs,
            repository = routePolicyRepository,
        )
    }

    fun saveReconfigureStatus(status: ReconfigureStatus) {
        ReconfigureStatusStore.save(prefs, status)
        reconfigureStatus = status
    }

    fun refreshMtProtoRuntimeState() {
        mtProtoRuntimeState = mtProtoRuntimeAdapter.getState()
    }

    fun mtProtoConfigFromInput(): MtProtoProxyConfig {
        return mtProtoProxyConfig.copy(
            port = portText.toIntOrNull() ?: 0,
        )
    }

    fun mtProtoSafeConfigFromInput(): MtProtoProxyConfig {
        val candidate = mtProtoConfigFromInput()
        return if (MtProtoProxyConfigValidator.validate(candidate).isValid) {
            candidate
        } else {
            mtProtoProxyConfig
        }
    }

    fun saveMtProtoConfig(config: MtProtoProxyConfig) {
        mtProtoProxyConfig = mtProtoProxyConfigRepository.save(config)
    }

    fun selectLocalProxyFrontend(type: LocalProxyFrontendType) {
        if (isRunning) return
        localProxyFrontendType = type
        localProxyFrontendRepository.save(type)
        val mtProtoEnabled = type == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL
        saveMtProtoConfig(
            mtProtoSafeConfigFromInput().copy(
                enabled = mtProtoEnabled,
                experimentalAcknowledged = mtProtoProxyConfig.experimentalAcknowledged || mtProtoEnabled,
            ),
        )
        refreshMtProtoRuntimeState()
    }

    fun updateProxyPort(raw: String) {
        if (raw.any { !it.isDigit() } || raw.length > 5) {
            return
        }
        portText = raw
        prefs.edit().putString("port", raw).apply()
        val port = raw.toIntOrNull()
        if (port != null && port in 1..65535) {
            mtProtoProxyConfig = mtProtoProxyConfigRepository.save(
                mtProtoProxyConfig.copy(port = port),
            )
        }
    }

    fun updateMtProtoFakeTlsDomain(raw: String) {
        val normalizedDomain = MtProtoFakeTlsDomain.normalize(raw)
        val candidate = mtProtoProxyConfig.copy(
            fakeTlsDomain = raw,
            fakeTlsPassthrough = mtProtoProxyConfig.fakeTlsPassthrough &&
                MtProtoFakeTlsDomain.isValid(normalizedDomain),
        )
        mtProtoProxyConfig = candidate
        if (MtProtoProxyConfigValidator.validate(candidate).isValid) {
            mtProtoProxyConfig = mtProtoProxyConfigRepository.save(candidate)
        }
    }

    fun updateMtProtoFakeTlsPassthrough(enabled: Boolean) {
        saveMtProtoConfig(
            mtProtoSafeConfigFromInput().copy(fakeTlsPassthrough = enabled),
        )
    }

    fun regenerateMtProtoSecret() {
        saveMtProtoConfig(
            mtProtoSafeConfigFromInput().copy(secret = MtProtoSecretGenerator.generate()),
        )
    }

    fun currentDcEntries(): List<String> =
        FlowsealDcPreset.dcEntries(
            DcIpTexts(dc1Text, dc2Text, dc4Text, dc203Text),
            flowsealDcOnlyEnabled,
        )

    fun applyDcTexts(texts: DcIpTexts) {
        dc1Text = texts.dc1
        dc2Text = texts.dc2
        dc4Text = texts.dc4
        dc203Text = texts.dc203
    }

    fun refreshWorkerPoolState() {
        workerPoolEnabled = workerPoolRepository.getWorkerPoolConfig().enabled
        workerPoolWorkers = workerPoolRepository.getWorkers()
        workerPoolSelectedWorker = workerPoolRepository.getSelectedWorker()
        workerPoolSelectionStrategy = workerPoolRepository.getWorkerPoolConfig().selectionStrategy
    }

    fun runWorkerHealthCheck(workerId: String) {
        if (checkingWorkerIds.contains(workerId)) return
        checkingWorkerIds = checkingWorkerIds + workerId
        coroutineScope.launch {
            try {
                workerHealthRepository.checkWorker(
                    context = context,
                    workerId = workerId,
                    maskDomains = maskDomainsInReport,
                    force = true,
                )
                refreshWorkerPoolState()
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.worker_health_check_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                checkingWorkerIds = checkingWorkerIds - workerId
            }
        }
    }

    fun runWorkerHealthCheckAll() {
        if (isCheckingAllWorkers) return
        val enabledCount = workerPoolWorkers.count { it.enabled }
        if (enabledCount == 0) {
            Toast.makeText(
                context,
                context.getString(R.string.worker_health_no_enabled_workers),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        isCheckingAllWorkers = true
        coroutineScope.launch {
            try {
                val summary = workerHealthRepository.checkAllEnabledWorkers(
                    context = context,
                    maskDomains = maskDomainsInReport,
                    force = true,
                )
                refreshWorkerPoolState()
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.worker_health_check_all_finished,
                        summary.healthyCount,
                        summary.degradedCount,
                        summary.deadCount,
                        summary.skippedCount,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.worker_health_check_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                isCheckingAllWorkers = false
            }
        }
    }

    fun buildWorkerPoolHealthSummary(): com.amurcanov.tgwsproxy.diagnostics.WorkerPoolHealthSummaryUi {
        val workers = workerPoolWorkers
        return com.amurcanov.tgwsproxy.diagnostics.WorkerPoolHealthSummaryUi(
            workersCount = workers.size,
            healthyCount = workers.count { it.enabled && it.state == com.amurcanov.tgwsproxy.worker.WorkerHealthState.HEALTHY },
            degradedCount = workers.count { it.enabled && it.state == com.amurcanov.tgwsproxy.worker.WorkerHealthState.DEGRADED },
            deadCount = workers.count { it.enabled && it.state == com.amurcanov.tgwsproxy.worker.WorkerHealthState.DEAD },
            disabledCount = workers.count { !it.enabled },
            selectedWorkerState = workerPoolSelectedWorker?.state,
            lastHealthCheckAtMs = workerHealthRepository.getLastAllCheckAtMs()
                ?: workers.mapNotNull { it.lastCheckedAt }.maxOrNull(),
        )
    }

    fun enrichedRuntimeRoute(): RouteRuntimeState {
        return WorkerRuntimeTruth.enrichRouteState(
            routeState = uiMetrics.runtime.routeRuntime,
            repository = workerPoolRepository,
            legacyWorkerDomain = workerDomainText,
            maskDomains = maskDomainsInReport,
        )
    }

    fun buildWorkerFailoverSnapshot(): com.amurcanov.tgwsproxy.worker.WorkerFailoverRuntimeSnapshot {
        return WorkerRuntimeTruth.buildFailoverSnapshot(
            routeState = uiMetrics.runtime.routeRuntime,
            repository = workerPoolRepository,
            legacyWorkerDomain = workerDomainText,
            maskDomains = maskDomainsInReport,
        )
    }

    fun buildWorkerPoolUiInput(): WorkerPoolUiInput {
        return WorkerPoolUiInput(
            poolEnabled = workerPoolEnabled,
            config = workerPoolRepository.getWorkerPoolConfig(),
            workers = workerPoolWorkers,
            selectedWorker = workerPoolSelectedWorker,
            failoverSnapshot = if (workerPoolEnabled) buildWorkerFailoverSnapshot() else null,
            routeState = enrichedRuntimeRoute(),
            maskDomains = maskDomainsInReport,
            lastHealthCheckAtMs = workerHealthRepository.getLastAllCheckAtMs()
                ?: workerPoolWorkers.mapNotNull { it.lastCheckedAt }.maxOrNull(),
            isProxyRunning = isRunning,
            isDiagRunning = isDiagRunning,
            checkingWorkerIds = checkingWorkerIds,
            isCheckingAllWorkers = isCheckingAllWorkers,
        )
    }

    fun buildWorkerPoolUiState(): WorkerPoolUiState {
        return WorkerPoolUiStateMapper.map(buildWorkerPoolUiInput(), context)
    }

    fun buildWorkerPoolCompactUi(): com.amurcanov.tgwsproxy.worker.WorkerPoolCompactUiModel? {
        return WorkerPoolUiStateMapper.mapCompact(buildWorkerPoolUiInput(), context)
    }

    fun buildWorkerSelectionSummary(): com.amurcanov.tgwsproxy.diagnostics.WorkerSelectionSummaryUi? {
        if (!workerPoolEnabled) return null
        val preview = workerPoolRepository.previewSelection()
        val snapshot = buildWorkerFailoverSnapshot()
        return com.amurcanov.tgwsproxy.diagnostics.WorkerSelectionSummaryUi(
            strategy = preview.strategy,
            selectionReason = preview.reason.wireValue,
            candidateCount = preview.candidateCount,
            candidateNames = preview.candidates.map { it.workerName.ifBlank { it.workerId } },
            runtimeWorkerName = snapshot.runtimeWorkerName,
            roundRobinCursor = preview.roundRobinCursor,
            lowestLatencyMaxAgeMs = preview.lowestLatencyMaxAgeMs,
        )
    }

    fun buildWorkerFailoverSummary(): com.amurcanov.tgwsproxy.diagnostics.WorkerFailoverSummaryUi? {
        if (!workerPoolEnabled) return null
        val snapshot = buildWorkerFailoverSnapshot()
        val preview = workerPoolRepository.previewSelection()
        return com.amurcanov.tgwsproxy.diagnostics.WorkerFailoverSummaryUi(
            enabledWorkersCount = snapshot.enabledWorkersCount,
            candidateCount = preview.candidateCount,
            selectedWorkerName = snapshot.selectedWorkerName,
            runtimeWorkerName = snapshot.runtimeWorkerName,
            lastSuccessfulWorkerName = snapshot.lastSuccessfulWorkerName,
            lastFailedWorkerName = snapshot.lastFailedWorkerName,
            failoverReason = snapshot.failoverReason.wireValue,
            failoverActive = snapshot.failoverActive,
            attemptCount = snapshot.attemptCount,
        )
    }

    LaunchedEffect(Unit) {
        refreshWorkerPoolState()
    }

    fun runtimeConfigFactory(): ProxyRuntimeConfigFactory {
        return ProxyRuntimeConfigFactory(
            prefs = prefs,
            routePolicyRepository = routePolicyRepository,
            adaptiveRouteStatsRepository = adaptiveRouteStatsRepository,
            cfUpstreamDomainsProvider = { cfUpstreamState.domains },
            manualCfDomainsProvider = { manualCfDomains },
            workerDomainProvider = {
                WorkerRouteResolver.resolveDomain(workerPoolRepository, workerDomainText)
            },
            workerFailoverPayloadProvider = {
                WorkerFailoverConfigBuilder.build(workerPoolRepository)
            },
            workerDestinationModeProvider = { workerDestinationMode },
            flowsealMediaFixEnabledProvider = { flowsealMediaFixEnabled },
            flowsealMediaFixDcProvider = {
                flowsealMediaFixDcText.toIntOrNull() ?: WorkerDestinationMode.DEFAULT_MEDIA_FIX_DC
            },
            flowsealMediaFixIpProvider = { flowsealMediaFixIpText },
            dcEntriesProvider = { currentDcEntries() },
            poolSizeProvider = { selectedPoolSize },
            portProvider = { portText.toIntOrNull() },
        )
    }

    LaunchedEffect(settingsPage) {
        if (settingsPage != SettingsPage.ROUTES) {
            routesSettingsPage = RoutesSettingsPage.OVERVIEW
        }
        if (settingsPage != SettingsPage.CLOUDFLARE) {
            cloudflareSettingsPage = CloudflareSettingsPage.OVERVIEW
        }
    }

    BackHandler(enabled = currentPage == ProxyScreenPage.Diagnostics && !hasOpenModal) {
        currentPage = ProxyScreenPage.Main
        coroutineScope.launch { screenScroll.scrollTo(0) }
    }

    BackHandler(enabled = currentPage == ProxyScreenPage.Settings && !hasOpenModal) {
        when {
            settingsPage == SettingsPage.ROUTES && routesSettingsPage != RoutesSettingsPage.OVERVIEW -> {
                routesSettingsPage = RoutesSettingsPage.OVERVIEW
            }
            settingsPage == SettingsPage.CLOUDFLARE && cloudflareSettingsPage != CloudflareSettingsPage.OVERVIEW -> {
                cloudflareSettingsPage = CloudflareSettingsPage.OVERVIEW
            }
            settingsPage != SettingsPage.HOME -> {
                settingsPage = SettingsPage.HOME
            }
            else -> {
                currentPage = ProxyScreenPage.Main
            }
        }
        coroutineScope.launch { screenScroll.scrollTo(0) }
    }

    BackHandler(enabled = currentPage == ProxyScreenPage.Main && !hasOpenModal) {
        showExitConfirm = true
    }

    val runRuntimeLogSave by rememberUpdatedState<(Uri) -> Unit> { treeUri ->
        if (isSavingLogs) return@rememberUpdatedState
        isSavingLogs = true
        val snapshot = logs
        coroutineScope.launch {
            val profile = NetworkProfileProvider.current(context)
            val policySnapshot = RoutePolicyDiagnostics.buildSnapshot(context, prefs, routePolicyRepository)
            val effectivePolicyMarkdown = RoutePolicyDiagnostics.formatMarkdown(
                context = context,
                snapshot = policySnapshot,
                maskSensitive = !includeDomainsInLogExport,
            )
            val routeProbeMarkdown = routeProbeReport?.let {
                RouteLevelDiagnosticsFormatter.formatMarkdown(
                    context,
                    it,
                    maskSensitive = true,
                    poolMetrics = uiMetrics.runtime,
                )
            }
            val poolMetricsMarkdown = PoolMetricsFormatter.formatMarkdown(uiMetrics.runtime)
            val adaptiveSection = if (connectionMode == ConnectionMode.Auto || connectionMode == ConnectionMode.DirectWithFallback) {
                AdaptiveDiagnosticsReport.buildAdaptiveLogSection(
                    context = context,
                    versionName = appVersionName(context),
                    connectionMode = connectionMode,
                    strategy = autoStrategy,
                    profile = profile,
                    workerDomain = WorkerDomain.normalize(workerDomainText),
                    cachedUpstreamCount = cfUpstreamState.domains.size,
                    builtInCount = CfDomain.builtInDomains.size,
                    stats = adaptiveRouteStatsRepository.snapshotForDisplay(profile.id),
                    maskDomains = !includeDomainsInLogExport,
                    effectiveRoutePolicyMarkdown = effectivePolicyMarkdown,
                )
            } else {
                null
            }
            val report = RuntimeLogExport.save(
                context = context,
                treeUri = treeUri,
                logs = snapshot,
                proxyRunning = isRunning,
                adaptiveDiagnosticsSection = adaptiveSection,
                effectiveRoutePolicySection = if (adaptiveSection == null) effectivePolicyMarkdown else null,
                routeProbeReportSection = routeProbeMarkdown,
                poolMetricsSection = poolMetricsMarkdown,
            )
            isSavingLogs = false
            val status = if (report.savedUri != null) {
                context.getString(R.string.logs_saved_status, report.fileName, report.lineCount)
            } else {
                context.getString(R.string.logs_save_failed_status, report.fileName)
            }
            lastLogStatus = status
            prefs.edit().putString("last_log_status", status).apply()
            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
        }
    }

    val reportFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val pendingAction = pendingFolderAction
        pendingFolderAction = null
        if (uri == null) {
            if (pendingAction != null) {
                Toast.makeText(context, context.getString(R.string.report_folder_not_selected), Toast.LENGTH_SHORT).show()
            }
            return@rememberLauncherForActivityResult
        }

        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }

        reportFolderUriText = uri.toString()
        prefs.edit().putString("report_folder_uri", reportFolderUriText).apply()
        Toast.makeText(context, context.getString(R.string.report_folder_saved), Toast.LENGTH_SHORT).show()

        when (pendingAction) {
            PendingFolderAction.SaveRuntimeLogs -> runRuntimeLogSave(uri)
            null -> Unit
        }
    }

    DisposableEffect(logsEnabled) {
        if (logsEnabled) {
            LogManager.startListening(context)
        } else {
            LogManager.stopListening(clear = false)
        }
        onDispose {
            if (logsEnabled) {
                LogManager.stopListening(clear = false)
            }
        }
    }

    LaunchedEffect(Unit) {
        val activity = context as? MainActivity
        if (activity?.intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_STATUS, false) == true) {
            currentPage = ProxyScreenPage.Main
            activity.intent?.removeExtra(MainActivity.EXTRA_OPEN_STATUS)
        }
    }

    LaunchedEffect(uiMetrics.runtime.routeRuntime.lastUpdatedAtMs, uiMetrics.runtime.routeRuntime.lastFailedWorkerId, uiMetrics.runtime.routeRuntime.lastSuccessfulWorkerId) {
        if (!isRunning) return@LaunchedEffect
        workerRuntimeFailureRecorder.onRouteRuntimeUpdate(enrichedRuntimeRoute())
        refreshWorkerPoolState()
    }

    LaunchedEffect(currentPage) {
        screenScroll.scrollTo(0)
        if (currentPage == ProxyScreenPage.Diagnostics) {
            DiagnosticsViewModel.onScreenOpened()
            diagnosticsViewModel.syncRuntimeRoute(enrichedRuntimeRoute())
            diagnosticsViewModel.loadFromRepository()
            val persistentEnabled = prefs.getBoolean("persistent_logs_enabled", false)
            val bytes = PersistentLogStore.totalSizeBytes(context)
            diagnosticsViewModel.updatePersistentLogsState(
                enabled = persistentEnabled,
                sizeLabel = ConnectionMetricsFormatter.formatBytes(bytes),
            )
        }
        if (currentPage == ProxyScreenPage.Settings) {
            refreshRoutePolicySnapshot()
            reconfigureStatus = ReconfigureStatusStore.load(prefs)
        }
    }

    LaunchedEffect(
        isRunning,
        localProxyFrontendType,
        currentPage,
        workerPoolEnabled,
        workerPoolWorkers,
    ) {
        if (localProxyFrontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) {
            refreshMtProtoRuntimeState()
        }
        if (currentPage == ProxyScreenPage.Diagnostics) {
            diagnosticsViewModel.refreshFrontendDiagnostics(
                context = context,
                workerPoolSnapshot = workerPoolRepository.buildReportSnapshot(
                    legacyWorkerDomain = workerDomainText,
                    maskDomains = maskDomainsInReport,
                ),
            )
        }
    }

    LaunchedEffect(Unit) {
        diagnosticsViewModel.reportEvents.collect { event ->
            when (event) {
                DiagnosticReportEvent.CopySuccess -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.diagnostic_report_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is DiagnosticReportEvent.ShareReady -> {
                    context.startActivity(event.intent)
                }
                is DiagnosticReportEvent.Failed -> {
                    Toast.makeText(context, context.getString(event.messageRes), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun applyCfUpstreamState(state: CfDomainUpstreamState) {
        cfUpstreamState = state
        autoUpdateCfDomains = state.autoUpdateEnabled
        cfMirrorEnabled = state.mirrorEnabled
        cfMirrorUrlText = state.mirrorUrl
        val manualSnapshot = manualCfDomains
        val upstreamDomains = state.domains
        coroutineScope.launch(Dispatchers.Default) {
            val rows = CfDomainDiagnosticsState.snapshot(manualSnapshot, upstreamDomains)
            withContext(Dispatchers.Main) {
                cfDomainRows = rows
            }
        }
        if (isRunning) {
            syncCfDomainsToRuntimeHolder.value()
        }
    }

    fun applyCfMirrorSettings(enabled: Boolean, url: String) {
        cfMirrorEnabled = enabled
        cfMirrorUrlText = url
        cfMirrorValidationError = when (
            val validation = CfDomainMirrorUrlValidator.validate(enabled, url)
        ) {
            is CfDomainMirrorValidation.Invalid -> validation.message
            else -> null
        }
        applyCfUpstreamState(cfDomainListRepository.setMirrorSettings(enabled, url))
    }

    fun showCfDomainUpdateResult(result: CfDomainListUpdateResult) {
        when (result) {
            is CfDomainListUpdateResult.Success -> {
                applyCfUpstreamState(result.state)
                val sourceLabel = context.getString(result.sourceType.labelKey())
                Toast.makeText(
                    context,
                    if (result.dryRun) {
                        context.getString(
                            R.string.cf_update_test_ok,
                            result.domainCount,
                            safeToastFormatArg(sourceLabel),
                        )
                    } else {
                        context.getString(R.string.cf_domains_update_success, result.domainCount)
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }

            is CfDomainListUpdateResult.NotModified -> {
                applyCfUpstreamState(result.state)
                val sourceLabel = context.getString(result.sourceType.labelKey())
                Toast.makeText(
                    context,
                    if (result.dryRun) {
                        context.getString(
                            R.string.cf_update_test_not_modified,
                            safeToastFormatArg(sourceLabel),
                        )
                    } else {
                        context.getString(R.string.cf_domains_update_not_modified)
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }

            is CfDomainListUpdateResult.Failure -> {
                applyCfUpstreamState(result.state)
                val stageLabel = context.getString(result.stage.userMessageKey())
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.cf_domains_update_failed,
                        stageLabel,
                        safeToastFormatArg(result.message),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }

            is CfDomainListUpdateResult.MirrorInvalid -> {
                cfMirrorValidationError = result.message
                Toast.makeText(
                    context,
                    context.getString(R.string.cf_update_mirror_invalid),
                    Toast.LENGTH_LONG,
                ).show()
            }

            CfDomainListUpdateResult.AlreadyRunning -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.cf_domains_update_running),
                    Toast.LENGTH_SHORT,
                ).show()
            }

            else -> Unit
        }
    }

    fun launchCfDomainUpdate(block: suspend () -> CfDomainListUpdateResult) {
        if (isCfDomainUpdateRunning) {
            return
        }
        isCfDomainUpdateRunning = true
        coroutineScope.launch {
            try {
                showCfDomainUpdateResult(block())
            } catch (t: Throwable) {
                Log.e("MainActivity", "CF domain update failed", t)
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.cf_domains_update_failed,
                        context.getString(R.string.cf_update_error_unavailable),
                        safeToastFormatArg(t.message ?: "unknown"),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                isCfDomainUpdateRunning = false
            }
        }
    }

    fun applyManualCfDomains(raw: String) {
        manualCfDomainsText = raw
        val parsed = CfManualDomainList.parse(raw)
        invalidManualCfDomains = parsed.invalidEntries
        manualCfDomains = manualCfDomainRepository.save(parsed.domains)
        cfDomainRows = CfDomainDiagnosticsState.snapshot(manualCfDomains, cfUpstreamState.domains)
        if (isRunning) {
            syncCfDomainsToRuntimeHolder.value()
        }
    }

    LaunchedEffect(currentPage, autoUpdateCfDomains) {
        if (currentPage != ProxyScreenPage.Settings || !autoUpdateCfDomains || isCfDomainUpdateRunning) {
            return@LaunchedEffect
        }
        isCfDomainUpdateRunning = true
        try {
            when (val result = cfDomainListUpdater.maybeAutoUpdate()) {
                is CfDomainListUpdateResult.Success -> applyCfUpstreamState(result.state)
                is CfDomainListUpdateResult.NotModified -> applyCfUpstreamState(result.state)
                is CfDomainListUpdateResult.Failure -> applyCfUpstreamState(result.state)
                else -> Unit
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "CF domain auto-update failed", t)
        } finally {
            isCfDomainUpdateRunning = false
        }
    }

    val startProxyAction by rememberUpdatedState {
        if (localProxyFrontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) {
            val candidate = mtProtoConfigFromInput().copy(
                enabled = true,
                experimentalAcknowledged = true,
            )
            val validation = MtProtoProxyConfigValidator.validate(candidate)
            if (!validation.isValid) {
                Toast.makeText(
                    context,
                    mtProtoValidationMessage(context, validation.errors),
                    Toast.LENGTH_SHORT,
                ).show()
                return@rememberUpdatedState
            }
            val normalizedCandidate = candidate.normalized()
            saveMtProtoConfig(normalizedCandidate)
            localProxyFrontendRepository.save(LocalProxyFrontendType.MTPROTO_EXPERIMENTAL)
            Log.i(
                "TgWsProxy",
                "MTProto frontend start requested host=${normalizedCandidate.host} " +
                    "port=${normalizedCandidate.port} " +
                    "secret=${MtProtoSecretMasking.mask(candidate.secret)} " +
                    "fake_tls=${normalizedCandidate.fakeTlsDomain.isNotBlank()} " +
                    "fake_tls_passthrough=${normalizedCandidate.fakeTlsPassthrough}",
            )
            val startIntent = Intent(context, ProxyService::class.java).apply {
                action = ProxyService.ACTION_START
                putExtra(ProxyService.EXTRA_PORT, normalizedCandidate.port)
                runtimeConfigFactory().build(context)?.let { routeConfig ->
                    putExtra(ProxyService.EXTRA_IPS, routeConfig.ips)
                    putExtra(ProxyService.EXTRA_POOL_SIZE, routeConfig.poolSize)
                }
            }
            ContextCompat.startForegroundService(context, startIntent)
            return@rememberUpdatedState
        }

        if (portText.toIntOrNull() == null) {
            Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
            return@rememberUpdatedState
        }
        val config = runtimeConfigFactory().build(context)

        if (config == null || config.ips.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.dc_ip_required), Toast.LENGTH_SHORT).show()
            return@rememberUpdatedState
        }
        Log.i("TgWsProxy", effectiveRoutePolicyLog("effective route policy start", config))
        val startSnapshot = RoutePolicyDiagnostics.buildSnapshot(context, prefs, routePolicyRepository)
        routePolicySnapshot = startSnapshot
        lastRuntimeConfigKey = runtimeConfigKey(config)
        Log.i("TgWsProxy", RoutePolicyDiagnostics.formatLogLine(startSnapshot))
        
        val startIntent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
            putExtra(ProxyService.EXTRA_PORT, config.port)
            putExtra(ProxyService.EXTRA_IPS, config.ips)
            putExtra(ProxyService.EXTRA_POOL_SIZE, config.poolSize)
        }
        ContextCompat.startForegroundService(context, startIntent)
    }

    fun buildRouteProbeRequest(): RouteProbeRequest {
        return RouteProbeRequest(
            workerDomain = WorkerRouteResolver.resolveDomain(workerPoolRepository, workerDomainText),
            manualCfDomains = manualCfDomains,
            cachedUpstreamDomains = cfUpstreamState.domains,
            networkProfile = currentNetworkProfile,
        )
    }

    fun buildDiagnosticReportUiContext(): DiagnosticReportUiContext {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
        val poolUiState = if (workerPoolEnabled) buildWorkerPoolUiState() else null
        return DiagnosticReportUiContext(
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = versionCode,
            buildType = if (isDebug) "debug" else "release",
            connectionMode = context.getString(connectionMode.displayLabelRes()),
            proxyPort = portText,
            workerDomain = WorkerRouteResolver.resolveDomain(workerPoolRepository, workerDomainText),
            workerPoolSnapshot = workerPoolRepository.buildReportSnapshot(
                legacyWorkerDomain = workerDomainText,
                maskDomains = maskDomainsInReport,
            ),
            workerHealthLastCheckAtMs = workerHealthRepository.getLastAllCheckAtMs()
                ?: workerPoolWorkers.mapNotNull { it.lastCheckedAt }.maxOrNull(),
            workerSelectionPreview = if (workerPoolEnabled) workerPoolRepository.previewSelection() else null,
            enrichedRuntimeRoute = enrichedRuntimeRoute(),
            workerPoolSelectedWorkerMissing = poolUiState?.configWarning ==
                com.amurcanov.tgwsproxy.worker.WorkerPoolConfigWarning.SELECTED_NOT_FOUND,
            workerPoolNoEnabledWorkers = poolUiState?.contentState ==
                com.amurcanov.tgwsproxy.worker.WorkerPoolContentState.NO_ENABLED,
            workerPoolInvalidConfig = poolUiState?.contentState ==
                com.amurcanov.tgwsproxy.worker.WorkerPoolContentState.INVALID_CONFIG,
            cfProxyConfigured = manualCfDomains.isNotEmpty() || cfUpstreamState.domains.isNotEmpty(),
            maskDomains = maskDomainsInReport,
            fallbackEnabled = routePolicySnapshot.policy.allowFallback,
            diagnosticsEnabled = true,
        )
    }

    fun runDiagnosticsCopyReport() {
        coroutineScope.launch {
            diagnosticsViewModel.copyReport(
                context = context,
                uiContext = buildDiagnosticReportUiContext(),
                recentLogLines = logs.map(AppLogSanitizer::sanitizeText),
            )
        }
    }

    fun runDiagnosticsShareReport() {
        coroutineScope.launch {
            diagnosticsViewModel.shareReport(
                context = context,
                uiContext = buildDiagnosticReportUiContext(),
                recentLogLines = logs.map(AppLogSanitizer::sanitizeText),
                chooserTitle = context.getString(R.string.diagnostic_report_share),
            )
        }
    }

    val diagnosticsCheckingLabel = stringResource(R.string.diagnostics_checking)

    fun runDiagnosticsAll() {
        coroutineScope.launch {
            diagnosticsViewModel.runAll(context, buildRouteProbeRequest(), diagnosticsCheckingLabel)
        }
    }

    fun runDiagnosticsDirect() {
        coroutineScope.launch {
            diagnosticsViewModel.runTarget(
                context,
                buildRouteProbeRequest(),
                RouteProbeTarget.DIRECT_WEBSOCKET,
                diagnosticsCheckingLabel,
            )
        }
    }

    fun runDiagnosticsWorker() {
        coroutineScope.launch {
            diagnosticsViewModel.runTarget(
                context,
                buildRouteProbeRequest(),
                RouteProbeTarget.WORKER_WEBSOCKET,
                diagnosticsCheckingLabel,
            )
        }
    }

    fun runDiagnosticsCloudflare() {
        coroutineScope.launch {
            diagnosticsViewModel.runTarget(
                context,
                buildRouteProbeRequest(),
                RouteProbeTarget.CLOUDFLARE_PROXY,
                diagnosticsCheckingLabel,
            )
        }
    }

    fun runDiagnosticsNetwork() {
        coroutineScope.launch {
            diagnosticsViewModel.runTarget(
                context,
                buildRouteProbeRequest(),
                RouteProbeTarget.CURRENT_NETWORK,
                diagnosticsCheckingLabel,
            )
        }
    }

    fun runDiagnosticsTelegram() {
        coroutineScope.launch {
            diagnosticsViewModel.runTarget(
                context,
                buildRouteProbeRequest(),
                RouteProbeTarget.TELEGRAM_REACHABILITY,
                diagnosticsCheckingLabel,
            )
        }
    }

    val runEffectiveRouteProbe by rememberUpdatedState {
        if (isDiagRunning || isRouteProbeRunning) return@rememberUpdatedState
        isDiagRunning = true
        isRouteProbeRunning = true
        diagStatusText = context.getString(R.string.route_probe_running)
        coroutineScope.launch {
            try {
                val report = EffectiveRouteConnectionDiagnostics.probeEffectivePolicy(
                    context = context,
                    prefs = prefs,
                    routePolicyRepository = routePolicyRepository,
                    workerDomain = workerDomainText,
                    manualCfDomains = manualCfDomains,
                    cachedUpstreamDomains = cfUpstreamState.domains,
                )
                routeProbeReport = report
                val status = RouteLevelDiagnosticsFormatter.formatShort(context, report)
                diagStatusText = status
                prefs.edit().putString("diag_last_status", status).apply()
                RouteLevelDiagnosticsFormatter.formatLogLines(report).forEach { line ->
                    Log.i("TgWsProxy", line)
                }
            } finally {
                isRouteProbeRunning = false
                isDiagRunning = false
            }
        }
    }

    fun runNetworkReconfigure(profile: NetworkProfile) {
        if (!isRunning) {
            return
        }
        val config = runtimeConfigFactory().build(context)
        if (config == null || config.ips.isBlank()) {
            Log.w("TgWsProxy", "network reconfigure failed: config rebuild returned empty networkType=${profile.type.prefValue}")
            saveReconfigureStatus(
                ReconfigureStatus(
                    type = ReconfigureStatusType.FAILED,
                    networkType = profile.type,
                    message = "config_build_failed",
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            return
        }
        val configKey = runtimeConfigKey(config)
        if (configKey == lastRuntimeConfigKey) {
            saveReconfigureStatus(
                ReconfigureStatus(
                    type = ReconfigureStatusType.SKIPPED,
                    networkType = config.profile.type,
                    message = "same_runtime_config",
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            Log.i("TgWsProxy", "network reconfigure skipped: same runtime config networkType=${config.profile.type.prefValue}")
            refreshRoutePolicySnapshot()
            return
        }
        lastReconfigureAtMs = System.currentTimeMillis()
        Log.i("TgWsProxy", effectiveRoutePolicyLog("effective route policy reconfigure", config))
        val snapshot = RoutePolicyDiagnostics.buildSnapshot(context, prefs, routePolicyRepository)
        routePolicySnapshot = snapshot
        Log.i("TgWsProxy", RoutePolicyDiagnostics.formatLogLine(snapshot))
        saveReconfigureStatus(
            ReconfigureStatus(
                type = ReconfigureStatusType.SUCCESS,
                networkType = config.profile.type,
                message = "action_reconfigure_sent",
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        lastRuntimeConfigKey = configKey
        val intent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_RECONFIGURE
            putExtra(ProxyService.EXTRA_PORT, config.port)
            putExtra(ProxyService.EXTRA_IPS, config.ips)
            putExtra(ProxyService.EXTRA_POOL_SIZE, config.poolSize)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    syncCfDomainsToRuntimeHolder.value = {
        if (isRunning) {
            runNetworkReconfigure(NetworkProfileProvider.current(context))
        }
    }

    val latestReconfigureAction by rememberUpdatedState<(NetworkProfile) -> Unit> { profile ->
        val now = System.currentTimeMillis()
        val remainingDelay = MIN_RECONFIGURE_INTERVAL_MS - (now - lastReconfigureAtMs)
        pendingReconfigureJob?.cancel()
        if (remainingDelay > 0) {
            Log.i("TgWsProxy", "network reconfigure delayed: debounce guard networkType=${profile.type.prefValue}")
            saveReconfigureStatus(
                ReconfigureStatus(
                    type = ReconfigureStatusType.SKIPPED,
                    networkType = profile.type,
                    message = "throttled",
                    updatedAtMs = now,
                ),
            )
            pendingReconfigureJob = coroutineScope.launch {
                delay(remainingDelay)
                runNetworkReconfigure(profile)
            }
        } else {
            pendingReconfigureJob = null
            runNetworkReconfigure(profile)
        }
    }

    DisposableEffect(isRunning) {
        if (!isRunning) {
            pendingReconfigureJob?.cancel()
            pendingReconfigureJob = null
            onDispose { }
        } else {
            val monitor = NetworkChangeMonitor(
                context = context.applicationContext,
                coroutineScope = coroutineScope,
                onNetworkChanged = { latestReconfigureAction(it) },
            )
            monitor.start()
            onDispose {
                monitor.stop()
                pendingReconfigureJob?.cancel()
                pendingReconfigureJob = null
            }
        }
    }

    val stopProxyAction by rememberUpdatedState {
        val stopIntent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    val applyInTelegramAction by rememberUpdatedState {
        if (localProxyFrontendType == LocalProxyFrontendType.MTPROTO_EXPERIMENTAL) {
            val proxyUrl = MtProtoTelegramLinkBuilder.tgUri(mtProtoConfigFromInput())
            if (proxyUrl == null) {
                Toast.makeText(context, context.getString(R.string.mtproto_link_unavailable), Toast.LENGTH_SHORT).show()
                return@rememberUpdatedState
            }
            openTelegram(context, proxyUrl)
        } else {
            val port = portText.toIntOrNull() ?: DEFAULT_LOCAL_PROXY_PORT
            val proxyUrl = "tg://socks?server=127.0.0.1&port=$port"
            openTelegram(context, proxyUrl)
        }
    }
    val mtProtoUiStatus = MtProtoUiStatusResolver.resolve(
        frontendType = localProxyFrontendType,
        config = mtProtoProxyConfig,
        serviceRunning = isRunning,
        runtimeState = mtProtoRuntimeState,
    )
    val proxyPortLabel = portText.ifBlank { DEFAULT_LOCAL_PROXY_PORT.toString() }
    val proxyAddress = when (localProxyFrontendType) {
        LocalProxyFrontendType.SOCKS5 -> "$DEFAULT_LOCAL_PROXY_HOST:$proxyPortLabel"
        LocalProxyFrontendType.MTPROTO_EXPERIMENTAL -> "${mtProtoProxyConfig.host}:$proxyPortLabel"
    }
    val activePolicyChipText = when (localProxyFrontendType) {
        LocalProxyFrontendType.SOCKS5 -> if (isRunning) {
            uiMetrics.runtime.routeLabel(context)
        } else {
            stringResource(connectionMode.displayLabelRes())
        }
        LocalProxyFrontendType.MTPROTO_EXPERIMENTAL -> stringResource(mtProtoUiStatus.labelRes())
    }
    val openUrl = { url: String ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.open_link_failed), Toast.LENGTH_SHORT).show()
        }
    }
    val diagLabelDirect = stringResource(R.string.diag_test_direct)
    val diagLabelWorker = stringResource(R.string.diag_test_worker)
    val diagLabelCf = stringResource(R.string.diag_test_cf)
    val diagLabelTcp = stringResource(R.string.diag_test_tcp)
    val diagLabelAll = stringResource(R.string.diag_test_all)
    val copyProxyAddressAction by rememberUpdatedState {
        val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Proxy address", proxyAddress))
        Toast.makeText(context, context.getString(R.string.proxy_address_copied), Toast.LENGTH_SHORT).show()
    }
    val copyMtProtoTelegramLinkAction by rememberUpdatedState {
        val link = MtProtoTelegramLinkBuilder.httpsLink(mtProtoConfigFromInput())
        if (link == null) {
            Toast.makeText(context, context.getString(R.string.mtproto_link_unavailable), Toast.LENGTH_SHORT).show()
            return@rememberUpdatedState
        }
        val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("MTProto proxy link", link))
        Toast.makeText(context, context.getString(R.string.mtproto_link_copied), Toast.LENGTH_SHORT).show()
    }
    val shareMtProtoTelegramLinkAction by rememberUpdatedState {
        val link = MtProtoTelegramLinkBuilder.httpsLink(mtProtoConfigFromInput())
        if (link == null) {
            Toast.makeText(context, context.getString(R.string.mtproto_link_unavailable), Toast.LENGTH_SHORT).show()
            return@rememberUpdatedState
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.mtproto_link_share_title)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentPage) {
                            ProxyScreenPage.Settings -> stringResource(R.string.screen_settings_title)
                            ProxyScreenPage.Diagnostics -> stringResource(R.string.diagnostics_title)
                            ProxyScreenPage.Main -> stringResource(R.string.screen_main_title)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (currentPage == ProxyScreenPage.Settings || currentPage == ProxyScreenPage.Diagnostics) {
                        IconButton(onClick = {
                            when {
                                currentPage == ProxyScreenPage.Settings &&
                                    settingsPage == SettingsPage.ROUTES &&
                                    routesSettingsPage != RoutesSettingsPage.OVERVIEW -> {
                                    routesSettingsPage = RoutesSettingsPage.OVERVIEW
                                }
                                currentPage == ProxyScreenPage.Settings &&
                                    settingsPage == SettingsPage.CLOUDFLARE &&
                                    cloudflareSettingsPage != CloudflareSettingsPage.OVERVIEW -> {
                                    cloudflareSettingsPage = CloudflareSettingsPage.OVERVIEW
                                }
                                currentPage == ProxyScreenPage.Settings && settingsPage != SettingsPage.HOME -> {
                                    settingsPage = SettingsPage.HOME
                                }
                                else -> {
                                    currentPage = ProxyScreenPage.Main
                                }
                            }
                            coroutineScope.launch { screenScroll.scrollTo(0) }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showTipsModal = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = stringResource(R.string.action_tips),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showInfoModal = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.action_info),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (currentPage == ProxyScreenPage.Main || currentPage == ProxyScreenPage.Diagnostics) {
                        IconButton(onClick = {
                            currentPage = ProxyScreenPage.Settings
                            settingsPage = SettingsPage.HOME
                        }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.action_settings),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            // Constrain content width for tablets to look good anywhere
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .verticalScroll(screenScroll),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top // Push top fields higher
            ) {
                if (currentPage == ProxyScreenPage.Main) {
                    ProxyAddressCard(
                        proxyAddress = proxyAddress,
                        isRunning = isRunning,
                        activeModeLabel = activePolicyChipText,
                        onCopyAddress = copyProxyAddressAction,
                    )

                    if (notificationPrefs.showMetricsInApp) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ProxyConnectionMetricsCard(
                            ui = uiMetrics.copy(
                                serviceStatus = when {
                                    !isRunning -> ProxyServiceStatus.STOPPED
                                    uiMetrics.serviceStatus == ProxyServiceStatus.STOPPED ->
                                        ProxyServiceStatus.RUNNING
                                    else -> uiMetrics.serviceStatus
                                },
                            ),
                            showMetrics = true,
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    AnimatedContent(
                        targetState = isRunning,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        },
                        label = "mainRunAnim"
                    ) { running ->
                        Button(
                            onClick = {
                                if (running) stopProxyAction() else startProxyAction()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                if (running) stringResource(R.string.stop_proxy) else stringResource(R.string.start_proxy),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    FilledTonalButton(
                        onClick = applyInTelegramAction,
                        enabled = isRunning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.apply_telegram),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!isRunning) {
                        Text(
                            stringResource(R.string.apply_telegram_disabled_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                } else if (currentPage == ProxyScreenPage.Diagnostics) {
                    DiagnosticsScreen(
                        state = diagnosticsScreenState.copy(
                            runtimeRoute = RuntimeRouteUiModel.from(enrichedRuntimeRoute()),
                        ),
                        onCheckAll = ::runDiagnosticsAll,
                        onCheckDirect = ::runDiagnosticsDirect,
                        onCheckWorker = ::runDiagnosticsWorker,
                        onCheckCloudflare = ::runDiagnosticsCloudflare,
                        onCheckNetwork = ::runDiagnosticsNetwork,
                        onCheckTelegram = ::runDiagnosticsTelegram,
                        onCopyReport = ::runDiagnosticsCopyReport,
                        onShareReport = ::runDiagnosticsShareReport,
                        workerPoolHealth = if (workerPoolEnabled) buildWorkerPoolHealthSummary() else null,
                        isCheckingWorkerHealth = isCheckingAllWorkers,
                        onCheckAllWorkers = if (workerPoolEnabled) ::runWorkerHealthCheckAll else null,
                        workerPoolUiState = if (workerPoolEnabled) buildWorkerPoolUiState() else null,
                        onOpenWorkerPool = if (workerPoolEnabled) {
                            {
                                currentPage = ProxyScreenPage.Settings
                                settingsPage = SettingsPage.CLOUDFLARE
                                cloudflareSettingsPage = CloudflareSettingsPage.WORKER_POOL
                            }
                        } else {
                            null
                        },
                        workerFailoverSummary = if (workerPoolEnabled) buildWorkerFailoverSummary() else null,
                        workerSelectionSummary = if (workerPoolEnabled) buildWorkerSelectionSummary() else null,
                    )
                } else {
                if (isRunning) {
                    RunningProxySettingsBanner()
                }
                val applyRecommendedRoutes: () -> Unit = {
                    RouteDefaultsMigration.applyRecommendedPreset1800(
                        prefs = prefs,
                        repository = routePolicyRepository,
                    )
                    wifiRoutePolicy = routePolicyRepository.load(NetworkProfileType.WIFI)
                    mobileRoutePolicy = routePolicyRepository.load(NetworkProfileType.MOBILE)
                    hasSavedWifiRoutePolicy = routePolicyRepository.hasSavedPolicy(NetworkProfileType.WIFI)
                    hasSavedMobileRoutePolicy = routePolicyRepository.hasSavedPolicy(NetworkProfileType.MOBILE)
                    refreshRoutePolicySnapshot()
                    Toast.makeText(
                        context,
                        context.getString(R.string.route_policy_recommended_applied),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                val settingsNetworkLabel = RoutePolicyDisplayNames.networkTypeLabel(
                    context,
                    currentNetworkProfile.type,
                )
                when (settingsPage) {
                    SettingsPage.HOME -> {
                        SettingsHomeScreen(
                            networkLabel = settingsNetworkLabel,
                            proxyAddress = proxyAddress,
                            routeLabel = activePolicyChipText,
                            applyRecommendedEnabled = !isRunning,
                            onNavigate = { settingsPage = it },
                            onApplyRecommendedRoutes = applyRecommendedRoutes,
                        )
                    }
                    SettingsPage.CONNECTION -> {
                        SettingsPageHeader(
                            titleRes = R.string.settings_section_connection_title,
                            subtitleRes = R.string.settings_section_connection_subtitle,
                        )
                if (cfProxyOnly) {
                    HintText(stringResource(R.string.cf_only_hint))
                }
                ProxyFrontendSettingsSection(
                    frontendType = localProxyFrontendType,
                    mtProtoConfig = mtProtoProxyConfig,
                    mtProtoUiStatus = mtProtoUiStatus,
                    controlsEnabled = !isRunning,
                    onFrontendTypeChange = ::selectLocalProxyFrontend,
                    onMtProtoFakeTlsDomainChange = ::updateMtProtoFakeTlsDomain,
                    onMtProtoFakeTlsPassthroughChange = ::updateMtProtoFakeTlsPassthrough,
                    onRegenerateSecret = {
                        regenerateMtProtoSecret()
                        Toast.makeText(
                            context,
                            context.getString(R.string.mtproto_secret_regenerated),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onCopyTelegramLink = copyMtProtoTelegramLinkAction,
                    onShareTelegramLink = shareMtProtoTelegramLinkAction,
                )
                if (isRunning) {
                    SettingsSectionLockedSummary(R.string.settings_connection_locked)
                } else {
                // Proxy Port Input
                OutlinedTextField(
                    value = portText,
                    onValueChange = ::updateProxyPort,
                    label = { Text(stringResource(R.string.proxy_port_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    singleLine = true
                )

                // DC selection modal button
                if (localProxyFrontendType == LocalProxyFrontendType.SOCKS5) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { showIpSetupModal = true }
                ) {
                    OutlinedTextField(
                        value = stringResource(R.string.configure_addresses),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.ip_settings_label)) },
                        enabled = false,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Pool size selector
                Text(
                    stringResource(R.string.ws_pool_size_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(4, 6, 8).forEach { size ->
                        val isSelected = selectedPoolSize == size
                        FilledTonalButton(
                            onClick = { 
                                selectedPoolSize = size
                                prefs.edit().putInt("pool", size).apply()
                            },
                            enabled = !isRunning,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(
                                "$size",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                }
                }
                    }
                    SettingsPage.ROUTES -> {
                        SettingsPageHeader(
                            titleRes = R.string.settings_section_routes_title,
                            subtitleRes = R.string.settings_section_routes_subtitle,
                        )
                        RoutesSettingsScreen(
                            page = routesSettingsPage,
                            onPageChange = { routesSettingsPage = it },
                            currentProfile = currentNetworkProfile,
                            connectionMode = connectionMode,
                            isProxyRunning = isRunning,
                            wifiPolicy = wifiRoutePolicy,
                            mobilePolicy = mobileRoutePolicy,
                            hasSavedWifiPolicy = hasSavedWifiRoutePolicy,
                            hasSavedMobilePolicy = hasSavedMobileRoutePolicy,
                            policySnapshot = routePolicySnapshot,
                            reconfigureStatus = reconfigureStatus,
                            routeProbeReport = routeProbeReport,
                            isRouteProbeRunning = isRouteProbeRunning,
                            onConnectionModeChange = { mode ->
                                connectionMode = mode
                                prefs.edit().putString("connection_mode", mode.prefValue).apply()
                                when (mode) {
                                    ConnectionMode.CFOnly -> {
                                        cfProxyOnly = true
                                        cfProxyPriority = true
                                        cfProxyEnabled = true
                                    }
                                    ConnectionMode.CFFirst -> {
                                        cfProxyOnly = false
                                        cfProxyPriority = true
                                        cfProxyEnabled = true
                                    }
                                    ConnectionMode.DirectOnly -> {
                                        cfProxyOnly = false
                                        cfProxyPriority = false
                                        cfProxyEnabled = false
                                    }
                                    ConnectionMode.WorkerOnly, ConnectionMode.WorkerFirst -> {
                                        cfProxyOnly = false
                                        cfProxyPriority = false
                                        cfProxyEnabled = true
                                        workerEnabled = true
                                    }
                                    else -> {
                                        cfProxyOnly = false
                                        cfProxyPriority = false
                                        cfProxyEnabled = true
                                    }
                                }
                                prefs.edit()
                                    .putBoolean("cfproxy_only", cfProxyOnly)
                                    .putBoolean("cfproxy_priority", cfProxyPriority)
                                    .putBoolean("cfproxy_enabled", cfProxyEnabled)
                                    .putBoolean("worker_enabled", workerEnabled)
                                    .apply()
                                refreshRoutePolicySnapshot()
                            },
                            onApplyRecommendedPreset = applyRecommendedRoutes,
                            onWifiPolicyChange = { policy ->
                                val normalized = NetworkRoutePolicyEditor.normalize(
                                    policy.copy(networkType = NetworkProfileType.WIFI),
                                )
                                routePolicyRepository.save(normalized)
                                RouteDefaultsMigration.markUserModified(prefs)
                                wifiRoutePolicy = routePolicyRepository.load(NetworkProfileType.WIFI)
                                hasSavedWifiRoutePolicy = routePolicyRepository.hasSavedPolicy(NetworkProfileType.WIFI)
                                refreshRoutePolicySnapshot()
                            },
                            onMobilePolicyChange = { policy ->
                                val normalized = NetworkRoutePolicyEditor.normalize(
                                    policy.copy(networkType = NetworkProfileType.MOBILE),
                                )
                                routePolicyRepository.save(normalized)
                                RouteDefaultsMigration.markUserModified(prefs)
                                mobileRoutePolicy = routePolicyRepository.load(NetworkProfileType.MOBILE)
                                hasSavedMobileRoutePolicy = routePolicyRepository.hasSavedPolicy(NetworkProfileType.MOBILE)
                                refreshRoutePolicySnapshot()
                            },
                            onResetWifiPolicy = {
                                routePolicyRepository.reset(NetworkProfileType.WIFI)
                                RouteDefaultsMigration.markUserModified(prefs)
                                wifiRoutePolicy = routePolicyRepository.load(NetworkProfileType.WIFI)
                                hasSavedWifiRoutePolicy = routePolicyRepository.hasSavedPolicy(NetworkProfileType.WIFI)
                                refreshRoutePolicySnapshot()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.route_policy_reset_done),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onResetMobilePolicy = {
                                routePolicyRepository.reset(NetworkProfileType.MOBILE)
                                RouteDefaultsMigration.markUserModified(prefs)
                                mobileRoutePolicy = routePolicyRepository.load(NetworkProfileType.MOBILE)
                                hasSavedMobileRoutePolicy = routePolicyRepository.hasSavedPolicy(NetworkProfileType.MOBILE)
                                refreshRoutePolicySnapshot()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.route_policy_reset_done),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onCopyPolicyDiagnostics = {
                                val markdown = RoutePolicyDiagnostics.formatMarkdown(
                                    context = context,
                                    snapshot = RoutePolicyDiagnostics.buildSnapshot(
                                        context,
                                        prefs,
                                        routePolicyRepository,
                                    ),
                                    maskSensitive = maskDomainsInReport,
                                )
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("route-policy-diagnostics", markdown))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.route_policy_diagnostics_copied),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onRunRouteProbe = runEffectiveRouteProbe,
                            onCopyRouteProbeReport = {
                                routeProbeReport?.let { report ->
                                    val markdown = RouteLevelDiagnosticsFormatter.formatMarkdown(
                                        context,
                                        report,
                                        maskSensitive = true,
                                        poolMetrics = uiMetrics.runtime,
                                    )
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("route-probe-report", markdown))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.route_probe_report_copied),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                    SettingsPage.CLOUDFLARE -> {
                        if (cloudflareSettingsPage == CloudflareSettingsPage.OVERVIEW) {
                            SettingsPageHeader(
                                titleRes = R.string.settings_section_cloudflare_title,
                                subtitleRes = R.string.settings_section_cloudflare_subtitle,
                            )
                        }
                        CloudflareSettingsScreen(
                            page = cloudflareSettingsPage,
                            onPageChange = { cloudflareSettingsPage = it },
                            isProxyRunning = isRunning,
                            workerDomainText = workerDomainText,
                            workerEnabled = workerEnabled,
                            workerNormalizeHint = workerNormalizeHint,
                            workerPoolEnabled = workerPoolEnabled,
                            workerPoolWorkers = workerPoolWorkers,
                            workerPoolSelectedWorker = workerPoolSelectedWorker,
                            workerPoolUiState = buildWorkerPoolUiState(),
                            maskDomainsInSettings = maskDomainsInReport,
                            onWorkerDomainChange = { value ->
                                workerDomainText = value
                                workerNormalizeHint = null
                                if (WorkerDomain.normalize(value).isNotBlank()) {
                                    workerEnabled = true
                                }
                            },
                            onWorkerDomainBlur = {
                                val normalized = WorkerDomain.normalize(workerDomainText)
                                if (normalized.isNotBlank() && normalized != workerDomainText.trim()) {
                                    workerDomainText = normalized
                                    workerNormalizeHint = context.getString(
                                        R.string.worker_domain_normalized,
                                        normalized,
                                    )
                                }
                                prefs.edit()
                                    .putString("worker_domain", WorkerDomain.normalize(workerDomainText))
                                    .putBoolean("worker_enabled", workerEnabled)
                                    .apply()
                            },
                            onWorkerEnabledChange = { enabled ->
                                workerEnabled = enabled
                                prefs.edit().putBoolean("worker_enabled", enabled).apply()
                            },
                            onWorkerPoolEnabledChange = { enabled ->
                                WorkerPoolUiLogger.poolEnabledChanged(enabled)
                                workerPoolRepository.setPoolEnabled(enabled)
                                refreshWorkerPoolState()
                            },
                            workerDestinationMode = workerDestinationMode,
                            flowsealMediaFixEnabled = flowsealMediaFixEnabled,
                            flowsealMediaFixDcText = flowsealMediaFixDcText,
                            flowsealMediaFixIpText = flowsealMediaFixIpText,
                            flowsealDcOnlyEnabled = flowsealDcOnlyEnabled,
                            onFlowsealDcOnlyEnabledChange = { enabled ->
                                flowsealDcOnlyEnabled = enabled
                                val texts = if (enabled) {
                                    FlowsealDcPreset.enable(
                                        prefs,
                                        DcIpTexts(dc1Text, dc2Text, dc4Text, dc203Text),
                                    )
                                } else {
                                    FlowsealDcPreset.disable(prefs)
                                }
                                applyDcTexts(texts)
                                if (enabled) {
                                    workerDestinationMode = WorkerDestinationMode.FLOWSEAL_DC_MAP
                                    prefs.edit()
                                        .putString(
                                            ProxyRuntimeConfigFactory.KEY_WORKER_DESTINATION_MODE,
                                            WorkerDestinationMode.FLOWSEAL_DC_MAP.prefValue,
                                        )
                                        .apply()
                                }
                                if (isRunning) {
                                    runNetworkReconfigure(NetworkProfileProvider.current(context))
                                }
                            },
                            onWorkerDestinationModeChange = { mode ->
                                workerDestinationMode = mode
                                prefs.edit()
                                    .putString(ProxyRuntimeConfigFactory.KEY_WORKER_DESTINATION_MODE, mode.prefValue)
                                    .apply()
                            },
                            onFlowsealMediaFixEnabledChange = { enabled ->
                                flowsealMediaFixEnabled = enabled
                                prefs.edit()
                                    .putBoolean(ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_ENABLED, enabled)
                                    .apply()
                            },
                            onFlowsealMediaFixDcChange = { flowsealMediaFixDcText = it },
                            onFlowsealMediaFixIpChange = { flowsealMediaFixIpText = it },
                            onFlowsealDestinationSettingsBlur = {
                                prefs.edit()
                                    .putInt(
                                        ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_DC,
                                        flowsealMediaFixDcText.toIntOrNull()
                                            ?: WorkerDestinationMode.DEFAULT_MEDIA_FIX_DC,
                                    )
                                    .putString(
                                        ProxyRuntimeConfigFactory.KEY_FLOWSEAL_MEDIA_FIX_IP,
                                        flowsealMediaFixIpText.trim().ifBlank {
                                            WorkerDestinationMode.DEFAULT_MEDIA_FIX_IP
                                        },
                                    )
                                    .apply()
                            },
                            onSelectionStrategyChange = { strategy ->
                                val previous = workerPoolSelectionStrategy
                                workerPoolRepository.setSelectionStrategy(strategy)
                                if (previous != strategy) {
                                    WorkerPoolUiLogger.strategyChanged(previous, strategy)
                                }
                                refreshWorkerPoolState()
                            },
                            onSelectWorker = { id ->
                                WorkerPoolUiLogger.workerSelected(id)
                                workerPoolRepository.selectWorker(id)
                                refreshWorkerPoolState()
                            },
                            onAddWorker = { name, url, enabled, priority ->
                                val result = workerPoolRepository.addWorker(
                                    WorkerEndpoint.create(
                                        name = name,
                                        url = url,
                                        enabled = enabled,
                                        priority = priority,
                                    ),
                                )
                                if (result.isSuccess) {
                                    val created = result.getOrThrow()
                                    WorkerPoolUiLogger.workerSaved(created.id)
                                    if (workerPoolRepository.getWorkerPoolConfig().selectedWorkerId.isNullOrBlank()) {
                                        workerPoolRepository.selectWorker(created.id)
                                    }
                                    refreshWorkerPoolState()
                                    null
                                } else {
                                    when ((result.exceptionOrNull() as? WorkerPoolOperationException)?.code) {
                                        WorkerPoolError.INVALID_WORKER_URL -> WorkerValidationError.INVALID_URL
                                        else -> WorkerValidationError.EMPTY_NAME
                                    }
                                }
                            },
                            onUpdateWorker = { worker ->
                                val result = workerPoolRepository.updateWorker(worker)
                                if (result.isSuccess) {
                                    WorkerPoolUiLogger.workerSaved(worker.id)
                                    refreshWorkerPoolState()
                                    null
                                } else {
                                    WorkerValidationError.INVALID_URL
                                }
                            },
                            onDeleteWorker = { id ->
                                WorkerPoolUiLogger.workerDeleted(id)
                                workerPoolRepository.removeWorker(id)
                                refreshWorkerPoolState()
                            },
                            onSetWorkerEnabled = { id, enabled ->
                                if (enabled) {
                                    WorkerPoolUiLogger.workerEnabled(id)
                                } else {
                                    WorkerPoolUiLogger.workerDisabled(id)
                                }
                                workerPoolRepository.setWorkerEnabled(id, enabled)
                                refreshWorkerPoolState()
                            },
                            onCheckWorker = ::runWorkerHealthCheck,
                            onCheckAllWorkers = ::runWorkerHealthCheckAll,
                            onWorkerPoolScreenOpened = { WorkerPoolUiLogger.screenOpened() },
                            onOpenDiagnosticsFromWorkerPool = {
                                currentPage = ProxyScreenPage.Diagnostics
                            },
                            onTestWorker = {
                                val probeDomain = if (workerPoolEnabled) {
                                    WorkerRouteResolver.resolveDomain(workerPoolRepository, workerDomainText)
                                } else {
                                    workerDomainText
                                }
                                if (WorkerDomain.normalize(probeDomain).isBlank()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.cf_worker_not_configured),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else if (!isDiagRunning) {
                                    isDiagRunning = true
                                    diagStatusText = context.getString(R.string.diag_running)
                                    coroutineScope.launch {
                                        val report = ConnectionDiagnostics.probeWorker(probeDomain)
                                        ConnectionDiagnostics.logReport(report)
                                        val status = if (report.successCount > 0) {
                                            context.getString(
                                                R.string.cf_worker_test_ok,
                                                report.successCount,
                                                report.totalCount,
                                            )
                                        } else {
                                            context.getString(R.string.cf_worker_test_fail)
                                        }
                                        diagStatusText = status
                                        prefs.edit().putString("diag_last_status", status).apply()
                                        isDiagRunning = false
                                        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onOpenWorkerHelp = {
                                openUrl("https://github.com/Regstar2/TgWsProxy_Android/blob/main/docs/cloudflare-worker.md")
                            },
                            manualCfDomainsText = manualCfDomainsText,
                            invalidManualCfDomains = invalidManualCfDomains,
                            onManualCfDomainsChange = ::applyManualCfDomains,
                            cfDomainRows = cfDomainRows,
                            cfDomainLastCheckAtMs = cfDomainLastCheckAtMs,
                            upstreamState = cfUpstreamState,
                            autoUpdateEnabled = autoUpdateCfDomains,
                            mirrorEnabled = cfMirrorEnabled,
                            mirrorUrl = cfMirrorUrlText,
                            mirrorValidationError = cfMirrorValidationError,
                            isUpdateRunning = isCfDomainUpdateRunning,
                            isDiagRunning = isDiagRunning,
                            onAutoUpdateChange = {
                                autoUpdateCfDomains = it
                                applyCfUpstreamState(cfDomainListRepository.setAutoUpdateEnabled(it))
                            },
                            onMirrorEnabledChange = { enabled ->
                                applyCfMirrorSettings(enabled, cfMirrorUrlText)
                            },
                            onMirrorUrlChange = { url ->
                                applyCfMirrorSettings(cfMirrorEnabled, url)
                            },
                            onTestCfDomains = {
                                if (!isDiagRunning) {
                                    isDiagRunning = true
                                    diagStatusText = context.getString(R.string.diag_running)
                                    coroutineScope.launch {
                                        val report = ConnectionDiagnostics.probeCfPool(
                                            manualCfDomains,
                                            cfUpstreamState.domains,
                                        )
                                        ConnectionDiagnostics.logReport(report.routeReport)
                                        cfDomainRows = report.domains
                                        cfDomainLastCheckAtMs = report.checkedAtMs
                                            val manual = report.summary.manualResult?.let {
                                                safeToastFormatArg(
                                                    "${it.domain} ${cfDomainStatusLabel(context, it.status())}",
                                                )
                                            } ?: context.getString(R.string.cf_domains_unset)
                                            val best = report.summary.bestDomain?.let {
                                                safeToastFormatArg("${it.domain}, ${it.lastLatencyMs ?: 0} ms")
                                            } ?: context.getString(R.string.cf_domains_none)
                                        val status = context.getString(
                                            R.string.cf_domains_test_summary,
                                            manual,
                                            report.summary.availableCachedUpstream,
                                            report.summary.failedCachedUpstream,
                                            report.summary.uncheckedCachedUpstream,
                                            report.summary.availableBuiltIn,
                                            report.summary.failedBuiltIn,
                                            report.summary.uncheckedBuiltIn,
                                            best,
                                        )
                                        diagStatusText = status
                                        prefs.edit().putString("diag_last_status", status).apply()
                                        isDiagRunning = false
                                        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onUpdateUpstream = {
                                launchCfDomainUpdate { cfDomainListUpdater.manualUpdate() }
                            },
                            onResetCooldown = {
                                CfDomainDiagnosticsState.resetCooldowns()
                                NativeProxy.resetCfDomainCooldowns()
                                cfDomainRows = CfDomainDiagnosticsState.snapshot(
                                    manualCfDomains,
                                    cfUpstreamState.domains,
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.cf_domains_cooldown_reset_done),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onTestPrimarySource = {
                                launchCfDomainUpdate { cfDomainListUpdater.testPrimarySource() }
                            },
                            onTestMirrorSource = {
                                launchCfDomainUpdate { cfDomainListUpdater.testMirrorSource() }
                            },
                            overviewFooter = {
                                if (connectionMode == ConnectionMode.Auto || connectionMode == ConnectionMode.DirectWithFallback) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    CollapsibleSettingsSection(
                                        titleRes = R.string.section_auto_route,
                                        initiallyExpanded = false,
                                    ) {
                                        val adaptiveProfile = NetworkProfileProvider.current(context)
                                        AdaptiveRoutingPanel(
                                            profile = adaptiveProfile,
                                            strategy = autoStrategy,
                                            stats = adaptiveRouteStatsRepository.snapshotForDisplay(adaptiveProfile.id),
                                            maskDomainsInReport = maskDomainsInReport,
                                            includeDomainsInLogExport = includeDomainsInLogExport,
                                            detailsExpanded = adaptiveDetailsExpanded,
                                            onDetailsExpandedChange = { adaptiveDetailsExpanded = it },
                                            onStrategyChange = { strategy ->
                                                autoStrategy = strategy
                                                prefs.edit().putString("auto_strategy", strategy.prefValue).apply()
                                                refreshRoutePolicySnapshot()
                                            },
                                            onMaskDomainsChange = { mask ->
                                                maskDomainsInReport = mask
                                                prefs.edit().putBoolean("mask_domains_in_export", mask).apply()
                                            },
                                            onIncludeDomainsInLogsChange = { include ->
                                                includeDomainsInLogExport = include
                                                prefs.edit().putBoolean("include_domains_in_log_export", include).apply()
                                            },
                                            onCopyReport = {
                                                val effectivePolicyMarkdown = RoutePolicyDiagnostics.formatMarkdown(
                                                    context = context,
                                                    snapshot = RoutePolicyDiagnostics.buildSnapshot(
                                                        context,
                                                        prefs,
                                                        routePolicyRepository,
                                                    ),
                                                    maskSensitive = maskDomainsInReport,
                                                )
                                                val markdown = AdaptiveDiagnosticsReport.buildMarkdown(
                                                    context = context,
                                                    versionName = appVersionName(context),
                                                    connectionMode = connectionMode,
                                                    strategy = autoStrategy,
                                                    profile = adaptiveProfile,
                                                    workerDomain = WorkerDomain.normalize(workerDomainText),
                                                    manualCfDomains = manualCfDomains,
                                                    cachedUpstreamCount = cfUpstreamState.domains.size,
                                                    builtInCount = CfDomain.builtInDomains.size,
                                                    stats = adaptiveRouteStatsRepository.snapshotForDisplay(adaptiveProfile.id),
                                                    maskDomains = maskDomainsInReport,
                                                    effectiveRoutePolicyMarkdown = effectivePolicyMarkdown,
                                                ) + routeProbeReport?.let { report ->
                                                    "\n\n" + RouteLevelDiagnosticsFormatter.formatMarkdown(
                                                        context,
                                                        report,
                                                        maskSensitive = true,
                                                        poolMetrics = uiMetrics.runtime,
                                                    )
                                                }.orEmpty()
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics", markdown))
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.adaptive_report_copied),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            },
                                            onResetAll = {
                                                adaptiveRouteStatsRepository.resetAll()
                                                NativeProxy.resetAdaptiveRouteStats(true)
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.adaptive_stats_reset_done),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            },
                                            onResetNetwork = {
                                                val profile = NetworkProfileProvider.current(context)
                                                adaptiveRouteStatsRepository.resetCurrentNetwork(profile.id)
                                                NativeProxy.resetAdaptiveNetworkRouteStats(profile.id)
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.adaptive_stats_reset_done),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    SettingsPage.DIAGNOSTICS_LOGS -> {
                        SettingsPageHeader(
                            titleRes = R.string.settings_section_diagnostics_logs_title,
                            subtitleRes = R.string.settings_section_diagnostics_logs_subtitle,
                        )
                CollapsibleSettingsSection(
                    titleRes = R.string.section_diagnostics,
                    initiallyExpanded = true,
                ) {
                val runDiag: (String, suspend () -> ConnectionProbeReport) -> Unit = { label, block ->
                    if (!isDiagRunning) {
                        isDiagRunning = true
                        diagStatusText = context.getString(R.string.diag_running)
                        coroutineScope.launch {
                            val report = block()
                            ConnectionDiagnostics.logReport(report)
                            val status = "$label: ${report.successCount}/${report.totalCount}"
                            diagStatusText = status
                            prefs.edit().putString("diag_last_status", status).apply()
                            isDiagRunning = false
                            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                CompactDiagnosticsSection(
                    lastStatus = diagStatusText,
                    isRunning = isRunning,
                    isDiagRunning = isDiagRunning,
                    onProbeAll = {
                        runDiag(diagLabelAll) {
                            ConnectionDiagnostics.probeAll(
                                workerDomainText,
                                manualCfDomains,
                                cfUpstreamState.domains,
                            )
                        }
                    },
                    onProbeDirect = { runDiag(diagLabelDirect) { ConnectionDiagnostics.probeDirectWs() } },
                    onProbeWorker = { runDiag(diagLabelWorker) { ConnectionDiagnostics.probeWorker(workerDomainText) } },
                    onProbeCf = {
                        runDiag(diagLabelCf) {
                            ConnectionDiagnostics.probeCfProxy(manualCfDomains, cfUpstreamState.domains)
                        }
                    },
                    onProbeTcp = { runDiag(diagLabelTcp) { ConnectionDiagnostics.probeTcpFallback() } },
                )
                OutlinedButton(
                    onClick = { currentPage = ProxyScreenPage.Diagnostics },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.diagnostics_open_screen))
                }
                }

                SettingsSectionCard(titleRes = R.string.section_logs) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.logs_enabled_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (logsEnabled) stringResource(R.string.logs_enabled_hint_on) else stringResource(R.string.logs_enabled_hint_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = logsEnabled,
                        onCheckedChange = {
                            logsEnabled = it
                            prefs.edit().putBoolean("logs_enabled", it).apply()
                        }
                    )
                }

                HintText(stringResource(R.string.settings_hint_logs))

                FilledTonalButton(
                    onClick = { reportFolderLauncher.launch(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.report_folder_button),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (reportFolderUriText.isNotBlank() || lastLogStatus.isNotBlank()) {
                    Text(
                        text = buildString {
                            append(
                                if (reportFolderUriText.isBlank()) {
                                    stringResource(R.string.report_folder_not_selected)
                                } else {
                                    stringResource(R.string.report_folder_selected)
                                }
                            )
                            if (lastLogStatus.isNotBlank()) {
                                append("\n")
                                append(lastLogStatus)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )
                }

                OutlinedButton(
                    onClick = { showRuntimeLogsDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.logs_show_runtime))
                }

                FilledTonalButton(
                    onClick = {
                        if (isSavingLogs) return@FilledTonalButton
                        if (reportFolderUriText.isBlank()) {
                            pendingFolderAction = PendingFolderAction.SaveRuntimeLogs
                            reportFolderLauncher.launch(null)
                        } else {
                            runRuntimeLogSave(Uri.parse(reportFolderUriText))
                        }
                    },
                    enabled = !isSavingLogs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (isSavingLogs) stringResource(R.string.saving_logs) else stringResource(R.string.save_logs),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Persistent file logging (optional)
                var persistentEnabled by rememberSaveable {
                    mutableStateOf(prefs.getBoolean("persistent_logs_enabled", false))
                }
                var persistentLevelName by rememberSaveable {
                    mutableStateOf(
                        prefs.getString("persistent_logs_level", PersistentLogVerbosity.IMPORTANT.name)
                            ?: PersistentLogVerbosity.IMPORTANT.name
                    )
                }
                var retentionDays by rememberSaveable {
                    mutableStateOf(prefs.getInt("persistent_logs_retention_days", 7).coerceAtLeast(1))
                }
                var maxSizeMb by rememberSaveable {
                    mutableStateOf(prefs.getInt("persistent_logs_max_size_mb", 50).coerceAtLeast(10))
                }
                var levelMenuExpanded by rememberSaveable { mutableStateOf(false) }
                var retentionMenuExpanded by rememberSaveable { mutableStateOf(false) }
                var sizeMenuExpanded by rememberSaveable { mutableStateOf(false) }
                var showClearPersistentConfirm by remember { mutableStateOf(false) }
                var persistentActionStatus by rememberSaveable { mutableStateOf("") }
                var persistentSizeText by rememberSaveable { mutableStateOf("") }

                LaunchedEffect(persistentEnabled, retentionDays, maxSizeMb) {
                    // Periodic size refresh; keep it lightweight.
                    val bytes = PersistentLogStore.totalSizeBytes(context)
                    persistentSizeText = ConnectionMetricsFormatter.formatBytes(bytes)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.persistent_logs_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                if (persistentEnabled) R.string.persistent_logs_enabled_hint else R.string.persistent_logs_disabled_hint
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = persistentEnabled,
                        onCheckedChange = {
                            persistentEnabled = it
                            PersistentLoggingPrefsStore.saveEnabled(prefs, it)
                            if (it) {
                                PersistentLogStore.initIfNeeded(context)
                            } else {
                                PersistentLogStore.flushAsync()
                            }
                        },
                    )
                }

                Text(
                    text = stringResource(R.string.persistent_logs_size, persistentSizeText.ifBlank { "—" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                val writeErr = PersistentLogStore.getLastWriteError()
                if (!writeErr.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.persistent_logs_write_error, writeErr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }

                // Level selector
                Text(stringResource(R.string.persistent_logs_level_title), style = MaterialTheme.typography.bodySmall)
                Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
                    OutlinedButton(
                        onClick = { levelMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Text(
                            text = when (runCatching { PersistentLogVerbosity.valueOf(persistentLevelName) }.getOrDefault(PersistentLogVerbosity.IMPORTANT)) {
                                PersistentLogVerbosity.ERRORS_ONLY -> stringResource(R.string.persistent_logs_level_errors)
                                PersistentLogVerbosity.IMPORTANT -> stringResource(R.string.persistent_logs_level_important)
                                PersistentLogVerbosity.VERBOSE -> stringResource(R.string.persistent_logs_level_verbose)
                                PersistentLogVerbosity.DEBUG -> stringResource(R.string.persistent_logs_level_debug)
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = levelMenuExpanded,
                        onDismissRequest = { levelMenuExpanded = false },
                    ) {
                        fun pick(v: PersistentLogVerbosity) {
                            persistentLevelName = v.name
                            PersistentLoggingPrefsStore.saveVerbosity(prefs, v)
                            levelMenuExpanded = false
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.persistent_logs_level_errors)) }, onClick = { pick(PersistentLogVerbosity.ERRORS_ONLY) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.persistent_logs_level_important)) }, onClick = { pick(PersistentLogVerbosity.IMPORTANT) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.persistent_logs_level_verbose)) }, onClick = { pick(PersistentLogVerbosity.VERBOSE) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.persistent_logs_level_debug)) }, onClick = { pick(PersistentLogVerbosity.DEBUG) })
                    }
                }

                // Retention selector
                Text(stringResource(R.string.persistent_logs_retention_title), style = MaterialTheme.typography.bodySmall)
                Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
                    val retentionOptions = listOf(1, 3, 7, 30)
                    OutlinedButton(
                        onClick = { retentionMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.persistent_logs_retention_value, retentionDays),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = retentionMenuExpanded,
                        onDismissRequest = { retentionMenuExpanded = false },
                    ) {
                        retentionOptions.forEach { days ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.persistent_logs_retention_value, days)) },
                                onClick = {
                                    retentionDays = days
                                    PersistentLoggingPrefsStore.saveRetentionDays(prefs, days)
                                    retentionMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                // Size selector
                Text(stringResource(R.string.persistent_logs_max_size_title), style = MaterialTheme.typography.bodySmall)
                Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
                    val sizeOptions = listOf(10, 50, 100)
                    OutlinedButton(
                        onClick = { sizeMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.persistent_logs_max_size_value, maxSizeMb),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = sizeMenuExpanded,
                        onDismissRequest = { sizeMenuExpanded = false },
                    ) {
                        sizeOptions.forEach { mb ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.persistent_logs_max_size_value, mb)) },
                                onClick = {
                                    maxSizeMb = mb
                                    PersistentLoggingPrefsStore.saveMaxSizeMb(prefs, mb)
                                    sizeMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                runCatching {
                                    val report = PersistentLogExport.exportZip(context)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_STREAM, report.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.persistent_logs_export)))
                                    persistentActionStatus = context.getString(
                                        R.string.persistent_logs_export_done,
                                        report.fileName,
                                        report.fileCount,
                                    )
                                }.onFailure { e ->
                                    persistentActionStatus = context.getString(
                                        R.string.persistent_logs_export_failed,
                                        e.javaClass.simpleName,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = true,
                    ) {
                        Text(stringResource(R.string.persistent_logs_export))
                    }
                    OutlinedButton(
                        onClick = { showClearPersistentConfirm = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.persistent_logs_clear))
                    }
                }

                if (persistentActionStatus.isNotBlank()) {
                    Text(
                        text = persistentActionStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                if (showClearPersistentConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearPersistentConfirm = false },
                        title = { Text(stringResource(R.string.persistent_logs_clear_title)) },
                        text = { Text(stringResource(R.string.persistent_logs_clear_message)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showClearPersistentConfirm = false
                                    coroutineScope.launch {
                                        runCatching {
                                            PersistentLogStore.clearAll(context)
                                            val bytes = PersistentLogStore.totalSizeBytes(context)
                                            persistentSizeText = ConnectionMetricsFormatter.formatBytes(bytes)
                                            persistentActionStatus = context.getString(R.string.persistent_logs_clear_done)
                                        }.onFailure { e ->
                                            persistentActionStatus = context.getString(
                                                R.string.persistent_logs_clear_failed,
                                                e.javaClass.simpleName,
                                            )
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.persistent_logs_clear_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearPersistentConfirm = false }) {
                                Text(stringResource(R.string.persistent_logs_clear_cancel))
                            }
                        },
                    )
                }
                }
                    }
                    SettingsPage.APP -> {
                        SettingsPageHeader(
                            titleRes = R.string.settings_section_app_title,
                            subtitleRes = R.string.settings_section_app_subtitle,
                        )
                SettingsSectionCard(
                    titleRes = R.string.section_notifications,
                    subtitle = stringResource(R.string.section_notifications_hint),
                ) {
                NotificationSettingsSection(
                    prefs = notificationPrefs,
                    onChange = { updated ->
                        notificationPrefs = updated
                        NotificationPreferences.save(context, updated)
                    },
                )
                }

                SettingsSectionCard(titleRes = R.string.section_appearance) {
                Text(
                    stringResource(R.string.theme_mode_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        ThemeMode.System to stringResource(R.string.theme_system),
                        ThemeMode.Light to stringResource(R.string.theme_light),
                        ThemeMode.Dark to stringResource(R.string.theme_dark)
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text(
                    stringResource(R.string.language_mode_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                val languageOptions = listOf(
                    LanguageMode.System to stringResource(R.string.language_system),
                    LanguageMode.Russian to stringResource(R.string.language_russian),
                    LanguageMode.English to stringResource(R.string.language_english)
                )
                val selectedLanguageLabel = languageOptions
                    .firstOrNull { it.first == languageMode }
                    ?.second
                    ?: stringResource(R.string.language_system)
                var languageMenuExpanded by rememberSaveable { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    OutlinedButton(
                        onClick = { languageMenuExpanded = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Text(
                            text = selectedLanguageLabel,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null
                        )
                    }
                    DropdownMenu(
                        expanded = languageMenuExpanded,
                        onDismissRequest = { languageMenuExpanded = false }
                    ) {
                        languageOptions.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    languageMenuExpanded = false
                                    onLanguageModeChange(mode)
                                }
                            )
                        }
                    }
                }

                Text(
                    stringResource(R.string.language_restart_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                }
                    }
                }
                }
            }
        }
    }

    if (showRuntimeLogsDialog) {
        RuntimeLogsDialog(
            logs = logs,
            logsEnabled = logsEnabled,
            onDismiss = { showRuntimeLogsDialog = false },
            onCopy = {
                val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Logs", logs.joinToString("\n")))
                Toast.makeText(context, context.getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
            },
            onSave = {
                if (reportFolderUriText.isBlank()) {
                    pendingFolderAction = PendingFolderAction.SaveRuntimeLogs
                    reportFolderLauncher.launch(null)
                } else {
                    runRuntimeLogSave(Uri.parse(reportFolderUriText))
                }
            },
        )
    }

    if (showOnboarding) {
        OnboardingDialog(
            onComplete = { dontShow ->
                if (dontShow) {
                    prefs.edit().putBoolean("onboarding_completed", true).apply()
                }
                showOnboarding = false
            },
            onDismiss = { showOnboarding = false },
        )
    }

    if (showInfoModal) {
        InfoDialog(onDismiss = { showInfoModal = false })
    }

    if (showTipsModal) {
        TipsDialog(
            onDismiss = { showTipsModal = false },
            onShowOnboarding = {
                showTipsModal = false
                showOnboarding = true
            },
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.exit_confirm_title)) },
            text = { Text(stringResource(R.string.exit_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        (context as? ComponentActivity)?.finish()
                    }
                ) {
                    Text(stringResource(R.string.exit_confirm_positive))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(stringResource(R.string.exit_confirm_negative))
                }
            }
        )
    }

    if (showIpSetupModal) {
        IpSetupDialog(
            flowsealDcOnlyEnabled = flowsealDcOnlyEnabled,
            dc1Text = dc1Text,
            onDc1Change = {
                if (flowsealDcOnlyEnabled) return@IpSetupDialog
                dc1Text = it
                prefs.edit().putString(DcIpTexts.KEY_DC1, it).apply()
            },
            dc2Text = dc2Text,
            onDc2Change = {
                if (flowsealDcOnlyEnabled) return@IpSetupDialog
                dc2Text = it
                prefs.edit().putString(DcIpTexts.KEY_DC2, it).apply()
            },
            dc4Text = dc4Text,
            onDc4Change = {
                if (flowsealDcOnlyEnabled) return@IpSetupDialog
                dc4Text = it
                prefs.edit().putString(DcIpTexts.KEY_DC4, it).apply()
            },
            dc203Text = dc203Text,
            onDc203Change = {
                if (flowsealDcOnlyEnabled) return@IpSetupDialog
                dc203Text = it
                prefs.edit().putString(DcIpTexts.KEY_DC203, it).apply()
            },
            onDismiss = { showIpSetupModal = false }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp)
    )
}

@Composable
private fun HintText(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

private fun effectiveRoutePolicyLog(prefix: String, config: ProxyRuntimeStartConfig): String {
    val policy = config.effectivePolicy.policy
    return "$prefix: networkType=${config.profile.type.prefValue} " +
        "source=${config.effectivePolicy.source} " +
        "legacyMode=${config.effectivePolicy.legacyMode.prefValue} " +
        "routes=${policy.enabledRoutes.joinToString("|") { it.prefValue }} " +
        "preferred=${policy.preferredRoute?.prefValue.orEmpty()} " +
        "fallback=${policy.allowFallback}"
}

private fun runtimeConfigKey(config: ProxyRuntimeStartConfig): String {
    return "${config.port}|${config.poolSize}|${config.ips}"
}

private fun appVersionName(context: Context): String {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}

private fun safeToastFormatArg(value: String): String = value.replace("%", "%%")

private fun mtProtoValidationMessage(
    context: Context,
    errors: Set<MtProtoProxyConfigValidationError>,
): String {
    val resId = when (errors.firstOrNull()) {
        MtProtoProxyConfigValidationError.EMPTY_HOST -> R.string.mtproto_validation_host_empty
        MtProtoProxyConfigValidationError.INVALID_PORT -> R.string.mtproto_validation_port_invalid
        MtProtoProxyConfigValidationError.INVALID_SECRET -> R.string.mtproto_validation_secret_invalid
        MtProtoProxyConfigValidationError.INVALID_FAKE_TLS_DOMAIN ->
            R.string.mtproto_validation_fake_tls_domain_invalid
        null -> R.string.mtproto_link_unavailable
    }
    return context.getString(resId)
}

private fun cfDomainStatusLabel(context: Context, status: CfDomainStatus): String {
    return context.getString(
        when (status) {
            CfDomainStatus.OK -> R.string.cf_domains_status_ok
            CfDomainStatus.FAILED -> R.string.cf_domains_status_failed
            CfDomainStatus.COOLDOWN -> R.string.cf_domains_status_cooldown
            CfDomainStatus.UNCHECKED -> R.string.cf_domains_status_unchecked
        }
    )
}

@Composable
fun IpSetupDialog(
    flowsealDcOnlyEnabled: Boolean = false,
    dc1Text: String, onDc1Change: (String) -> Unit,
    dc2Text: String, onDc2Change: (String) -> Unit,
    dc4Text: String, onDc4Change: (String) -> Unit,
    dc203Text: String, onDc203Change: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val onIpChange = { newValue: String, update: (String) -> Unit ->
        if (newValue.all { it.isDigit() || it == '.' }) {
            update(newValue)
        }
    }

    @Composable
    fun dcInput(label: String, value: String, update: (String) -> Unit, enabled: Boolean = true) {
        OutlinedTextField(
            value = value,
            onValueChange = { onIpChange(it, update) },
            label = { Text(label) },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            singleLine = true
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.dc_pool_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 20.dp),
                    fontWeight = FontWeight.SemiBold
                )

                if (flowsealDcOnlyEnabled) {
                    Text(
                        text = stringResource(R.string.flowseal_dc_only_ip_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                
                dcInput("DC1", dc1Text, onDc1Change, enabled = !flowsealDcOnlyEnabled)
                dcInput("DC2", dc2Text, onDc2Change, enabled = !flowsealDcOnlyEnabled)
                dcInput("DC4", dc4Text, onDc4Change, enabled = !flowsealDcOnlyEnabled)
                dcInput("DC203", dc203Text, onDc203Change, enabled = !flowsealDcOnlyEnabled)
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.done), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = appVersionName(context)
    val openLink = { url: String ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("url", url))
            Toast.makeText(context, context.getString(R.string.open_link_failed), Toast.LENGTH_SHORT).show()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.info_title, versionName),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = stringResource(R.string.info_current_build_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.info_current_build_body),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.info_mobile_body),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.info_cfproxy_body),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.info_custom_domain_body),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.info_usage_body),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.info_attribution_body),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.info_repo_body),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.info_privacy_body),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                Text(stringResource(R.string.repo_link_label), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "→ Regstar2/TgWsProxy_Android",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp, start = 8.dp).clickable {
                        openLink("https://github.com/Regstar2/TgWsProxy_Android")
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.runtime_link_label), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "→ Flowseal/tg-ws-proxy",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp, start = 8.dp).clickable { openLink("https://github.com/Flowseal/tg-ws-proxy") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.android_fork_link_label), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "→ amurcanov/tg-ws-proxy-android",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp, start = 8.dp).clickable { openLink("https://github.com/amurcanov/tg-ws-proxy-android") },
                )

                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { openLink("https://github.com/Regstar2/TgWsProxy_Android") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.about_open_repo))
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("version", versionName))
                            Toast.makeText(context, versionName, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.about_copy_version))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.info_close), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun TipsDialog(onDismiss: () -> Unit, onShowOnboarding: () -> Unit) {
    val sections = listOf(
        stringResource(R.string.tips_section_quick_start) to stringResource(R.string.tips_quick_start),
        stringResource(R.string.tips_section_telegram) to stringResource(R.string.tips_telegram),
        stringResource(R.string.tips_section_modes) to stringResource(R.string.tips_modes),
        stringResource(R.string.tips_section_worker) to stringResource(R.string.tips_worker),
        stringResource(R.string.tips_section_cf) to stringResource(R.string.tips_cf),
        stringResource(R.string.tips_section_notification) to stringResource(R.string.tips_notification),
        stringResource(R.string.tips_section_cf_custom) to stringResource(R.string.tips_cf_custom),
        stringResource(R.string.tips_section_troubleshooting) to stringResource(R.string.tips_troubleshooting),
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.tips_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                sections.forEach { (title, body) ->
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                }
                TextButton(onClick = onShowOnboarding) {
                    Text(stringResource(R.string.help_show_onboarding))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.info_close))
                    }
                }
            }
        }
    }
}

fun formatLogLine(raw: String): String {
    // Raw logcat line example:
    // 03-24 14:30:45.057 I/TgWsProxy(24567): INFO  11:30:45 WS pool warmup started...
    // Prefer logcat's own timestamp to avoid timezone/clock mismatches.
    val timePrefix = runCatching {
        // "MM-dd HH:mm:ss.SSS" -> time is at [6, 14)
        if (raw.length >= 14) raw.substring(6, 14) else ""
    }.getOrDefault("")
    val infoIdx = raw.indexOf("INFO  ")
    if (infoIdx >= 0) {
        val msg = raw.substring(infoIdx + 6).trim()
        return "• " + (if (timePrefix.isNotBlank()) "$timePrefix " else "") + msg
    }
    val warnIdx = raw.indexOf("WARN  ")
    if (warnIdx >= 0) {
        val msg = raw.substring(warnIdx + 6).trim()
        return "⚠ " + (if (timePrefix.isNotBlank()) "$timePrefix " else "") + msg
    }
    val errIdx = raw.indexOf("ERROR ")
    if (errIdx >= 0) {
        val msg = raw.substring(errIdx + 6).trim()
        return "✖ " + (if (timePrefix.isNotBlank()) "$timePrefix " else "") + msg
    }
    val dbgIdx = raw.indexOf("DEBUG ")
    if (dbgIdx >= 0) {
        val msg = raw.substring(dbgIdx + 6).trim()
        return "◦ " + (if (timePrefix.isNotBlank()) "$timePrefix " else "") + msg
    }
    // Fallback: try to find the message after ): 
    val msgIdx = raw.indexOf("): ")
    if (msgIdx >= 0) {
        val msg = raw.substring(msgIdx + 3).trim()
        return "• " + (if (timePrefix.isNotBlank()) "$timePrefix " else "") + msg
    }
    return raw.trim()
}

fun openTelegram(context: Context, url: String) {
    val pm = context.packageManager
    val uri = Uri.parse(url)
    
    for (pkg in telegramApps) {
        try {
            pm.getPackageInfo(pkg, 0)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage(pkg)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (e: PackageManager.NameNotFoundException) {
            // App not found, skip
        } catch (e: Exception) {
            // Activity not found or other err
        }
    }
    
    // Fallback: just open any app that handles tg:// link
    try {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(fallbackIntent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.telegram_not_found), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DiagnosticRunButton(
    label: String,
    proxyRunning: Boolean,
    isDiagRunning: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    prefs: android.content.SharedPreferences,
    onRunningChange: (Boolean) -> Unit,
    onStatusChange: (String) -> Unit,
    block: suspend () -> ConnectionProbeReport,
) {
    OutlinedButton(
        onClick = {
            if (isDiagRunning) return@OutlinedButton
            onRunningChange(true)
            onStatusChange(context.getString(R.string.diag_running))
            coroutineScope.launch {
                val report = block()
                ConnectionDiagnostics.logReport(report)
                val status = "$label: ${report.successCount}/${report.totalCount}"
                onStatusChange(status)
                prefs.edit().putString("diag_last_status", status).apply()
                onRunningChange(false)
                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
            }
        },
        enabled = !proxyRunning && !isDiagRunning,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
    ) {
        Text(label)
    }
}

object LogManager {
    private const val LOG_TAG = "TgWsProxy"
    private const val MAX_LOG_LINES = 400
    val logs = MutableStateFlow<List<String>>(emptyList())
    private var job: Job? = null
    private var logcatProcess: Process? = null

    fun startListening(context: Context) {
        if (job?.isActive == true) return
        logs.value = emptyList()
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
                val persistent = PersistentLoggingPrefsStore.load(prefs)
                val minLevel = if (persistent.enabled && persistent.verbosity == PersistentLogVerbosity.DEBUG) "D" else "I"
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "-s", "$LOG_TAG:$minLevel"))
                logcatProcess = process
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                while (isActive) {
                    val line = reader.readLine() ?: break
                    logs.update { current ->
                        val next = current + line
                        if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
                    }
                    runCatching {
                        val p = PersistentLoggingPrefsStore.load(prefs)
                        PersistentLogStore.logRawLogcatLine(context, p, line)
                    }
                }
            } catch (e: Exception) {
                logs.update { current ->
                    val next = current + "ERROR logcat reader failed: ${e.javaClass.simpleName}"
                    if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
                }
            } finally {
                logcatProcess?.destroy()
                logcatProcess = null
            }
        }
    }

    fun stopListening(clear: Boolean = true) {
        job?.cancel()
        job = null
        logcatProcess?.destroy()
        logcatProcess = null
        if (clear) {
            logs.value = emptyList()
        }
    }
}
