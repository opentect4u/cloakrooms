package com.synergic.cloakroom.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PasswordHasherTest {

    private val hasher = PasswordHasher()

    @Test
    fun `pbkdf2 matches the JDK implementation`() {
        val salt = "salt-1234".toByteArray()
        listOf(1 to 32, 2 to 32, 1000 to 32, 10 to 64).forEach { (iterations, length) ->
            val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("Admin@123".toCharArray(), salt, iterations, length * 8))
                .encoded
            assertArrayEquals(jdk, PasswordHasher.pbkdf2("Admin@123", salt, iterations, length))
        }
    }

    @Test
    fun `hash verifies the right password only`() {
        val stored = hasher.hash("User@123")
        assertTrue(hasher.verify("User@123", stored))
        assertFalse(hasher.verify("user@123", stored))
        assertFalse(hasher.verify("", stored))
    }

    @Test
    fun `same password gets a different salt each time`() {
        assertNotEquals(hasher.hash("Admin@123"), hasher.hash("Admin@123"))
    }

    @Test
    fun `plain text is never stored`() {
        assertFalse(hasher.hash("Admin@123").contains("Admin@123"))
    }

    @Test
    fun `malformed stored values do not verify`() {
        listOf(null, "", "Admin@123", "pbkdf2_sha256$10\$zz\$00", "md5$1$00$00").forEach {
            assertFalse(hasher.verify("Admin@123", it))
        }
    }
}
