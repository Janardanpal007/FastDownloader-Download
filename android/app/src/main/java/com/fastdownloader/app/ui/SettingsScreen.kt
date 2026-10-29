package com.fastdownloader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fastdownloader.app.BuildConfig
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Filters
import com.fastdownloader.app.engine.Net
import com.fastdownloader.app.engine.Settings
import com.fastdownloader.app.engine.Ytdlp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val c = colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Engine.settingsFlow.collectAsState()
    fun set(change: (Settings) -> Settings) = Engine.updateSettings(change)
    var ipResult by remember { mutableStateOf<String?>(null) }
    var ytdlpStatus by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(c.panel).padding(16.dp)) {
            Label("Settings")
            Text("Make it yours", style = Type.title, color = c.text)
        }
        Divider()

        Section("General") {
            Info("Download folder", "Download/FastDownloader")
            Toggle("Sort into category folders", "ZIP, DOC, Programs, Videos, Music, Torrent, Images, Other", s.categorize) { v -> set { it.copy(categorize = v) } }
            Stepper("Downloads at once", s.maxConcurrent, 1..10) { v -> set { it.copy(maxConcurrent = v) } }
            Stepper("Parts per file", s.segments, 1..32, hint = "More parts = faster on most servers") { v -> set { it.copy(segments = v) } }
            NumberField("Speed limit (KB/s)", s.speedLimitKBps, "0 = no limit") { v -> set { it.copy(speedLimitKBps = v) } }
            Toggle("Wi-Fi only", "Wait instead of using mobile data", s.wifiOnly) { v -> set { it.copy(wifiOnly = v) } }
            Toggle("Catch copied links", "Offer to download a link you copy", s.clipboardMonitor) { v -> set { it.copy(clipboardMonitor = v) } }
            Toggle("Notify when done", null, s.notifyOnComplete, last = true) { v -> set { it.copy(notifyOnComplete = v) } }
        }

        Section("Videos") {
            Choice("Video format", listOf("mp4" to "MP4", "mkv" to "MKV"), s.videoContainer) { v -> set { it.copy(videoContainer = v) } }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Video site support (yt-dlp)", style = Type.strong, color = c.text)
                    Text(ytdlpStatus ?: (Ytdlp.version(ctx)?.let { "Version $it · updates daily" } ?: "Updates daily"), style = Type.small, color = c.muted)
                }
                LineButton("Update") {
                    ytdlpStatus = "Updating…"
                    scope.launch {
                        ytdlpStatus = withContext(Dispatchers.IO) { runCatching { Ytdlp.updateIfDue(ctx, force = true) }.getOrElse { "Update failed: ${it.message}" } }
                    }
                }
            }
        }

        Section("Torrents") {
            Stepper("Max connections", s.torrentConnections, 20..1000, step = 10) { v -> set { it.copy(torrentConnections = v) } }
            Toggle("Extra public trackers", "Finds more peers for magnet links", s.torrentExtraTrackers) { v -> set { it.copy(torrentExtraTrackers = v) } }
            Toggle(
                "Only over a VPN", "Kill switch: torrents pause whenever no VPN is connected" + if (Engine.onVpn) " · VPN on now" else " · no VPN now",
                s.torrentVpnOnly, last = true,
            ) { v -> set { it.copy(torrentVpnOnly = v) } }
        }

        Section("Privacy") {
            Choice("Proxy", listOf("none" to "None", "http" to "HTTP", "socks5" to "SOCKS5"), s.proxyType) { v -> set { it.copy(proxyType = v) } }
            if (s.proxyType != "none") {
                TextSetting("Proxy host", s.proxyHost) { v -> set { it.copy(proxyHost = v.trim()) } }
                NumberField("Proxy port", s.proxyPort, null) { v -> set { it.copy(proxyPort = v) } }
                TextSetting("Username (optional)", s.proxyUser) { v -> set { it.copy(proxyUser = v) } }
                TextSetting("Password (optional)", s.proxyPass, secret = true) { v -> set { it.copy(proxyPass = v) } }
                Text(
                    "Downloads, video sites and torrents all go through the proxy. If it stops working, downloads fail instead of using your real address.",
                    style = Type.small, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Divider()
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Address sites see", style = Type.strong, color = c.text)
                    Text(ipResult ?: "Tap Check", style = Type.small, color = c.muted)
                }
                LineButton("Check") {
                    ipResult = "Checking…"
                    scope.launch { ipResult = withContext(Dispatchers.IO) { runCatching { Net.visibleIp() }.getOrElse { "Couldn't check: ${it.message}" } } }
                }
            }
            Divider()
            Toggle("Ask sites not to track", "Sends Do Not Track and Global Privacy Control", s.privacyHeaders) { v -> set { it.copy(privacyHeaders = v) } }
            Toggle("Remove tracking tags from links", "utm_source, fbclid, gclid and others", s.stripTracking) { v -> set { it.copy(stripTracking = v) } }
            Toggle("Block ads and trackers", "In the Browser tab, with uBlock Origin's filter lists", s.blockAds) { v -> set { it.copy(blockAds = v) } }
            FilterLists(s.adblockAllow) { set { it.copy(adblockAllow = emptyList()) } }
            Toggle("Keep download history", "Off: finished downloads leave the list (files stay)", s.keepHistory) { v -> set { it.copy(keepHistory = v) } }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineButton("Clear history") { Engine.clearHistory() }
                LineButton("Clear browser data") {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.WebStorage.getInstance().deleteAllData()
                }
            }
        }

        Section("Profile") {
            TextSetting("Your name", s.userName, last = true) { v -> set { it.copy(userName = v) } }
        }

        Section("About") {
            Info("FastDownloader", "Version ${BuildConfig.VERSION_NAME} for Android 8 to 17")
            Info("Made by", "Copyright © 2026 Janardan/TSE" + if (s.userName.isNotBlank()) " · registered to ${s.userName}" else "")
            Info(
                "Licence",
                "Free software under the GNU GPL v3, with no warranty. Source code: github.com/Janardanpal007/FastDownloader-Download (android folder).",
            )
            Info(
                "Built with", "youtubedl-android (GPL-3.0), yt-dlp (Unlicense), FFmpeg (LGPL/GPL), Python (PSF), QuickJS (MIT), " +
                    "libtorrent (BSD), OkHttp (Apache 2.0), Jetpack Compose (Apache 2.0), Material Icons (Apache 2.0), " +
                    "Space Grotesk, JetBrains Mono and Inter (SIL OFL).",
                last = true,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** uBlock Origin's lists: how many rules are loaded, when they were updated, and an Update button. */
@Composable
private fun FilterLists(allowedSites: List<String>, onClearAllowed: () -> Unit) {
    val c = colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Filters.status.collectAsState()
    LaunchedEffect(Unit) { if (Filters.engine == null) withContext(Dispatchers.IO) { runCatching { Filters.load(ctx) } } }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Filter lists", style = Type.strong, color = c.text)
        Text(Filters.LISTS.joinToString(", ") { it.name }, style = Type.small, color = c.muted)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                st.loading -> st.message ?: "Loading…"
                st.networkRules == 0 -> "Loads when you open the Browser tab"
                else -> "%,d blocking and %,d hiding rules".format(st.networkRules, st.cosmeticRules) +
                    (if (st.updatedAt > 0) " · updated " + java.text.DateFormat.getDateInstance().format(java.util.Date(st.updatedAt)) else " · built-in copy") +
                    (st.message?.let { " · $it" } ?: "")
            },
            style = Type.small, color = c.muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineButton("Update lists", enabled = !st.loading) {
                scope.launch { withContext(Dispatchers.IO) { runCatching { Filters.updateIfDue(ctx, force = true) } } }
            }
            if (allowedSites.isNotEmpty()) LineButton("Block on ${allowedSites.size} allowed site" + if (allowedSites.size == 1) "" else "s") { onClearAllowed() }
        }
    }
    Divider()
}

