package com.fastdownloader.app.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fastdownloader.app.engine.Updater
import com.fastdownloader.app.engine.Categories
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Links
import kotlinx.coroutines.launch
import java.io.File

/** What the Add panel opens with: a link, plus the browser's headers when it came from the Browser tab. */
data class AddRequest(val url: String, val headers: Map<String, String> = emptyMap(), val auto: Boolean = true)

enum class Tab { BROWSER, DOWNLOADS, SETTINGS }

class MainActivity : ComponentActivity() {
    private var addRequest by mutableStateOf<AddRequest?>(null)
    private var clipLink by mutableStateOf<String?>(null)
    private var tab by mutableStateOf(Tab.BROWSER)
    private var lastClip: String? = null
    private var pendingAfterPermission: (() -> Unit)? = null

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingAfterPermission?.invoke()
        pendingAfterPermission = null
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        askNotificationsOnce()
        setContent {
            FastTheme {
                Root(
                    tab = tab,
                    onTab = { tab = it },
                    addRequest = addRequest,
                    onAdd = { req -> addRequest = req },
                    onAddClosed = { addRequest = null },
                    clipLink = clipLink,
                    onClipDismiss = { clipLink = null },
                    withStorage = ::withStorage,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Links shared from other apps, magnet links, .torrent files and selected text. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_VIEW -> intent.data?.let { viewData(it, intent.type) }
            else -> null
        }
        intent.getStringExtra(EXTRA_OPEN_ID)?.let { tab = Tab.DOWNLOADS }
        val link = if (text != null && text.startsWith("/")) text else Links.firstLink(text)
        if (link != null) {
            tab = Tab.DOWNLOADS
            addRequest = AddRequest(link)
        }
        setIntent(Intent())
    }

    private fun viewData(uri: Uri, type: String?): String? {
        if (uri.scheme == "magnet") return uri.toString()
        val isTorrent = type == "application/x-bittorrent" || uri.lastPathSegment?.endsWith(".torrent") == true
        if (!isTorrent) return uri.toString()
        // content:// links stop working once the other app is done, so keep a copy.
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        return Engine.saveTorrentFile(bytes).path
    }

    /** Clipboard catcher. Android only lets the app read it while on screen, so it's checked on focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !Engine.settings.clipboardMonitor || addRequest != null) return
        val cm = getSystemService(ClipboardManager::class.java)
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() ?: return
        if (text == lastClip) return
        lastClip = text
        val link = Links.firstLink(text) ?: return
        if (Categories.looksLikeFileUrl(link) || Links.isVideoPageUrl(link)) clipLink = link
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Android 8–10 need storage permission to save into Download/. Newer versions don't. */
    private fun withStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            Engine.createFolders()
            action()
            return
        }
        pendingAfterPermission = { Engine.createFolders(); action() }
        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    companion object {
        const val EXTRA_OPEN_ID = "open_id"
        const val EXTRA_UPDATE = "update"
    }
}

@Composable
private fun Root(
    tab: Tab,
    onTab: (Tab) -> Unit,
    addRequest: AddRequest?,
    onAdd: (AddRequest) -> Unit,
    onAddClosed: () -> Unit,
    clipLink: String?,
    onClipDismiss: () -> Unit,
    withStorage: (() -> Unit) -> Unit,
) {
    val c = colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().bottomBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The browser stays loaded while other tabs are shown, so pages aren't lost.
                Box(if (tab == Tab.BROWSER) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
                    BrowserScreen(visible = tab == Tab.BROWSER, onDownload = { onAdd(it) })
                }
                if (tab != Tab.BROWSER) {
                    Box(Modifier.fillMaxSize().background(c.bg)) {
                        if (tab == Tab.DOWNLOADS) DownloadsScreen(onAdd = { onAdd(AddRequest("", auto = false)) }) else SettingsScreen()
                    }
                }
            }
            UpdateBanner()
            if (clipLink != null) ClipBanner(clipLink, onOpen = { onClipDismiss(); onAdd(AddRequest(clipLink)) }, onDismiss = onClipDismiss)
            Divider()
            TabBar(tab, onTab)
        }
        if (addRequest != null) {
            AddPanel(request = addRequest, onClose = onAddClosed, withStorage = withStorage, onAdded = { onAddClosed(); onTab(Tab.DOWNLOADS) })
        }
    }
}

