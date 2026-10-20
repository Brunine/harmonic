package app.harmonic.music.ui.screens

import android.text.format.Formatter
import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.harmonic.music.BuildConfig
import app.harmonic.music.data.prefs.AudioQuality
import app.harmonic.music.data.prefs.ThemeMode
import app.harmonic.music.data.prefs.ThemePalette
import app.harmonic.music.ui.theme.paletteSwatch
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val context = LocalContext.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val cookies by c.account.cookies.collectAsStateWithLifecycle()
    val signedIn = cookies != null && c.account.isLoggedIn
    var qualityDialog by remember { mutableStateOf<QualityTarget?>(null) }
    var cacheDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var paletteDialog by remember { mutableStateOf(false) }
    var cacheUsed by remember { mutableLongStateOf(-1L) }
    var downloadsUsed by remember { mutableLongStateOf(-1L) }

    fun runAsync(block: suspend () -> Unit) {
        c.appScope.launch(Dispatchers.Main) { block() }
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
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header("Appearance")
            ClickRow("Theme", s.themeMode.label) { themeDialog = true }
            ClickRow("Colours", s.palette.label) { paletteDialog = true }
            SwitchRow(
                "Pure black",
                "Black backgrounds in dark theme. Saves battery on OLED screens",
                s.pureBlack,
            ) { runAsync { c.settings.setPureBlack(it) } }

            Header("Audio quality")
            ClickRow("On Wi-Fi", s.qualityWifi.label) { qualityDialog = QualityTarget.WIFI }
            ClickRow("On mobile data", s.qualityMobile.label) { qualityDialog = QualityTarget.MOBILE }
            ClickRow("Downloads", s.qualityDownload.label) { qualityDialog = QualityTarget.DOWNLOAD }

            Header("Data & battery")
            SwitchRow("Download only on Wi-Fi", "Queued downloads wait for an unmetered network", s.downloadWifiOnly) {
                runAsync { c.settings.setDownloadWifiOnly(it) }
                c.downloads.setWifiOnly(it)
            }
            SwitchRow("Smaller artwork on mobile data", "Loads ~226px covers instead of ~544px", s.dataSaverImages) {
                runAsync { c.settings.setDataSaverImages(it) }
            }
            SwitchRow(
                "Audio offload",
                "Lets the phone's audio chip decode with the screen off. Turn off if you hear glitches",
                s.audioOffload,
            ) { runAsync { c.settings.setAudioOffload(it) } }
            ClickRow(
                "Streaming cache",
                "${s.cacheSizeMb} MB limit" +
                    if (cacheUsed >= 0) " • ${Formatter.formatShortFileSize(context, cacheUsed)} used" else "",
            ) { cacheDialog = true }
            ClickRow("Clear streaming cache", "Downloads are not affected") {
                runAsync {
                    withContext(Dispatchers.IO) {
                        val cache = c.playerCache
                        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
                        cacheUsed = cache.cacheSpace
                    }
                    actions.showMessage("Streaming cache cleared")
                }
            }
            if (downloadsUsed >= 0) {
                ListItem(
                    headlineContent = { Text("Downloads storage") },
                    supportingContent = { Text(Formatter.formatShortFileSize(context, downloadsUsed)) },
                )
            }

            Header("Playback")
            SwitchRow("Autoplay similar songs", "Keeps the music going with YouTube recommendations", s.autoplayRelated) {
                runAsync { c.settings.setAutoplayRelated(it) }
            }
            SwitchRow(
                "Skip non-music parts",
                "Skips intros, outros and sponsor segments in music videos (SponsorBlock)",
                s.sponsorBlock,
            ) { runAsync { c.settings.setSponsorBlock(it) } }

            Header("Account")
            if (signedIn) {
                ClickRow(
                    "Sign out",
                    "Signed in" + (c.account.accountName.value?.let { " as $it" } ?: "") +
                        ". Used only for age-restricted tracks",
                ) { c.account.logout() }
            } else {
                ClickRow("Sign in with Google", "Optional. Only needed to play age-restricted tracks") {
                    actions.navigate(Routes.LOGIN)
                }
            }

            Header("About")
            ListItem(
                headlineContent = { Text("Harmonic ${BuildConfig.VERSION_NAME}") },
                supportingContent = {
                    Text("Unofficial client. Streams are fetched with NewPipeExtractor; no ads are requested.")
                },
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
            title = target.title,
            options = AudioQuality.entries.map { it to "${it.label}\n${it.description}" },
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

    if (themeDialog) {
        ChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries.map { it to it.label },
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
            title = "Streaming cache size",
            options = listOf(128, 256, 512, 1024, 2048).map { it to "$it MB" },
            selected = s.cacheSizeMb,
            onDismiss = { cacheDialog = false },
        ) { mb ->
            cacheDialog = false
            runAsync { c.settings.setCacheSizeMb(mb) }
            actions.showMessage("Applies the next time the app starts")
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
        title = { Text("Colours") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { palette ->
                    ListItem(
                        headlineContent = { Text(palette.label) },
                        supportingContent = when (palette) {
                            ThemePalette.WALLPAPER -> ({ Text("Matches your phone's wallpaper") })
                            ThemePalette.ALBUM -> ({ Text("Changes with each song") })
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
                                                listOf(Color(0xFFFF6B4A), Color(0xFFF2A33A), Color(0xFF2E9E5B), Color(0xFF2F80ED), Color(0xFF8E5CF7), Color(0xFFFF6B4A)),
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class QualityTarget(val title: String) {
    WIFI("Quality on Wi-Fi"),
    MOBILE("Quality on mobile data"),
    DOWNLOAD("Download quality"),
}

@Composable
private fun Header(text: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        text,
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
