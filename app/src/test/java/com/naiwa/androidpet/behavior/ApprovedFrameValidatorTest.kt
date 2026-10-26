package com.naiwa.androidpet.behavior

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ApprovedFrameValidatorTest {
    private val bytes = "reviewed fixture bytes".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `all reviewed hashes and decoder validation are needed`() {
        assertTrue(ApprovedFrameValidator.verify(listOf("naiwa/wave/a.png"),mapOf("a.png" to hash),{bytes},{_,_ -> true}))
        assertFalse(ApprovedFrameValidator.verify(listOf("naiwa/wave/a.png"),mapOf("a.png" to hash),{bytes},{_,_ -> false}))
    }

    @Test fun `changed or missing frames cannot reuse an old approval`() {
        assertFalse(ApprovedFrameValidator.verify(listOf("a.png"),mapOf("a.png" to hash),{"changed".toByteArray()},{_,_ -> true}))
        assertFalse(ApprovedFrameValidator.verify(listOf("a.png"),mapOf("a.png" to hash),{throw IllegalArgumentException("missing")},{_,_ -> true}))
        assertFalse(ApprovedFrameValidator.verify(listOf("a.png"),mapOf("a.png" to hash,"b.png" to hash),{bytes},{_,_ -> true}))
    }

    @Test fun `no approval inventory and duplicate paths fail closed`() {
        assertFalse(ApprovedFrameValidator.verify(emptyList(),emptyMap(),{bytes},{_,_ -> true}))
        assertFalse(ApprovedFrameValidator.verify(listOf("a.png"),emptyMap(),{bytes},{_,_ -> true}))
        assertFalse(ApprovedFrameValidator.verify(listOf("a.png","a.png"),mapOf("a.png" to hash),{bytes},{_,_ -> true}))
    }
}
