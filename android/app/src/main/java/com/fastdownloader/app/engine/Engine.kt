package com.fastdownloader.app.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Runs the download queue: at most [Settings.maxConcurrent] at once, the rest wait in order.
 * Saves progress every couple of seconds, so a download continues after the app or phone restarts.
 */
object Engine {
    private lateinit var app: Context
    private lateinit var store: Store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val items = mutableListOf<Item>() // guarded by `this`
    private val tasks = HashMap<String, Task>()
    private val notes = HashMap<String, String>()
    private val meters = HashMap<String, Meter>()
    private val userPaused = HashSet<String>()
    val throttle = Throttle()

    private val _items = MutableStateFlow<List<ItemView>>(emptyList())
    val itemsFlow: StateFlow<List<ItemView>> = _items.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    val settingsFlow: StateFlow<Settings> = _settings.asStateFlow()
    private val _finished = MutableSharedFlow<ItemView>(extraBufferCapacity = 16)
    /** Each download that completes or fails, for notifications. */
    val finished = _finished.asSharedFlow()

    val settings get() = _settings.value
    @Volatile private var dirty = false
    @Volatile var onVpn = false
        private set
    @Volatile var metered = false
        private set
    @Volatile var online = true
        private set
    /** Set by DownloadService: called when a download starts so the service keeps the app running. */
    @Volatile var onActive: (() -> Unit)? = null

