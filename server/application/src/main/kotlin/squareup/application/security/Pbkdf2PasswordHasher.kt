package squareup.application.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Pbkdf2PasswordHasher : PasswordHasher {
    private val random = SecureRandom()
    private val iterations = 600_000

    override fun hash(password: String): String {
        require(password.length in 15..128) { "Password length must be between 15 and 128 characters." }
        val salt = ByteArray(16)
        random.nextBytes(salt)
        val encoder = Base64.getEncoder()
        return listOf("pbkdf2-sha256", iterations.toString(), encoder.encodeToString(salt), encoder.encodeToString(derive(password, salt)))
            .joinToString(":")
    }

    override fun matches(password: String, encoded: String): Boolean {
        if (password.length !in 15..128 || encoded.length > 256) return false
        val parts = encoded.split(':')
        return !(parts.size != 4 || parts[0] != "pbkdf2-sha256" || parts[1] != iterations.toString()) && try {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(parts[2])
            val expected = decoder.decode(parts[3])
            salt.size == 16 && expected.size == 32 && MessageDigest.isEqual(expected, derive(password, salt))
        } catch (failure: IllegalArgumentException) {
            false
        }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val characters = password.toCharArray()
        val spec = PBEKeySpec(characters, salt, iterations, 256)
        characters.fill('\u0000')
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