@Composable
private fun ClipBanner(link: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = colors
    Divider()
    Row(
        Modifier.fillMaxWidth().background(c.accentSoft).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label("Link copied", color = c.accent)
            Text(link, style = Type.small, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        SolidButton("Download", onClick = onOpen, modifier = Modifier.height(40.dp))
        IconBox(Icons.Close, "Dismiss", Modifier.padding(start = 6.dp), onClick = onDismiss)
    }
}

/** Shown on every tab while a newer version is out (until dismissed): download, then install, right here. */
@Composable
private fun UpdateBanner() {
    val c = colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    var dismissed by rememberSaveable { mutableStateOf("") }
    val r = st.available ?: return
    if (dismissed == r.version) return
    Divider()
    Row(
        Modifier.fillMaxWidth().background(c.accentSoft).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(if (st.error != null) "Update failed" else "Update available", color = if (st.error != null) c.err else c.accent)
            Text(
                when {
                    st.error != null -> st.error!!
                    st.progress != null -> "Downloading ${r.version}… ${(st.progress!! * 100).toInt()}%"
                    st.ready != null -> "${r.version} is downloaded and checked"
                    else -> "FastDownloader ${r.version} (you have ${st.current})"
                },
                style = Type.small, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            st.ready != null -> SolidButton("Install", onClick = { Updater.install(ctx) }, modifier = Modifier.height(40.dp))
            r.apkUrl == null -> SolidButton("Open", onClick = { Updater.openPage(ctx) }, modifier = Modifier.height(40.dp))
            else -> SolidButton(
                if (st.progress != null) "Wait" else "Update",
                onClick = {
                    if (st.progress == null) scope.launch(kotlinx.coroutines.Dispatchers.IO) { Updater.download(ctx) }
                },
                modifier = Modifier.height(40.dp),
            )
        }
        IconBox(Icons.Close, "Dismiss", Modifier.padding(start = 6.dp), onClick = { dismissed = r.version })
    }
}

@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    val c = colors
    Row(Modifier.fillMaxWidth().height(44.dp).background(c.panel), horizontalArrangement = Arrangement.SpaceEvenly) {
        TabButton("Browser", Icons.Globe, tab == Tab.BROWSER, Modifier.weight(1f)) { onTab(Tab.BROWSER) }
        VDivider(44.dp)
        TabButton("Downloads", Icons.List, tab == Tab.DOWNLOADS, Modifier.weight(1f)) { onTab(Tab.DOWNLOADS) }
        VDivider(44.dp)
        TabButton("Settings", Icons.Tune, tab == Tab.SETTINGS, Modifier.weight(1f)) { onTab(Tab.SETTINGS) }
    }
}

@Composable
private fun TabButton(text: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = colors
    Box(modifier.fillMaxSize().clickable(onClick = onClick)) {
        if (selected) Box(Modifier.fillMaxWidth().height(2.dp).background(c.text).align(Alignment.TopCenter))
        // Icon and name side by side: a slim bar leaves more of the screen for pages.
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) c.text else c.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Label(text, color = if (selected) c.text else c.muted)
        }
    }
}

/** Opens a finished file in the app the user picks. */
fun openFile(ctx: android.content.Context, path: String): Boolean {
    val f = File(path)
    if (!f.exists()) return false
    if (f.isDirectory) return false
    return runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(Categories.extension(f.name)) ?: "*/*"
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Open with",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}

fun shareFile(ctx: android.content.Context, path: String) {
    val f = File(path)
    if (!f.isFile) return
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(Categories.extension(f.name)) ?: "*/*"
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