@Composable
private fun Info(title: String, text: String, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = Type.strong, color = colors.text)
        Text(text, style = Type.small, color = colors.muted)
    }
    if (!last) Divider()
}

@Composable
private fun Toggle(title: String, hint: String?, value: Boolean, last: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = colors
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = c.text)
            if (hint != null) Text(hint, style = Type.small, color = c.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.solidBg, checkedThumbColor = c.solidFg, uncheckedTrackColor = c.panel2,
                uncheckedBorderColor = c.lineStrong, uncheckedThumbColor = c.muted,
            ),
        )
    }
    if (!last) Divider()
}

@Composable
private fun Stepper(title: String, value: Int, range: IntRange, step: Int = 1, hint: String? = null, onChange: (Int) -> Unit) {
    val c = colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = c.text)
            if (hint != null) Text(hint, style = Type.small, color = c.muted)
        }
        IconBox(Icons.Minus, "Less") { onChange((value - step).coerceIn(range)) }
        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { Text("$value", style = Type.heading, color = c.text) }
        IconBox(Icons.Add, "More") { onChange((value + step).coerceIn(range)) }
    }
    Divider()
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.strong, color = colors.text, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((key, label) in options) Chip(label, value == key) { onChange(key) }
        }
    }
    Divider()
}

@Composable
private fun TextSetting(title: String, value: String, secret: Boolean = false, last: Boolean = false, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Label(title)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it; onChange(it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            textStyle = Type.body.copy(color = colors.text), colors = fieldColors(),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        )
    }
    if (!last) Divider()
}

@Composable
private fun NumberField(title: String, value: Int, hint: String?, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0 && hint == null) "" else value.toString()) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong, color = colors.text)
            if (hint != null) Text(hint, style = Type.small, color = colors.muted)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { t -> text = t.filter { it.isDigit() }.take(7); onChange(text.toIntOrNull() ?: 0) },
            modifier = Modifier.width(120.dp), singleLine = true, textStyle = Type.small.copy(color = colors.text), colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    Divider()
}
