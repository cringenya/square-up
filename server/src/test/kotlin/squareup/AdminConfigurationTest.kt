package squareup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AdminConfigurationTest {
    @Test
    fun `reads initial admin parameters preserving password whitespace`() {
        val account = assertNotNull(AdminConfiguration.fromEnvironment(mapOf(
            "ADMIN_LOGIN" to "admin", "ADMIN_EMAIL" to "admin@example.test", "ADMIN_PASSWORD" to "  admin password here  ",
            "ADMIN_FIRST_NAME" to "First", "ADMIN_LAST_NAME" to "Last"
        )))
        assertEquals("admin", account.login)
        assertEquals("admin@example.test", account.email)
        assertEquals("First", account.firstName)
        assertEquals("Last", account.lastName)
        assertEquals("  admin password here  ", account.password)
    }

    @Test
    fun `allows absent configuration and rejects incomplete or blank values`() {
        assertNull(AdminConfiguration.fromEnvironment(emptyMap()))
        assertFailsWith<IllegalArgumentException> { AdminConfiguration.fromEnvironment(mapOf("ADMIN_LOGIN" to "admin")) }
        assertFailsWith<IllegalArgumentException> { AdminConfiguration.fromEnvironment(mapOf(
            "ADMIN_LOGIN" to "admin", "ADMIN_EMAIL" to "admin@example.test", "ADMIN_PASSWORD" to "",
            "ADMIN_FIRST_NAME" to "First", "ADMIN_LAST_NAME" to "Last"
        )) }
    }
}
