package squareup.persistence

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import squareup.application.exception.AccessDeniedFailure
import squareup.application.security.AccessPolicy
import squareup.application.account.AccountLogin
import squareup.application.account.AccountService
import squareup.application.account.AdminAccount
import squareup.application.exception.AuthenticationFailure
import squareup.application.exception.ConflictFailure
import squareup.application.exception.NotFoundFailure
import squareup.application.participant.ParticipantService
import squareup.application.security.PasswordHasher
import squareup.application.security.Pbkdf2PasswordHasher
import squareup.application.account.Registration
import squareup.application.security.SessionManager
import squareup.application.exception.StorageFailure
import squareup.application.transaction.TransactionManager
import squareup.application.user.UserService
import squareup.application.user.UserUpdate
import squareup.application.exception.ValidationFailure
import squareup.domain.event.Event
import squareup.domain.expense.Expense
import squareup.domain.money.Money
import squareup.domain.user.Participant
import squareup.domain.user.UserRole
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AccountServiceTest {
    private val password = "Initial password here"
    private val changedPassword = "A different password here"

    @Test
    fun `creates initial admin once and does not overwrite an existing account`() {
        withServices { services ->
            val admin = services.bootstrap()
            assertEquals(UserRole.ADMIN, admin.user.role)
            assertEquals("Admin", admin.user.firstName)
            assertEquals("Person", admin.user.lastName)
            assertNull(services.accounts.bootstrapAdmin(null))
            assertNull(services.accounts.bootstrapAdmin(AdminAccount("Changed", "Name", "changed@example.test", "changed", changedPassword)))
            assertEquals(admin.user.id, services.accounts.login("admin", password).user.id)
            assertFailsWith<AuthenticationFailure> { services.accounts.login("changed", changedPassword) }
            services.transactions.transaction { repositories ->
                val user = assertNotNull(repositories.users.findById(admin.user.id))
                assertTrue(user.getPasswordHash().startsWith("pbkdf2-sha256:600000:"))
                assertFalse(user.getPasswordHash().contains(password))
                assertEquals(1, repositories.participants.findAll().size)
                assertEquals(1, repositories.users.findAll().size)
            }
        }
    }

    @Test
    fun `requires initial admin configuration and rolls back an invalid bootstrap`() {
        withServices { services ->
            assertFailsWith<ValidationFailure> { services.accounts.bootstrapAdmin(null) }
            assertFailsWith<ValidationFailure> {
                services.accounts.bootstrapAdmin(AdminAccount("Admin", "Person", "invalid", "admin", password))
            }
            services.transactions.transaction { repositories ->
                assertTrue(repositories.participants.findAll().isEmpty())
                assertTrue(repositories.users.findAll().isEmpty())
            }
            assertEquals(UserRole.ADMIN, services.bootstrap().user.role)
        }
    }

    @Test
    fun `registers only a free participant and preserves its existing history`() {
        withServices { services ->
            val admin = services.bootstrap()
            val guest = services.participants.create(admin.session.id, "Guest", "Person")
            val eventId = services.transactions.transaction { repositories ->
                val owner = assertNotNull(repositories.participants.findById(admin.user.participantId))
                val participant = assertNotNull(repositories.participants.findById(guest.id))
                val event = Event.Builder().withName("Trip").withParticipants(setOf(owner, participant)).build()
                repositories.events.save(event)
                event.getId()
            }
            val registered = services.accounts.register(Registration(guest.id, "guest@example.test", " guest ", password))
            assertEquals(UserRole.USER, registered.role)
            assertEquals("guest", registered.login)
            val login = services.accounts.login("guest", password)
            assertEquals(setOf(eventId), services.participants.findById(login.session.id, guest.id).eventIds)
            assertFailsWith<ConflictFailure> { services.accounts.register(Registration(guest.id, "other@example.test", "other", password)) }
            val free = services.participants.create(admin.session.id, "Free", "Person")
            assertFailsWith<ConflictFailure> { services.accounts.register(Registration(free.id, "other@example.test", "guest", password)) }
            assertFailsWith<NotFoundFailure> { services.accounts.register(Registration(Uuid.random(), "other@example.test", "other", password)) }
            assertNull(services.participants.findById(admin.session.id, free.id).userId)
        }
    }

    @Test
    fun `rejects invalid registration and incorrect credentials without creating sessions`() {
        withServices { services ->
            val admin = services.bootstrap()
            val free = services.participants.create(admin.session.id, "Free", "Person")
            for (registration in listOf(
                Registration(free.id, "invalid", "new", password), Registration(free.id, "new@example.test", " ", password),
                Registration(free.id, "new@example.test", "new", "short")
            )) assertFailsWith<ValidationFailure> { services.accounts.register(registration) }
            for (credentials in listOf("admin" to "Wrong password here", "missing" to password, "admin" to "short")) {
                assertFailsWith<AuthenticationFailure> { services.accounts.login(credentials.first, credentials.second) }
            }
            assertNull(services.participants.findById(admin.session.id, free.id).userId)
            assertFailsWith<AuthenticationFailure> { services.users.current("unknown") }
            services.accounts.logout(admin.session.id)
            services.accounts.logout(admin.session.id)
            assertFailsWith<AuthenticationFailure> { services.users.current(admin.session.id) }
        }
    }

    @Test
    fun `allows self editing and admin operations while denying other user access`() {
        withServices { services ->
            val admin = services.bootstrap()
            val user = services.register(admin, "user")
            val other = services.register(admin, "other")
            val updated = services.users.update(user.session.id, user.user.id, UserUpdate(firstName = "Updated", lastName = "Name", login = "renamed"))
            assertEquals("Updated", updated.firstName)
            assertEquals("Name", updated.lastName)
            assertEquals("renamed", services.users.current(user.session.id).login)
            assertEquals(user.user.id, services.accounts.login("renamed", password).user.id)
            assertFailsWith<AuthenticationFailure> { services.accounts.login("user", password) }
            assertFailsWith<AccessDeniedFailure> { services.users.findById(user.session.id, other.user.id) }
            assertFailsWith<AccessDeniedFailure> { services.users.update(user.session.id, other.user.id, UserUpdate(firstName = "Forbidden")) }
            assertFailsWith<AccessDeniedFailure> { services.users.findAll(user.session.id) }
            assertFailsWith<AccessDeniedFailure> { services.users.changeRole(user.session.id, user.user.id, UserRole.ADMIN) }
            assertFailsWith<AccessDeniedFailure> { services.users.delete(user.session.id, other.user.id) }
            assertFailsWith<AccessDeniedFailure> { services.participants.findById(user.session.id, other.user.participantId) }
            assertFailsWith<AccessDeniedFailure> { services.participants.create(user.session.id, "Forbidden", "Person") }
            assertEquals("Profile edit", services.participants.update(user.session.id, user.user.participantId, "Profile edit").firstName)
            assertFailsWith<AccessDeniedFailure> { services.participants.update(user.session.id, other.user.participantId, "Forbidden") }
            assertFailsWith<AccessDeniedFailure> { services.participants.delete(user.session.id, other.user.participantId) }
            assertEquals("Profile edit", services.participants.findById(user.session.id, user.user.participantId).firstName)
            assertEquals(other.user.id, services.users.update(admin.session.id, other.user.id, UserUpdate(firstName = "Admin edit")).id)
            assertEquals(3, services.users.findAll(admin.session.id).size)
        }
    }

    @Test
    fun `rolls back invalid edits and preserves sessions until successful password change`() {
        withServices { services ->
            val admin = services.bootstrap()
            val user = services.register(admin, "user")
            val secondSession = services.accounts.login("user", password)
            assertFailsWith<ConflictFailure> { services.users.update(user.session.id, user.user.id, UserUpdate(login = "admin", password = changedPassword)) }
            assertFailsWith<ValidationFailure> { services.users.update(user.session.id, user.user.id, UserUpdate(firstName = "Changed", lastName = "", password = changedPassword)) }
            assertEquals("First", services.users.current(secondSession.session.id).firstName)
            assertEquals(user.user.id, services.accounts.login("user", password).user.id)
            val changed = services.users.update(user.session.id, user.user.id, UserUpdate(password = changedPassword))
            assertEquals(user.user.id, changed.id)
            assertFailsWith<AuthenticationFailure> { services.users.current(user.session.id) }
            assertFailsWith<AuthenticationFailure> { services.users.current(secondSession.session.id) }
            assertFailsWith<AuthenticationFailure> { services.accounts.login("user", password) }
            assertEquals(user.user.id, services.accounts.login("user", changedPassword).user.id)
            assertEquals(admin.user.id, services.users.current(admin.session.id).id)
        }
    }

    @Test
    fun `preserves the last admin and revokes sessions only on an actual role change`() {
        withServices { services ->
            val admin = services.bootstrap()
            assertFailsWith<ConflictFailure> { services.users.changeRole(admin.session.id, admin.user.id, UserRole.USER) }
            assertFailsWith<ConflictFailure> { services.users.delete(admin.session.id, admin.user.id) }
            services.users.changeRole(admin.session.id, admin.user.id, UserRole.ADMIN)
            assertEquals(admin.user.id, services.users.current(admin.session.id).id)
            val second = services.register(admin, "second", UserRole.ADMIN)
            services.users.changeRole(admin.session.id, second.user.id, UserRole.USER)
            assertFailsWith<AuthenticationFailure> { services.users.current(second.session.id) }
            val renewed = services.accounts.login("second", password)
            assertEquals(UserRole.USER, renewed.user.role)
            assertFailsWith<AccessDeniedFailure> { services.users.findAll(renewed.session.id) }
            services.users.changeRole(admin.session.id, second.user.id, UserRole.ADMIN)
            val newAdmin = services.accounts.login("second", password)
            services.users.delete(newAdmin.session.id, admin.user.id)
            assertFailsWith<AuthenticationFailure> { services.users.current(admin.session.id) }
            assertEquals(newAdmin.user.id, services.users.current(newAdmin.session.id).id)
        }
    }

    @Test
    fun `provides admin participant CRUD and rejects referenced participant deletion`() {
        withServices { services ->
            val admin = services.bootstrap()
            val guest = services.participants.create(admin.session.id, "Guest", "Person")
            assertEquals("Changed", services.participants.update(admin.session.id, guest.id, firstName = "Changed").firstName)
            assertEquals(2, services.participants.findAll(admin.session.id).size)
            assertFailsWith<ConflictFailure> { services.participants.delete(admin.session.id, admin.user.participantId) }
            services.participants.delete(admin.session.id, guest.id)
            assertFailsWith<NotFoundFailure> { services.participants.findById(admin.session.id, guest.id) }
            assertFailsWith<NotFoundFailure> { services.participants.delete(admin.session.id, guest.id) }
        }
    }

    @Test
    fun `rejects deleting the last registered event participant without losing account or session`() {
        withServices { services ->
            val admin = services.bootstrap()
            val user = services.register(admin, "user")
            services.saveEvent(user.user.participantId, null, withExpense = false)
            assertFailsWith<ConflictFailure> { services.users.delete(admin.session.id, user.user.id) }
            assertEquals(user.user.id, services.users.current(user.session.id).id)
            assertEquals(1, services.participants.findById(user.session.id, user.user.participantId).eventIds.size)
        }
    }

    @Test
    fun `rejects deleting the last registered expense participant even after event exit`() {
        withServices { services ->
            val admin = services.bootstrap()
            val user = services.register(admin, "user")
            val eventId = services.saveEvent(user.user.participantId, admin.user.participantId, withExpense = true)
            services.transactions.transaction { repositories ->
                val event = assertNotNull(repositories.events.findById(eventId))
                event.removeParticipant(assertNotNull(repositories.participants.findById(user.user.participantId)))
                repositories.events.save(event)
            }
            assertFailsWith<ConflictFailure> { services.users.delete(admin.session.id, user.user.id) }
            assertEquals(user.user.id, services.users.current(user.session.id).id)
            assertEquals(1, services.participants.findById(user.session.id, user.user.participantId).expenseIds.size)
        }
    }

    @Test
    fun `deletes user preserving participant and histories when another registered role remains`() {
        withServices { services ->
            val admin = services.bootstrap()
            val user = services.register(admin, "user")
            val eventId = services.saveEvent(user.user.participantId, admin.user.participantId, withExpense = true, registeredPayer = true)
            services.users.delete(admin.session.id, user.user.id)
            assertFailsWith<AuthenticationFailure> { services.users.current(user.session.id) }
            assertFailsWith<NotFoundFailure> { services.users.findById(admin.session.id, user.user.id) }
            val participant = services.participants.findById(admin.session.id, user.user.participantId)
            assertNull(participant.userId)
            assertEquals(setOf(eventId), participant.eventIds)
            assertEquals(1, participant.expenseIds.size)
            assertFailsWith<ConflictFailure> { services.participants.delete(admin.session.id, participant.id) }
        }
    }

    @Test
    fun `concurrent login cannot leave an old password session valid after password change`() {
        val verifying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val intercept = AtomicBoolean(false)
        val actual = Pbkdf2PasswordHasher()
        val passwords = object : PasswordHasher {
            override fun hash(password: String): String = actual.hash(password)
            override fun matches(password: String, encoded: String): Boolean {
                if (intercept.compareAndSet(true, false)) {
                    verifying.countDown()
                    check(release.await(15, TimeUnit.SECONDS)) { "Password verification timed out." }
                }
                return actual.matches(password, encoded)
            }
        }
        withServices(passwords) { services ->
            val admin = services.bootstrap()
            val user = services.register(admin, "user")
            val executor = Executors.newFixedThreadPool(2)
            try {
                intercept.set(true)
                val login = executor.submit<AccountLogin> { services.accounts.login("user", password) }
                assertTrue(verifying.await(15, TimeUnit.SECONDS))
                val update = executor.submit { services.users.update(admin.session.id, user.user.id, UserUpdate(password = changedPassword)) }
                release.countDown()
                val oldPasswordLogin = login.get(15, TimeUnit.SECONDS)
                update.get(15, TimeUnit.SECONDS)
                assertFailsWith<AuthenticationFailure> { services.users.current(oldPasswordLogin.session.id) }
                assertEquals(user.user.id, services.accounts.login("user", changedPassword).user.id)
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `storage failures do not expose JDBC exceptions to application callers`() {
        EmbeddedPostgres.start().use { postgres ->
            val database = JdbcDatabase(DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "", listOf("classpath:db/migration")))
            val transactions = JdbcTransactionManager(database)
            database.close()
            val failure = assertFailsWith<StorageFailure> { transactions.transaction { it.users.findAll() } }
            assertNull(failure.cause)
        }
    }

    private fun withServices(passwords: PasswordHasher = Pbkdf2PasswordHasher(), block: (Services) -> Unit) =
        withTestTransactions { block(Services(it, passwords)) }

    private inner class Services(val transactions: TransactionManager, passwords: PasswordHasher) {
        val sessions = SessionManager()
        private val access = AccessPolicy(sessions)
        val accounts = AccountService(transactions, passwords, sessions)
        val users = UserService(transactions, passwords, sessions, access)
        val participants = ParticipantService(transactions, access)

        fun bootstrap(): AccountLogin {
            accounts.bootstrapAdmin(AdminAccount("Admin", "Person", "admin@example.test", "admin", password))
            return accounts.login("admin", password)
        }

        fun register(admin: AccountLogin, login: String, role: UserRole = UserRole.USER): AccountLogin {
            val participant = participants.create(admin.session.id, "First", "Last")
            val registration = Registration(participant.id, "$login@example.test", login, password)
            if (role == UserRole.USER) accounts.register(registration) else users.create(admin.session.id, registration, role)
            return accounts.login(login, password)
        }

        fun saveEvent(authorId: Uuid, anotherId: Uuid?, withExpense: Boolean, registeredPayer: Boolean = false): Uuid = transactions.transaction { repositories ->
            val author = assertNotNull(repositories.participants.findById(authorId))
            val another = anotherId?.let { assertNotNull(repositories.participants.findById(it)) }
            val guest = Participant.build("Guest", "Payer")
            repositories.participants.save(guest)
            val participants = mutableSetOf(author, guest)
            if (another != null) participants.add(another)
            val event = Event.Builder().withName("Trip").withParticipants(participants).build()
            repositories.events.save(event)
            if (withExpense) {
                val payer = if (registeredPayer) assertNotNull(another) else guest
                val expense = Expense.Builder().withName("Lunch").withAmount(Money(101u)).withAuthor(author)
                    .withPayer(payer).withDebtors(setOf(guest)).withEvent(event).build()
                repositories.expenses.save(expense)
            }
            event.getId()
        }
    }
}
