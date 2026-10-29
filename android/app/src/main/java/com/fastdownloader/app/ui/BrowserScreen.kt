package com.fastdownloader.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Links
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream

/** A video or audio file the page loaded. */
data class Media(val url: String, val kind: String, val referer: String)

/** Ad and tracker domains, shared with the browser extension (extension/rules/trackers.json). */
object Blocklist {
    @Volatile private var domains: Set<String>? = null

    fun load(ctx: Context): Set<String> = domains ?: runCatching {
        val rules = Json.parseToJsonElement(ctx.assets.open("trackers.json").bufferedReader().readText()).jsonArray
        rules.flatMap { r ->
            r.jsonObject["condition"]?.jsonObject?.get("requestDomains")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        }.toSet()
    }.getOrDefault(emptySet()).also { domains = it }

    fun blocked(ctx: Context, host: String?): Boolean {
        if (host == null) return false
        val set = load(ctx)
        var h = host.lowercase()
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }
}

private val MEDIA = Regex("""\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|opus|flac)(\?|#|$)""", RegexOption.IGNORE_CASE)
private val EMPTY get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
private const val HOME = "https://duckduckgo.com/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(visible: Boolean, onDownload: (AddRequest) -> Unit) {
    val c = colors
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var address by remember { mutableStateOf(HOME) }
    var pageUrl by remember { mutableStateOf(HOME) }
    var loading by remember { mutableStateOf(false) }
    var blockedCount by remember { mutableIntStateOf(0) }
    var showMedia by remember { mutableStateOf(false) }
    val media = remember { mutableStateListOf<Media>() }
    var canBack by remember { mutableStateOf(false) }

    fun headersFor(url: String, referer: String?): Map<String, String> {
        val h = linkedMapOf("User-Agent" to com.fastdownloader.app.engine.Net.userAgent)
        CookieManager.getInstance().getCookie(url)?.let { h["Cookie"] = it }
        if (referer != null) h["Referer"] = referer
        return h
    }

    val web = remember {
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url
                    if (!request.isForMainFrame && Engine.settings.blockAds && Blocklist.blocked(ctx, u.host)) {
                        post { blockedCount++ }
                        return EMPTY
                    }
                    val s = u.toString()
                    MEDIA.find(s)?.let { m ->
                        val kind = m.groupValues[1].lowercase()
                        post {
                            // Skip the many small pieces a stream is made of; keep its playlist.
                            if (media.none { it.url == s } && !(kind == "mp4" && s.contains("range=", true))) {
                                media.add(Media(s, kind, pageUrl))
                            }
                        }
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val s = request.url.toString()
                    if (Links.isMagnet(s)) { onDownload(AddRequest(s)); return true }
                    return request.url.scheme !in setOf("http", "https")
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    loading = true
                    if (url != pageUrl) { media.clear(); blockedCount = 0 }
                    pageUrl = url
                    address = url
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loading = false
                    canBack = view.canGoBack()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    pageUrl = url
                    address = url
                    canBack = view.canGoBack()
                }
            }
            // Links the page offers as downloads come here instead of the system downloader.
            setDownloadListener { url, _, _, _, _ -> onDownload(AddRequest(url, headersFor(url, pageUrl))) }
            loadUrl(HOME)
        }
    }

    BackHandler(enabled = visible && canBack) { web.goBack() }

    fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            Links.isMagnet(t) -> { onDownload(AddRequest(t)); return }
            Links.httpUri(t) != null -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + android.net.Uri.encode(t)
        }
        web.loadUrl(url)
        focus.clearFocus()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(c.panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(Icons.Back, "Back", tint = if (canBack) c.text else c.muted) { if (web.canGoBack()) web.goBack() }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = address, onValueChange = { address = it }, modifier = Modifier.weight(1f), singleLine = true,
                textStyle = Type.small.copy(color = c.text), colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(address) }),
            )
            Spacer(Modifier.width(6.dp))
            IconBox(if (loading) Icons.Close else Icons.Refresh, "Reload") { if (loading) web.stopLoading() else web.reload() }
        }
        Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { (web.parent as? ViewGroup)?.removeView(web); web }, modifier = Modifier.fillMaxSize())
        }
        Divider()
        // Download bar: this page as a video (any site yt-dlp knows), plus media files the page loaded.
        Row(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(if (media.isEmpty()) "No media found yet" else "${media.size} media found", color = if (media.isEmpty()) c.muted else c.accent)
                Text(
                    if (Engine.settings.blockAds) "$blockedCount ads and trackers blocked" else "Ad blocking is off",
                    style = Type.small, color = c.muted, maxLines = 1,
                )
            }
            if (media.isNotEmpty()) {
                LineButton("Files") { showMedia = !showMedia }
                Spacer(Modifier.width(8.dp))
            }
            SolidButton("Download", icon = Icons.Download) {
                onDownload(AddRequest(pageUrl, headersFor(pageUrl, pageUrl)))
            }
        }
        if (showMedia && media.isNotEmpty()) {
            Divider()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).background(c.panel)) {
                items(media.reversed(), key = { it.url }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            showMedia = false
                            onDownload(AddRequest(m.url, headersFor(m.url, m.referer)))
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.background(c.panel2).padding(horizontal = 5.dp, vertical = 1.dp)) { Label(m.kind) }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            android.net.Uri.parse(m.url).lastPathSegment ?: m.url,
                            style = Type.small, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                    Divider()
                }
            }
        }
    }
}
