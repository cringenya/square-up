package squareup.application.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {
    private val passwords = Pbkdf2PasswordHasher()

    @Test
    fun `uses separate salts and verifies correct password`() {
        val password = "A password with spaces"
        val first = passwords.hash(password)
        val second = passwords.hash(password)
        assertNotEquals(first, second)
        assertEquals("pbkdf2-sha256:600000", first.split(':').take(2).joinToString(":"))
        assertTrue(passwords.matches(password, first))
        assertTrue(passwords.matches(password, second))
        assertFalse(passwords.matches("A different password", first))
        assertFalse(first.contains(password))
    }

    @Test
    fun `preserves spaces and supports unicode and password length boundaries`() {
        val password = "  пароль с пробелами  "
        val hash = passwords.hash(password)
        assertTrue(passwords.matches(password, hash))
        assertFalse(passwords.matches(password.trim(), hash))
        for (length in listOf(15, 128)) {
            val value = "x".repeat(length)
            assertTrue(passwords.matches(value, passwords.hash(value)))
        }
        for (length in listOf(0, 14, 129)) {
            assertFailsWith<IllegalArgumentException> { passwords.hash("x".repeat(length)) }
            assertFalse(passwords.matches("x".repeat(length), hash))
        }
    }

    @Test
    fun `rejects malformed or unsupported stored hashes`() {
        val password = "A valid password here"
        val valid = passwords.hash(password)
        val invalid = listOf("", "hash", "a:b:c:d:e", valid.replace("600000", "999999999"),
            valid.replace("pbkdf2-sha256", "sha256"), "pbkdf2-sha256:600000:!:!",
            "pbkdf2-sha256:600000:AA==:AA==", "x".repeat(257))
        for (encoded in invalid) assertFalse(passwords.matches(password, encoded))
    }
}
