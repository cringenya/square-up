package squareup.persistence

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.api.FlywayException
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JdbcDatabaseTest {
    @Test
    fun `applies migrations before accepting transactions`() {
        withDatabase { database, _ ->
            assertEquals(listOf(0), recordIds(database))
            val versions = database.transaction { connection ->
                connection.prepareStatement("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank").use { statement ->
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) add(result.getString(1))
                        }
                    }
                }
            }
            assertEquals(listOf("1", "2"), versions)
        }
    }

    @Test
    fun `commits all statements and returns block result`() {
        withDatabase { database, _ ->
            val result = database.transaction { connection ->
                assertFalse(connection.autoCommit)
                insert(connection, 1, "first")
                insert(connection, 2, "second")
                "saved"
            }

            assertEquals("saved", result)
            assertEquals(listOf(0, 1, 2), recordIds(database))
        }
    }

    @Test
    fun `rolls back all changes and preserves original exception`() {
        withDatabase { database, _ ->
            val failure = IllegalStateException("Cannot complete operation.")
            val thrown = assertFailsWith<IllegalStateException> {
                database.transaction { connection ->
                    insert(connection, 1, "first")
                    insert(connection, 2, "second")
                    throw failure
                }
            }

            assertSame(failure, thrown)
            assertEquals(listOf(0), recordIds(database))
            database.transaction { insert(it, 3, "after rollback") }
            assertEquals(listOf(0, 3), recordIds(database))
        }
    }

    @Test
    fun `rolls back on database constraint violation`() {
        withDatabase { database, _ ->
            val failure = assertFailsWith<SQLException> {
                database.transaction { connection ->
                    insert(connection, 1, "first")
                    insert(connection, 1, "duplicate id")
                }
            }

            assertEquals("23505", failure.sqlState)
            assertEquals(listOf(0), recordIds(database))
        }
    }

    @Test
    fun `rolls back when deferred constraint fails at commit`() {
        withDatabase { database, _ ->
            var blockCompleted = false
            val failure = assertFailsWith<SQLException> {
                database.transaction { connection ->
                    insert(connection, 1, "same label")
                    insert(connection, 2, "same label")
                    blockCompleted = true
                }
            }

            assertTrue(blockCompleted)
            assertEquals("23505", failure.sqlState)
            assertEquals(listOf(0), recordIds(database))
        }
    }

    @Test
    fun `returns connections after success failure and nullable result`() {
        withDatabase { database, _ ->
            var successfulConnection: Connection? = null
            assertEquals(null, database.transaction { connection ->
                successfulConnection = connection
                null
            })
            assertTrue(requireNotNull(successfulConnection).isClosed)

            var failedConnection: Connection? = null
            assertFailsWith<IllegalArgumentException> {
                database.transaction { connection ->
                    failedConnection = connection
                    throw IllegalArgumentException("Invalid operation.$failedConnection")
                }
            }
            assertTrue(requireNotNull(failedConnection).isClosed)

            repeat(10) {
                assertEquals(listOf(0), recordIds(database))
            }
        }
    }

    @Test
    fun `retains committed data after pool restart without repeating migrations`() {
        EmbeddedPostgres.start().use { postgres ->
            val config = config(postgres)
            JdbcDatabase(config).use { database ->
                database.transaction { insert(it, 1, "retained") }
            }
            JdbcDatabase(config).use { database ->
                assertEquals(listOf(0, 1), recordIds(database))
            }
        }
    }

    @Test
    fun `rejects transactions after close and closes idempotently`() {
        EmbeddedPostgres.start().use { postgres ->
            val database = JdbcDatabase(config(postgres))
            database.close()
            database.close()

            assertFailsWith<SQLException> { recordIds(database) }
        }
    }

    @Test
    fun `rejects missing migrations and closes startup connections`() {
        EmbeddedPostgres.start().use { postgres ->
            assertFailsWith<FlywayException> {
                JdbcDatabase(config(postgres, locations = listOf("classpath:db/missing")))
            }
            assertEquals(0, otherConnections(postgres))
        }
    }

    @Test
    fun `rejects broken migration without leaving partial schema or connections`() {
        EmbeddedPostgres.start().use { postgres ->
            assertFailsWith<FlywayException> {
                JdbcDatabase(config(postgres, locations = listOf("classpath:db/broken")))
            }
            assertEquals(0, otherConnections(postgres))
            postgres.postgresDatabase.connection.use { connection ->
                connection.prepareStatement("SELECT to_regclass('broken_records')").use { statement ->
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertEquals(null, result.getString(1))
                    }
                }
            }
        }
    }

    @Test
    fun `limits waiting when pool is exhausted and keeps outer transaction usable`() {
        withDatabase { database, _ ->
            database.transaction { connection ->
                insert(connection, 1, "outer")
                assertFailsWith<SQLException> { recordIds(database) }
            }
            assertEquals(listOf(0, 1), recordIds(database))
        }
    }

    @Test
    fun `keeps concurrent transactions independent`() {
        EmbeddedPostgres.start().use { postgres ->
            JdbcDatabase(config(postgres, maximumPoolSize = 2)).use { database ->
                val inserted = CountDownLatch(1)
                val release = CountDownLatch(1)
                val executor = Executors.newSingleThreadExecutor()
                val failure = IllegalStateException("Roll back first transaction.")
                try {
                    val pending = executor.submit<Unit> {
                        database.transaction { connection ->
                            insert(connection, 1, "uncommitted")
                            inserted.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "Concurrent transaction timed out." }
                            throw failure
                        }
                    }

                    assertTrue(inserted.await(10, TimeUnit.SECONDS))
                    assertEquals(listOf(0), recordIds(database))
                    database.transaction { insert(it, 2, "committed") }
                    release.countDown()
                    val thrown = assertFailsWith<ExecutionException> { pending.get(10, TimeUnit.SECONDS) }
                    assertSame(failure, thrown.cause)
                    assertEquals(listOf(0, 2), recordIds(database))
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun withDatabase(block: (JdbcDatabase, EmbeddedPostgres) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            JdbcDatabase(config(postgres)).use { database -> block(database, postgres) }
        }
    }

    private fun config(
        postgres: EmbeddedPostgres,
        locations: List<String> = listOf("classpath:db/transactions"),
        maximumPoolSize: Int = 1
    ): DatabaseConfig {
        return DatabaseConfig(
            jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
            username = "postgres",
            password = "",
            migrationLocations = locations,
            maximumPoolSize = maximumPoolSize,
            connectionTimeoutMillis = 250
        )
    }

    private fun insert(connection: Connection, id: Int, label: String) {
        connection.prepareStatement("INSERT INTO transaction_records (id, label) VALUES (?, ?)").use { statement ->
            statement.setInt(1, id)
            statement.setString(2, label)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun recordIds(database: JdbcDatabase): List<Int> {
        return database.transaction { connection ->
            connection.prepareStatement("SELECT id FROM transaction_records ORDER BY id").use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.getInt(1))
                    }
                }
            }
        }
    }

    private fun otherConnections(postgres: EmbeddedPostgres): Int {
        return postgres.postgresDatabase.connection.use { connection ->
            connection.prepareStatement("SELECT count(1) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()").use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next()) { "Connection count must be returned." }
                    result.getInt(1)
                }
            }
        }
    }
}
