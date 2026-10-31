package com.fastdownloader.app.ui

import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import android.os.Message
import android.webkit.WebChromeClient
import android.view.View
import android.content.pm.ActivityInfo
import android.content.ContextWrapper
import android.content.Context
import android.app.Activity
import android.os.Build
import android.view.WindowManager
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import com.fastdownloader.app.engine.Status
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

/** Browser state the rest of the app's screen follows. */
object BrowserChrome {
    /** A video fills the screen (the phone is sideways): the app hides its own bars. */
    var videoMode by mutableStateOf(false)
}

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
    private val onMedia: (Media) -> Unit,
    private val onPopupBlocked: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /** uBlock scriptlets for the page or frame at [url], as JSON (assets/scriptlets.js runs them). */
    @JavascriptInterface
    fun scriptlets(url: String, top: Boolean): String =
        if (state.blocking(if (top) url else state.topUrl)) Filters.engine?.scriptletsFor(url) ?: "[]" else "[]"

    @JavascriptInterface
    fun popupBlocked(@Suppress("UNUSED_PARAMETER") url: String) {
        main.post { onPopupBlocked() }
    }

    /** A video playlist the page loaded, recognised by its content (sites often hide the usual .m3u8 name). */
    @JavascriptInterface
    fun media(url: String, kind: String, frame: String) {
        if (!url.startsWith("http") || kind !in setOf("m3u8", "mpd")) return
        main.post { onMedia(Media(url, kind, frame)) }
    }

    @JavascriptInterface
    fun hideShorts(): Boolean = Engine.settings.hideShorts

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
fun BrowserScreen(visible: Boolean, onDownload: (AddRequest) -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit) {
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
    // A video the page put in full screen: its view and how to tell the page it has left full screen.
    var fullScreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }
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
        // The playlist loaded by the video's own frame (players often sit in a frame of another site).
        val streams = media.filter { it.kind == "m3u8" || it.kind == "mpd" }
        val stream = streams.lastOrNull { AdBlock.hostOf(it.referer) == AdBlock.hostOf(click.page) } ?: streams.lastOrNull()
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

    // The scriptlets first: they must run before the page's own scripts.
    val pageScript = remember {
        ctx.assets.open("scriptlets.js").bufferedReader().readText() + "\n" + ctx.assets.open("page.js").bufferedReader().readText()
    }
    val bridge = remember {
        PageBridge(
            state,
            onVideo = { downloadVideo(it) },
            onFound = { frame, n -> if (n > 0) videosByFrame[frame] = n else videosByFrame.remove(frame) },
            onMedia = { m -> if (media.none { it.url == m.url }) media.add(m) },
            onPopupBlocked = { blockedCount++ },
        )
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
            // New windows go through onCreateWindow below, where pop-ups and pop-unders are stopped.
            settings.setSupportMultipleWindows(true)
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
                        // The page or frame that asked for it: some servers only answer requests from their own player.
                        val referer = request.requestHeaders["Referer"] ?: request.requestHeaders["referer"] ?: state.topUrl
                        post {
                            // Skip the many small pieces a stream is made of; keep its playlist.
                            if (media.none { it.url == s } && !(kind == "mp4" && s.contains("range=", true))) {
                                media.add(Media(s, kind, referer))
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
                    BrowserChrome.videoMode = false
                    if (url != pageUrl) { media.clear(); blockedCount = 0; videosByFrame.clear(); showMedia = false }
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
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullScreen?.second?.onCustomViewHidden()
                    fullScreen = view to callback
                }

                override fun onHideCustomView() {
                    fullScreen = null
                }

                // A page opening a new window. A link the user tapped opens here, in this tab. Windows a page
                // opens by itself, or from a tap somewhere else on the page (the usual "pop-under" ad trick),
                // only open when they stay on the same site.
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val blocking = state.blocking()
                    fun open(url: String) {
                        if (blocking && Filters.engine?.shouldBlock(url, state.topUrl, AdBlock.Type.POPUP) == true) blockedCount++
                        else view.loadUrl(url)
                    }
                    val hit = view.hitTestResult
                    val link = hit.extra?.takeIf {
                        hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                    }
                    if (isUserGesture && link != null && link.startsWith("http")) { open(link); return false }
                    if (!isUserGesture && blocking) { blockedCount++; return false }
                    // Not a link: let the window start loading in a hidden view to see where it goes.
                    val probe = WebView(view.context)
                    var decided = false
                    fun decide(url: String) {
                        if (decided || url.isEmpty() || url == "about:blank") return
                        decided = true
                        probe.stopLoading()
                        val site = { u: String -> AdBlock.hostOf(u.lowercase())?.let { AdBlock.baseDomain(it) } }
                        if (!blocking || site(url) == site(state.topUrl)) open(url) else blockedCount++
                        probe.post { probe.destroy() }
                    }
                    probe.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { decide(r.url.toString()); return true }
                        override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { decide(url) }
                    }
                    probe.postDelayed({ if (!decided) { decided = true; probe.destroy() } }, 10_000)
                    (resultMsg.obj as WebView.WebViewTransport).webView = probe
                    resultMsg.sendToTarget()
                    return true
                }
            }
            // Links the page offers as downloads come here instead of the system downloader.
            setDownloadListener { url, _, _, _, _ -> onDownload(AddRequest(url, headersFor(url, pageUrl))) }
            loadUrl(BROWSER_HOME)
        }
    }

    BackHandler(enabled = visible && canBack) { web.goBack() }
    BackHandler(enabled = fullScreen != null) { fullScreen?.second?.onCustomViewHidden(); fullScreen = null }

    // Full-screen video covers the whole window, without status or navigation bars, turned to match the video.
    DisposableEffect(fullScreen) {
        val fs = fullScreen
        val act = ctx.findActivity()
        if (fs == null || act == null) return@DisposableEffect onDispose {}
        val root = act.window.decorView as ViewGroup
        val match = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        fs.first.setBackgroundColor(android.graphics.Color.BLACK)
        root.addView(fs.first, match)
        // The video is stretched to fill the whole screen, whatever its shape: nothing cropped, no black bars.
        web.evaluateJavascript(FILL_SCREEN, null)
        val bars = WindowCompat.getInsetsController(act.window, act.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        // Use the whole screen on every phone, including beside a notch or camera hole.
        val cutout = if (Build.VERSION.SDK_INT >= 28) act.window.attributes.layoutInDisplayCutoutMode else 0
        if (Build.VERSION.SDK_INT >= 28) {
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val orientation = act.requestedOrientation
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Upright videos (shorts, reels) stay upright.
        web.evaluateJavascript(FULLSCREEN_SHAPE) { shape ->
            if (fullScreen === fs && shape == "\"portrait\"") act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        onDispose {
            root.removeView(fs.first)
            bars.show(WindowInsetsCompat.Type.systemBars())
            // Back to the phone's own rotation setting.
            act.requestedOrientation = orientation
            if (Build.VERSION.SDK_INT >= 28) act.window.attributes = act.window.attributes.apply { layoutInDisplayCutoutMode = cutout }
        }
    }

    // Phone turned sideways while a video plays: the page's player fills the screen and the app's bars and the
    // system bars step aside. Turning back (or Back) undoes it. Real full screen takes over when the page asks.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(landscape, visible, fullScreen) {
        if (landscape && visible && fullScreen == null) {
            val frames = videosByFrame.keys.filter { it.isNotEmpty() }
                .joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
            web.evaluateJavascript("window.__fdVideoMode ? window.__fdVideoMode(true, $frames) : ''") { r ->
                BrowserChrome.videoMode = r == "\"video\"" || r == "\"frame\""
            }
        } else {
            if (fullScreen == null) web.evaluateJavascript("window.__fdVideoMode && window.__fdVideoMode(false)", null)
            BrowserChrome.videoMode = false
        }
    }
    BackHandler(enabled = BrowserChrome.videoMode && fullScreen == null) {
        web.evaluateJavascript("window.__fdVideoMode && window.__fdVideoMode(false)", null)
        BrowserChrome.videoMode = false
    }
    DisposableEffect(BrowserChrome.videoMode) {
        val act = ctx.findActivity()
        if (!BrowserChrome.videoMode || act == null) return@DisposableEffect onDispose {}
        val bars = WindowCompat.getInsetsController(act.window, act.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        val cutout = if (Build.VERSION.SDK_INT >= 28) act.window.attributes.layoutInDisplayCutoutMode else 0
        if (Build.VERSION.SDK_INT >= 28) {
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28) act.window.attributes = act.window.attributes.apply { layoutInDisplayCutoutMode = cutout }
        }
    }

    fun go(text: String) {
        val typed = text.trim()
        val joined = typed.replace(Regex("""\.\s+"""), ".")
        val t = if (!joined.contains(' ') && joined.contains('.')) joined else typed
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
        // A compact address bar, so pages (and their cookie or terms dialogs) keep as much room as possible.
        if (!BrowserChrome.videoMode) Row(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = address, onValueChange = { address = it }, singleLine = true,
                textStyle = Type.small.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { go(address) }),
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, c.border).padding(horizontal = 10.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(4.dp))
            // Download and the Downloads list in one: with videos on the page a tap downloads (the number is how
            // many); otherwise it opens the list (the number is how many are downloading). Holding it always opens the list.
            DownloadsButton(
                onClick = {
                    if (videoCount > 0) onDownload(AddRequest(pageUrl, headersFor(pageUrl, pageUrl)))
                    else if (media.isNotEmpty()) showMedia = !showMedia
                    else onDownloads()
                },
                onLongClick = onDownloads,
                videos = videoCount,
                found = videoCount > 0 || media.isNotEmpty(),
            )
            Spacer(Modifier.width(4.dp))
            // Shield with the number blocked: ad blocking on this site (tap to turn it off or on for the site).
            Box {
                IconBox(
                    Icons.Shield, "Ad blocking on this site ($blockedCount blocked)", BarIcon,
                    tint = if (!appSettings.blockAds || siteAllowed) c.muted else c.ok,
                ) {
                    if (!appSettings.blockAds) {
                        Toast.makeText(ctx, "Ad blocking is off in Settings › Privacy", Toast.LENGTH_SHORT).show()
                    } else if (host.isNotEmpty()) {
                        Engine.updateSettings { s ->
                            s.copy(adblockAllow = if (siteAllowed) s.adblockAllow.filterNot { host == it || host.endsWith(".$it") } else s.adblockAllow + host)
                        }
                        Toast.makeText(
                            ctx,
                            if (siteAllowed) "Blocking ads on $host" else "Ads allowed on $host ($blockedCount were blocked)",
                            Toast.LENGTH_SHORT,
                        ).show()
                        web.reload()
                    }
                }
                if (appSettings.blockAds && !siteAllowed && blockedCount > 0) {
                    Text(
                        if (blockedCount > 99) "99+" else "$blockedCount",
                        style = Type.label.copy(fontSize = 9.sp), color = c.solidFg,
                        modifier = Modifier.align(Alignment.TopEnd).background(c.solidBg).padding(horizontal = 3.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            IconBox(if (loading) Icons.Close else Icons.Refresh, "Reload", BarIcon) { if (loading) web.stopLoading() else web.reload() }
            Spacer(Modifier.width(4.dp))
            IconBox(Icons.Tune, "Settings", BarIcon, onClick = onSettings)
        }
        if (!BrowserChrome.videoMode) Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { (web.parent as? ViewGroup)?.removeView(web); web }, modifier = Modifier.fillMaxSize())
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

/** "portrait" or "landscape" for the video now in full screen ("" when it's in a frame the page can't see into). */
private const val FULLSCREEN_SHAPE = """(function(){var e=document.fullscreenElement||document.webkitFullscreenElement;
var v=e&&(e.tagName==='VIDEO'?e:e.querySelector('video'));
return v&&v.videoWidth?(v.videoHeight>v.videoWidth?'portrait':'landscape'):'';})()"""

/** Stretches the page's full-screen video to fill the whole screen. */
private const val FILL_SCREEN =
    "(function(){if(document.getElementById('__fdFill'))return;var s=document.createElement('style');s.id='__fdFill';" +
        "s.textContent=':fullscreen video,:-webkit-full-screen video,video:fullscreen,video:-webkit-full-screen{" +
        "position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;" +
        "object-fit:fill!important;transform:none!important}';(document.head||document.documentElement).appendChild(s);})()"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The address bar's buttons, a little narrower than usual so the address keeps room. */
private val BarIcon = Modifier.width(36.dp)

/**
 * The Download button. [videos] > 0: the number of videos on the page, in blue. Otherwise the number of downloads
 * in progress. [found]: something on the page can be downloaded.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsButton(onClick: () -> Unit, onLongClick: () -> Unit = onClick, videos: Int = 0, found: Boolean = false, modifier: Modifier = BarIcon) {
    val c = colors
    val items by Engine.itemsFlow.collectAsState()
    val active = items.count { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }
    val badge = if (videos > 0) videos else active
    Box {
        Box(
            modifier.size(40.dp).border(1.dp, c.border).combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Download,
                if (videos > 0) "Download ($videos videos on this page)" else "Downloads ($active in progress)",
                tint = if (found) c.accent else c.text, modifier = Modifier.size(18.dp),
            )
        }
        if (badge > 0) {
            Text(
                if (badge > 99) "99+" else "$badge", style = Type.label.copy(fontSize = 9.sp),
                color = c.solidFg,
                modifier = Modifier.align(Alignment.TopEnd).background(if (videos > 0) c.accent else c.solidBg).padding(horizontal = 3.dp),
            )
        }
    }
}
