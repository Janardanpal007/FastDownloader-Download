package com.fastdownloader.app.engine

import android.webkit.MimeTypeMap
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** What a link points to, found with a one-byte request before downloading. */
data class Probe(
    val finalUrl: String,
    val size: Long,
    val resumable: Boolean,
    val fileName: String,
    val mime: String?,
    val etag: String?,
    val lastModified: String?,
) {
    val isWebPage get() = mime?.startsWith("text/html") == true
}

/**
 * The one HTTP client used for downloads, link checks and yt-dlp's proxy setting.
 * Rebuilt when proxy or privacy settings change. With a proxy there is no direct fallback:
 * if the proxy is down, downloads fail rather than leaking the real address.
 */
object Net {
    @Volatile var userAgent: String =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    @Volatile private var settings = Settings()
    @Volatile private var client: OkHttpClient = build(settings)

    fun client(): OkHttpClient = client

    fun configure(s: Settings) {
        val old = settings
        settings = s
        if (old.proxyType != s.proxyType || old.proxyHost != s.proxyHost || old.proxyPort != s.proxyPort ||
            old.proxyUser != s.proxyUser || old.proxyPass != s.proxyPass || old.privacyHeaders != s.privacyHeaders
        ) {
            client = build(s)
        }
    }

    /** socks5h:// or http:// form, for yt-dlp. Null when no proxy is set. */
    fun proxyUrl(): String? {
        val s = settings
        if (!s.proxyOn) return null
        val auth = if (s.proxyUser.isNotEmpty()) "${enc(s.proxyUser)}:${enc(s.proxyPass)}@" else ""
        val scheme = if (s.proxyType == "socks5") "socks5h" else "http"
        return "$scheme://$auth${s.proxyHost}:${s.proxyPort}"
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    private fun build(s: Settings): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        if (s.proxyOn) {
            if (s.proxyType == "socks5") {
                // Unresolved address: the proxy looks up host names too, so DNS doesn't leak.
                b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(s.proxyHost, s.proxyPort)))
                if (s.proxyUser.isNotEmpty()) {
                    Authenticator.setDefault(object : Authenticator() {
                        override fun getPasswordAuthentication() =
                            if (requestingHost == s.proxyHost) PasswordAuthentication(s.proxyUser, s.proxyPass.toCharArray()) else null
                    })
                }
            } else {
                b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(s.proxyHost, s.proxyPort)))
                if (s.proxyUser.isNotEmpty()) {
                    b.proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(s.proxyUser, s.proxyPass)).build()
                    }
                }
            }
        }
        b.addInterceptor { chain ->
            val r = chain.request().newBuilder()
            if (chain.request().header("User-Agent") == null) r.header("User-Agent", userAgent)
            if (s.privacyHeaders) r.header("DNT", "1").header("Sec-GPC", "1")
            chain.proceed(r.build())
        }
        return b.build()
    }

    fun request(url: String, headers: Map<String, String>, range: String? = null): Request {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) runCatching { b.header(k, v) }
        if (range != null) b.header("Range", range).header("Accept-Encoding", "identity")
        return b.build()
    }

    /** Size, name and resume support, using a request for the first byte only. */
    fun probe(url: String, headers: Map<String, String>): Probe {
        client.newCall(request(url, headers, "bytes=0-0")).execute().use { r ->
            if (!r.isSuccessful) throw IOException(httpError(r.code))
            val total = when (r.code) {
                206 -> r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1
                else -> r.header("Content-Length")?.toLongOrNull() ?: -1
            }
            val mime = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val finalUrl = r.request.url.toString()
            return Probe(
                finalUrl = finalUrl,
                size = total,
                resumable = r.code == 206 && total > 0,
                fileName = fileNameFor(r, finalUrl, mime),
                mime = mime,
                etag = r.header("ETag"),
                lastModified = r.header("Last-Modified"),
            )
        }
    }

    fun httpError(code: Int) = when (code) {
        401, 403 -> "The server refused access (HTTP $code). The link may need you to sign in, or it has expired."
        404, 410 -> "File not found (HTTP $code). The link may have expired."
        416 -> "The server couldn't send this part of the file (HTTP 416)."
        429 -> "The server says too many requests (HTTP 429). Try again later."
        in 500..599 -> "The server had a problem (HTTP $code). Try again later."
        else -> "The server answered HTTP $code."
    }

    private fun fileNameFor(r: Response, url: String, mime: String?): String {
        var name = contentDispositionName(r.header("Content-Disposition"))
        if (name.isNullOrBlank()) {
            name = runCatching {
                URLDecoder.decode(r.request.url.encodedPathSegments.lastOrNull { it.isNotEmpty() } ?: "", "UTF-8")
            }.getOrNull()
        }
        if (name.isNullOrBlank()) name = r.request.url.host
        var safe = Links.safeFileName(name!!)
        if (Categories.extension(safe).isEmpty() && mime != null && !mime.startsWith("text/html")) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { safe = "$safe.$it" }
        }
        return safe
    }

    private fun contentDispositionName(cd: String?): String? {
        if (cd == null) return null
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let {
            return runCatching { URLDecoder.decode(it.groupValues[2].trim().trim('"'), it.groupValues[1].ifEmpty { "UTF-8" }) }.getOrNull()
        }
        Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1] }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim() }
        return null
    }

    /** A short, plain explanation of a connection problem. */
    fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Couldn't find this site. Check the link and your internet connection."
        is java.net.SocketTimeoutException -> "The site took too long to answer. Try again later."
        is java.net.ConnectException -> if (settings.proxyOn) "Couldn't connect through the proxy. Check its address in Settings › Privacy." else "Couldn't connect to the site."
        is javax.net.ssl.SSLException -> "The site's secure connection failed."
        else -> if (e.message?.contains("end of stream", true) == true) "The site closed the connection without answering. It may be down; try again later."
        else e.message ?: "Couldn't reach this link."
    }

    /** The public address sites see (through the proxy when one is set). */
    fun visibleIp(): String =
        client.newCall(Request.Builder().url("https://api.ipify.org").build()).execute().use {
            if (!it.isSuccessful) throw IOException(httpError(it.code))
            it.body.string().trim()
        }
}

/** Shared speed limit for all downloads (token bucket). 0 = no limit. */
class Throttle {
    @Volatile var bytesPerSecond: Long = 0
    private var tokens = 0.0
    private var last = System.nanoTime()

    fun take(n: Int) {
        val rate = bytesPerSecond
        if (rate <= 0) return
        val waitMs: Long
        synchronized(this) {
            val now = System.nanoTime()
            tokens = minOf(rate.toDouble(), tokens + (now - last) / 1e9 * rate)
            last = now
            tokens -= n
            waitMs = if (tokens < 0) (-tokens / rate * 1000).toLong() else 0
        }
        if (waitMs > 0) Thread.sleep(waitMs)
    }
}
