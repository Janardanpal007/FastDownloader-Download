package com.fastdownloader.app.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import com.fastdownloader.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest

/**
 * Looks for a newer FastDownloader on GitHub (the public downloads repository's latest release).
 * Nothing installs by itself: the user downloads the APK for this phone, it's checked against the
 * release's SHA256SUMS.txt, and Android's own installer asks before updating.
 */
object Updater {
    private const val RELEASES_API = "https://api.github.com/repos/Janardanpal007/FastDownloader-Download/releases/latest"
    const val EVERY_MS = 6 * 3600_000L

    data class Release(val version: String, val page: String, val apkName: String?, val apkUrl: String?, val apkSize: Long, val sha256: String?)

    data class State(
        val current: String = BuildConfig.VERSION_NAME,
        val available: Release? = null,
        val checking: Boolean = false,
        val lastCheck: Long = 0,
        /** 0..1 while the APK downloads. */
        val progress: Float? = null,
        val ready: File? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** "v1.10.2" → [1, 10, 2] */
    fun parseVersion(v: String): List<Int>? =
        Regex("""^v?(\d+)\.(\d+)\.(\d+)""").find(v.trim())?.groupValues?.drop(1)?.map { it.toInt() }

    fun isNewer(a: String, b: String): Boolean {
        val x = parseVersion(a) ?: return false
        val y = parseVersion(b) ?: return false
        for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }

    /** The release's APK for this phone's processor (arm64, arm, x86_64), else the universal one. */
    fun pickApk(names: List<String>, abis: List<String> = Build.SUPPORTED_ABIS.toList()): String? {
        val suffix = when (abis.firstOrNull()) {
            "arm64-v8a" -> "android-arm64.apk"
            "armeabi-v7a" -> "android-arm.apk"
            "x86_64" -> "android-x86_64.apk"
            else -> null
        }
        return names.firstOrNull { suffix != null && it.endsWith(suffix) } ?: names.firstOrNull { it.endsWith("android-universal.apk") }
    }

    /** "<sha256>  <file name>" lines → name to sha256 */
    fun parseSums(text: String): Map<String, String> = text.lines().mapNotNull { line ->
        Regex("""^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$""").find(line)?.let { it.groupValues[2] to it.groupValues[1].lowercase() }
    }.toMap()

    private fun fetch(url: String, accept: String = "*/*"): String =
        Net.client().newCall(Net.request(url, mapOf("Accept" to accept))).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body.string()
        }

    /** Returns the newer release, or null when this version is the newest. Runs network calls: call off the main thread. */
    fun check(): Release? {
        _state.value = _state.value.copy(checking = true, error = null)
        try {
            val rel = Json.parseToJsonElement(fetch(RELEASES_API, "application/vnd.github+json")).jsonObject
            val tag = rel["tag_name"]?.jsonPrimitive?.content.orEmpty()
            val draft = rel["draft"]?.jsonPrimitive?.content == "true" || rel["prerelease"]?.jsonPrimitive?.content == "true"
            if (draft || !isNewer(tag, BuildConfig.VERSION_NAME)) {
                _state.value = _state.value.copy(checking = false, available = null, lastCheck = System.currentTimeMillis())
                return null
            }
            val assets = rel["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
            fun name(a: Map<String, kotlinx.serialization.json.JsonElement>) = a["name"]?.jsonPrimitive?.content.orEmpty()
            val apkName = pickApk(assets.map { name(it) })
            val apk = assets.firstOrNull { name(it) == apkName }
            val sumsUrl = assets.firstOrNull { name(it) == "SHA256SUMS.txt" }?.get("browser_download_url")?.jsonPrimitive?.content
            val sha = if (apkName != null && sumsUrl != null) runCatching { parseSums(fetch(sumsUrl))[apkName] }.getOrNull() else null
            val release = Release(
                version = tag.removePrefix("v"),
                page = rel["html_url"]?.jsonPrimitive?.content.orEmpty(),
                apkName = apkName,
                apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.content,
                apkSize = apk?.get("size")?.jsonPrimitive?.longOrNull ?: -1,
                sha256 = sha,
            )
            val keep = _state.value.takeIf { it.available?.version == release.version }
            _state.value = State(available = release, lastCheck = System.currentTimeMillis(), progress = keep?.progress, ready = keep?.ready)
            return release
        } catch (e: Exception) {
            _state.value = _state.value.copy(checking = false, error = "Couldn't check for updates (${e.message ?: "no connection"})")
            throw e
        }
    }

    /** Downloads the APK into the app's cache and checks its SHA-256. Call off the main thread. */
    fun download(ctx: Context) {
        val rel = _state.value.available ?: return
        val url = rel.apkUrl ?: error("This release has no Android app")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // older updates
        val out = File(dir, rel.apkName!!)
        _state.value = _state.value.copy(progress = 0f, ready = null, error = null)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            Net.client().newCall(Net.request(url, emptyMap())).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val total = r.body.contentLength().takeIf { it > 0 } ?: rel.apkSize
                r.body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        var shown = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            md.update(buf, 0, n)
                            got += n
                            if (total > 0 && got - shown > total / 100) {
                                shown = got
                                _state.value = _state.value.copy(progress = got.toFloat() / total)
                            }
                        }
                    }
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (rel.sha256 != null && actual != rel.sha256) {
                out.delete()
                error("The download doesn't match the published checksum, so it was deleted. Try again.")
            }
            _state.value = _state.value.copy(progress = null, ready = out)
        } catch (e: Exception) {
            out.delete()
            _state.value = _state.value.copy(progress = null, error = e.message ?: "Download failed")
        }
    }

    /**
     * Opens Android's installer for the downloaded update. Android 8+ first needs "Install unknown apps"
     * allowed for FastDownloader; then this opens that settings page instead and returns false.
     */
    fun install(ctx: Context): Boolean {
        val file = _state.value.ready ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    fun openPage(ctx: Context) {
        val page = _state.value.available?.page ?: return
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
