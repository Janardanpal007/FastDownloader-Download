package com.fastdownloader.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastdownloader.app.engine.Categories
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Format
import com.fastdownloader.app.engine.Links
import com.fastdownloader.app.engine.Net
import com.fastdownloader.app.engine.Probe
import com.fastdownloader.app.engine.Torrents
import com.fastdownloader.app.engine.VideoDetails
import com.fastdownloader.app.engine.Ytdlp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the link turned out to be. */
private sealed class Found {
    data object Nothing : Found()
    data class Checking(val message: String) : Found()
    data class FileLink(val probe: Probe) : Found()
    data class Video(val url: String, val details: VideoDetails) : Found()
    data class Torrent(val source: String, val name: String) : Found()
    data class Failed(val message: String) : Found()
}

private val STREAM = Regex("""\.(m3u8|mpd)(\?|$)""", RegexOption.IGNORE_CASE)

/** Works out what a link is: a file, a video page, a stream or a torrent. Runs off the main thread. */
private suspend fun inspect(ctx: Context, raw: String, headers: Map<String, String>, status: (String) -> Unit): Found = withContext(Dispatchers.IO) {
    val url = raw.trim()
    if (Links.isMagnet(url)) return@withContext Found.Torrent(url, Torrents.magnetName(url) ?: "Torrent (name arrives when it starts)")
    if (url.startsWith("/") && url.endsWith(".torrent")) {
        val name = runCatching { org.libtorrent4j.TorrentInfo(File(url)).name() }.getOrNull()
            ?: return@withContext Found.Failed("This .torrent file couldn't be read.")
        return@withContext Found.Torrent(url, name)
    }
    if (Links.httpUri(url) == null) return@withContext Found.Failed("Paste a link that starts with http://, https:// or magnet:")

    suspend fun video(): Found {
        status("Reading video details… The first time takes a few seconds longer.")
        val cookies = Ytdlp.cookieFile(ctx, url, headers["Cookie"])
        return Found.Video(url, Ytdlp.inspect(ctx, url, headers, cookies))
    }

    if (Links.isVideoPageUrl(url) || STREAM.containsMatchIn(url)) {
        return@withContext runCatching { video() }.getOrElse { Found.Failed(it.message ?: "Couldn't read this video.") }
    }
    status("Checking the link…")
    val probe = runCatching { Net.probe(url, headers) }.getOrElse { return@withContext Found.Failed(Net.friendly(it)) }
    val mime = probe.mime ?: ""
    if (mime.contains("mpegurl") || mime.contains("dash+xml")) {
        return@withContext runCatching { video() }.getOrElse { Found.Failed(it.message ?: "Couldn't read this stream.") }
    }
    if (probe.isWebPage) {
        // A page, not a file: it may still have a video yt-dlp knows how to find.
        return@withContext runCatching { video() }.getOrElse {
            Found.Failed("This link opens a web page, not a file. If the page has a video, open it in the Browser tab and tap Download there.")
        }
    }
    if (Categories.extension(probe.fileName) == "torrent" || mime == "application/x-bittorrent") {
        status("Reading the torrent…")
        val bytes = Net.client().newCall(Net.request(probe.finalUrl, headers)).execute().use { it.body.bytes() }
        val f = Engine.saveTorrentFile(bytes)
        val name = runCatching { org.libtorrent4j.TorrentInfo(f).name() }.getOrNull()
            ?: return@withContext Found.Failed("This .torrent file couldn't be read.")
        return@withContext Found.Torrent(f.path, name)
    }
    Found.FileLink(probe)
}

