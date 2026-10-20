package app.harmonic.music.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.harmonic.music.BuildConfig
import app.harmonic.music.CrashLog
import app.harmonic.music.R
import app.harmonic.music.data.prefs.AppSettings
import app.harmonic.music.data.prefs.AudioQuality
import app.harmonic.music.data.prefs.InterruptAction
import app.harmonic.music.data.prefs.ThemeMode
import app.harmonic.music.data.prefs.ThemePalette
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.Routes
import app.harmonic.music.ui.theme.paletteSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val context = LocalContext.current
    val res = context.resources
    val s by c.settings.state.collectAsStateWithLifecycle()
    val cookies by c.account.cookies.collectAsStateWithLifecycle()
    val signedIn = cookies != null && c.account.isLoggedIn
    var qualityDialog by remember { mutableStateOf<QualityTarget?>(null) }
    var cacheDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var paletteDialog by remember { mutableStateOf(false) }
    var interruptDialog by remember { mutableStateOf<InterruptTarget?>(null) }
    var crashCount by remember { mutableIntStateOf(CrashLog.reports(context).size) }
    var cacheUsed by remember { mutableLongStateOf(-1L) }
    var downloadsUsed by remember { mutableLongStateOf(-1L) }

    fun runAsync(block: suspend () -> Unit) {
        c.appScope.launch(Dispatchers.Main) { block() }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runAsync {
            runCatching { c.backup.export(uri) }
                .onSuccess { sum ->
                    actions.showMessage(
                        res.getString(
                            R.string.backup_saved,
                            res.getQuantityString(R.plurals.songs_count, sum.songs, sum.songs),
                            res.getQuantityString(R.plurals.playlists_count, sum.playlists, sum.playlists),
                        ),
                    )
                }
                .onFailure { actions.showMessage(res.getString(R.string.backup_failed)) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runAsync {
            runCatching { c.backup.import(uri) }
                .onSuccess { sum ->
                    actions.showMessage(
                        res.getString(
                            R.string.restore_done,
                            res.getQuantityString(R.plurals.songs_count, sum.songs, sum.songs),
                            res.getQuantityString(R.plurals.playlists_count, sum.playlists, sum.playlists),
                        ),
                    )
                }
                .onFailure {
                    actions.showMessage(
                        res.getString(if (it.message == "NOT_A_BACKUP") R.string.restore_not_backup else R.string.restore_failed),
                    )
                }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cacheUsed = c.playerCache.cacheSpace
            downloadsUsed = c.downloadCache.cacheSpace
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header(R.string.appearance)
            ClickRow(stringResource(R.string.theme), stringResource(s.themeMode.label)) { themeDialog = true }
            ClickRow(stringResource(R.string.colours), stringResource(s.palette.label)) { paletteDialog = true }
            SwitchRow(
                stringResource(R.string.pure_black),
                stringResource(R.string.pure_black_desc),
                s.pureBlack,
            ) { runAsync { c.settings.setPureBlack(it) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClickRow(stringResource(R.string.app_language), stringResource(R.string.app_language_desc)) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }
                }
            }

            Header(R.string.audio_quality)
            ClickRow(stringResource(R.string.on_wifi), stringResource(s.qualityWifi.label)) { qualityDialog = QualityTarget.WIFI }
            ClickRow(stringResource(R.string.on_mobile), stringResource(s.qualityMobile.label)) { qualityDialog = QualityTarget.MOBILE }
            ClickRow(stringResource(R.string.downloads), stringResource(s.qualityDownload.label)) { qualityDialog = QualityTarget.DOWNLOAD }

            Header(R.string.data_battery)
            SwitchRow(stringResource(R.string.wifi_only_downloads), stringResource(R.string.wifi_only_downloads_desc), s.downloadWifiOnly) {
                runAsync { c.settings.setDownloadWifiOnly(it) }
                c.downloads.setWifiOnly(it)
            }
            SwitchRow(stringResource(R.string.small_artwork), stringResource(R.string.small_artwork_desc), s.dataSaverImages) {
                runAsync { c.settings.setDataSaverImages(it) }
            }
            SwitchRow(stringResource(R.string.audio_offload), stringResource(R.string.audio_offload_desc), s.audioOffload) {
                runAsync { c.settings.setAudioOffload(it) }
            }
            ClickRow(
                stringResource(R.string.streaming_cache),
                if (cacheUsed >= 0) {
                    stringResource(R.string.cache_limit_used, s.cacheSizeMb, Formatter.formatShortFileSize(context, cacheUsed))
                } else {
                    stringResource(R.string.cache_limit, s.cacheSizeMb)
                },
            ) { cacheDialog = true }
            ClickRow(stringResource(R.string.clear_cache), stringResource(R.string.clear_cache_desc)) {
                runAsync {
                    withContext(Dispatchers.IO) {
                        val cache = c.playerCache
                        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
                        cacheUsed = cache.cacheSpace
                    }
                    actions.showMessage(res.getString(R.string.cache_cleared))
                }
            }
            if (downloadsUsed >= 0) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.downloads_storage)) },
                    supportingContent = { Text(Formatter.formatShortFileSize(context, downloadsUsed)) },
                )
            }

            Header(R.string.playback)
            SwitchRow(stringResource(R.string.autoplay), stringResource(R.string.autoplay_desc), s.autoplayRelated) {
                runAsync { c.settings.setAutoplayRelated(it) }
            }
            SwitchRow(stringResource(R.string.sponsorblock), stringResource(R.string.sponsorblock_desc), s.sponsorBlock) {
                runAsync { c.settings.setSponsorBlock(it) }
            }

            Header(R.string.interruptions)
            InterruptTarget.entries.forEach { target ->
                ClickRow(
                    stringResource(target.title),
                    stringResource(target.current(s).label) + " · " + stringResource(target.example),
                ) { interruptDialog = target }
            }
            SwitchRow(stringResource(R.string.resume_after), stringResource(R.string.resume_after_desc), s.resumeAfterInterruption) {
                runAsync { c.settings.setResumeAfterInterruption(it) }
            }

            Header(R.string.backup)
            ClickRow(stringResource(R.string.backup_create), stringResource(R.string.backup_create_desc)) {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                exportLauncher.launch("harmonic-backup-$date.json")
            }
            ClickRow(stringResource(R.string.backup_restore), stringResource(R.string.backup_restore_desc)) {
                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            }

            Header(R.string.account)
            if (signedIn) {
                ClickRow(stringResource(R.string.sign_out), stringResource(R.string.sign_out_desc)) { c.account.logout() }
            } else {
                ClickRow(stringResource(R.string.sign_in), stringResource(R.string.sign_in_desc)) {
                    actions.navigate(Routes.LOGIN)
                }
            }

            Header(R.string.crash_reports)
            if (crashCount > 0) {
                ClickRow(
                    stringResource(R.string.crash_share),
                    pluralStringResource(R.plurals.crash_saved, crashCount, crashCount),
                ) {
                    CrashLog.latest(context)?.let { report ->
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Harmonic crash report")
                            .putExtra(Intent.EXTRA_TEXT, report)
                        context.startActivity(Intent.createChooser(send, null))
                    }
                }
                ClickRow(stringResource(R.string.crash_delete), stringResource(R.string.crash_delete_desc)) {
                    CrashLog.clear(context)
                    crashCount = 0
                }
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.crash_none)) },
                    supportingContent = { Text(stringResource(R.string.crash_none_desc)) },
                )
            }

            Header(R.string.about)
            ListItem(
                headlineContent = { Text("Harmonic ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text(stringResource(R.string.about_desc)) },
            )
        }
    }

    qualityDialog?.let { target ->
        val selected = when (target) {
            QualityTarget.WIFI -> s.qualityWifi
            QualityTarget.MOBILE -> s.qualityMobile
            QualityTarget.DOWNLOAD -> s.qualityDownload
        }
        ChoiceDialog(
            title = stringResource(target.title),
            options = AudioQuality.entries.map { it to stringResource(it.label) + "\n" + stringResource(it.description) },
            selected = selected,
            onDismiss = { qualityDialog = null },
        ) { q ->
            qualityDialog = null
            runAsync {
                when (target) {
                    QualityTarget.WIFI -> c.settings.setQualityWifi(q)
                    QualityTarget.MOBILE -> c.settings.setQualityMobile(q)
                    QualityTarget.DOWNLOAD -> c.settings.setQualityDownload(q)
                }
            }
        }
    }

    interruptDialog?.let { target ->
        ChoiceDialog(
            title = stringResource(target.title),
            options = target.options.map { it to stringResource(it.label) },
            selected = target.current(s),
            onDismiss = { interruptDialog = null },
        ) { action ->
            interruptDialog = null
            runAsync {
                when (target) {
                    InterruptTarget.CALLS -> c.settings.setOnCalls(action)
                    InterruptTarget.RECORDING -> c.settings.setOnRecording(action)
                    InterruptTarget.OTHER_SOUNDS -> c.settings.setOnOtherSounds(action)
                    InterruptTarget.NAVIGATION -> c.settings.setOnNavigation(action)
                    InterruptTarget.OTHER_MUSIC -> c.settings.setOnOtherMusic(action)
                }
            }
        }
    }

    if (themeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.theme),
            options = ThemeMode.entries.map { it to stringResource(it.label) },
            selected = s.themeMode,
            onDismiss = { themeDialog = false },
        ) { mode ->
            themeDialog = false
            runAsync { c.settings.setThemeMode(mode) }
        }
    }

    if (paletteDialog) {
        PaletteDialog(
            selected = s.palette,
            onDismiss = { paletteDialog = false },
        ) { palette ->
            paletteDialog = false
            runAsync { c.settings.setPalette(palette) }
        }
    }

    if (cacheDialog) {
        ChoiceDialog(
            title = stringResource(R.string.cache_size),
            options = listOf(128, 256, 512, 1024, 2048).map { it to "$it MB" },
            selected = s.cacheSizeMb,
            onDismiss = { cacheDialog = false },
        ) { mb ->
            cacheDialog = false
            runAsync { c.settings.setCacheSizeMb(mb) }
            actions.showMessage(res.getString(R.string.applies_next_start))
        }
    }
}

