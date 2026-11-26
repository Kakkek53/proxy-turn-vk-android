package com.wdtt.client

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import android.content.res.Configuration
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.ui.ExceptionsTab
import com.wdtt.client.ui.LogsTab
import com.wdtt.client.ui.parseQrConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun isTvDevice(context: Context): Boolean =
    context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

private val TvColors = darkColorScheme(
    background = Color(0xFF151515),
    surface = Color(0xFF252525),
    primary = Color(0xFFF4F4F4),
    onPrimary = Color(0xFF151515)
)

private enum class TvSection(val label: String) {
    Configs("Конфиги"), Connection("Подключение"), Vk("Вход в ВК"), Bypass("Обход"), Logs("Логи")
}

@Composable
internal fun TvScreen(activity: MainActivity, settings: SettingsStore) {
    val profilesStore = remember { ProfilesStore(activity) }
    val profiles by profilesStore.profiles.collectAsStateWithLifecycle(emptyList())
    val currentId by settings.currentProfileId.collectAsStateWithLifecycle("")
    val mode by settings.connectionMode.collectAsStateWithLifecycle(SettingsStore.CONNECTION_MODE_VPN)
    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val connecting by TunnelManager.isConnecting.collectAsStateWithLifecycle()
    val pipeline by TunnelManager.connectionPipeline.collectAsStateWithLifecycle()
    val failure by TunnelManager.lastFatalError.collectAsStateWithLifecycle()
    val workers by settings.workersPerHash.collectAsStateWithLifecycle(9)
    val hashes by settings.vkHashes.collectAsStateWithLifecycle("")
    val vkMode by settings.vkAuthMode.collectAsStateWithLifecycle("anonymous")
    val visibleCall by settings.tvVisibleVkCall.collectAsStateWithLifecycle(false)
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf<TvSection?>(null) }
    var importMessage by rememberSaveable { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var vkMessage by rememberSaveable { mutableStateOf("") }

    val receiveConfig: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) scope.launch {
            val profile = withContext(Dispatchers.IO) {
                try {
                    val resolver = activity.contentResolver
                    val link = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?.trim()?.removePrefix("\uFEFF") ?: return@withContext null
                    if (link.any { it.isWhitespace() }) {
                        return@withContext null
                    }
                    val legacy = link.startsWith("wdtt://") || link.startsWith("wddt://")
                    val modern = link.startsWith("qwdtt://config") || link.startsWith("qwdtt:config") ||
                        link.startsWith("qwddt://config")
                    if (!legacy && !modern) return@withContext null
                    val normalized = when {
                        link.startsWith("wddt://") -> "wdtt://" + link.removePrefix("wddt://")
                        link.startsWith("qwddt://") -> "qwdtt://" + link.removePrefix("qwddt://")
                        else -> link
                    }
                    val parsed = parseQrConfig(normalized) ?: return@withContext null
                    if (!legacy) return@withContext parsed
                    val name = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    }.getOrNull()
                        ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
                    parsed.copy(name = name.replace(Regex("(?i)\\.json$"), "").ifBlank { parsed.name })
                } catch (_: java.io.IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
            if (profile == null) {
                importMessage = "Ошибка. В файле .json должна быть одна ссылка на конфиг."
            } else {
                try {
                    profilesStore.saveProfile(profile)
                    importMessage = "Конфиг добавлен"
                } catch (_: java.io.IOException) {
                    importMessage = "Не удалось сохранить конфиг"
                }
            }
        }
    }

    val getConfig = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), receiveConfig)
    val openConfig = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent {
            val intent = super.createIntent(context, input)
            @Suppress("DEPRECATION")
            val handlers = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            if (handlers.none {
                    val name = it.activityInfo.packageName
                    !name.startsWith("com.google.android.tv.frameworkpackagestubs") &&
                        !name.startsWith("com.android.tv.frameworkpackagestubs")
                }) throw ActivityNotFoundException()
            return intent
        }
    }, receiveConfig)

    BackHandler(section != null) { section = null }
    val homeFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val selected = profiles.any { it.id == currentId }
    LaunchedEffect(section, selected, applying) {
        if (section == null && !applying) {
            if (selected) homeFocus.requestFocus() else settingsFocus.requestFocus()
        }
    }

    MaterialTheme(colorScheme = TvColors) {
        Surface(Modifier.fillMaxSize(), color = TvColors.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp)) {
                val open = section
                if (open == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TvButton(
                            onClick = { section = TvSection.Configs },
                            modifier = Modifier.focusRequester(settingsFocus).size(52.dp),
                            bright = true
                        ) { Icon(Icons.Default.Settings, "Настройки") }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Сервера", style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.height(12.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(profiles, key = { it.id }) { profile ->
                                    TvRow(profile.name, profile.id == currentId, !running && !connecting && !applying) {
                                        applying = true
                                        scope.launch {
                                            try {
                                                profilesStore.applyProfile(activity, profile.id)
                                                if (settings.vkAuthMode.first() == "account") {
                                                    settings.saveTvWorkers(profile.workersPerHash.coerceIn(1, SettingsStore.VK_ACCOUNT_MAX_WORKERS))
                                                }
                                            } finally {
                                                applying = false
                                            }
                                        }
                                    }
                                }
                            }
                            if (profiles.isEmpty()) Text("Добавьте конфиг в настройках")
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            TvButton(
                                onClick = {
                                    if (running || connecting) TunnelControl.stop(activity)
                                    else if (mode == SettingsStore.CONNECTION_MODE_SOCKS) TunnelControl.startFromSavedSettings(activity)
                                    else activity.prepareVpnThen { TunnelControl.startFromSavedSettings(activity) }
                                },
                                modifier = Modifier.focusRequester(homeFocus).size(132.dp),
                                enabled = (selected || running || connecting) && !applying,
                                bright = !running,
                                chosen = running,
                                shape = CircleShape
                            ) {
                                Icon(Icons.Default.PowerSettingsNew, if (running || connecting) "Отключить" else "Подключить",
                                    Modifier.size(72.dp))
                            }
                            Spacer(Modifier.height(10.dp))
                            val stage = when {
                                pipeline.failed != null || pipeline.timedOut || failure != null -> "Ошибка"
                                connecting && pipeline.current in listOf(null, ConnectionStep.DNS, ConnectionStep.VK, ConnectionStep.CAPTCHA) -> "Подключение к звонку"
                                connecting -> "Подключение к серверу"
                                else -> ""
                            }
                            Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                                AnimatedContent(stage, label = "connectionStage") { Text(it) }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvButton(onClick = { section = null }, modifier = Modifier.size(52.dp), bright = true) {
                            Text("‹", style = MaterialTheme.typography.headlineMedium)
                        }
                        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(0.38f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TvSection.entries.forEach { item ->
                                TvRow(item.label, item == open) { section = item }
                            }
                        }
                        Box(Modifier.weight(0.62f).fillMaxHeight()) {
                            when (open) {
                                TvSection.Bypass -> ExceptionsTab()
                                TvSection.Logs -> LogsTab()
                                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    when (open) {
                                        TvSection.Configs -> {
                                            item {
                                                TvRow("+ Добавить конфиг из .json", selected = true) {
                                                    try {
                                                        openConfig.launch(arrayOf("*/*"))
                                                    } catch (_: ActivityNotFoundException) {
                                                        try {
                                                            getConfig.launch("*/*")
                                                        } catch (_: ActivityNotFoundException) {
                                                            importMessage = "На телевизоре нет приложения для выбора файла"
                                                        }
                                                    }
                                                }
                                            }
                                            items(profiles, key = { it.id }) { profile ->
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text(profile.name, Modifier.weight(1f).padding(12.dp))
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                        TvButton(onClick = {
                                                            scope.launch {
                                                                val saved = withContext(Dispatchers.IO) {
                                                                    val port = if (settings.manualPortsEnabled.first()) settings.serverDtlsPort.first() else 56000
                                                                    saveTvConfig(activity, profile, port)
                                                                }
                                                                Toast.makeText(activity, if (saved) "Конфиг сохранён в Downloads" else "Не удалось сохранить конфиг", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }) { Text("Экспорт .json") }
                                                    }
                                                }
                                            }
                                            if (importMessage.isNotEmpty()) item { Text(importMessage) }
                                        }
                                        TvSection.Connection -> {
                                            item { Text("Режим подключения") }
                                            items(listOf("WG" to SettingsStore.CONNECTION_MODE_VPN,
                                                "RAW" to SettingsStore.CONNECTION_MODE_RAWTUN,
                                                "SOCKS5" to SettingsStore.CONNECTION_MODE_SOCKS)) { (label, value) ->
                                                TvRow(label, mode == value, !running && !connecting) {
                                                    scope.launch { settings.saveConnectionMode(value) }
                                                }
                                            }
                                            item {
                                                val account = vkMode == "account"
                                                val step = if (account) 1 else SettingsStore.WORKERS_PER_VK_GROUP
                                                val max = if (account) SettingsStore.VK_ACCOUNT_MAX_WORKERS else {
                                                    SettingsStore.maxAnonymousWorkers(hashes.split(Regex("[,\\s]+"))
                                                        .filter { it.length >= 16 }.distinct().size)
                                                }
                                                val power = workers.coerceIn(step, max)
                                                Row(verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text("Мощность: $power", Modifier.weight(1f))
                                                    TvButton({ scope.launch { settings.saveTvWorkers((power - step).coerceAtLeast(step)) } },
                                                        Modifier.size(46.dp), enabled = power > step && !running && !connecting) { Text("−") }
                                                    TvButton({ scope.launch { settings.saveTvWorkers((power + step).coerceAtMost(max)) } },
                                                        Modifier.size(46.dp), enabled = power < max && !running && !connecting) { Text("+") }
                                                }
                                            }
                                        }
                                        TvSection.Vk -> {
                                            item {
                                                TvRow("Войти в ВК", enabled = !loggingIn && !running && !connecting) {
                                                    loggingIn = true
                                                    scope.launch {
                                                        try {
                                                            val result = VkAuthWebViewManager.loginOnly(activity)
                                                            if (result.isSuccess) {
                                                                settings.saveVkAuthMode("account")
                                                                settings.saveTvWorkers(workers.coerceIn(1, SettingsStore.VK_ACCOUNT_MAX_WORKERS))
                                                            }
                                                            vkMessage = if (result.isSuccess) "Вход выполнен" else "Ошибка входа"
                                                        } finally {
                                                            loggingIn = false
                                                        }
                                                    }
                                                }
                                            }
                                            item {
                                                TvRow("Окно звонка ВК: " + if (visibleCall) "видимое" else "скрытое") {
                                                    scope.launch { settings.saveTvVisibleVkCall(!visibleCall) }
                                                }
                                            }
                                            if (vkMessage.isNotEmpty()) item { Text(vkMessage) }
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@android.annotation.TargetApi(Build.VERSION_CODES.Q)
private fun saveTvConfig(context: Context, profile: ConnectionProfile, serverPort: Int): Boolean {
    val resolver = context.contentResolver
    val name = profile.name.map { if (it in "/\\:" || it.isISOControl()) '_' else it }.joinToString("")
        .trim().trim('.').take(100).ifBlank { "qWDTT" }
    val peer = PeerAddress.ensurePort(profile.peer, serverPort)
    val link = android.net.Uri.Builder().scheme("qwdtt").authority("config")
        .appendQueryParameter("name", profile.name)
        .appendQueryParameter("peer", peer)
        .appendQueryParameter("hashes", profile.vkHashes)
        .appendQueryParameter("workers", profile.workersPerHash.toString())
        .appendQueryParameter("port", profile.listenPort.toString())
        .appendQueryParameter("pass", profile.password)
        .build().toString()
    var uri: android.net.Uri? = null
    return try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        uri = destination
        resolver.openOutputStream(destination)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(link) }
            ?: throw java.io.IOException("Downloads stream unavailable")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        if (resolver.update(destination, values, null, null) == 0) throw java.io.IOException("Downloads entry unavailable")
        true
    } catch (_: Exception) {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        false
    }
}

@Composable
private fun TvRow(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    TvButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled, chosen = selected) {
        Text(label, Modifier.fillMaxWidth().padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bright: Boolean = false,
    chosen: Boolean = false,
    shape: Shape = RoundedCornerShape(10.dp),
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        focused && enabled -> Color(0xFFD1E8FF)
        bright -> TvColors.primary
        chosen -> Color(0xFFBDBDBD)
        else -> TvColors.surface
    }
    Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        enabled = enabled,
        shape = shape,
        contentPadding = PaddingValues(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = if (focused || bright || chosen) TvColors.onPrimary else Color.White,
            disabledContainerColor = container,
            disabledContentColor = if (bright || chosen) TvColors.onPrimary else Color.LightGray
        ),
        content = content
    )
}
