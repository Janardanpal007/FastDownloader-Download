package com.fastdownloader.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fastdownloader.app.engine.AdBlock
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Filters
import com.fastdownloader.app.engine.Links
import com.fastdownloader.app.engine.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream

/** A video or audio file the page loaded. */
data class Media(val url: String, val kind: String, val referer: String)

/** A tap on a page's Download button. [page] is the page or frame the video is in. */
private data class VideoClick(val page: String, val top: Boolean, val src: String)

private val MEDIA = Regex("""\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|opus|flac)(\?|#|$)""", RegexOption.IGNORE_CASE)
/**
 * The answer to a blocked request: "204 No Content", readable from the page's origin. A browser extension
 * makes blocked requests fail; WebView can't, and an empty 200 reply or a cross-origin error breaks
 * sites like YouTube that expect their tracking calls to just fail quietly.
 */
private fun blockedResponse(req: WebResourceRequest, pageUrl: String): WebResourceResponse {
    val origin = req.requestHeaders["Origin"] ?: req.requestHeaders["origin"]
        ?: pageUrl.substringBefore("://", "") .let { scheme -> if (scheme.isEmpty()) "*" else "$scheme://" + (AdBlock.hostOf(pageUrl) ?: "") }
    val headers = mapOf(
        "Access-Control-Allow-Origin" to origin,
        "Access-Control-Allow-Credentials" to "true",
        "Cache-Control" to "no-store",
    )
    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", headers, ByteArrayInputStream(ByteArray(0)))
}
const val BROWSER_HOME = "https://www.youtube.com/"

/** What the WebView's background threads need to know about the current page. */
private class PageState {
    @Volatile var topUrl: String = BROWSER_HOME
    @Volatile var lastTouch: Long = 0

    /** Blocking is on, and the user hasn't turned it off for this site. */
    fun blocking(url: String = topUrl): Boolean {
        val s = Engine.settings
        if (!s.blockAds) return false
        val host = AdBlock.hostOf(url.lowercase()) ?: return true
        return s.adblockAllow.none { host == it || host.endsWith(".$it") }
    }
}

