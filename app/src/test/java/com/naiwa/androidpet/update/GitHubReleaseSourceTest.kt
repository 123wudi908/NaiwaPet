package com.naiwa.androidpet.update

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class GitHubReleaseSourceTest {
    private val api = "https://api.github.com/repos/123wudi908/NaiwaPet"
    private val base = "https://github.com/123wudi908/NaiwaPet/releases/download/v1.2/"
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val metadata = """{"tag":"v1.2","versionName":"1.2","versionCode":8,"applicationId":"com.naiwa.androidpet","assetName":"naiwa.apk","size":1234,"sha256":"${"a".repeat(64)}","notes":"新功能"}""".toByteArray()
    private val signature = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(metadata); sign() }
    private val release = """{"draft":false,"prerelease":false,"tag_name":"v1.2","published_at":"2026-10-27T00:00:00Z","assets":[{"name":"naiwa-update.json","size":${metadata.size},"browser_download_url":"${base}naiwa-update.json"},{"name":"naiwa-update.sig","size":${signature.size},"browser_download_url":"${base}naiwa-update.sig"},{"name":"naiwa.apk","size":1234,"browser_download_url":"${base}naiwa.apk"}]}""".toByteArray()

    private fun source(replies: Map<String, HttpReply>, version: Long = 5) = GitHubReleaseSource(
        HttpTransport { url, _ -> replies[url] ?: error("Unexpected URL: $url") }, key.public, "com.naiwa.androidpet", version)

    @Test fun `missing latest release is distinguished from inaccessible repository`() {
        val missing = mapOf("$api/releases/latest" to HttpReply(404), api to HttpReply(200))
        assertTrue(source(missing).check() is CheckResult.Unavailable)
        val inaccessible = missing + (api to HttpReply(404))
        assertTrue(source(inaccessible).check() is CheckResult.Failure)
    }

    @Test fun `rate limit is surfaced without claiming latest version`() {
        val result = source(mapOf("$api/releases/latest" to HttpReply(403, headers = mapOf("X-RateLimit-Remaining" to "0")))).check()
        assertTrue(result is CheckResult.Failure)
        assertTrue((result as CheckResult.Failure).reason.contains("限流"))
    }

    @Test fun `only signed release metadata can advertise a newer version`() {
        val replies = mapOf("$api/releases/latest" to HttpReply(200, release),
            "${base}naiwa-update.json" to HttpReply(200, metadata), "${base}naiwa-update.sig" to HttpReply(200, signature))
        assertTrue(source(replies).check() is CheckResult.Available)
        assertEquals(CheckResult.UpToDate, source(replies, 8).check())
        val tampered = replies + ("${base}naiwa-update.json" to HttpReply(200, metadata + 32))
        assertTrue(source(tampered).check() is CheckResult.Unavailable)
    }

    @Test fun `connection loss while fetching signed metadata is a retryable failure`() {
        val source = GitHubReleaseSource(HttpTransport { url, _ ->
            if (url == "$api/releases/latest") HttpReply(200, release) else throw java.io.IOException("offline")
        }, key.public, "com.naiwa.androidpet", 5)
        assertTrue(source.check() is CheckResult.Failure)
    }
}
