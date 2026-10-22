package squareup.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DatabaseConfigTest {
    @Test
    fun `rejects invalid connection and pool settings`() {
        assertFailsWith<IllegalArgumentException> { config(jdbcUrl = "jdbc:h2:mem:test") }
        assertFailsWith<IllegalArgumentException> { config(username = " ") }
        assertFailsWith<IllegalArgumentException> { config(locations = emptyList()) }
        assertFailsWith<IllegalArgumentException> { config(locations = listOf(" ")) }
        assertFailsWith<IllegalArgumentException> { config(maximumPoolSize = 0) }
        assertFailsWith<IllegalArgumentException> { config(connectionTimeoutMillis = 249) }
    }

    @Test
    fun `copies migration locations and does not print connection secrets`() {
        val locations = mutableListOf("classpath:db/transactions")
        val config = config(locations = locations)
        locations.clear()

        assertEquals(listOf("classpath:db/transactions"), config.migrationLocations)
        assertFalse(config.toString().contains("test-password"))
        assertFalse(config.toString().contains(config.jdbcUrl))
    }

    private fun config(
        jdbcUrl: String = "jdbc:postgresql://localhost/test",
        username: String = "postgres",
        locations: List<String> = listOf("classpath:db/transactions"),
        maximumPoolSize: Int = 1,
        connectionTimeoutMillis: Long = 250
    ): DatabaseConfig {
        return DatabaseConfig(jdbcUrl, username, "test-password", locations, maximumPoolSize, connectionTimeoutMillis)
    }
}
