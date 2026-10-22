package squareup

import squareup.config.ServerConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConfigurationTest {
    private val database = mapOf("DB_URL" to "jdbc:postgresql://localhost:5432/squareup", "DB_USER" to "squareup", "DB_PASSWORD" to "local-test-password")

    @Test
    fun `reads defaults with secure cookies and external database parameters`() {
        val config = ServerConfiguration.fromEnvironment(database)
        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals(3600, config.sessionLifetime.seconds)
        assertTrue(config.secureCookies)
        assertEquals("INFO", config.logLevel)
        assertEquals(database.getValue("DB_URL"), config.database.jdbcUrl)
    }

    @Test
    fun `reads explicit server session cookie and logging parameters`() {
        val config = ServerConfiguration.fromEnvironment(database + mapOf("SERVER_HOST" to "0.0.0.0", "SERVER_PORT" to "9000", "SESSION_SECONDS" to "60", "COOKIE_SECURE" to "false", "LOG_LEVEL" to "WARN"))
        assertEquals("0.0.0.0", config.host)
        assertEquals(9000, config.port)
        assertEquals(60, config.sessionLifetime.seconds)
        assertFalse(config.secureCookies)
        assertEquals("WARN", config.logLevel)
    }

    @Test
    fun `rejects missing secrets and invalid configuration values`() {
        for (key in database.keys) assertFailsWith<IllegalArgumentException> { ServerConfiguration.fromEnvironment(database - key) }
        for ((key, value) in listOf("SERVER_HOST" to " ", "SERVER_PORT" to "0", "SERVER_PORT" to "65536", "SERVER_PORT" to "text", "SESSION_SECONDS" to "-1", "SESSION_SECONDS" to "text", "COOKIE_SECURE" to "yes", "LOG_LEVEL" to "UNKNOWN")) {
            assertFailsWith<IllegalArgumentException> { ServerConfiguration.fromEnvironment(database + (key to value)) }
        }
    }
}
