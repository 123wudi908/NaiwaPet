package com.naiwa.androidpet.update

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class UpdatePolicyTest {
    @Test fun `only a strictly higher version code is an update`() {
        assertFalse(UpdatePolicy.isNewer(5, 4))
        assertFalse(UpdatePolicy.isNewer(5, 5))
        assertTrue(UpdatePolicy.isNewer(5, 6))
        assertTrue(UpdatePolicy.isNewer(5, 100))
    }

    @Test fun `notifications need consent permission background and an unignored unsent version`() {
        val eligible = NotificationDecision(true, true, false, 8, 0, 0)
        assertTrue(UpdatePolicy.shouldNotify(eligible))
        assertFalse(UpdatePolicy.shouldNotify(eligible.copy(enabled = false)))
        assertFalse(UpdatePolicy.shouldNotify(eligible.copy(permission = false)))
        assertFalse(UpdatePolicy.shouldNotify(eligible.copy(foreground = true)))
        assertFalse(UpdatePolicy.shouldNotify(eligible.copy(ignoredVersion = 8)))
        assertFalse(UpdatePolicy.shouldNotify(eligible.copy(notifiedVersion = 8)))
        assertFalse(UpdatePolicy.shouldNotify(eligible.copy(version = 7, notifiedVersion = 8)))
        assertFalse(UpdatePolicy.shouldNotify(eligible.copy(snoozedVersion = 8, snoozeUntil = 1000, now = 999)))
        assertTrue(UpdatePolicy.shouldNotify(eligible.copy(snoozedVersion = 8, snoozeUntil = 1000, now = 1000)))
    }

    @Test fun `signed bytes must match the installed signer and survive no tampering`() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val signer = generator.generateKeyPair()
        val stranger = generator.generateKeyPair()
        val bytes = "release metadata".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signer.private); update(bytes); sign()
        }
        assertTrue(UpdatePolicy.verifyMetadata(bytes, signature, signer.public))
        assertFalse(UpdatePolicy.verifyMetadata("tampered".toByteArray(), signature, signer.public))
        assertFalse(UpdatePolicy.verifyMetadata(bytes, signature, stranger.public))
    }

    @Test fun `trusted release binds its package tag asset length and digest`() {
        val manifest = ReleaseManifest("v1.2", "1.2", 8, "com.naiwa.androidpet",
            "naiwa.apk", 1234, "a".repeat(64), "说明")
        val asset = ReleaseAsset("naiwa.apk", 1234, "https://github.com/123wudi908/NaiwaPet/releases/download/v1.2/naiwa.apk")
        assertTrue(UpdatePolicy.validManifest(manifest, "v1.2", "com.naiwa.androidpet", asset))
        assertFalse(UpdatePolicy.validManifest(manifest, "v1.3", "com.naiwa.androidpet", asset))
        assertFalse(UpdatePolicy.validManifest(manifest, "v1.2", "other.package", asset))
        assertFalse(UpdatePolicy.validManifest(manifest, "v1.2", "com.naiwa.androidpet", asset.copy(size = 1235)))
        assertFalse(UpdatePolicy.validManifest(manifest.copy(sha256 = "bad"), "v1.2", "com.naiwa.androidpet", asset))
        assertFalse(UpdatePolicy.validManifest(manifest.copy(versionCode = 0), "v1.2", "com.naiwa.androidpet", asset))
        assertFalse(UpdatePolicy.validManifest(manifest, "v1.2", "com.naiwa.androidpet", asset.copy(url = "http://evil.example/naiwa.apk")))
    }

    @Test fun `network redirects cannot leave approved HTTPS GitHub hosts`() {
        assertTrue(HttpSecurity.allowed("https://api.github.com/repos/123wudi908/NaiwaPet"))
        assertTrue(HttpSecurity.allowed("https://release-assets.githubusercontent.com/a"))
        assertFalse(HttpSecurity.allowed("http://api.github.com/repos/123wudi908/NaiwaPet"))
        assertFalse(HttpSecurity.allowed("https://github.com.evil.example/a"))
        assertFalse(HttpSecurity.allowed("https://evil.example@github.com:444/a"))
        assertFalse(HttpSecurity.allowed("https://127.0.0.1/a"))
    }

    @Test fun `installation requires matching package expected code and current signer`() {
        val expected = ArchiveIdentity("com.naiwa.androidpet", 8, "abc")
        assertTrue(UpdatePolicy.validArchive(expected, "com.naiwa.androidpet", 5, 8, "abc"))
        assertFalse(UpdatePolicy.validArchive(expected.copy(applicationId = "other"), "com.naiwa.androidpet", 5, 8, "abc"))
        assertFalse(UpdatePolicy.validArchive(expected.copy(versionCode = 7), "com.naiwa.androidpet", 5, 8, "abc"))
        assertFalse(UpdatePolicy.validArchive(expected, "com.naiwa.androidpet", 8, 8, "abc"))
        assertFalse(UpdatePolicy.validArchive(expected.copy(signerDigest = "different"), "com.naiwa.androidpet", 5, 8, "abc"))
    }
}