@Composable
private fun PaletteDialog(selected: ThemePalette, onDismiss: () -> Unit, onSelect: (ThemePalette) -> Unit) {
    val options = ThemePalette.entries.filter {
        it != ThemePalette.WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.colours)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { palette ->
                    ListItem(
                        headlineContent = { Text(stringResource(palette.label)) },
                        supportingContent = when (palette) {
                            ThemePalette.WALLPAPER -> ({ Text(stringResource(R.string.palette_wallpaper_desc)) })
                            ThemePalette.ALBUM -> ({ Text(stringResource(R.string.palette_album_desc)) })
                            else -> null
                        },
                        leadingContent = {
                            val swatch = paletteSwatch(palette)
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (swatch != null) {
                                            Brush.linearGradient(listOf(swatch, swatch))
                                        } else {
                                            Brush.sweepGradient(
                                                listOf(
                                                    Color(0xFFFF2E97), Color(0xFFF2A33A), Color(0xFF2E9E5B),
                                                    Color(0xFF2DE2E6), Color(0xFF8E5CF7), Color(0xFFFF2E97),
                                                ),
                                            )
                                        },
                                    ),
                            )
                        },
                        trailingContent = { RadioButton(selected = palette == selected, onClick = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = palette == selected, onClick = { onSelect(palette) }),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private enum class InterruptTarget(
    @StringRes val title: Int,
    @StringRes val example: Int,
    val options: List<InterruptAction> = InterruptAction.entries,
) {
    CALLS(R.string.int_calls, R.string.int_calls_desc),
    RECORDING(R.string.int_recording, R.string.int_recording_desc),
    OTHER_SOUNDS(R.string.int_other_sounds, R.string.int_other_sounds_desc),
    NAVIGATION(R.string.int_navigation, R.string.int_navigation_desc),
    OTHER_MUSIC(
        R.string.int_other_music,
        R.string.int_other_music_desc,
        listOf(InterruptAction.PAUSE, InterruptAction.KEEP),
    ),
    ;

    fun current(s: AppSettings): InterruptAction = when (this) {
        CALLS -> s.onCalls
        RECORDING -> s.onRecording
        OTHER_SOUNDS -> s.onOtherSounds
        NAVIGATION -> s.onNavigation
        OTHER_MUSIC -> s.onOtherMusic
    }
}

private enum class QualityTarget(@StringRes val title: Int) {
    WIFI(R.string.quality_wifi_title),
    MOBILE(R.string.quality_mobile_title),
    DOWNLOAD(R.string.quality_download_title),
}

@Composable
private fun Header(@StringRes text: Int) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        stringResource(text).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ClickRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { RadioButton(selected = value == selected, onClick = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, onClick = { onSelect(value) }),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
