package com.fastdownloader.app.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The filter lists uBlock Origin uses by default. Copies of the bundled ones ship inside the app,
 * so blocking works from the first launch; all of them are refreshed from their publishers every
 * few days (through the proxy, if one is set).
 */
object Filters {
    data class FilterList(val id: String, val name: String, val url: String, val bundled: Boolean)

    val LISTS = listOf(
        FilterList("ublock-filters", "uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt", true),
        FilterList("ublock-privacy", "uBlock filters: Privacy", "https://ublockorigin.github.io/uAssets/filters/privacy.txt", true),
        // Exceptions that keep sites working (consent dialogs, sign-ins, video players), as uBlock Origin has by default.
        FilterList("ublock-unbreak", "uBlock filters: Unbreak", "https://ublockorigin.github.io/uAssets/filters/unbreak.txt", true),
        FilterList("ublock-quick-fixes", "uBlock filters: Quick fixes", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt", true),
        FilterList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", true),
        FilterList("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", true),
        FilterList(
            "peter-lowe", "Peter Lowe's ad and tracking server list",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", false,
        ),
    )

    data class Status(val loading: Boolean = false, val networkRules: Int = 0, val cosmeticRules: Int = 0, val updatedAt: Long = 0, val message: String? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile var engine: AdBlock? = null
        private set
    private const val EVERY = 4 * 24 * 3600_000L

    private fun dir(ctx: Context) = File(ctx.filesDir, "filters").apply { mkdirs() }

    /** Reads the lists (downloaded copy if there is one, else the bundled one) and builds the blocker. */
    @Synchronized
    fun load(ctx: Context) {
        _status.value = _status.value.copy(loading = true)
        val texts = LISTS.mapNotNull { list ->
            val f = File(dir(ctx), "${list.id}.txt")
            when {
                f.exists() -> f.readText()
                list.bundled -> runCatching { ctx.assets.open("filters/${list.id}.txt").bufferedReader().readText() }.getOrNull()
                else -> null
            }
        } + ownList(ctx)
        val ab = AdBlock.parse(texts)
        engine = ab
        val updated = LISTS.mapNotNull { File(dir(ctx), "${it.id}.txt").takeIf { f -> f.exists() }?.lastModified() }.maxOrNull() ?: 0
        _status.value = Status(false, ab.networkRules, ab.cosmeticRules, updated)
    }

    /** FastDownloader's own ad and tracker domains (shared with the browser extension). */
    private fun ownList(ctx: Context): String = runCatching {
        val rules = Json.parseToJsonElement(ctx.assets.open("trackers.json").bufferedReader().readText()).jsonArray
        rules.flatMap { r ->
            r.jsonObject["condition"]?.jsonObject?.get("requestDomains")?.jsonArray?.map { "||${it.jsonPrimitive.content}^" } ?: emptyList()
        }.joinToString("\n")
    }.getOrDefault("")

    /** Downloads fresh copies of the lists when they're older than a few days (or now, with [force]). */
    fun updateIfDue(ctx: Context, force: Boolean = false): String {
        val d = dir(ctx)
        val newest = LISTS.mapNotNull { File(d, "${it.id}.txt").takeIf { f -> f.exists() }?.lastModified() }.maxOrNull() ?: 0
        if (!force && System.currentTimeMillis() - newest < EVERY && LISTS.all { File(d, "${it.id}.txt").exists() }) {
            if (engine == null) load(ctx)
            return "Up to date"
        }
        _status.value = _status.value.copy(loading = true, message = "Updating filter lists…")
        var ok = 0
        for (list in LISTS) {
            runCatching {
                val text = Net.client().newCall(Net.request(list.url, emptyMap())).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body.string()
                }
                // Don't replace a good list with an error page.
                if (text.length < 1000 || text.trimStart().startsWith("<")) error("not a filter list")
                val tmp = File(d, "${list.id}.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(d, "${list.id}.txt")) || run { File(d, "${list.id}.txt").delete(); tmp.renameTo(File(d, "${list.id}.txt")) }
                ok++
            }
        }
        load(ctx)
        val msg = if (ok == LISTS.size) "All ${LISTS.size} lists updated" else "$ok of ${LISTS.size} lists updated (the rest keep their last copy)"
        _status.value = _status.value.copy(message = msg)
        return msg
    }
}