/** The calls the page script (assets/page.js) can make. Every frame of every page can call these. */
private class PageBridge(
    private val state: PageState,
    private val onVideo: (VideoClick) -> Unit,
    private val onFound: (frame: String, count: Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /** YouTube ad removal is part of ad blocking: off when blocking is off for the site. */
    @JavascriptInterface
    fun youtubeAdsOff(): Boolean = state.blocking()

    @JavascriptInterface
    fun cosmeticBase(url: String): String =
        if (state.blocking()) Filters.engine?.cosmeticBase(url) ?: "" else ""

    @JavascriptInterface
    fun cosmeticFor(url: String, idsAndClasses: String): String =
        if (state.blocking()) Filters.engine?.cosmeticFor(url, idsAndClasses.take(200_000)) ?: "" else ""

    /** Only acted on right after a real tap, so a page can't open download panels by itself. */
    @JavascriptInterface
    fun video(json: String) {
        if (System.currentTimeMillis() - state.lastTouch > 3000) return
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        val click = VideoClick(
            page = o["page"]?.jsonPrimitive?.contentOrNull ?: return,
            top = o["top"]?.jsonPrimitive?.booleanOrNull ?: true,
            src = o["src"]?.jsonPrimitive?.contentOrNull ?: "",
        )
        main.post { onVideo(click) }
    }

    @JavascriptInterface
    fun found(count: Int, url: String, top: Boolean) {
        main.post { onFound(if (top) "" else url, count) }
    }
}

/** Resource type for filter rules like $script or $image. WebView doesn't say, so it's worked out. */
private fun typeOf(req: WebResourceRequest): AdBlock.Type {
    if (req.isForMainFrame) return AdBlock.Type.DOCUMENT
    val accept = req.requestHeaders["Accept"]?.lowercase() ?: ""
    val ext = (req.url.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
    return when {
        accept.startsWith("text/html") -> AdBlock.Type.SUBDOCUMENT
        accept.startsWith("text/css") || ext == "css" -> AdBlock.Type.STYLESHEET
        accept.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp") -> AdBlock.Type.IMAGE
        ext == "js" || ext == "mjs" -> AdBlock.Type.SCRIPT
        ext in setOf("woff", "woff2", "ttf", "otf", "eot") -> AdBlock.Type.FONT
        ext in setOf("mp4", "webm", "m3u8", "mpd", "ts", "m4s", "mp3", "m4a", "aac", "ogg") -> AdBlock.Type.MEDIA
        accept.contains("json") -> AdBlock.Type.XHR
        else -> AdBlock.Type.OTHER
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "RequiresFeature")
@Composable
fun BrowserScreen(visible: Boolean, onDownload: (AddRequest) -> Unit) {
    val c = colors
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val appSettings by Engine.settingsFlow.collectAsState()
    val filters by Filters.status.collectAsState()
    var address by remember { mutableStateOf(BROWSER_HOME) }
    var pageUrl by remember { mutableStateOf(BROWSER_HOME) }
    var loading by remember { mutableStateOf(false) }
    var blockedCount by remember { mutableIntStateOf(0) }
    var showMedia by remember { mutableStateOf(false) }
    val media = remember { mutableStateListOf<Media>() }
    val videosByFrame = remember { mutableStateMapOf<String, Int>() }
    var canBack by remember { mutableStateOf(false) }
    val state = remember { PageState() }

    // Build the blocker from the filter lists, and refresh them if they're a few days old.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { if (Filters.engine == null) Filters.load(ctx) }
            runCatching { Filters.updateIfDue(ctx) }
        }
    }

    fun headersFor(url: String, referer: String?): Map<String, String> {
        val h = linkedMapOf("User-Agent" to Net.userAgent)
        CookieManager.getInstance().getCookie(url)?.let { h["Cookie"] = it }
        if (referer != null) h["Referer"] = referer
        return h
    }

    /** A Download button on a video was tapped: pick the best way to get that video. */
    fun downloadVideo(click: VideoClick) {
        val top = state.topUrl
        val src = click.src
        val stream = media.lastOrNull { it.kind == "m3u8" || it.kind == "mpd" }
        val req = when {
            // Sites yt-dlp knows (YouTube, Vimeo, X…): the page, for every quality.
            Links.isVideoPageUrl(click.page) -> AddRequest(click.page, headersFor(click.page, top))
            Links.isVideoPageUrl(top) -> AddRequest(top, headersFor(top, top))
            // A plain video file.
            src.startsWith("http") -> AddRequest(src, headersFor(src, click.page))
            // Streamed (blob:) video: the playlist the page loaded, or else let yt-dlp read the page.
            stream != null -> AddRequest(stream.url, headersFor(stream.url, stream.referer))
            else -> AddRequest(click.page, headersFor(click.page, top))
        }
        onDownload(req)
    }

    val pageScript = remember { ctx.assets.open("page.js").bufferedReader().readText() }
    val bridge = remember {
        PageBridge(state, onVideo = { downloadVideo(it) }, onFound = { frame, n -> if (n > 0) videosByFrame[frame] = n else videosByFrame.remove(frame) })
    }
    val documentStart = remember { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }

    val web = remember {
        if (com.fastdownloader.app.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
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
            addJavascriptInterface(bridge, "__fdBridge")
            // In every page and frame, before the page's own scripts (falls back to after load on old WebViews).
            if (documentStart) WebViewCompat.addDocumentStartJavaScript(this, pageScript, setOf("*"))
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_UP) state.lastTouch = System.currentTimeMillis()
                false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val s = request.url.toString()
                    if (!request.isForMainFrame && state.blocking()) {
                        val engine = Filters.engine
                        if (engine != null && engine.shouldBlock(s, state.topUrl, typeOf(request))) {
                            post { blockedCount++ }
                            return blockedResponse(request, state.topUrl)
                        }
                    }
                    MEDIA.find(s)?.let { m ->
                        val kind = m.groupValues[1].lowercase()
                        post {
                            // Skip the many small pieces a stream is made of; keep its playlist.
                            if (media.none { it.url == s } && !(kind == "mp4" && s.contains("range=", true))) {
                                media.add(Media(s, kind, state.topUrl))
                            }
                        }
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val s = request.url.toString()
                    if (Links.isMagnet(s)) { onDownload(AddRequest(s)); return true }
                    if (request.url.scheme !in setOf("http", "https")) return true
                    // Pop-ups and redirects to ad pages that the user didn't tap on.
                    if (request.isForMainFrame && !request.hasGesture() && state.blocking() &&
                        Filters.engine?.shouldBlock(s, state.topUrl, AdBlock.Type.POPUP) == true
                    ) {
                        blockedCount++
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    loading = true
                    if (url != pageUrl) { media.clear(); blockedCount = 0; videosByFrame.clear() }
                    pageUrl = url
                    state.topUrl = url
                    address = url
                    if (!documentStart) view.evaluateJavascript(pageScript, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loading = false
                    canBack = view.canGoBack()
                    if (!documentStart) view.evaluateJavascript(pageScript, null)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    // Sites like YouTube change pages without reloading.
                    if (url != pageUrl) media.clear()
                    pageUrl = url
                    state.topUrl = url
                    address = url
                    canBack = view.canGoBack()
                }
            }
            // Links the page offers as downloads come here instead of the system downloader.
            setDownloadListener { url, _, _, _, _ -> onDownload(AddRequest(url, headersFor(url, pageUrl))) }
            loadUrl(BROWSER_HOME)
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

    val host = AdBlock.hostOf(pageUrl.lowercase())?.removePrefix("www.")?.removePrefix("m.") ?: ""
    val siteAllowed = host.isNotEmpty() && appSettings.adblockAllow.any { host == it || host.endsWith(".$it") }
    val videoCount = videosByFrame.values.sum()

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
            // Shield: ad blocking on this site (tap to turn it off or on for the site).
            IconBox(
                Icons.Shield, "Ad blocking on this site",
                tint = if (!appSettings.blockAds || siteAllowed) c.muted else c.ok,
            ) {
                if (!appSettings.blockAds) {
                    Toast.makeText(ctx, "Ad blocking is off in Settings › Privacy", Toast.LENGTH_SHORT).show()
                } else if (host.isNotEmpty()) {
                    Engine.updateSettings { s ->
                        s.copy(adblockAllow = if (siteAllowed) s.adblockAllow.filterNot { host == it || host.endsWith(".$it") } else s.adblockAllow + host)
                    }
                    Toast.makeText(ctx, if (siteAllowed) "Blocking ads on $host" else "Ads allowed on $host", Toast.LENGTH_SHORT).show()
                    web.reload()
                }
            }
            Spacer(Modifier.width(6.dp))
            IconBox(if (loading) Icons.Close else Icons.Refresh, "Reload") { if (loading) web.stopLoading() else web.reload() }
        }
        Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { (web.parent as? ViewGroup)?.removeView(web); web }, modifier = Modifier.fillMaxSize())
        }
        Divider()
        // Download bar: videos on this page (each also has its own Download button), files it loaded, ads blocked.
        Row(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(
                    when {
                        videoCount == 1 -> "1 video on this page"
                        videoCount > 1 -> "$videoCount videos on this page"
                        media.isNotEmpty() -> "${media.size} media files found"
                        else -> "No video found yet"
                    },
                    color = if (videoCount > 0 || media.isNotEmpty()) c.accent else c.muted,
                )
                Text(
                    when {
                        !appSettings.blockAds -> "Ad blocking is off"
                        siteAllowed -> "Ads allowed on this site"
                        filters.loading && Filters.engine == null -> "Loading uBlock filter lists…"
                        else -> "$blockedCount ads and trackers blocked"
                    },
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
