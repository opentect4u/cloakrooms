package com.synergic.cloakroom.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PBKDF2-HMAC-SHA256 with a random per-password salt.
 *
 * The algorithm is implemented over `HmacSHA256` because `SecretKeyFactory("PBKDF2WithHmacSHA256")`
 * only exists from Android 8 (API 26) and this app supports Android 7 (API 24).
 *
 * Stored format: `pbkdf2_sha256$<iterations>$<salt hex>$<hash hex>`, so the iteration count can be
 * raised later without breaking existing hashes.
 */
@Singleton
class PasswordHasher @Inject constructor() {

    private val random = SecureRandom()

    fun hash(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = pbkdf2(password, salt, iterations, KEY_BYTES)
        return listOf(PREFIX, iterations.toString(), salt.toHex(), derived.toHex()).joinToString("$")
    }

    fun verify(password: String, stored: String?): Boolean {
        if (password.isEmpty() || stored.isNullOrEmpty()) return false
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = parts[2].hexToBytesOrNull() ?: return false
        val expected = parts[3].hexToBytesOrNull() ?: return false
        val actual = pbkdf2(password, salt, iterations, expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    companion object {
        private const val PREFIX = "pbkdf2_sha256"
        private const val DEFAULT_ITERATIONS = 60_000
        private const val SALT_BYTES = 16
        private const val KEY_BYTES = 32

        /** RFC 8018 section 5.2. */
        fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
            require(iterations >= 1 && keyLength >= 1)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val hLen = mac.macLength
            val blocks = (keyLength + hLen - 1) / hLen
            val out = ByteArray(blocks * hLen)
            for (block in 1..blocks) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                repeat(iterations - 1) {
                    u = mac.doFinal(u)
                    for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                }
                t.copyInto(out, (block - 1) * hLen)
            }
            return out.copyOf(keyLength)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.hexToBytesOrNull(): ByteArray? {
            if (length % 2 != 0) return null
            return ByteArray(length / 2) { i ->
                substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
            }
        }
    }
}
