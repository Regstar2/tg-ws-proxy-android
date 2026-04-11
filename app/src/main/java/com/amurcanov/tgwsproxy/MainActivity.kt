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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.InputStreamReader
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

private const val APP_INFO_VERSION = "1.2.5-ui"

private enum class PendingFolderAction {
    SaveRuntimeLogs,
}

private enum class ProxyScreenPage {
    Main,
    Settings,
}

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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Ignored in this example, but handles Tiramisu+ notifications
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activityPrefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        applyLanguageMode(parseLanguageMode(activityPrefs.getString("language_mode", LanguageMode.System.name)))
        
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
    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    var currentPage by rememberSaveable { mutableStateOf(ProxyScreenPage.Main) }
    var dc1Text by remember { mutableStateOf(prefs.getString("dc1", "149.154.167.220") ?: "149.154.167.220") }
    var dc2Text by remember { mutableStateOf(prefs.getString("dc2", "149.154.167.220") ?: "149.154.167.220") }
    var dc4Text by remember { mutableStateOf(prefs.getString("dc4", "149.154.167.220") ?: "149.154.167.220") }
    var dc203Text by remember { mutableStateOf(prefs.getString("dc203", "149.154.167.220") ?: "149.154.167.220") }
    var portText by remember { mutableStateOf(prefs.getString("port", "1081") ?: "1081") }
    var selectedPoolSize by remember { mutableStateOf(prefs.getInt("pool", 4)) }
    var cfProxyEnabled by remember { mutableStateOf(prefs.getBoolean("cfproxy_enabled", true)) }
    var cfProxyPriority by remember { mutableStateOf(prefs.getBoolean("cfproxy_priority", true)) }
    var cfProxyOnly by remember { mutableStateOf(prefs.getBoolean("cfproxy_only", false)) }
    var cfProxyDomainText by remember { mutableStateOf(prefs.getString("cfproxy_domain", "pclead.co.uk") ?: "pclead.co.uk") }
    var logsEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean("logs_enabled", true)) }
    var showLogs by rememberSaveable { mutableStateOf(true) }
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
    val hasOpenModal = showInfoModal || showTipsModal || showIpSetupModal || showExitConfirm

    BackHandler(enabled = currentPage == ProxyScreenPage.Settings && !hasOpenModal) {
        currentPage = ProxyScreenPage.Main
    }

    BackHandler(enabled = currentPage == ProxyScreenPage.Main && !hasOpenModal) {
        showExitConfirm = true
    }

    val runRuntimeLogSave by rememberUpdatedState<(Uri) -> Unit> { treeUri ->
        if (isSavingLogs) return@rememberUpdatedState
        isSavingLogs = true
        val snapshot = logs
        coroutineScope.launch {
            val report = RuntimeLogExport.save(
                context = context,
                treeUri = treeUri,
                logs = snapshot,
                proxyRunning = isRunning
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
            LogManager.startListening()
        } else {
            LogManager.stopListening(clear = false)
        }
        onDispose {
            if (logsEnabled) {
                LogManager.stopListening(clear = false)
            }
        }
    }

    LaunchedEffect(showLogs) {
        if (showLogs) {
            delay(120)
            screenScroll.animateScrollTo(screenScroll.maxValue)
        }
    }

    val startProxyAction by rememberUpdatedState {
        val port = portText.toIntOrNull()
        if (port == null) {
            Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
            return@rememberUpdatedState
        }
        val parsedIps = buildList {
            if (dc1Text.isNotBlank()) add("1:${dc1Text.trim()}")
            if (dc2Text.isNotBlank()) add("2:${dc2Text.trim()}")
            if (dc4Text.isNotBlank()) add("4:${dc4Text.trim()}")
            if (dc203Text.isNotBlank()) add("203:${dc203Text.trim()}")
            add("@cfproxy=${if (cfProxyEnabled) 1 else 0}")
            add("@cfproxy_priority=${if (cfProxyPriority) 1 else 0}")
            add("@cfproxy_only=${if (cfProxyOnly) 1 else 0}")
            if (cfProxyDomainText.isNotBlank()) add("@cfproxy_domain=${cfProxyDomainText.trim()}")
        }.joinToString(",")

        if (parsedIps.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.dc_ip_required), Toast.LENGTH_SHORT).show()
            return@rememberUpdatedState
        }
        
        val startIntent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
            putExtra(ProxyService.EXTRA_PORT, port)
            putExtra(ProxyService.EXTRA_IPS, parsedIps)
            putExtra(ProxyService.EXTRA_POOL_SIZE, selectedPoolSize)
        }
        ContextCompat.startForegroundService(context, startIntent)
    }

    val stopProxyAction by rememberUpdatedState {
        val stopIntent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    val applyInTelegramAction by rememberUpdatedState {
        val port = portText.toIntOrNull() ?: 1081
        val proxyUrl = "tg://socks?server=127.0.0.1&port=$port"
        openTelegram(context, proxyUrl)
    }
    val proxyAddress = "127.0.0.1:${portText.ifBlank { "1081" }}"
    val proxyModeText = when {
        cfProxyOnly -> "CF only"
        cfProxyPriority -> "CF first"
        cfProxyEnabled -> "Direct + CF fallback"
        else -> "Direct"
    }
    val copyProxyAddressAction by rememberUpdatedState {
        val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Proxy address", proxyAddress))
        Toast.makeText(context, context.getString(R.string.proxy_address_copied), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (currentPage == ProxyScreenPage.Settings) {
                            stringResource(R.string.screen_settings_title)
                        } else {
                            stringResource(R.string.screen_main_title)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (currentPage == ProxyScreenPage.Settings) {
                        IconButton(onClick = { currentPage = ProxyScreenPage.Main }) {
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
                    if (currentPage == ProxyScreenPage.Main) {
                        IconButton(onClick = { currentPage = ProxyScreenPage.Settings }) {
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
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.proxy_address_label),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                ) {
                                    Text(
                                        text = if (isRunning) stringResource(R.string.status_running) else stringResource(R.string.status_stopped),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = proxyAddress,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = copyProxyAddressAction) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.copy_proxy_address),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.active_mode_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = proxyModeText,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
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

                } else {
                SectionTitle(stringResource(R.string.section_connection))
                if (cfProxyOnly) {
                    HintText(stringResource(R.string.cf_only_hint))
                }

                // Proxy Port Input
                OutlinedTextField(
                    value = portText,
                    onValueChange = { 
                        portText = it
                        prefs.edit().putString("port", it).apply()
                    },
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

                SectionTitle(stringResource(R.string.section_cloudflare))

                OutlinedTextField(
                    value = cfProxyDomainText,
                    onValueChange = {
                        cfProxyDomainText = it
                        prefs.edit().putString("cfproxy_domain", it).apply()
                    },
                    enabled = !isRunning,
                    label = { Text(stringResource(R.string.cf_domain_label)) },
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.cf_proxy_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.cf_proxy_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = cfProxyEnabled,
                        enabled = !isRunning,
                        onCheckedChange = {
                            cfProxyEnabled = it
                            if (!it) {
                                cfProxyOnly = false
                                prefs.edit().putBoolean("cfproxy_only", false).apply()
                            }
                            prefs.edit().putBoolean("cfproxy_enabled", it).apply()
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.cf_first_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.cf_first_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = cfProxyPriority,
                        enabled = !isRunning && cfProxyEnabled,
                        onCheckedChange = {
                            cfProxyPriority = it
                            prefs.edit().putBoolean("cfproxy_priority", it).apply()
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.cf_only_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.cf_only_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = cfProxyOnly,
                        enabled = !isRunning,
                        onCheckedChange = {
                            cfProxyOnly = it
                            if (it) {
                                cfProxyEnabled = true
                                cfProxyPriority = true
                                prefs.edit()
                                    .putBoolean("cfproxy_enabled", true)
                                    .putBoolean("cfproxy_priority", true)
                                    .apply()
                            }
                            prefs.edit().putBoolean("cfproxy_only", it).apply()
                        }
                    )
                }

                SectionTitle(stringResource(R.string.section_appearance))
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
                        .padding(bottom = 16.dp)
                )

                SectionTitle(stringResource(R.string.section_logs))

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

                SectionTitle(stringResource(R.string.section_diagnostics))

                // Logs toggle button — same style as main buttons
                Button(
                    onClick = { showLogs = !showLogs },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(
                        if (showLogs) stringResource(R.string.hide_logs) else stringResource(R.string.show_logs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (isSavingLogs) stringResource(R.string.saving_logs) else stringResource(R.string.save_logs),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (showLogs) {
                    val scroll = rememberScrollState()
                    val primaryColor = MaterialTheme.colorScheme.primary
                    val density = LocalDensity.current
                    val trackPadding = 12.dp
                    val scrollbarWidth = 4.dp

                    // Auto-scroll to bottom when new logs arrive
                    LaunchedEffect(logs.size) {
                        scroll.animateScrollTo(scroll.maxValue)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Runtime logs (${logs.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 320.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            text = if (!logsEnabled) {
                                stringResource(R.string.logs_disabled)
                            } else if (logs.isEmpty()) {
                                stringResource(R.string.logs_empty)
                            } else {
                                logs.joinToString("\n") { formatLogLine(it) }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 12.dp, end = 40.dp, top = 12.dp, bottom = 12.dp)
                                .verticalScroll(scroll),
                            color = primaryColor,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.5
                        )

                        val trackHeightPx = max(
                            with(density) { maxHeight.toPx() - trackPadding.toPx() * 2 },
                            0f
                        )
                        val maxScrollPx = scroll.maxValue.toFloat()
                        val minThumbPx = with(density) { 28.dp.toPx() }
                        val thumbHeightPx = if (trackHeightPx <= 0f || maxScrollPx <= 0f) {
                            trackHeightPx
                        } else {
                            max(minThumbPx, (trackHeightPx * trackHeightPx) / (trackHeightPx + maxScrollPx))
                        }
                        val thumbOffsetPx = if (maxScrollPx <= 0f || trackHeightPx <= thumbHeightPx) {
                            0f
                        } else {
                            (scroll.value / maxScrollPx) * (trackHeightPx - thumbHeightPx)
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = trackPadding, bottom = trackPadding, end = 8.dp)
                                .fillMaxHeight()
                                .width(scrollbarWidth)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = trackPadding, end = 8.dp)
                                .offset(y = with(density) { thumbOffsetPx.toDp() })
                                .width(scrollbarWidth)
                                .height(with(density) { thumbHeightPx.toDp() })
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        )
                        IconButton(
                            onClick = {
                                val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("Logs", logs.joinToString("\n")))
                                Toast.makeText(context, context.getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                stringResource(R.string.copy_logs),
                                tint = primaryColor.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                }
            }
        }
    }

    if (showInfoModal) {
        InfoDialog(onDismiss = { showInfoModal = false })
    }

    if (showTipsModal) {
        TipsDialog(onDismiss = { showTipsModal = false })
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
            dc1Text = dc1Text,
            onDc1Change = {
                dc1Text = it
                prefs.edit().putString("dc1", it).apply()
            },
            dc2Text = dc2Text,
            onDc2Change = { 
                dc2Text = it
                prefs.edit().putString("dc2", it).apply()
            },
            dc4Text = dc4Text,
            onDc4Change = { 
                dc4Text = it
                prefs.edit().putString("dc4", it).apply()
            },
            dc203Text = dc203Text,
            onDc203Change = { 
                dc203Text = it
                prefs.edit().putString("dc203", it).apply()
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

@Composable
fun IpSetupDialog(
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
    fun dcInput(label: String, value: String, update: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = { onIpChange(it, update) },
            label = { Text(label) },
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
                
                dcInput("DC1", dc1Text, onDc1Change)
                dcInput("DC2", dc2Text, onDc2Change)
                dcInput("DC4", dc4Text, onDc4Change)
                dcInput("DC203", dc203Text, onDc203Change)
                
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
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.info_title, APP_INFO_VERSION),
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
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                val openLink = { url: String ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.open_link_failed), Toast.LENGTH_SHORT).show()
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.runtime_link_label), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "→ Flowseal/tg-ws-proxy",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp, start = 8.dp).clickable { openLink("https://github.com/Flowseal/tg-ws-proxy") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.android_fork_link_label), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "→ amurcanov/tg-ws-proxy-android",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp, start = 8.dp).clickable { openLink("https://github.com/amurcanov/tg-ws-proxy-android") }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

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
fun TipsDialog(onDismiss: () -> Unit) {
    val tips = listOf(
        stringResource(R.string.main_hint_bydpi),
        stringResource(R.string.main_hint_background),
        stringResource(R.string.cf_only_hint),
        stringResource(R.string.cf_default_domain_hint),
        stringResource(R.string.cf_media_hint),
        stringResource(R.string.cf_custom_domain_hint),
        stringResource(R.string.settings_hint_logs),
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.tips_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                tips.forEach { tip ->
                    Text(
                        text = "• $tip",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
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

fun formatLogLine(raw: String): String {
    // Raw logcat line example:
    // 03-24 14:30:45.057 I/TgWsProxy(24567): INFO  11:30:45 WS pool warmup started...
    // We want to extract: "11:30:45 WS pool warmup started..."
    val infoIdx = raw.indexOf("INFO  ")
    if (infoIdx >= 0) {
        return "• " + raw.substring(infoIdx + 6).trim()
    }
    val warnIdx = raw.indexOf("WARN  ")
    if (warnIdx >= 0) {
        return "⚠ " + raw.substring(warnIdx + 6).trim()
    }
    val errIdx = raw.indexOf("ERROR ")
    if (errIdx >= 0) {
        return "✖ " + raw.substring(errIdx + 6).trim()
    }
    val dbgIdx = raw.indexOf("DEBUG ")
    if (dbgIdx >= 0) {
        return "◦ " + raw.substring(dbgIdx + 6).trim()
    }
    // Fallback: try to find the message after ): 
    val msgIdx = raw.indexOf("): ")
    if (msgIdx >= 0) {
        return "• " + raw.substring(msgIdx + 3).trim()
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

object LogManager {
    private const val LOG_TAG = "TgWsProxy"
    private const val MAX_LOG_LINES = 400
    val logs = MutableStateFlow<List<String>>(emptyList())
    private var job: Job? = null
    private var logcatProcess: Process? = null

    fun startListening() {
        if (job?.isActive == true) return
        logs.value = emptyList()
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "-s", "$LOG_TAG:I"))
                logcatProcess = process
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                while (isActive) {
                    val line = reader.readLine() ?: break
                    logs.update { current ->
                        val next = current + line
                        if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
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
