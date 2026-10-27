package com.naiwa.androidpet.update

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

sealed interface DownloadResult {
    data object Ready : DownloadResult
    data object Cancelled : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}

/** Writes only to private storage; a partial or unverified APK is never promoted. */
class ArtifactDownloader(private val target: File, private val open: () -> InputStream) {
    fun download(size: Long, sha256: String, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): DownloadResult {
        val part = File(target.parentFile, target.name + ".part")
        try {
            if (cancelled()) return DownloadResult.Cancelled
            if (matches(target, size, sha256)) return DownloadResult.Ready
            if (target.exists() && !target.delete()) return DownloadResult.Failed("无法清除损坏的下载文件。")
            target.parentFile?.mkdirs()
            var total = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            open().use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled() || Thread.currentThread().isInterrupted) return DownloadResult.Cancelled
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > size) return DownloadResult.Failed("下载文件大小不符合发布记录。")
                        digest.update(buffer, 0, read); output.write(buffer, 0, read); progress(total, size)
                    }
                    output.fd.sync()
                }
            }
            if (cancelled() || Thread.currentThread().isInterrupted) return DownloadResult.Cancelled
            if (total != size || hex(digest.digest()) != sha256)
                return DownloadResult.Failed("下载文件已损坏，摘要与可信发布记录不一致。")
            if (target.exists() && !target.delete()) return DownloadResult.Failed("无法替换旧下载文件。")
            if (!part.renameTo(target)) return DownloadResult.Failed("无法保存下载文件。")
            return DownloadResult.Ready
        } catch (_: Exception) {
            return if (cancelled() || Thread.currentThread().isInterrupted) DownloadResult.Cancelled
            else DownloadResult.Failed("下载失败，请检查网络和存储后重试。")
        }
        finally { if (part.exists()) part.delete() }
    }

    companion object {
        fun matches(file: File, size: Long, sha256: String): Boolean = try {
            if (!file.isFile || file.length() != size) false else {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
                }
                hex(digest.digest()) == sha256
            }
        } catch (_: Exception) { false }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