@Composable
fun AddPanel(request: AddRequest, onClose: () -> Unit, withStorage: (() -> Unit) -> Unit, onAdded: () -> Unit) {
    val c = colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember(request) { mutableStateOf(request.url) }
    var found by remember(request) { mutableStateOf<Found>(Found.Nothing) }
    var fileName by remember(request) { mutableStateOf("") }
    var quality by remember(request) { mutableIntStateOf(0) }
    var checkedUrl by remember(request) { mutableStateOf<String?>(null) }

    fun check() {
        val u = url.trim()
        if (u.isEmpty()) return
        checkedUrl = u
        found = Found.Checking("Checking the link…")
        scope.launch {
            val r = inspect(ctx, u, request.headers) { msg -> scope.launch { if (found is Found.Checking) found = Found.Checking(msg) } }
            if (checkedUrl != u) return@launch
            found = r
            if (r is Found.FileLink) fileName = r.probe.fileName
            if (r is Found.Video) quality = r.details.defaultIndex
        }
    }

    LaunchedEffect(request) {
        if (request.url.isEmpty()) {
            // Empty Add: offer the clipboard's link, if any.
            val clip = runCatching {
                ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            }.getOrNull()
            Links.firstLink(clip)?.let { url = it }
        } else if (request.auto) {
            check()
        }
    }

    BackHandler(onBack = onClose)

    fun download() {
        val f = found
        withStorage {
            when (f) {
                is Found.FileLink -> Engine.addHttp(url.trim(), f.probe, fileName.ifBlank { f.probe.fileName }, request.headers)
                is Found.Video -> Engine.addVideo(f.url, f.details.title, f.details.qualities[quality], request.headers)
                is Found.Torrent -> Engine.addTorrent(f.source, f.name.takeUnless { it.startsWith("Torrent (") })
                else -> return@withStorage
            }
            onAdded()
        }
    }

    // Dimmed backdrop, panel from the bottom.
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .background(c.panel)
                .border(1.dp, c.lineStrong)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("New download")
                    Text("Add a link", style = Type.heading, color = c.text)
                }
                IconBox(Icons.Close, "Close", onClick = onClose)
            }
            Divider()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Label("Link")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; if (found !is Found.Checking) found = Found.Nothing },
                        modifier = Modifier.weight(1f),
                        textStyle = Type.small.copy(color = c.text),
                        placeholder = { Text("https://…  or  magnet:?…", style = Type.small, color = c.muted) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { check() }),
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.width(8.dp))
                    LineButton("Check", enabled = url.isNotBlank()) { check() }
                }
                Spacer(Modifier.height(16.dp))
                when (val f = found) {
                    Found.Nothing -> Text(
                        "Files, videos from YouTube and 1,000+ other sites, streams and torrents. Tip: in any app, tap Share on a link and pick FastDownloader.",
                        style = Type.body, color = c.muted,
                    )
                    is Found.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.text)
                        Spacer(Modifier.width(12.dp))
                        Text(f.message, style = Type.body, color = c.muted)
                    }
                    is Found.Failed -> Box(Modifier.fillMaxWidth().background(c.errSoft).padding(12.dp)) {
                        Text(f.message, style = Type.body, color = c.err)
                    }
                    is Found.FileLink -> FileDetails(f.probe, fileName) { fileName = it }
                    is Found.Video -> VideoChoices(f.details, quality) { quality = it }
                    is Found.Torrent -> InfoRows(listOf("Type" to "Torrent", "Name" to f.name, "Saved in" to "Download/FastDownloader/Torrent"))
                }
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineButton("Cancel", Modifier.weight(1f), onClick = onClose)
                SolidButton(
                    "Download",
                    Modifier.weight(2f),
                    enabled = found is Found.FileLink || found is Found.Video || found is Found.Torrent,
                    icon = Icons.Download,
                    onClick = ::download,
                )
            }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = colors.text, unfocusedBorderColor = colors.lineStrong, cursorColor = colors.text,
    focusedContainerColor = colors.panel, unfocusedContainerColor = colors.panel,
)

@Composable
private fun FileDetails(p: Probe, name: String, onName: (String) -> Unit) {
    val c = colors
    Label("Save as")
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = name, onValueChange = onName, modifier = Modifier.fillMaxWidth(), singleLine = true,
        textStyle = Type.body.copy(color = c.text), colors = fieldColors(),
    )
    Spacer(Modifier.height(12.dp))
    val cat = Categories.forName(name)
    InfoRows(
        listOf(
            "Size" to if (p.size > 0) Format.size(p.size) else "Unknown",
            "Folder" to if (Engine.settings.categorize) "Download/FastDownloader/$cat" else "Download/FastDownloader",
            "Speed-up" to if (p.resumable) "${Engine.settings.segments} parts at once · can pause and resume" else "One connection · this server can't resume",
        ),
    )
}

@Composable
private fun VideoChoices(d: VideoDetails, selected: Int, onSelect: (Int) -> Unit) {
    val c = colors
    Text(d.title, style = Type.strong, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(4.dp))
    Text(
        listOfNotNull(d.site, if (d.durationSeconds > 0) "%d:%02d".format(d.durationSeconds / 60, d.durationSeconds % 60) else null).joinToString(" · "),
        style = Type.small, color = c.muted,
    )
    Spacer(Modifier.height(14.dp))
    Label("Quality")
    Spacer(Modifier.height(6.dp))
    Column(Modifier.fillMaxWidth().border(1.dp, c.border)) {
        d.qualities.forEachIndexed { i, q ->
            if (i > 0) Divider()
            Row(
                Modifier.fillMaxWidth().background(if (i == selected) c.accentSoft else Color.Transparent).clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(q.label, style = Type.small.copy(color = c.text), modifier = Modifier.weight(1f))
                if (i == selected) Icon(Icons.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val q = d.qualities.getOrNull(selected)
    Text(
        if (q?.audio != null) "Saved in Download/FastDownloader/Music as ${q.audio.uppercase()}."
        else "Saved in Download/FastDownloader/Videos as ${Engine.settings.videoContainer.uppercase()} (change in Settings › Videos).",
        style = Type.body, color = c.muted,
    )
}

@Composable
fun InfoRows(rows: List<Pair<String, String>>) {
    val c = colors
    Column(Modifier.fillMaxWidth().border(1.dp, c.border)) {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Label(k, Modifier.width(96.dp))
                Text(v, style = Type.body, color = c.text, modifier = Modifier.weight(1f))
            }
        }
    }
}
