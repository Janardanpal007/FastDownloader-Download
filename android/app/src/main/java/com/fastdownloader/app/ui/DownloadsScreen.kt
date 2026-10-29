package com.fastdownloader.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastdownloader.app.BuildConfig
import com.fastdownloader.app.engine.Categories
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Format
import com.fastdownloader.app.engine.ItemView
import com.fastdownloader.app.engine.Kind
import com.fastdownloader.app.engine.Status

private const val ALL = "All"
private const val ACTIVE = "Active"
private const val DONE = "Done"

@Composable
fun DownloadsScreen(onAdd: () -> Unit) {
    val c = colors
    val items by Engine.itemsFlow.collectAsState()
    val settings by Engine.settingsFlow.collectAsState()
    var filter by remember { mutableStateOf(ALL) }

    val shown = items.filter {
        when (filter) {
            ALL -> true
            ACTIVE -> it.status != Status.COMPLETED
            DONE -> it.status == Status.COMPLETED
            else -> it.category == filter
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(Modifier.fillMaxWidth().background(c.panel).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("FastDownloader", color = c.text)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.background(c.panel2).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("v${BuildConfig.VERSION_NAME}", style = Type.small, color = c.muted)
                    }
                }
                Spacer(Modifier.height(4.dp))
                val active = items.count { it.status == Status.DOWNLOADING }
                val speed = items.sumOf { it.speed }
                Text(
                    when {
                        active > 0 -> "$active downloading · ${Format.speed(speed)}"
                        settings.userName.isNotBlank() -> "Hi, ${settings.userName}"
                        else -> "Downloads"
                    },
                    style = Type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SolidButton("Add", icon = Icons.Add, onClick = onAdd)
        }
        Divider()

        // Filters
        val filters = listOf(ALL, ACTIVE, DONE) + Categories.NAMES.filter { cat -> items.any { it.category == cat } }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in filters) {
                val n = when (f) {
                    ALL -> items.size
                    ACTIVE -> items.count { it.status != Status.COMPLETED }
                    DONE -> items.count { it.status == Status.COMPLETED }
                    else -> items.count { it.category == f }
                }
                Chip("$f $n", filter == f) { filter = f }
            }
        }
        Divider()

        // Bulk actions
        if (items.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.any { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }) LineButton("Pause all") { Engine.pauseAll() }
                if (items.any { it.status == Status.PAUSED || it.status == Status.ERROR }) LineButton("Resume all") { Engine.resumeAll() }
                if (items.any { it.status == Status.COMPLETED }) LineButton("Clear finished", color = c.muted) { Engine.clearCompleted() }
            }
            Divider()
        }

        if (shown.isEmpty()) {
            Empty(filter == ALL, onAdd)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { item ->
                    ItemRow(item)
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun Empty(nothingAtAll: Boolean, onAdd: () -> Unit) {
    val c = colors
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Label(if (nothingAtAll) "No downloads yet" else "Nothing here")
        Spacer(Modifier.height(8.dp))
        Text("Paste a link, share one from any app, or find a video in the Browser tab.", style = Type.heading, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Files are saved in Download/FastDownloader, sorted into ZIP, DOC, Programs, Videos, Music, Torrent and other folders.",
            style = Type.body, color = c.muted,
        )
        Spacer(Modifier.height(20.dp))
        SolidButton("Add a download", icon = Icons.Add, onClick = onAdd)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(item: ItemView) {
    val c = colors
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val barColor = when (item.status) {
        Status.COMPLETED -> c.ok
        Status.PAUSED -> c.warn
        Status.ERROR -> c.err
        else -> c.accent
    }

    fun open() {
        if (!openFile(ctx, item.path)) {
            Toast.makeText(ctx, "Saved in ${item.path.substringAfter("/Download/", item.path).let { "Download/$it" }}", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { if (item.status == Status.COMPLETED) open() }, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(c.panel2).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Text(
                            when (item.kind) { Kind.TORRENT -> "TORRENT"; Kind.VIDEO -> "VIDEO"; Kind.HTTP -> item.category.uppercase() },
                            style = Type.label.copy(fontSize = 9.sp),
                            color = c.muted,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(item.fileName, style = Type.strong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when (item.status) {
                Status.DOWNLOADING, Status.QUEUED -> IconBox(Icons.Pause, "Pause") { Engine.pause(item.id) }
                Status.PAUSED, Status.ERROR -> IconBox(Icons.Play, "Resume") { Engine.resume(item.id) }
                Status.COMPLETED -> IconBox(Icons.Open, "Open") { open() }
            }
            Spacer(Modifier.width(6.dp))
            Box {
                IconBox(Icons.More, "More") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.panel) {
                    if (item.status == Status.COMPLETED) {
                        DropdownMenuItem(text = { Text("Open", style = Type.body) }, onClick = { menu = false; open() })
                        DropdownMenuItem(text = { Text("Share file", style = Type.body) }, onClick = { menu = false; shareFile(ctx, item.path) })
                    }
                    DropdownMenuItem(text = { Text("Copy link", style = Type.body) }, onClick = {
                        menu = false
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", item.url))
                    })
                    DropdownMenuItem(text = { Text("Remove from list", style = Type.body) }, onClick = { menu = false; Engine.remove(item.id, deleteFile = false) })
                    DropdownMenuItem(
                        text = { Text(if (item.status == Status.COMPLETED) "Delete file" else "Cancel and delete", style = Type.body, color = c.err) },
                        onClick = { menu = false; Engine.remove(item.id, deleteFile = true) },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Progress bar
        Box(Modifier.fillMaxWidth().height(4.dp).background(c.barBg)) {
            Box(Modifier.fillMaxWidth(item.fraction.coerceIn(0f, 1f)).height(4.dp).background(barColor))
        }
        Spacer(Modifier.height(8.dp))
        Text(meta(item), style = Type.small, color = if (item.status == Status.ERROR) c.err else c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun meta(v: ItemView): String {
    val pct = "${(v.fraction * 100).toInt()}%"
    val size = when {
        v.total > 0 && v.status != Status.COMPLETED -> "${Format.size(v.received)} / ${Format.size(v.total)}"
        v.total > 0 -> Format.size(v.total)
        v.received > 0 -> Format.size(v.received)
        else -> null
    }
    return when (v.status) {
        Status.DOWNLOADING -> listOfNotNull(pct, size, if (v.speed > 0) Format.speed(v.speed) else null, Format.eta(v.etaSeconds).ifEmpty { null }, v.note)
        Status.QUEUED -> listOfNotNull(v.note ?: "Waiting", size)
        Status.PAUSED -> listOfNotNull("Paused", pct, size, if (!v.resumable && v.received > 0) "this server can't resume: starts over" else null)
        Status.COMPLETED -> listOfNotNull("Done", size)
        Status.ERROR -> listOfNotNull(v.error ?: "Failed")
    }.joinToString("  ·  ")
}
