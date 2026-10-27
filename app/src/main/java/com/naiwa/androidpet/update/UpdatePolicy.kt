package com.naiwa.androidpet.update

import java.security.PublicKey
import java.security.Signature
import java.net.URI

data class ReleaseManifest(val tag: String, val versionName: String, val versionCode: Long,
    val applicationId: String, val assetName: String, val size: Long, val sha256: String, val notes: String)
data class ReleaseAsset(val name: String, val size: Long, val url: String)
data class ArchiveIdentity(val applicationId: String, val versionCode: Long, val signerDigest: String)

data class NotificationDecision(
    val enabled: Boolean,
    val permission: Boolean,
    val foreground: Boolean,
    val version: Long,
    val ignoredVersion: Long,
    val notifiedVersion: Long,
    val snoozedVersion: Long = 0,
    val snoozeUntil: Long = 0,
    val now: Long = 0
)

object UpdatePolicy {
    fun isNewer(current: Long, candidate: Long): Boolean = candidate > current

    fun shouldNotify(decision: NotificationDecision): Boolean =
        decision.enabled && decision.permission && !decision.foreground && decision.version > 0 &&
            decision.version != decision.ignoredVersion && decision.version > decision.notifiedVersion &&
            !(decision.version == decision.snoozedVersion && decision.now < decision.snoozeUntil)

    fun validManifest(manifest: ReleaseManifest, tag: String, packageName: String, asset: ReleaseAsset): Boolean {
        val uri = try { URI(asset.url) } catch (_: Exception) { return false }
        return manifest.tag == tag && tag.isNotBlank() && manifest.applicationId == packageName &&
            manifest.versionCode > 0 && manifest.versionName.isNotBlank() && manifest.versionName.length <= 100 &&
            manifest.assetName == asset.name && asset.name.matches(Regex("[A-Za-z0-9._-]+\\.apk")) &&
            manifest.size == asset.size && asset.size in 1..(200L * 1024 * 1024) &&
            manifest.sha256.matches(Regex("[0-9a-f]{64}")) && manifest.notes.length <= 65_536 &&
            uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawPath.startsWith("/123wudi908/NaiwaPet/releases/download/")
    }

    fun validArchive(archive: ArchiveIdentity, applicationId: String, installedVersion: Long,
        expectedVersion: Long, installedSignerDigest: String): Boolean =
        archive.applicationId == applicationId && archive.versionCode == expectedVersion &&
            isNewer(installedVersion, archive.versionCode) && installedSignerDigest.isNotBlank() &&
            archive.signerDigest == installedSignerDigest

    fun verifyMetadata(bytes: ByteArray, signature: ByteArray, key: PublicKey): Boolean = try {
        val algorithm = when (key.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> return false
        }
        Signature.getInstance(algorithm).run { initVerify(key); update(bytes); verify(signature) }
    } catch (_: Exception) { false }
}
