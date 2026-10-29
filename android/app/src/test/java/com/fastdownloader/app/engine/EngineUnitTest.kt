package com.fastdownloader.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files as NioFiles

class EngineUnitTest {
    @Test
    fun splitCoversTheWholeFileWithoutGaps() {
        for (total in listOf(1L, 1000L, 5_000_000L, 1_073_741_825L)) {
            for (n in listOf(1, 3, 8, 32)) {
                val segs = HttpTask.split(total, n)
                assertEquals(0L, segs.first().start)
                assertEquals(total - 1, segs.last().end)
                segs.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
                assertEquals(total, segs.sumOf { it.remaining })
            }
        }
    }

    @Test
    fun smallFilesGetFewerParts() {
        assertEquals(1, HttpTask.split(500_000, 8).size) // under 1 MB: one part
        assertEquals(3, HttpTask.split(3L shl 20, 8).size)
        assertEquals(8, HttpTask.split(100L shl 20, 8).size)
    }

    @Test
    fun qualityTiersMatchWhatSitesShow() {
        assertEquals(2160, Ytdlp.tier(3840, 2160))
        assertEquals(2160, Ytdlp.tier(3840, 1600)) // wide film in 4K
        assertEquals(2160, Ytdlp.tier(2160, 3840)) // vertical 4K Short
        assertEquals(1080, Ytdlp.tier(1920, 1080))
        assertEquals(1440, Ytdlp.tier(2560, 1440))
        assertEquals(720, Ytdlp.tier(1280, 720))
        assertEquals(360, Ytdlp.tier(0, 360))
    }

    @Test
    fun sizesAreReadable() {
        assertEquals("512 B", Format.size(512))
        assertEquals("1.5 KB", Format.size(1536))
        assertEquals("1.0 GB", Format.size(1L shl 30))
        assertEquals("2m 5s left", Format.eta(125))
    }

    @Test
    fun uniqueNamesSkipTakenAndUnfinishedFiles() {
        val dir = NioFiles.createTempDirectory("fd").toFile()
        java.io.File(dir, "a.zip").writeText("x")
        java.io.File(dir, "a (2).zip.fdpart").writeText("x")
        assertEquals("a (3).zip", Files.uniqueFile(dir, "a.zip").name)
        assertEquals("b.zip", Files.uniqueFile(dir, "b.zip").name)
        assertEquals("b (2).zip", Files.uniqueFile(dir, "b.zip", taken = setOf("b.zip")).name)
        assertTrue(dir.deleteRecursively())
    }
}
