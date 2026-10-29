package com.fastdownloader.app.engine

import android.net.Uri

/**
 * Category = sub-folder of Download/FastDownloader and a filter on the main screen.
 * Same names and file types as the desktop app (src/engine/categories.js).
 */
object Categories {
    const val ZIP = "ZIP"
    const val DOC = "DOC"
    const val PROGRAMS = "Programs"
    const val VIDEO = "Videos"
    const val MUSIC = "Music"
    const val IMAGES = "Images"
    const val TORRENT = "Torrent"
    const val OTHER = "Other"

    val NAMES = listOf(ZIP, DOC, PROGRAMS, VIDEO, MUSIC, TORRENT, IMAGES, OTHER)

    private val TYPES = mapOf(
        ZIP to listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "cab", "zst"),
        DOC to listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "csv", "epub"),
        PROGRAMS to listOf("exe", "msi", "msix", "appx", "bat", "apk", "xapk", "apks", "dmg", "deb", "rpm", "jar"),
        VIDEO to listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "ts"),
        MUSIC to listOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus"),
        IMAGES to listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "tiff", "ico", "heic"),
        TORRENT to listOf("torrent"),
    )
    private val BY_EXT = TYPES.flatMap { (cat, exts) -> exts.map { it to cat } }.toMap()

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun forName(fileName: String): String = BY_EXT[extension(fileName)] ?: OTHER

    /** True if a link looks like a file to download (or a magnet link). */
    fun looksLikeFileUrl(text: String): Boolean {
        val t = text.trim()
        if (Links.isMagnet(t)) return true
        val u = Links.httpUri(t) ?: return false
        val ext = extension(u.lastPathSegment ?: "")
        return ext in BY_EXT || ext in setOf("bin", "dat", "img")
    }
}

object Links {
    private val URL_IN_TEXT = Regex("""(magnet:\?[^\s"'<>]+|https?://[^\s"'<>]+)""", RegexOption.IGNORE_CASE)

    fun isMagnet(text: String) = text.trim().startsWith("magnet:?", ignoreCase = true) &&
        text.contains("xt=urn:bt", ignoreCase = true)

    fun httpUri(text: String): Uri? = runCatching {
        val u = Uri.parse(text.trim())
        if ((u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty()) u else null
    }.getOrNull()

    /** The first link in shared text ("Check this out https://…" → the link). */
    fun firstLink(text: String?): String? = text?.let { URL_IN_TEXT.find(it)?.value?.trimEnd('.', ',', ')', ']') }

    // Sites whose pages are videos rather than files (desktop: src/engine/ytdlp.js).
    private val SITES = listOf(
        Regex("""(^|\.)(youtube\.com|youtube-nocookie\.com|youtu\.be)$""") to "YouTube",
        Regex("""(^|\.)vimeo\.com$""") to "Vimeo",
        Regex("""(^|\.)dailymotion\.com$|(^|\.)dai\.ly$""") to "Dailymotion",
        Regex("""(^|\.)(twitter\.com|x\.com)$""") to "X (Twitter)",
        Regex("""(^|\.)instagram\.com$""") to "Instagram",
        Regex("""(^|\.)tiktok\.com$""") to "TikTok",
        Regex("""(^|\.)(facebook\.com|fb\.watch)$""") to "Facebook",
        Regex("""(^|\.)reddit\.com$""") to "Reddit",
        Regex("""(^|\.)twitch\.tv$""") to "Twitch",
        Regex("""(^|\.)soundcloud\.com$""") to "SoundCloud",
        Regex("""(^|\.)bilibili\.com$""") to "Bilibili",
        Regex("""(^|\.)ted\.com$""") to "TED",
    )

    fun videoSite(url: String): String? {
        val host = httpUri(url)?.host?.lowercase() ?: return null
        return SITES.firstOrNull { it.first.containsMatchIn(host) }?.second
    }

    /** A link to an actual video (not a site's home page). */
    fun isVideoPageUrl(url: String): Boolean {
        val site = videoSite(url) ?: return false
        val u = httpUri(url) ?: return false
        val path = u.path ?: ""
        if (site == "YouTube") {
            return if (u.host!!.endsWith("youtu.be")) path.length > 1
            else Regex("""^/(watch|shorts/|live/|embed/)""").containsMatchIn(path)
        }
        return path.split('/').any { it.isNotEmpty() }
    }

    // Tracking tags removed from links (desktop: src/engine/privacy.js).
    private val TRACKING = Regex(
        "^(utm_\\w+|fbclid|gclid|gclsrc|dclid|gbraid|wbraid|msclkid|yclid|twclid|ttclid|li_fat_id|mc_cid|mc_eid|igshid|igsh|" +
            "_hsenc|_hsmi|__hssc|__hstc|__hsfp|hsCtaTracking|mkt_tok|oly_anon_id|oly_enc_id|vero_id|wickedid|rb_clickid|s_cid|" +
            "ref_src|ref_url|spm|scm)$",
        RegexOption.IGNORE_CASE,
    )
    private val SITE_TRACKING_HOST = Regex("""(^|\.)(youtube\.com|youtu\.be|spotify\.com)$""", RegexOption.IGNORE_CASE)
    private val SITE_TRACKING = Regex("^(si|pp|feature)$", RegexOption.IGNORE_CASE)
    private val SIGNED = Regex(
        "^(x-amz-signature|x-amz-credential|signature|sig|expires|token|policy|key-pair-id|x-goog-signature|se|sp|sv)$",
        RegexOption.IGNORE_CASE,
    )

    /** Removes tracking tags (utm_source, fbclid, …). Signed links are left alone, since changing them breaks them. */
    fun cleanUrl(url: String): String {
        val u = httpUri(url) ?: return url
        val names = u.queryParameterNames
        if (names.isEmpty() || names.any { SIGNED.matches(it) }) return url
        val siteRule = SITE_TRACKING_HOST.containsMatchIn(u.host ?: "")
        val drop = names.filter { TRACKING.matches(it) || (siteRule && SITE_TRACKING.matches(it)) }.toSet()
        if (drop.isEmpty()) return url
        val b = u.buildUpon().clearQuery()
        // Keep the remaining parameters in their original order and encoding.
        for (pair in (u.encodedQuery ?: "").split('&')) {
            val name = Uri.decode(pair.substringBefore('='))
            if (pair.isNotEmpty() && name !in drop) {
                b.encodedQuery(listOfNotNull(b.build().encodedQuery, pair).joinToString("&"))
            }
        }
        return b.build().toString()
    }

    /** Safe file name: no path separators or characters Android's storage rejects. */
    fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trim('.')
        return cleaned.take(180).ifEmpty { "download" }
    }
}
