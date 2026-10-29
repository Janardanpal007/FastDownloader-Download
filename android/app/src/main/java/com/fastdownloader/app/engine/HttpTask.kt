package com.fastdownloader.app.engine

import okhttp3.Call
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import kotlin.concurrent.thread

/** How a task ended. */
sealed class Outcome {
    data object Done : Outcome()
    data object Stopped : Outcome()
    data class Failed(val message: String, val network: Boolean = false) : Outcome()
}

interface Task {
    fun start()
    fun stop()
}

/**
 * Downloads one file over HTTP in parallel parts (Range requests), all written into one
 * "<name>.fdpart" file at their own offsets. When a connection finishes early it takes half of
 * the largest part still left, so all connections stay busy until the end. Progress is kept in
 * [Item.segments], so a paused or interrupted download continues where it stopped, even after
 * the app or phone restarts.
 */
class HttpTask(
    private val item: Item,
    private val connections: Int,
    private val throttle: Throttle,
    private val onEnd: (Outcome) -> Unit,
) : Task {
    @Volatile private var stopped = false
    private val calls = Collections.synchronizedSet(mutableSetOf<Call>())
    private val busy = Collections.synchronizedSet(mutableSetOf<Seg>())

    override fun start() {
        thread(name = "http-${item.id}") {
            val outcome = try {
                run()
            } catch (e: Throwable) {
                if (stopped) Outcome.Stopped else failure(e)
            }
            onEnd(outcome)
        }
    }

    override fun stop() {
        stopped = true
        synchronized(calls) { calls.forEach { it.cancel() } }
    }

    private fun run(): Outcome {
        val part = File(item.dir, item.fileName + PART)
        part.parentFile?.mkdirs()

        if (item.resumable && item.total > 0) {
            // Continuing: make sure the file on the server is still the same one.
            if (item.received > 0) {
                val p = runCatching { Net.probe(item.url, item.headers) }.getOrNull()
                val changed = p != null && (p.size != item.total ||
                    (item.etag != null && p.etag != null && p.etag != item.etag) ||
                    (item.lastModified != null && p.lastModified != null && p.lastModified != item.lastModified) ||
                    !p.resumable)
                if (changed || !part.exists()) reset()
            }
            synchronized(item) {
                if (item.segments.isEmpty()) item.segments.addAll(split(item.total, connections))
            }
            return segmented(part)
        }
        return single(part)
    }

    private fun reset() = synchronized(item) {
        item.segments.clear()
        item.received = 0
    }

    private fun segmented(part: File): Outcome {
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != item.total) raf.setLength(item.total)
            val channel = raf.channel
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val workers = (1..connections).map {
                thread(name = "seg-${item.id}-$it") {
                    try {
                        while (!stopped) {
                            val seg = nextSegment() ?: break
                            try {
                                fetch(seg, channel)
                            } finally {
                                busy.remove(seg)
                            }
                        }
                    } catch (e: Throwable) {
                        if (!stopped) {
                            errors += e
                            stop() // one part failed for good: stop the others and report it
                        }
                    }
                }
            }
            workers.forEach { it.join() }
            channel.force(false)
            val left = synchronized(item) { item.segments.sumOf { it.remaining } }
            return when {
                left == 0L -> finish(part)
                errors.isNotEmpty() -> failure(errors.first())
                else -> Outcome.Stopped
            }
        }
    }

    /** A part nobody is working on, or else half of the biggest one in progress. */
    private fun nextSegment(): Seg? = synchronized(item) {
        item.segments.firstOrNull { it.remaining > 0 && it !in busy }?.let { busy += it; return it }
        val biggest = item.segments.filter { it in busy }.maxByOrNull { it.remaining } ?: return null
        if (biggest.remaining < 2 * MIN_SPLIT) return null
        val mid = biggest.pos + biggest.remaining / 2
        val tail = Seg(mid, biggest.end)
        biggest.end = mid - 1
        item.segments += tail
        busy += tail
        tail
    }

    /** Downloads one part, retrying a few times if the connection drops. */
    private fun fetch(seg: Seg, channel: FileChannel) {
        var attempt = 0
        while (!stopped && seg.remaining > 0) {
            try {
                val from = synchronized(item) { seg.pos }
                val to = synchronized(item) { seg.end }
                val call = Net.client().newCall(Net.request(item.url, item.headers, "bytes=$from-$to"))
                calls += call
                try {
                    call.execute().use { r ->
                        if (r.code != 206) {
                            if (r.code == 200) throw FatalError("The server stopped accepting partial downloads. Remove this download and add it again.")
                            if (r.code in 400..499 && r.code != 408 && r.code != 429) throw FatalError(Net.httpError(r.code))
                            throw IOException(Net.httpError(r.code))
                        }
                        val input = r.body.byteStream()
                        val buf = ByteArray(BUF)
                        while (!stopped) {
                            val n = input.read(buf)
                            if (n < 0) break
                            var write: Int
                            var pos: Long
                            synchronized(item) {
                                write = minOf(n.toLong(), seg.remaining).toInt()
                                pos = seg.pos
                            }
                            if (write <= 0) break // another connection took over the rest of this part
                            var off = 0
                            while (off < write) off += channel.write(ByteBuffer.wrap(buf, off, write - off), pos + off)
                            synchronized(item) {
                                seg.done += write
                                item.received += write
                            }
                            attempt = 0
                            throttle.take(write)
                            if (write < n) break
                        }
                    }
                } finally {
                    calls -= call
                }
            } catch (e: FatalError) {
                throw e
            } catch (e: IOException) {
                if (stopped) return
                if (++attempt > RETRIES) throw e
                Thread.sleep(minOf(1000L shl (attempt - 1), 15_000L))
            }
        }
    }

    /** No resume support or unknown size: one connection, from the start. */
    private fun single(part: File): Outcome {
        reset()
        part.delete()
        val call = Net.client().newCall(Net.request(item.url, item.headers))
        calls += call
        call.execute().use { r ->
            if (!r.isSuccessful) return Outcome.Failed(Net.httpError(r.code))
            val length = r.body.contentLength()
            if (length > 0) synchronized(item) { item.total = length }
            part.outputStream().buffered(BUF).use { out ->
                val input = r.body.byteStream()
                val buf = ByteArray(BUF)
                while (!stopped) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    synchronized(item) { item.received += n }
                    throttle.take(n)
                }
            }
        }
        if (stopped) return Outcome.Stopped
        if (item.total > 0 && item.received != item.total) return Outcome.Failed("The connection closed before the whole file arrived.", network = true)
        synchronized(item) { item.total = item.received }
        return finish(part)
    }

    private fun finish(part: File): Outcome {
        val wanted = File(item.dir, item.fileName)
        val target = if (!wanted.exists()) wanted else Files.uniqueFile(File(item.dir), item.fileName, startAt = 2)
        if (!part.renameTo(target)) {
            // Android 11+ hides files other apps saved, so a name can be taken without us seeing it.
            val alt = Files.uniqueFile(File(item.dir), item.fileName, startAt = 2, taken = setOf(target.name))
            if (!part.renameTo(alt)) return Outcome.Failed("Couldn't save the file in ${item.dir}.")
            synchronized(item) { item.fileName = alt.name }
        } else {
            synchronized(item) { item.fileName = target.name }
        }
        return Outcome.Done
    }

    private fun failure(e: Throwable): Outcome = when (e) {
        is FatalError -> Outcome.Failed(e.message ?: "Download failed.")
        is UnknownHostException -> Outcome.Failed("No internet connection, or the site's address couldn't be found.", network = true)
        is SocketException, is InterruptedIOException -> Outcome.Failed("The connection was lost. It will continue when you're back online.", network = true)
        is IOException -> if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true)
            Outcome.Failed("Your phone is out of storage space.")
        else Outcome.Failed(e.message ?: "Download failed.", network = true)
        else -> Outcome.Failed(e.message ?: e.javaClass.simpleName)
    }

    private class FatalError(message: String) : IOException(message)

    companion object {
        const val PART = ".fdpart"
        private const val BUF = 64 * 1024
        private const val MIN_SPLIT = 1L shl 20 // 1 MB
        private const val RETRIES = 6

        /** Equal parts, but none smaller than 1 MB. */
        fun split(total: Long, connections: Int): List<Seg> {
            val n = maxOf(1, minOf(connections.toLong(), (total + MIN_SPLIT - 1) / MIN_SPLIT).toInt())
            val size = total / n
            return (0 until n).map { i ->
                val start = i * size
                Seg(start, if (i == n - 1) total - 1 else start + size - 1)
            }
        }
    }
}

object Files {
    /** "name.ext", or "name (2).ext", "name (3).ext"… if taken (also by an unfinished .fdpart). */
    fun uniqueFile(dir: File, name: String, startAt: Int = 1, taken: Set<String> = emptySet()): File {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = startAt
        while (true) {
            val candidate = if (i == 1) name else "$base ($i)$ext"
            val f = File(dir, candidate)
            if (!f.exists() && !File(dir, candidate + HttpTask.PART).exists() && candidate !in taken) return f
            i++
        }
    }
}
