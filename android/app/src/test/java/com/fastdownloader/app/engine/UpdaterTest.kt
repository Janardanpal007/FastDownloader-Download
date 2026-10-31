package com.fastdownloader.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versions() {
        assertTrue(Updater.isNewer("v1.2.0", "1.1.0"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertFalse(Updater.isNewer("v1.1.0", "1.1.0"))
        assertFalse(Updater.isNewer("1.0.9", "1.1.0"))
        assertFalse(Updater.isNewer("nightly", "1.1.0"))
    }

    @Test
    fun picksTheApkForThisPhone() {
        val names = listOf(
            "FastDownloader-1.2.0-android-arm.apk", "FastDownloader-1.2.0-android-arm64.apk",
            "FastDownloader-1.2.0-android-universal.apk", "FastDownloader-1.2.0-android-x86_64.apk",
            "FastDownloader-Setup-1.2.0.exe", "SHA256SUMS.txt",
        )
        assertEquals("FastDownloader-1.2.0-android-arm64.apk", Updater.pickApk(names, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("FastDownloader-1.2.0-android-arm.apk", Updater.pickApk(names, listOf("armeabi-v7a")))
        assertEquals("FastDownloader-1.2.0-android-x86_64.apk", Updater.pickApk(names, listOf("x86_64", "x86")))
        assertEquals("FastDownloader-1.2.0-android-universal.apk", Updater.pickApk(names, listOf("x86")))
        assertNull(Updater.pickApk(listOf("FastDownloader-Setup-1.2.0.exe"), listOf("arm64-v8a")))
    }

    @Test
    fun readsChecksums() {
        val h = "ab".repeat(32)
        val sums = Updater.parseSums("$h  FastDownloader-1.2.0-android-arm64.apk\r\n${"cd".repeat(32)} *x.apk\nnot a line")
        assertEquals(h, sums["FastDownloader-1.2.0-android-arm64.apk"])
        assertEquals("cd".repeat(32), sums["x.apk"])
        assertEquals(2, sums.size)
    }
}
