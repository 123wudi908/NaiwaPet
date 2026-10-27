package com.naiwa.androidpet.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

class ArtifactDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun `verified file is reused without a second download`() {
        val opens = AtomicInteger()
        val target = folder.root.resolve("update.apk")
        val downloader = ArtifactDownloader(target) { opens.incrementAndGet(); ByteArrayInputStream("abc".toByteArray()) }
        assertEquals(DownloadResult.Ready, downloader.download(3, digest, { false }) { _, _ -> })
        assertEquals(DownloadResult.Ready, downloader.download(3, digest, { false }) { _, _ -> })
        assertEquals(1, opens.get())
        assertEquals("abc", target.readText())
    }

    @Test fun `corruption and cancellation never leave an installable file`() {
        val target = folder.root.resolve("update.apk")
        target.writeText("old")
        val downloader = ArtifactDownloader(target) { ByteArrayInputStream("bad".toByteArray()) }
        assertTrue(downloader.download(3, digest, { false }) { _, _ -> } is DownloadResult.Failed)
        assertFalse(target.exists())
        var cancelled = false
        val correct = ArtifactDownloader(target) { ByteArrayInputStream("abc".toByteArray()) }
        assertEquals(DownloadResult.Cancelled, correct.download(3, digest, { cancelled }) { _, _ -> cancelled = true })
        assertFalse(target.exists())
        assertFalse(folder.root.resolve("update.apk.part").exists())
    }

    @Test fun `cancelled connection failure is reported as cancellation`() {
        var cancelled = false
        val downloader = ArtifactDownloader(folder.root.resolve("update.apk")) {
            object : java.io.InputStream() {
                override fun read(): Int { cancelled = true; throw java.io.IOException("closed") }
            }
        }
        assertEquals(DownloadResult.Cancelled, downloader.download(3, digest, { cancelled }) { _, _ -> })
    }
}
