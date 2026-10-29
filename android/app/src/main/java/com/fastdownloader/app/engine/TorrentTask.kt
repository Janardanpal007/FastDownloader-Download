package com.fastdownloader.app.engine

import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import kotlin.concurrent.thread

/** The one BitTorrent session for the whole app, started on first use. */
object Torrents {
    // Public trackers added to magnet links only (desktop: src/engine/manager.js).
    val EXTRA_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://opentracker.io:6969/announce",
        "https://tracker.tamersunion.org:443/announce",
    )

    private var session: SessionManager? = null

    @Synchronized
    fun session(s: Settings): SessionManager {
        session?.let { return it }
        val sm = SessionManager(false)
        sm.start(SessionParams(pack(s)))
        session = sm
        return sm
    }

    @Synchronized
    fun apply(s: Settings) {
        session?.applySettings(pack(s))
    }

    @Synchronized
    fun shutdown() {
        val sm = session ?: return
        session = null
        thread { runCatching { sm.stop() } }
    }

    val running get() = session != null

    private fun pack(s: Settings): SettingsPack {
        val sp = SettingsPack()
        sp.connectionsLimit(s.torrentConnections.coerceIn(20, 1000))
        sp.activeDownloads(8)
        sp.activeLimit(16)
        sp.downloadRateLimit(if (s.speedLimitKBps > 0) s.speedLimitKBps * 1024 else 0)
        sp.setInteger(settings_pack.int_types.connection_speed.swigValue(), 60)
        sp.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        if (s.proxyOn) {
            val type = when {
                s.proxyType == "http" -> settings_pack.proxy_type_t.http
                s.proxyUser.isNotEmpty() -> settings_pack.proxy_type_t.socks5_pw
                else -> settings_pack.proxy_type_t.socks5
            }
            sp.setInteger(settings_pack.int_types.proxy_type.swigValue(), type.swigValue())
            sp.setString(settings_pack.string_types.proxy_hostname.swigValue(), s.proxyHost)
            sp.setInteger(settings_pack.int_types.proxy_port.swigValue(), s.proxyPort)
            sp.setString(settings_pack.string_types.proxy_username.swigValue(), s.proxyUser)
            sp.setString(settings_pack.string_types.proxy_password.swigValue(), s.proxyPass)
            sp.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(), true)
            sp.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(), true)
            sp.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(), true)
        } else {
            sp.setInteger(settings_pack.int_types.proxy_type.swigValue(), settings_pack.proxy_type_t.none.swigValue())
        }
        return sp
    }

    /** "dn=" name of a magnet link, for showing before the torrent's details arrive. */
    fun magnetName(uri: String): String? = uri.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
        ?.let { runCatching { java.net.URLDecoder.decode(it.substring(3), "UTF-8") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    fun withExtraTrackers(magnet: String): String {
        val have = magnet.lowercase()
        return EXTRA_TRACKERS.filter { android.net.Uri.encode(it).lowercase() !in have && it.lowercase() !in have }
            .fold(magnet) { m, t -> "$m&tr=${android.net.Uri.encode(t)}" }
    }
}

/**
 * One torrent (magnet link or .torrent file), saved into Download/FastDownloader/Torrent.
 * Pausing removes it from the session but keeps the files; resuming adds it again and
 * libtorrent checks what is already on disk, so it continues after restarts too.
 */
class TorrentTask(
    private val item: Item,
    private val settings: Settings,
    private val onEnd: (Outcome) -> Unit,
) : Task {
    @Volatile private var stopped = false
    @Volatile var peers = 0
        private set
    @Volatile var rate = 0L
        private set
    @Volatile var stateNote: String? = null
        private set

    override fun start() {
        thread(name = "torrent-${item.id}") {
            onEnd(runCatching { run() }.getOrElse { Outcome.Failed(it.message ?: "Torrent failed.") })
        }
    }

    override fun stop() {
        stopped = true
    }

    private fun run(): Outcome {
        val sm = Torrents.session(settings)
        val dir = File(item.dir).apply { mkdirs() }
        val hash: Sha1Hash
        if (Links.isMagnet(item.url)) {
            val magnet = if (settings.torrentExtraTrackers) Torrents.withExtraTrackers(item.url) else item.url
            hash = AddTorrentParams.parseMagnetUri(magnet).infoHashes.best
            sm.download(magnet, dir, torrent_flags_t())
        } else {
            val ti = TorrentInfo(File(item.url))
            hash = ti.infoHash()
            synchronized(item) { if (item.fileName.isBlank()) item.fileName = ti.name() }
            sm.download(ti, dir)
        }

        var handle: TorrentHandle? = null
        var named = false
        while (!stopped) {
            val th = handle ?: sm.find(hash)?.takeIf { it.isValid }
            handle = th
            if (th != null) {
                val st = th.status()
                peers = st.numPeers()
                rate = st.downloadPayloadRate().toLong()
                stateNote = when (st.state()) {
                    TorrentStatus.State.DOWNLOADING_METADATA -> "Finding peers…"
                    TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "Checking files…"
                    else -> null
                }
                if (st.hasMetadata()) {
                    if (!named) {
                        // The status leaves the name empty unless asked; the torrent's own info has it.
                        val name = runCatching { th.torrentFile()?.name() }.getOrNull()
                        if (!name.isNullOrBlank()) synchronized(item) { item.fileName = Links.safeFileName(name) }
                        named = true
                    }
                    synchronized(item) {
                        item.total = st.totalWanted()
                        item.received = st.totalWantedDone()
                    }
                }
                if (st.errorCode().isError) {
                    sm.remove(th)
                    return Outcome.Failed(st.errorCode().message ?: "Torrent failed.")
                }
                if (st.hasMetadata() && (st.isFinished || st.isSeeding)) {
                    sm.remove(th)
                    return Outcome.Done
                }
            }
            Thread.sleep(1000)
        }
        handle?.let { sm.remove(it) }
        return Outcome.Stopped
    }
}
