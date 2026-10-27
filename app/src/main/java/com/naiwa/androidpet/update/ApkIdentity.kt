package com.naiwa.androidpet.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateFactory

/** Conservative same-current-certificate policy; rotations and multi-signer packages are rejected. */
object ApkIdentity {
    private val flags: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    fun installedInfo(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)
    @Suppress("DEPRECATION")
    fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun certificate(info: PackageInfo): ByteArray? {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.singleOrNull()?.toByteArray()
    }
    fun publicKey(info: PackageInfo): PublicKey? = try {
        val bytes = certificate(info) ?: return null
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)).publicKey
    } catch (_: Exception) { null }
    private fun digest(info: PackageInfo): String? = certificate(info)?.let { cert ->
        MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, release: UpdateRelease): String? {
      return try {
        val current = installedInfo(context)
        val key = publicKey(current) ?: return "无法确认当前应用的签名身份。"
        if (!UpdatePolicy.verifyMetadata(release.signedManifest, release.signature, key))
            return "发布清单签名不一致，已禁止安装。"
        val manifest = GitHubReleaseSource.parseManifest(release.signedManifest)
        if (manifest != release.manifest || !UpdatePolicy.validManifest(manifest, manifest.tag, context.packageName, release.asset))
            return "发布清单与下载信息不一致，已禁止安装。"
        if (!ArtifactDownloader.matches(file, manifest.size, manifest.sha256))
            return "APK 已损坏或摘要不一致，已禁止安装。"
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return "无法读取 APK，已禁止安装。"
        val currentDigest = digest(current) ?: return "无法确认当前应用的签名身份。"
        val archiveDigest = digest(archive) ?: return "无法确认下载包的签名身份。"
        if (!UpdatePolicy.validArchive(ArchiveIdentity(archive.packageName, version(archive), archiveDigest),
                context.packageName, version(current), manifest.versionCode, currentDigest))
            return "APK 包名、版本或签名不兼容，无法覆盖安装。"
        null
      } catch (_: Exception) { "无法验证安装包，已禁止安装。" }
    }
}
