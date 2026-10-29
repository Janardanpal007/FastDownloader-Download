package com.fastdownloader.app.engine

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToLong

/** One choice in the quality menu. */
data class Quality(val label: String, val format: String, val size: Long, val height: Int = 0, val audio: String? = null)

data class VideoDetails(val title: String, val durationSeconds: Long, val site: String?, val qualities: List<Quality>, val defaultIndex: Int)

/** yt-dlp, run with the Python, FFmpeg and QuickJS that youtubedl-android ships for each processor type. */
object Ytdlp {
    @Volatile private var ready = false
    private val json = Json { ignoreUnknownKeys = true }
    private val TIERS = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)

    @Synchronized
    fun ensure(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
    }

    fun version(ctx: Context): String? = runCatching { YoutubeDL.getInstance().versionName(ctx) }.getOrNull()

    /** Sites change often, so yt-dlp is updated in the background at most once a day. */
    fun updateIfDue(ctx: Context, force: Boolean = false): String {
        val prefs = ctx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        val last = prefs.getLong("lastUpdate", 0)
        if (!force && System.currentTimeMillis() - last < 24 * 3600_000L) return "Checked today"
        ensure(ctx)
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
        prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
        return if (status == YoutubeDL.UpdateStatus.DONE) "Updated to ${version(ctx)}" else "Already up to date"
    }

    /** Options every call gets: one video (not a playlist), same proxy, cookies and browser identity as the app. */
    fun base(req: YoutubeDLRequest, headers: Map<String, String>, cookieFile: File?) {
        req.addOption("--no-playlist")
        req.addOption("--encoding", "utf-8")
        req.addOption("--user-agent", headers["User-Agent"] ?: Net.userAgent)
        headers["Referer"]?.let { req.addOption("--referer", it) }
        for ((k, v) in headers) if (k !in setOf("User-Agent", "Referer", "Cookie")) req.addOption("--add-header", "$k:$v")
        Net.proxyUrl()?.let { req.addOption("--proxy", it) }
        if (cookieFile != null) req.addOption("--cookies", cookieFile.absolutePath)
    }

    fun inspect(ctx: Context, url: String, headers: Map<String, String>, cookieFile: File?): VideoDetails {
        ensure(ctx)
        fun request() = YoutubeDLRequest(url).also {
            base(it, headers, cookieFile)
            it.addOption("--dump-single-json")
            it.addOption("--skip-download")
            it.addOption("--extractor-retries", "3")
        }
        // A dropped connection while YouTube's player script loads leaves formats missing: try once more.
        val res = try {
            YoutubeDL.getInstance().execute(request())
        } catch (first: Exception) {
            try {
                YoutubeDL.getInstance().execute(request())
            } catch (e: Exception) {
                throw IllegalStateException(friendlyError(e.message ?: ""))
            }
        }
        val info = json.parseToJsonElement(res.out.lines().last { it.startsWith("{") }).jsonObject
        return details(url, info)
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull ?: num(k)?.toInt()

    fun tier(width: Int, height: Int): Int {
        if (width <= 0) return TIERS.minBy { abs(it - height) }
        val short = minOf(width, height)
        val long = maxOf(width, height)
        val p = maxOf(short, (long * 9 / 16.0).roundToLong().toInt())
        return TIERS.minBy { abs(it - p) }
    }

    /** One entry per resolution plus audio-only, like the desktop app (src/engine/ytdlp.js). */
    fun details(url: String, info: JsonObject): VideoDetails {
        val duration = info.num("duration") ?: 0.0
        val formats = (info["formats"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        fun size(f: JsonObject?): Long = f?.let {
            it["filesize"]?.jsonPrimitive?.longOrNull ?: it["filesize_approx"]?.jsonPrimitive?.longOrNull
                ?: it.num("tbr")?.let { tbr -> if (duration > 0) (tbr * 125 * duration).toLong() else null } ?: 0
        } ?: 0
        fun hasCodec(f: JsonObject, k: String) = f.str(k).let { it != null && it != "none" }

        val audio = formats.filter { hasCodec(it, "acodec") && !hasCodec(it, "vcodec") }
            .sortedByDescending { it.num("abr") ?: it.num("tbr") ?: 0.0 }
        val bestAudio = audio.firstOrNull { it.str("ext") == "m4a" } ?: audio.firstOrNull()
        val video = formats.filter { hasCodec(it, "vcodec") && (it.int("height") ?: 0) > 0 }
        val tiers = video.groupBy { tier(it.int("width") ?: 0, it.int("height") ?: 0) }
        // H.264 first (plays everywhere), plain HTTPS before HLS, then highest bitrate.
        fun rank(f: JsonObject) = (if (f.str("vcodec")?.startsWith("avc1") == true) 2 else 0) + (if (f.str("protocol") == "https") 1 else 0)
        val out = tiers.keys.sortedDescending().map { t ->
            val best = tiers[t]!!.sortedWith(compareByDescending<JsonObject> { rank(it) }.thenByDescending { it.num("tbr") ?: 0.0 }).first()
            val fps = (best.num("fps") ?: 0.0).let { if (it > 30) " ${it.toInt()}fps" else "" }
            val tag = when { t >= 4320 -> " 8K"; t >= 2160 -> " 4K"; t >= 1440 -> " 2K"; t >= 720 -> " HD"; else -> "" }
            val withAudio = hasCodec(best, "acodec")
            val bytes = size(best) + if (withAudio) 0 else size(bestAudio)
            val w = best.int("width") ?: 0
            val h = best.int("height") ?: 0
            val id = best.str("format_id")
            Quality(
                label = listOfNotNull("${t}p$fps$tag", if (w > 0 && h > 0) "$w×$h" else null, if (bytes > 0) "~${Format.size(bytes)}" else null).joinToString(" · "),
                format = if (withAudio) "$id/bv*[height<=$h]+ba/b" else "$id+ba[ext=m4a]/$id+ba/bv*[height<=$h]+ba/b",
                size = if (bytes > 0) bytes else -1,
                height = t,
            )
        }.toMutableList()
        if (out.isEmpty()) out += Quality("Best quality", "bv*+ba/b", -1)
        if (audio.isNotEmpty() || video.isEmpty()) {
            val a = size(bestAudio)
            out += Quality(listOfNotNull("Audio only · M4A", if (a > 0) Format.size(a) else null).joinToString(" · "), "ba[ext=m4a]/ba/b", if (a > 0) a else -1, audio = "m4a")
            out += Quality("Audio only · MP3", "ba/b", -1, audio = "mp3")
        }
        val preferred = out.indexOfFirst { it.height in 1..1080 }
        return VideoDetails(
            title = info.str("title") ?: info.str("fulltitle") ?: "video",
            durationSeconds = duration.toLong(),
            site = info.str("extractor_key") ?: Links.videoSite(url),
            qualities = out,
            defaultIndex = if (preferred >= 0) preferred else 0,
        )
    }

    fun friendlyError(raw: String): String {
        val msg = raw.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim() ?: raw.trim()
        return when {
            msg.contains("Sign in to confirm", true) || msg.contains("not a bot", true) ->
                "YouTube wants you to sign in first. Open YouTube in the Browser tab, sign in, then try again from there."
            msg.contains("Private video", true) -> "This video is private."
            msg.contains("age", true) && msg.contains("confirm", true) -> "This video is age-restricted. Sign in in the Browser tab, then try again from there."
            msg.contains("Unsupported URL", true) -> "No video was found at this link."
            msg.contains("Requested format is not available", true) ->
                "The site didn't send any video formats this time, usually because the connection dropped. Tap Check to try again."
            msg.contains("Unable to download webpage", true) || msg.contains("Failed to resolve", true) -> "Couldn't reach the site. Check your connection."
            msg.contains("HTTP Error 429", true) -> "The site says too many requests. Try again later."
            msg.isEmpty() -> "Couldn't read this video."
            else -> msg.take(300)
        }
    }

    /** Netscape cookie file from the in-app browser's cookies, so signed-in videos work. */
    fun cookieFile(ctx: Context, url: String, cookieHeader: String?): File? {
        if (cookieHeader.isNullOrBlank()) return null
        val host = Links.httpUri(url)?.host ?: return null
        val domain = "." + host.split('.').takeLast(2).joinToString(".")
        val f = File(ctx.cacheDir, "cookies-${host.hashCode()}.txt")
        val lines = cookieHeader.split(';').mapNotNull {
            val kv = it.trim()
            val i = kv.indexOf('=')
            if (i <= 0) null else "$domain\tTRUE\t/\tTRUE\t0\t${kv.substring(0, i)}\t${kv.substring(i + 1)}"
        }
        f.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
        return f
    }
}

/**
 * Downloads a video with yt-dlp into Download/FastDownloader/Videos (or Music for audio only).
 * Unfinished parts stay in the app's cache folder, so pausing and resuming continues them.
 */
class VideoTask(
    private val ctx: Context,
    private val item: Item,
    private val container: String,
    private val throttle: Throttle,
    private val onEnd: (Outcome) -> Unit,
) : Task {
    @Volatile private var stopped = false
    @Volatile var rate = 0L
        private set
    @Volatile var stageNote: String? = null
        private set

    private val workDir get() = File(ctx.cacheDir, "video/${item.id}")

    override fun start() {
        thread(name = "video-${item.id}") { onEnd(runCatching { run() }.getOrElse { failure(it) }) }
    }

    override fun stop() {
        stopped = true
        runCatching { YoutubeDL.getInstance().destroyProcessById(item.id) }
    }

    fun discardParts() = workDir.deleteRecursively()

    private fun failure(e: Throwable): Outcome =
        if (stopped) Outcome.Stopped else Outcome.Failed(Ytdlp.friendlyError(e.message ?: ""), network = e.message?.contains("Unable to download", true) == true)

    private fun run(): Outcome {
        stageNote = "Starting…"
        Ytdlp.ensure(ctx)
        val dest = File(item.dir).apply { mkdirs() }
        workDir.mkdirs()
        val req = YoutubeDLRequest(item.url)
        val cookies = Ytdlp.cookieFile(ctx, item.url, item.headers["Cookie"])
        Ytdlp.base(req, item.headers, cookies)
        req.addOption("-f", item.format ?: "bv*+ba/b")
        req.addOption("-P", dest.absolutePath)
        req.addOption("-P", "temp:${workDir.absolutePath}")
        req.addOption("-o", "%(title).150B.%(ext)s")
        req.addOption("--no-mtime")
        req.addOption("--newline")
        req.addOption("--no-overwrites")
        // The finished file's path goes to a file: plain --print would also silence the progress lines.
        val pathFile = File(workDir, "path.txt").apply { delete() }
        req.addCommands(listOf("--print-to-file", "after_move:filepath", pathFile.absolutePath))
        when (item.audio) {
            "mp3" -> req.addOption("-x").addOption("--audio-format", "mp3").addOption("--audio-quality", "0")
            "m4a" -> req.addOption("-x").addOption("--audio-format", "m4a")
            else -> req.addOption("--merge-output-format", container)
        }
        if (throttle.bytesPerSecond > 0) req.addOption("-r", "${throttle.bytesPerSecond}")

        val res = YoutubeDL.getInstance().execute(req, item.id) { progress, _, line ->
            parseProgress(progress, line)
        }
        if (stopped) return Outcome.Stopped
        val path = (pathFile.takeIf { it.exists() }?.readLines() ?: res.out.lines()).map { it.trim() }.lastOrNull { it.startsWith("/") }
        if (path != null) {
            val f = File(path)
            synchronized(item) {
                item.fileName = f.name
                item.dir = f.parent ?: item.dir
                item.total = f.length()
                item.received = item.total
            }
        }
        discardParts()
        return Outcome.Done
    }

    private val SIZE = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")
    private val SPEED = Regex("""at\s+([\d.]+)\s*([KMGT]?i?B)/s""")

    // Video and audio usually arrive as two streams, one after the other: progress adds them up.
    private var doneStreams = 0L
    private var streamTotal = 0L
    private var lastFraction = 0f

    private fun parseProgress(progress: Float, line: String) {
        when {
            line.startsWith("[Merger]") || line.startsWith("[VideoConvertor]") -> stageNote = "Joining video and audio…"
            line.startsWith("[ExtractAudio]") -> stageNote = "Converting audio…"
            line.startsWith("[download] Destination") -> stageNote = null
        }
        if (!line.startsWith("[download]")) return
        SPEED.find(line)?.let { rate = bytes(it.groupValues[1], it.groupValues[2]) }
        val fraction = if (progress >= 0) (progress / 100f).coerceIn(0f, 1f) else return
        val size = SIZE.find(line)?.let { bytes(it.groupValues[1], it.groupValues[2]) } ?: 0L
        synchronized(item) {
            // A new stream started: count the finished one in full.
            if (fraction + 0.05f < lastFraction || (size > 0 && streamTotal > 0 && size != streamTotal && fraction < 0.05f)) {
                doneStreams += streamTotal
            }
            if (size > 0) streamTotal = size
            lastFraction = fraction
            stageNote = null
            val received = doneStreams + (streamTotal * fraction).toLong()
            item.received = received
            item.total = maxOf(item.total, doneStreams + streamTotal)
            item.fraction = if (item.total > 0) (received.toFloat() / item.total).coerceIn(0f, 1f) else fraction
        }
    }

    private fun bytes(n: String, unit: String): Long {
        val v = n.toDoubleOrNull() ?: return 0
        val mult = when (unit.firstOrNull()) { 'K' -> 1024.0; 'M' -> 1024.0 * 1024; 'G' -> 1024.0 * 1024 * 1024; 'T' -> 1024.0 * 1024 * 1024 * 1024; else -> 1.0 }
        return (v * mult).toLong()
    }
}
