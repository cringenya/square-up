package squareup.persistence

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchemaMigrationTest {
    @Test
    fun `creates all application tables without initial accounts`() {
        withDatabase { database ->
            val tables = query(database, "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename") {
                it.getString(1)
            }
            assertEquals(
                listOf("event_participants", "events", "expense_shares", "expenses", "flyway_schema_history", "participants", "users"),
                tables
            )
            assertEquals(0L, query(database, "SELECT count(1) FROM public.users") { it.getLong(1) }.single())
        }
    }

    @Test
    fun `keeps logins unique and case sensitive`() {
        withDatabase { database ->
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val third = UUID.randomUUID()
            database.transaction { connection ->
                insertParticipant(connection, first)
                insertParticipant(connection, second)
                insertParticipant(connection, third)
                insertUser(connection, UUID.randomUUID(), first, "Masha")
                insertUser(connection, UUID.randomUUID(), second, "masha")
            }

            assertViolation("23505", "uq_users_login") {
                database.transaction { insertUser(it, UUID.randomUUID(), third, "Masha") }
            }
            assertEquals(listOf("Masha", "masha"), query(database, "SELECT login FROM public.users ORDER BY login") {
                it.getString(1)
            })
        }
    }

    @Test
    fun `rejects a second user linked to the same participant`() {
        withDatabase { database ->
            val fixture = createFixture(database)
            assertViolation("23505", "uq_users_participant_id") {
                database.transaction { insertUser(it, UUID.randomUUID(), fixture.author, "another") }
            }
            assertEquals(listOf(fixture.user), query(database, "SELECT id FROM public.users WHERE participant_id = ?", listOf(fixture.author)) {
                it.getObject(1, UUID::class.java)
            })
        }
    }

    @Test
    fun `rejects blank required text invalid roles and nulls`() {
        withDatabase { database ->
            val fixture = createFixture(database)
            val invalidUpdates = listOf(
                "UPDATE public.participants SET first_name = ' ' WHERE id = ?" to fixture.author,
                "UPDATE public.participants SET last_name = '' WHERE id = ?" to fixture.author,
                "UPDATE public.users SET login = ' ' WHERE id = ?" to fixture.user,
                "UPDATE public.users SET email = '' WHERE id = ?" to fixture.user,
                "UPDATE public.users SET password_hash = ' ' WHERE id = ?" to fixture.user,
                "UPDATE public.users SET role = 'OWNER' WHERE id = ?" to fixture.user,
                "UPDATE public.events SET name = ' ' WHERE id = ?" to fixture.event,
                "UPDATE public.expenses SET name = '' WHERE id = ?" to fixture.expense
            )
            for ((sql, id) in invalidUpdates) {
                assertViolation("23514") { execute(database, sql, listOf(id)) }
            }
            assertViolation("23502") {
                execute(database, "UPDATE public.event_participants SET active = NULL WHERE event_id = ?", listOf(fixture.event))
            }
            execute(database, "UPDATE public.users SET role = 'ADMIN' WHERE id = ?", listOf(fixture.user))
            assertEquals(listOf("ADMIN"), query(database, "SELECT role FROM public.users WHERE id = ?", listOf(fixture.user)) {
                it.getString(1)
            })
        }
    }

    @Test
    fun `stores maximum money and permits zero shares without truncation`() {
        withDatabase { database ->
            val fixture = createFixture(database)
            database.transaction { connection ->
                execute(connection, "UPDATE public.expenses SET amount_kopecks = ? WHERE id = ?", listOf(4_294_967_295L, fixture.expense))
                execute(connection, "UPDATE public.expense_shares SET amount_kopecks = ? WHERE expense_id = ? AND participant_id = ?", listOf(4_294_967_295L, fixture.expense, fixture.debtor))
                execute(connection, "UPDATE public.expense_shares SET amount_kopecks = 0 WHERE expense_id = ? AND participant_id = ?", listOf(fixture.expense, fixture.payer))
            }
            assertEquals(listOf(4_294_967_295L), query(database, "SELECT amount_kopecks FROM public.expenses WHERE id = ?", listOf(fixture.expense)) {
                it.getLong(1)
            })
            assertEquals(listOf(0L, 4_294_967_295L), query(database, "SELECT amount_kopecks FROM public.expense_shares WHERE expense_id = ? ORDER BY amount_kopecks", listOf(fixture.expense)) {
                it.getLong(1)
            })

            for (amount in listOf(-1L, 0L, 4_294_967_296L)) {
                assertViolation("23514", "ck_expenses_amount") {
                    execute(database, "UPDATE public.expenses SET amount_kopecks = ? WHERE id = ?", listOf(amount, fixture.expense))
                }
            }
            for (amount in listOf(-1L, 4_294_967_296L)) {
                assertViolation("23514", "ck_expense_shares_amount") {
                    execute(database, "UPDATE public.expense_shares SET amount_kopecks = ? WHERE expense_id = ? AND participant_id = ?", listOf(amount, fixture.expense, fixture.debtor))
                }
            }
        }
    }

    @Test
    fun `rejects references to missing objects`() {
        withDatabase { database ->
            val fixture = createFixture(database)
            val missing = UUID.randomUUID()
            val invalidUpdates = listOf(
                "UPDATE public.users SET participant_id = ? WHERE id = ?" to fixture.user,
                "UPDATE public.event_participants SET event_id = ? WHERE participant_id = ?" to fixture.debtor,
                "UPDATE public.event_participants SET participant_id = ? WHERE participant_id = ?" to fixture.debtor,
                "UPDATE public.expenses SET event_id = ? WHERE id = ?" to fixture.expense,
                "UPDATE public.expenses SET payer_id = ? WHERE id = ?" to fixture.expense,
                "UPDATE public.expenses SET author_id = ? WHERE id = ?" to fixture.expense,
                "UPDATE public.expense_shares SET expense_id = ? WHERE participant_id = ?" to fixture.debtor,
                "UPDATE public.expense_shares SET participant_id = ? WHERE participant_id = ?" to fixture.debtor
            )
            for ((sql, id) in invalidUpdates) {
                assertViolation("23503") { execute(database, sql, listOf(missing, id)) }
            }
        }
    }

    @Test
    fun `rejects duplicate memberships and debtor shares`() {
        withDatabase { database ->
            val fixture = createFixture(database)
            assertViolation("23505", "pk_event_participants") {
                execute(database, "INSERT INTO public.event_participants (event_id, participant_id, active) VALUES (?, ?, TRUE)", listOf(fixture.event, fixture.debtor))
            }
            assertViolation("23505", "pk_expense_shares") {
                execute(database, "INSERT INTO public.expense_shares (expense_id, participant_id, amount_kopecks) VALUES (?, ?, 1)", listOf(fixture.expense, fixture.debtor))
            }
        }
    }

    @Test
    fun `restricts deletion of referenced objects without cascading expenses`() {
        withDatabase { database ->
            val fixture = createFixture(database)
            for (participantId in listOf(fixture.author, fixture.payer, fixture.debtor)) {
                assertViolation("23503") {
                    execute(database, "DELETE FROM public.participants WHERE id = ?", listOf(participantId))
                }
            }
            assertViolation("23503") {
                execute(database, "DELETE FROM public.events WHERE id = ?", listOf(fixture.event))
            }
            assertViolation("23503") {
                execute(database, "DELETE FROM public.expenses WHERE id = ?", listOf(fixture.expense))
            }
            assertEquals(listOf(1L), query(database, "SELECT count(1) FROM public.expenses WHERE id = ?", listOf(fixture.expense)) {
                it.getLong(1)
            })
            assertEquals(listOf(1L, 100L), storedShares(database, fixture.expense))
        }
    }

    @Test
    fun `keeps historical memberships expenses and shares after participant exits`() {
        withDatabase { database ->
            val fixture = createFixture(database)
            execute(database, "UPDATE public.event_participants SET active = FALSE WHERE event_id = ? AND participant_id = ?", listOf(fixture.event, fixture.debtor))

            assertEquals(emptyList(), query(database, "SELECT event_id FROM public.event_participants WHERE participant_id = ? AND active", listOf(fixture.debtor)) {
                it.getObject(1, UUID::class.java)
            })
            assertEquals(listOf(fixture.event), query(database, "SELECT event_id FROM public.event_participants WHERE participant_id = ?", listOf(fixture.debtor)) {
                it.getObject(1, UUID::class.java)
            })
            assertEquals(listOf(fixture.expense), expenseHistory(database, fixture.debtor))
            assertEquals(listOf(1L, 100L), storedShares(database, fixture.expense))
        }
    }

    @Test
    fun `derives expense history for all roles and removes deleted expenses from history`() {
        withDatabase { database ->
            val fixture = createFixture(database)
            val participantIds = listOf(fixture.author, fixture.payer, fixture.debtor)
            for (participantId in participantIds) {
                assertEquals(listOf(fixture.expense), expenseHistory(database, participantId))
            }

            database.transaction { connection ->
                execute(connection, "DELETE FROM public.expense_shares WHERE expense_id = ?", listOf(fixture.expense))
                execute(connection, "DELETE FROM public.expenses WHERE id = ?", listOf(fixture.expense))
            }
            for (participantId in participantIds) {
                assertEquals(emptyList(), expenseHistory(database, participantId))
            }
        }
    }

    @Test
    fun `rolls back expense and shares together on invalid share`() {
        withDatabase { database ->
            val fixture = createFixture(database)
            val expenseId = UUID.randomUUID()
            assertViolation("23514", "ck_expense_shares_amount") {
                database.transaction { connection ->
                    insertExpense(connection, expenseId, fixture)
                    insertShare(connection, expenseId, fixture.debtor, 100)
                    insertShare(connection, expenseId, fixture.payer, -1)
                }
            }
            assertEquals(emptyList(), storedShares(database, expenseId))
            assertEquals(listOf(fixture.expense), expenseHistory(database, fixture.author))
        }
    }

    @Test
    fun `allows only one concurrent registration for a participant or login`() {
        EmbeddedPostgres.start().use { postgres ->
            JdbcDatabase(config(postgres, maximumPoolSize = 2)).use { database ->
                val firstParticipant = UUID.randomUUID()
                val secondParticipant = UUID.randomUUID()
                val thirdParticipant = UUID.randomUUID()
                database.transaction { connection ->
                    insertParticipant(connection, firstParticipant)
                    insertParticipant(connection, secondParticipant)
                    insertParticipant(connection, thirdParticipant)
                }
                assertConcurrentRegistrationRejected(database, firstParticipant, "first", firstParticipant, "second", "uq_users_participant_id")
                assertConcurrentRegistrationRejected(database, secondParticipant, "shared", thirdParticipant, "shared", "uq_users_login")
                assertEquals(listOf(2L), query(database, "SELECT count(1) FROM public.users") { it.getLong(1) })
            }
        }
    }

    @Test
    fun `reopens migrated database preserving ids memberships and stored shares`() {
        EmbeddedPostgres.start().use { postgres ->
            val config = config(postgres)
            val fixture = JdbcDatabase(config).use { createFixture(it) }
            JdbcDatabase(config).use { database ->
                assertEquals(listOf(1L, 100L), storedShares(database, fixture.expense))
                assertEquals(listOf(fixture.expense), expenseHistory(database, fixture.author))
                assertEquals(listOf(3L), query(database, "SELECT count(1) FROM public.event_participants WHERE event_id = ? AND active", listOf(fixture.event)) {
                    it.getLong(1)
                })
                assertEquals(listOf("1"), query(database, "SELECT version FROM public.flyway_schema_history WHERE success") {
                    it.getString(1)
                })
            }
        }
    }

    private fun assertConcurrentRegistrationRejected(
        database: JdbcDatabase,
        firstParticipant: UUID,
        firstLogin: String,
        secondParticipant: UUID,
        secondLogin: String,
        constraint: String
    ) {
        val inserted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Unit> {
                database.transaction { connection ->
                    insertUser(connection, UUID.randomUUID(), firstParticipant, firstLogin)
                    inserted.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Registration transaction timed out." }
                }
            }
            assertTrue(inserted.await(10, TimeUnit.SECONDS))
            val second = executor.submit<Unit> {
                database.transaction { connection ->
                    secondStarted.countDown()
                    insertUser(connection, UUID.randomUUID(), secondParticipant, secondLogin)
                }
            }
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            val failure = assertFailsWith<ExecutionException> { second.get(10, TimeUnit.SECONDS) }
            val sqlFailure = assertIs<PSQLException>(failure.cause)
            assertEquals("23505", sqlFailure.sqlState)
            assertEquals(constraint, sqlFailure.serverErrorMessage?.constraint)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun createFixture(database: JdbcDatabase): Fixture {
        val fixture = Fixture()
        database.transaction { connection ->
            for (id in listOf(fixture.author, fixture.payer, fixture.debtor)) insertParticipant(connection, id)
            insertUser(connection, fixture.user, fixture.author, "author")
            execute(connection, "INSERT INTO public.events (id, name) VALUES (?, ?)", listOf(fixture.event, "Trip"))
            for (id in listOf(fixture.author, fixture.payer, fixture.debtor)) {
                execute(connection, "INSERT INTO public.event_participants (event_id, participant_id, active) VALUES (?, ?, TRUE)", listOf(fixture.event, id))
            }
            insertExpense(connection, fixture.expense, fixture)
            insertShare(connection, fixture.expense, fixture.payer, 1)
            insertShare(connection, fixture.expense, fixture.debtor, 100)
        }
        return fixture
    }

    private fun insertParticipant(connection: Connection, id: UUID) {
        execute(connection, "INSERT INTO public.participants (id, first_name, last_name) VALUES (?, ?, ?)", listOf(id, "First", "Last"))
    }

    private fun insertUser(connection: Connection, id: UUID, participantId: UUID, login: String) {
        execute(connection, "INSERT INTO public.users (id, participant_id, email, login, password_hash, role) VALUES (?, ?, ?, ?, ?, ?)", listOf(id, participantId, "$login@example.test", login, "test-hash", "USER"))
    }

    private fun insertExpense(connection: Connection, id: UUID, fixture: Fixture) {
        execute(connection, "INSERT INTO public.expenses (id, event_id, name, amount_kopecks, payer_id, author_id) VALUES (?, ?, ?, ?, ?, ?)", listOf(id, fixture.event, "Lunch", 101L, fixture.payer, fixture.author))
    }

    private fun insertShare(connection: Connection, expenseId: UUID, participantId: UUID, amount: Long) {
        execute(connection, "INSERT INTO public.expense_shares (expense_id, participant_id, amount_kopecks) VALUES (?, ?, ?)", listOf(expenseId, participantId, amount))
    }

    private fun storedShares(database: JdbcDatabase, expenseId: UUID): List<Long> {
        return query(database, "SELECT amount_kopecks FROM public.expense_shares WHERE expense_id = ? ORDER BY amount_kopecks", listOf(expenseId)) {
            it.getLong(1)
        }
    }

    private fun expenseHistory(database: JdbcDatabase, participantId: UUID): List<UUID> {
        return query(database, "SELECT e.id FROM public.expenses e WHERE e.author_id = ? OR e.payer_id = ? OR EXISTS (SELECT 1 FROM public.expense_shares s WHERE s.expense_id = e.id AND s.participant_id = ?) ORDER BY e.id", listOf(participantId, participantId, participantId)) {
            it.getObject(1, UUID::class.java)
        }
    }

    private fun assertViolation(state: String, constraint: String? = null, block: () -> Unit) {
        val failure = assertFailsWith<SQLException> { block() }
        assertEquals(state, failure.sqlState)
        if (constraint != null) assertEquals(constraint, assertIs<PSQLException>(failure).serverErrorMessage?.constraint)
    }

    private fun execute(database: JdbcDatabase, sql: String, values: List<Any?> = emptyList()): Int {
        return database.transaction { execute(it, sql, values) }
    }

    private fun execute(connection: Connection, sql: String, values: List<Any?> = emptyList()): Int {
        return connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun <T> query(
        database: JdbcDatabase,
        sql: String,
        values: List<Any?> = emptyList(),
        readRow: (ResultSet) -> T
    ): List<T> {
        return database.transaction { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(readRow(result))
                    }
                }
            }
        }
    }

    private fun withDatabase(block: (JdbcDatabase) -> Unit) = withTestDatabase(1, block)

    private fun config(postgres: EmbeddedPostgres, maximumPoolSize: Int = 1): DatabaseConfig {
        return DatabaseConfig(
            jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
            username = "postgres",
            password = "",
            migrationLocations = listOf("classpath:db/migration"),
            maximumPoolSize = maximumPoolSize,
            connectionTimeoutMillis = 1_000
        )
    }

    private data class Fixture(
        val author: UUID = UUID.randomUUID(),
        val payer: UUID = UUID.randomUUID(),
        val debtor: UUID = UUID.randomUUID(),
        val user: UUID = UUID.randomUUID(),
        val event: UUID = UUID.randomUUID(),
        val expense: UUID = UUID.randomUUID()
    )
}
