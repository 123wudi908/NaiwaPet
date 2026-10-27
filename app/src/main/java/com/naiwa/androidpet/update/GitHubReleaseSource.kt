package com.naiwa.androidpet.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.PublicKey

data class HttpReply(val status: Int, val body: ByteArray = byteArrayOf(), val headers: Map<String, String> = emptyMap())
fun interface HttpTransport { fun get(url: String, maxBytes: Int): HttpReply }

/** Only a formal latest release with metadata signed by the installed identity is actionable. */
class GitHubReleaseSource(private val transport: HttpTransport, private val signer: PublicKey,
    private val applicationId: String, private val installedVersion: Long) : UpdateSource {
    override fun check(): CheckResult {
        val response = transport.get("$API/releases/latest", 1_048_576)
        if (response.status == 404) {
            val repo = transport.get(API, 65_536)
            return if (repo.status == 200) CheckResult.Unavailable("GitHub 仓库尚未发布正式 Release。") else failure(repo)
        }
        if (response.status != 200) return failure(response)
        return try {
            val release = JsonParser.parseString(response.body.toString(Charsets.UTF_8)).asJsonObject
            if (release.get("draft").asBoolean || release.get("prerelease").asBoolean)
                return CheckResult.Unavailable("没有可用的正式 Release。")
            val tag = release.get("tag_name").asString
            val assets = release.getAsJsonArray("assets").map { item ->
                val a = item.asJsonObject
                ReleaseAsset(a.get("name").asString, a.get("size").asLong, a.get("browser_download_url").asString)
            }
            val manifestAsset = assets.singleOrNull { it.name == "naiwa-update.json" }
            val signatureAsset = assets.singleOrNull { it.name == "naiwa-update.sig" }
            if (manifestAsset == null || signatureAsset == null ||
                !isReleaseUrl(manifestAsset.url) || !isReleaseUrl(signatureAsset.url))
                return CheckResult.Unavailable("此 Release 缺少可信的签名更新清单。")
            val manifestReply = transport.get(manifestAsset.url, 131_072)
            val signatureReply = transport.get(signatureAsset.url, 8_192)
            if (manifestReply.status != 200) return failure(manifestReply)
            if (signatureReply.status != 200) return failure(signatureReply)
            if (!UpdatePolicy.verifyMetadata(manifestReply.body, signatureReply.body, signer))
                return CheckResult.Unavailable("发布清单签名验证失败；可能与当前安装包签名不一致。")
            val manifest = parseManifest(manifestReply.body)
            val apk = assets.singleOrNull { it.name == manifest.assetName }
                ?: return CheckResult.Unavailable("Release 中找不到签名清单指定的 APK。")
            if (!UpdatePolicy.validManifest(manifest, tag, applicationId, apk))
                return CheckResult.Unavailable("发布清单与 Release 资产不一致。")
            if (!UpdatePolicy.isNewer(installedVersion, manifest.versionCode)) return CheckResult.UpToDate
            CheckResult.Available(UpdateRelease(manifest, apk,
                release.get("published_at")?.takeUnless { it.isJsonNull }?.asString ?: "",
                manifestReply.body, signatureReply.body))
        } catch (_: java.io.IOException) {
            CheckResult.Failure("网络连接失败，请检查网络后重试。")
        } catch (_: Exception) { CheckResult.Unavailable("Release 元数据不完整或格式无效。") }
    }

    private fun failure(reply: HttpReply): CheckResult.Failure {
        val remaining = reply.headers.entries.firstOrNull { it.key.equals("X-RateLimit-Remaining", true) }?.value
        return if (reply.status == 429 || (reply.status == 403 && remaining == "0"))
            CheckResult.Failure("GitHub API 已限流，请稍后重试。")
        else CheckResult.Failure("GitHub 仓库或 Release 暂时无法访问（HTTP ${reply.status}）。")
    }

    companion object {
        const val API = "https://api.github.com/repos/123wudi908/NaiwaPet"
        const val PROJECT = "https://github.com/123wudi908/NaiwaPet"
        fun isReleaseUrl(url: String): Boolean = try {
            val uri = java.net.URI(url)
            uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.rawUserInfo == null &&
                uri.rawPath.startsWith("/123wudi908/NaiwaPet/releases/download/")
        } catch (_: Exception) { false }
        fun parseManifest(bytes: ByteArray): ReleaseManifest {
            val json: JsonObject = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            return ReleaseManifest(json.get("tag").asString, json.get("versionName").asString,
                json.get("versionCode").asLong, json.get("applicationId").asString,
                json.get("assetName").asString, json.get("size").asLong,
                json.get("sha256").asString, json.get("notes").asString)
        }
    }
}