    private class Meter(var bytes: Long, var at: Long, var speed: Double = 0.0)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        store = Store(app.filesDir)
        _settings.value = store.loadSettings()
        applySettings(settings)
        synchronized(this) {
            items += store.loadItems()
            // Anything that was running when the app last stopped waits for the user to continue it.
            for (it in items) if (it.status == Status.DOWNLOADING || it.status == Status.QUEUED) it.status = Status.PAUSED
        }
        watchNetwork()
        scope.launch {
            var n = 0
            while (true) {
                publish()
                if (++n % 4 == 0 && dirty) save()
                delay(500)
            }
        }
    }

    // ---------- folders ----------

    val rootDir: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FastDownloader")

    fun dirFor(category: String): File = if (settings.categorize) File(rootDir, category) else rootDir

    /** Makes Download/FastDownloader and its category folders. */
    fun createFolders() {
        rootDir.mkdirs()
        if (settings.categorize) Categories.NAMES.forEach { File(rootDir, it).mkdirs() }
    }

    // ---------- adding ----------

    private fun newId() = UUID.randomUUID().toString().substring(0, 12)

    private fun takenNames(dir: String) = synchronized(this) { items.filter { it.dir == dir && it.status != Status.COMPLETED }.map { it.fileName }.toSet() }

    fun addHttp(url: String, probe: Probe, fileName: String, headers: Map<String, String>): String {
        val name = Links.safeFileName(fileName)
        val category = Categories.forName(name)
        val dir = dirFor(category)
        val unique = Files.uniqueFile(dir, name, taken = takenNames(dir.path)).name
        val item = Item(
            id = newId(), url = probe.finalUrl.ifEmpty { url }, kind = Kind.HTTP, fileName = unique, dir = dir.path,
            category = category, total = probe.size, resumable = probe.resumable, etag = probe.etag,
            lastModified = probe.lastModified, headers = headers,
        )
        return add(item)
    }

    fun addTorrent(source: String, name: String?): String {
        val dir = dirFor(Categories.TORRENT)
        val item = Item(
            id = newId(), url = source, kind = Kind.TORRENT,
            fileName = name ?: Torrents.magnetName(source) ?: "Torrent", dir = dir.path, category = Categories.TORRENT,
        )
        return add(item)
    }

    /** A .torrent file opened from another app: copied in, since content:// links don't last. */
    fun saveTorrentFile(bytes: ByteArray): File {
        val f = File(app.filesDir, "torrents/${newId()}.torrent")
        f.parentFile!!.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    fun addVideo(url: String, title: String, quality: Quality, headers: Map<String, String>): String {
        val category = if (quality.audio != null) Categories.MUSIC else Categories.VIDEO
        val ext = quality.audio ?: settings.videoContainer
        val item = Item(
            id = newId(), url = url, kind = Kind.VIDEO, fileName = "${Links.safeFileName(title)}.$ext",
            dir = dirFor(category).path, category = category, total = quality.size, headers = headers,
            format = quality.format, audio = quality.audio,
        )
        return add(item)
    }

    private fun add(item: Item): String {
        if (settings.stripTracking && item.kind != Kind.TORRENT) item.url = Links.cleanUrl(item.url)
        synchronized(this) { items.add(0, item) }
        dirty = true
        schedule()
        publish()
        return item.id
    }

    // ---------- actions ----------

    private fun find(id: String) = synchronized(this) { items.firstOrNull { it.id == id } }

    fun pause(id: String) {
        val task = synchronized(this) {
            val item = find(id) ?: return
            if (item.status == Status.QUEUED) item.status = Status.PAUSED
            if (tasks.containsKey(id)) userPaused += id
            tasks[id]
        }
        task?.stop() // the task reports back through finish(), which marks the item paused
        dirty = true
        publish()
    }

    fun resume(id: String) {
        synchronized(this) {
            val item = find(id) ?: return
            if (item.status == Status.PAUSED || item.status == Status.ERROR) {
                item.status = Status.QUEUED
                item.error = null
                item.retryOnNetwork = false
            }
        }
        dirty = true
        schedule()
        publish()
    }

    fun pauseAll() = synchronized(this) { items.map { it.id } }.forEach { pause(it) }

    fun resumeAll() = synchronized(this) { items.filter { it.status == Status.PAUSED || it.status == Status.ERROR }.map { it.id } }.forEach { resume(it) }

    /** Removes from the list; with [deleteFile], also deletes the file (or the unfinished parts). */
    fun remove(id: String, deleteFile: Boolean) {
        val item = find(id) ?: return
        val task = synchronized(this) { tasks.remove(id) }
        task?.stop()
        scope.launch(Dispatchers.IO) {
            if (task != null) delay(600) // let the task close its files first
            if (item.kind == Kind.HTTP) File(item.dir, item.fileName + HttpTask.PART).delete()
            if (item.kind == Kind.VIDEO) File(app.cacheDir, "video/${item.id}").deleteRecursively()
            if (deleteFile && (item.status == Status.COMPLETED || item.kind == Kind.TORRENT)) {
                val f = item.file
                if (f.isDirectory) f.deleteRecursively() else f.delete()
                scan(f)
            }
        }
        synchronized(this) { items.remove(item); notes.remove(id); meters.remove(id) }
        dirty = true
        schedule()
        publish()
    }

    fun clearCompleted() {
        synchronized(this) { items.removeAll { it.status == Status.COMPLETED } }
        dirty = true
        publish()
    }

    /** Forgets every finished download (the files stay). */
    fun clearHistory() = clearCompleted()

    fun updateSettings(change: (Settings) -> Settings) {
        val next = change(settings)
        _settings.value = next
        store.saveSettings(next)
        applySettings(next)
        enforceNetworkRules()
        schedule()
    }

    private fun applySettings(s: Settings) {
        Net.configure(s)
        throttle.bytesPerSecond = s.speedLimitKBps * 1024L
        Torrents.apply(s)
    }

    // ---------- queue ----------

    private fun blockedReason(item: Item): String? = when {
        !online -> "Waiting for internet"
        settings.wifiOnly && metered -> "Waiting for Wi-Fi"
        item.kind == Kind.TORRENT && settings.torrentVpnOnly && !onVpn -> "Waiting for VPN (torrents only run over a VPN)"
        else -> null
    }

    @Synchronized
    private fun schedule() {
        var running = tasks.size
        for (item in items.reversed()) { // oldest first
            if (item.status != Status.QUEUED) continue
            val reason = blockedReason(item)
            if (reason != null) { notes[item.id] = reason; continue }
            if (running >= settings.maxConcurrent.coerceIn(1, 10)) { notes[item.id] = "Waiting in queue"; continue }
            notes.remove(item.id)
            start(item)
            running++
        }
        if (tasks.isEmpty()) {
            // Nothing needs the torrent session: close it so it doesn't keep the network busy.
            if (items.none { it.kind == Kind.TORRENT && it.status == Status.QUEUED } && Torrents.running) Torrents.shutdown()
        }
    }

    private fun start(item: Item) {
        item.status = Status.DOWNLOADING
        item.error = null
        meters[item.id] = Meter(item.received, System.currentTimeMillis())
        if (!File(item.dir).exists()) File(item.dir).mkdirs()
        val s = settings
        val task: Task = when (item.kind) {
            Kind.HTTP -> HttpTask(item, s.segments.coerceIn(1, 32), throttle) { finish(item, it) }
            Kind.TORRENT -> TorrentTask(item, s) { finish(item, it) }
            Kind.VIDEO -> VideoTask(app, item, s.videoContainer, throttle) { finish(item, it) }
        }
        tasks[item.id] = task
        task.start()
        onActive?.invoke()
    }

    private fun finish(item: Item, outcome: Outcome) {
        synchronized(this) {
            tasks.remove(item.id)
            meters.remove(item.id)
            val byUser = userPaused.remove(item.id)
            if (item !in items) return@synchronized
            when (outcome) {
                Outcome.Done -> {
                    item.status = Status.COMPLETED
                    item.finishedAt = System.currentTimeMillis()
                    if (item.total <= 0) item.total = item.received
                    item.segments.clear()
                    item.headers = emptyMap() // sign-in cookies aren't needed any more
                }
                Outcome.Stopped -> if (item.status == Status.DOWNLOADING) {
                    // Stopped by a network rule (not the user): continue once allowed again.
                    item.status = if (!byUser && blockedReason(item) != null) Status.QUEUED else Status.PAUSED
                }
                is Outcome.Failed -> {
                    item.status = Status.ERROR
                    item.error = outcome.message
                    item.retryOnNetwork = outcome.network
                }
            }
        }
        if (outcome == Outcome.Done) {
            scan(item.file)
            if (!settings.keepHistory) scope.launch { delay(4000); synchronized(this@Engine) { items.remove(item) }; dirty = true }
        }
        if (outcome == Outcome.Done || outcome is Outcome.Failed) synchronized(this) { view(item) }?.let { _finished.tryEmit(it) }
        dirty = true
        save()
        schedule()
        publish()
    }

    /** Tells the gallery and file apps about a new file (Android 11+ notices by itself). */
    private fun scan(f: File) {
        val paths = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.map { it.path }.toList() else listOf(f.path)
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(app, paths.take(500).toTypedArray(), null, null)
    }

    // ---------- network ----------

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        fun update(caps: NetworkCapabilities?) {
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
            onVpn = vpnActive(cm)
        }
        update(cm.getNetworkCapabilities(cm.activeNetwork))
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val wasOnline = online
                update(caps)
                if (!wasOnline && online) retryNetworkFailures()
                enforceNetworkRules()
                schedule()
            }

            override fun onLost(network: Network) {
                update(cm.getNetworkCapabilities(cm.activeNetwork))
                onVpn = vpnActive(cm)
                enforceNetworkRules()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun vpnActive(cm: ConnectivityManager): Boolean =
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }

    /** Kill switch and Wi-Fi only: stop what isn't allowed on the current network right away. */
    private fun enforceNetworkRules() {
        val stop = synchronized(this) {
            items.filter { it.status == Status.DOWNLOADING && blockedReason(it) != null && online }.mapNotNull { tasks[it.id] }
        }
        stop.forEach { it.stop() }
    }

    private fun retryNetworkFailures() {
        synchronized(this) {
            for (it in items) if (it.status == Status.ERROR && it.retryOnNetwork) {
                it.status = Status.QUEUED
                it.error = null
                it.retryOnNetwork = false
            }
        }
        dirty = true
    }

    // ---------- state for the screens ----------

    private fun view(item: Item): ItemView? = synchronized(item) {
        val meter = meters[item.id]
        val task = tasks[item.id]
        val speed = when (task) {
            is TorrentTask -> task.rate
            is VideoTask -> task.rate
            else -> meter?.speed?.toLong() ?: 0
        }
        val left = if (item.total > 0) item.total - item.received else -1
        val fraction = when {
            item.status == Status.COMPLETED -> 1f
            item.total > 0 -> (item.received.toDouble() / item.total).toFloat().coerceIn(0f, 1f)
            else -> item.fraction
        }
        val note = when (task) {
            is TorrentTask -> task.stateNote ?: if (item.status == Status.DOWNLOADING) "${task.peers} peers" else null
            is VideoTask -> task.stageNote
            else -> null
        } ?: notes[item.id]
        ItemView(
            id = item.id, kind = item.kind, url = item.url, fileName = item.fileName, category = item.category,
            status = item.status, total = item.total, received = item.received, fraction = fraction, speed = speed,
            etaSeconds = if (speed > 0 && left >= 0) left / speed else -1, error = item.error, note = note,
            path = item.file.path, resumable = item.resumable || item.kind != Kind.HTTP, finishedAt = item.finishedAt,
        )
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val list = synchronized(this) {
            for ((id, m) in meters) {
                val item = items.firstOrNull { it.id == id } ?: continue
                val bytes = synchronized(item) { item.received }
                val dt = now - m.at
                if (dt >= 400) {
                    val inst = (bytes - m.bytes) * 1000.0 / dt
                    m.speed = if (m.speed == 0.0) inst else m.speed * 0.6 + inst * 0.4
                    m.bytes = bytes
                    m.at = now
                }
            }
            if (tasks.isNotEmpty()) dirty = true
            items.mapNotNull { view(it) }
        }
        _items.value = list
    }

    private fun save() {
        val copy = synchronized(this) { items.toList() }
        runCatching { store.saveItems(copy) }
        dirty = false
    }

    val activeCount get() = synchronized(this) { tasks.size }
}
