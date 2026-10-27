package com.naiwa.androidpet.update

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection

object HttpSecurity {
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    fun allowed(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.port == -1 && uri.rawUserInfo == null
    } catch (_: Exception) { false }
}

/** Bounded HTTPS requests and manually validated redirects; never forwards credentials. */
interface UpdateTransport : HttpTransport { fun openDownload(url: String): InputStream }

class GitHubHttpTransport(private val connectionFactory: (URL) -> URLConnection = { it.openConnection() }) : UpdateTransport {
    override fun get(url: String, maxBytes: Int): HttpReply {
        val connection = connect(url)
        try {
            val code = connection.responseCode
            val headers = mapOf("X-RateLimit-Remaining" to (connection.getHeaderField("X-RateLimit-Remaining") ?: ""))
            if (code != 200) return HttpReply(code, headers = headers)
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > maxBytes) throw IOException("response too large")
                    out.write(buffer, 0, n)
                }
            }
            return HttpReply(code, out.toByteArray(), headers)
        } finally { connection.disconnect() }
    }

    override fun openDownload(url: String): InputStream {
        if (!GitHubReleaseSource.isReleaseUrl(url)) throw IOException("untrusted release URL")
        val connection = connect(url)
        if (connection.responseCode != 200) { connection.disconnect(); throw IOException("download unavailable") }
        return object : FilterInputStream(connection.inputStream) {
            override fun close() { try { super.close() } finally { connection.disconnect() } }
        }
    }

    private fun connect(initial: String): HttpURLConnection {
        var url = initial
        repeat(6) {
            if (!HttpSecurity.allowed(url)) throw IOException("untrusted redirect")
            val connection = connectionFactory(URI(url).toURL()) as HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "NaiwaPet-Update")
            val status = try { connection.responseCode } catch (error: Exception) { connection.disconnect(); throw error }
            if (status !in 300..399) return connection
            val location = connection.getHeaderField("Location") ?: run { connection.disconnect(); throw IOException("missing redirect") }
            val next = URI(url).resolve(location).toString()
            connection.disconnect()
            url = next
        }
        throw IOException("too many redirects")
    }
}
