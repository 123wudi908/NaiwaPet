package com.naiwa.androidpet.behavior

import java.security.MessageDigest

object ApprovedFrameValidator {
    fun verify(frames: List<String>, hashes: Map<String,String>, read: (String) -> ByteArray,
        validate: (String, ByteArray) -> Boolean): Boolean {
        val names = frames.map { it.substringAfterLast('/') }
        if (frames.isEmpty() || frames.size > 120 || names.toSet().size != names.size || hashes.keys != names.toSet()) return false
        if (hashes.values.any { !it.matches(Regex("[0-9a-fA-F]{64}")) }) return false
        return try {
            frames.all { path ->
                val bytes = read(path)
                bytes.size in 1..16_777_216 &&
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        .equals(hashes.getValue(path.substringAfterLast('/')),ignoreCase = true) && validate(path,bytes)
            }
        } catch (_: Exception) { false }
    }
}
