package squareup.persistence

import squareup.application.repository.Repositories
import squareup.application.exception.ConflictFailure
import squareup.domain.event.Event
import squareup.domain.event.SettlementCalculator
import squareup.domain.expense.Expense
import squareup.domain.money.Money
import squareup.domain.user.Participant
import squareup.domain.user.User
import squareup.domain.user.UserRole
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class JdbcRepositoryTest {
    @Test
    fun `restores all entities with complete histories and unchanged shares`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            manager.transaction { repositories ->
                val user = assertNotNull(repositories.users.findById(fixture.user))
                assertEquals(fixture.login, user.getLogin())
                assertEquals("test-hash", user.getPasswordHash())
                assertEquals(UserRole.ADMIN, user.getRole())
                assertEquals("Author", user.getFirstName())
                assertEquals("Person", user.getLastName())
                assertEquals("author@example.test", user.getEmail())
                val event = assertNotNull(repositories.events.findById(fixture.event))
                assertEquals("Trip", event.getName())
                assertEquals(fixture.participantIds, event.getParticipantIds())
                assertEquals(listOf(fixture.expense), event.getExpenseIds())
                val expense = assertNotNull(repositories.expenses.findById(fixture.expense))
                assertEquals("Lunch", expense.getName())
                assertEquals(Money(101u), expense.getAmount())
                assertEquals(fixture.event, expense.getEventId())
                assertEquals(fixture.author, expense.getAuthorId())
                assertEquals(fixture.payer, expense.getPayerId())
                assertEquals(fixture.shares, expense.getShares())
                for (id in fixture.participantIds) {
                    val participant = assertNotNull(repositories.participants.findById(id))
                    assertEquals(setOf(fixture.event), participant.getEventIds())
                    assertEquals(if (id == fixture.replacement) emptySet() else setOf(fixture.expense), participant.getExpenseIds())
                }
            }
        }
    }

    @Test
    fun `shares loaded objects across lookups inside one transaction`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            val firstUser = manager.transaction { repositories ->
                val user = assertNotNull(repositories.users.findById(fixture.user))
                assertSame(user, repositories.users.findByLogin(fixture.login))
                assertSame(user, repositories.users.findAll().single())
                val participant = assertNotNull(repositories.participants.findById(fixture.author))
                assertSame(participant, repositories.participants.findAll().single { it.getParticipantId() == fixture.author })
                participant.updateName(firstName = "Changed")
                assertEquals("Changed", user.getFirstName())
                val event = assertNotNull(repositories.events.findById(fixture.event))
                assertSame(event, repositories.events.findAll().single())
                assertSame(event, repositories.events.findByParticipantId(fixture.author).single())
                val expense = assertNotNull(repositories.expenses.findById(fixture.expense))
                assertSame(expense, repositories.expenses.findAll().single())
                assertSame(expense, repositories.expenses.findByEventId(fixture.event).single())
                assertSame(expense, repositories.expenses.findByParticipantId(fixture.author).single())
                val replacement = assertNotNull(repositories.participants.findById(fixture.replacement))
                event.removeParticipant(replacement)
                assertFailsWith<IllegalArgumentException> { expense.addDebtor(replacement) }
                user
            }
            manager.transaction { repositories ->
                val secondUser = assertNotNull(repositories.users.findById(fixture.user))
                assertNotSame(firstUser, secondUser)
                assertEquals("Author", secondUser.getFirstName())
            }
        }
    }

    @Test
    fun `persists profile event and expense edits atomically`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            val updatedShares = manager.transaction { repositories ->
                val user = assertNotNull(repositories.users.findById(fixture.user))
                user.update(firstName = "New", lastName = "Name", login = "changed", passwordHash = "new-hash")
                user.changeRole(UserRole.USER)
                repositories.users.save(user)
                repositories.participants.save(assertNotNull(repositories.participants.findById(fixture.author)))
                val event = assertNotNull(repositories.events.findById(fixture.event))
                event.update("Updated trip")
                repositories.events.save(event)
                val expense = assertNotNull(repositories.expenses.findById(fixture.expense))
                val context = fixture.participantIds.associateWith { assertNotNull(repositories.participants.findById(it)) }
                expense.update("Dinner", Money(203u), fixture.replacement, setOf(fixture.debtor, fixture.replacement), context)
                repositories.expenses.save(expense)
                expense.getShares()
            }
            manager.transaction { repositories ->
                val user = assertNotNull(repositories.users.findByLogin("changed"))
                assertNull(repositories.users.findByLogin(fixture.login))
                assertEquals("New", user.getFirstName())
                assertEquals("Name", user.getLastName())
                assertEquals("new-hash", user.getPasswordHash())
                assertEquals(UserRole.USER, user.getRole())
                assertEquals("Updated trip", assertNotNull(repositories.events.findById(fixture.event)).getName())
                val expense = assertNotNull(repositories.expenses.findById(fixture.expense))
                assertEquals("Dinner", expense.getName())
                assertEquals(Money(203u), expense.getAmount())
                assertEquals(fixture.replacement, expense.getPayerId())
                assertEquals(fixture.author, expense.getAuthorId())
                assertEquals(fixture.event, expense.getEventId())
                assertEquals(updatedShares, expense.getShares())
                assertTrue(assertNotNull(repositories.participants.findById(fixture.payer)).getExpenseIds().isEmpty())
                assertTrue(repositories.expenses.findByParticipantId(fixture.payer).isEmpty())
                assertEquals(listOf(fixture.expense), repositories.expenses.findByParticipantId(fixture.replacement).map { it.getId() })
            }
        }
    }

    @Test
    fun `restores departed debtors and preserves their histories when amount changes`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            manager.transaction { repositories ->
                val event = assertNotNull(repositories.events.findById(fixture.event))
                event.removeParticipant(assertNotNull(repositories.participants.findById(fixture.debtor)))
                repositories.events.save(event)
                val expense = assertNotNull(repositories.expenses.findById(fixture.expense))
                expense.update(amount = Money(202u))
                repositories.expenses.save(expense)
            }
            manager.transaction { repositories ->
                val event = assertNotNull(repositories.events.findById(fixture.event))
                assertFalse(event.hasParticipant(fixture.debtor))
                assertTrue(repositories.events.findByParticipantId(fixture.debtor, activeOnly = true).isEmpty())
                assertSame(event, repositories.events.findByParticipantId(fixture.debtor).single())
                val participant = assertNotNull(repositories.participants.findById(fixture.debtor))
                assertEquals(setOf(fixture.event), participant.getEventIds())
                assertEquals(setOf(fixture.expense), participant.getExpenseIds())
                val expenses = repositories.expenses.findByEventId(fixture.event)
                assertEquals(Money(101u), expenses.single().getShares().getValue(fixture.debtor))
                assertTrue(SettlementCalculator().calculateBalances(event, expenses).any { it.participantId == fixture.debtor })
            }
        }
    }

    @Test
    fun `rejoins participant without duplicating membership or history`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            manager.transaction { repositories ->
                val event = assertNotNull(repositories.events.findById(fixture.event))
                val participant = assertNotNull(repositories.participants.findById(fixture.debtor))
                event.removeParticipant(participant)
                repositories.events.save(event)
                event.addParticipant(participant)
                repositories.events.save(event)
                repositories.events.save(event)
            }
            manager.transaction { repositories ->
                assertEquals(1, repositories.events.findByParticipantId(fixture.debtor, activeOnly = true).size)
                assertEquals(setOf(fixture.event), assertNotNull(repositories.participants.findById(fixture.debtor)).getEventIds())
                assertEquals(fixture.participantIds, assertNotNull(repositories.events.findById(fixture.event)).getParticipantIds())
            }
        }
    }

    @Test
    fun `keeps maximum money and stored shares stable across repeated saves`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            val shares = manager.transaction { repositories ->
                val expense = assertNotNull(repositories.expenses.findById(fixture.expense))
                expense.update(amount = Money(UInt.MAX_VALUE))
                repositories.expenses.save(expense)
                repositories.expenses.save(expense)
                expense.getShares()
            }
            repeat(2) {
                manager.transaction { repositories ->
                    val expense = assertNotNull(repositories.expenses.findById(fixture.expense))
                    assertEquals(Money(UInt.MAX_VALUE), expense.getAmount())
                    assertEquals(shares, expense.getShares())
                    assertEquals(UInt.MAX_VALUE.toLong(), expense.getShares().values.sumOf { it.kopeck.toLong() })
                }
            }
        }
    }

    @Test
    fun `returns every record in stable uuid order`() {
        withTestTransactions { manager ->
            val fixtures = manager.transaction { repositories -> List(51) { saveFixture(repositories) } }
            manager.transaction { repositories ->
                assertEquals(fixtures.flatMap { it.participantIds }.sortedBy { it.toString() }, repositories.participants.findAll().map { it.getParticipantId() })
                assertEquals(fixtures.map { it.user }.sortedBy { it.toString() }, repositories.users.findAll().map { it.getId() })
                assertEquals(fixtures.map { it.event }.sortedBy { it.toString() }, repositories.events.findAll().map { it.getId() })
                assertEquals(fixtures.map { it.expense }.sortedBy { it.toString() }, repositories.expenses.findAll().map { it.getId() })
                val last = fixtures.last()
                assertEquals(listOf(last.event), repositories.events.findByParticipantId(last.author).map { it.getId() })
                assertEquals(listOf(last.expense), repositories.expenses.findByParticipantId(last.author).map { it.getId() })
            }
        }
    }

    @Test
    fun `returns null and empty results for missing entities and idempotent deletion`() {
        withTestTransactions { manager ->
            manager.transaction { repositories ->
                val id = Uuid.random()
                assertNull(repositories.participants.findById(id))
                assertNull(repositories.users.findById(id))
                assertNull(repositories.users.findByLogin("missing"))
                assertNull(repositories.events.findById(id))
                assertNull(repositories.expenses.findById(id))
                assertTrue(repositories.events.findByParticipantId(id).isEmpty())
                assertTrue(repositories.expenses.findByEventId(id).isEmpty())
                assertTrue(repositories.expenses.findByParticipantId(id).isEmpty())
                assertFalse(repositories.participants.delete(id))
                assertFalse(repositories.users.delete(id))
                assertFalse(repositories.events.delete(id))
                assertFalse(repositories.expenses.delete(id))
            }
        }
    }

    @Test
    fun `deletes expense from shared event and all histories including departed debtor`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            manager.transaction { repositories ->
                val event = assertNotNull(repositories.events.findById(fixture.event))
                event.removeParticipant(assertNotNull(repositories.participants.findById(fixture.debtor)))
                repositories.events.save(event)
                val participants = fixture.participantIds.map { assertNotNull(repositories.participants.findById(it)) }
                assertTrue(repositories.expenses.delete(fixture.expense))
                assertTrue(event.getExpenseIds().isEmpty())
                for (participant in participants) assertTrue(participant.getExpenseIds().isEmpty())
                assertNull(repositories.expenses.findById(fixture.expense))
                assertFalse(repositories.expenses.delete(fixture.expense))
            }
            manager.transaction { repositories ->
                assertTrue(repositories.expenses.findAll().isEmpty())
                for (id in fixture.participantIds) assertTrue(assertNotNull(repositories.participants.findById(id)).getExpenseIds().isEmpty())
            }
        }
    }

    @Test
    fun `deletes event without expenses clearing active and historical memberships`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            manager.transaction { repositories ->
                val event = assertNotNull(repositories.events.findById(fixture.event))
                event.removeParticipant(assertNotNull(repositories.participants.findById(fixture.debtor)))
                repositories.events.save(event)
                assertTrue(repositories.expenses.delete(fixture.expense))
                assertTrue(repositories.events.delete(fixture.event))
                assertNull(repositories.events.findById(fixture.event))
                for (id in fixture.participantIds) assertTrue(assertNotNull(repositories.participants.findById(id)).getEventIds().isEmpty())
                assertFalse(repositories.events.delete(fixture.event))
            }
            manager.transaction { repositories ->
                assertTrue(repositories.events.findAll().isEmpty())
                for (id in fixture.participantIds) assertTrue(repositories.events.findByParticipantId(id).isEmpty())
            }
        }
    }

    @Test
    fun `rejects deleting event with expenses and restores memberships on rollback`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            val failure = assertFailsWith<ConflictFailure> {
                manager.transaction { it.events.delete(fixture.event) }
            }
            assertEquals("Object is still referenced or a reference is missing.", failure.message)
            manager.transaction { repositories ->
                assertEquals(fixture.participantIds, assertNotNull(repositories.events.findById(fixture.event)).getParticipantIds())
                assertEquals(fixture.shares, assertNotNull(repositories.expenses.findById(fixture.expense)).getShares())
            }
        }
    }

    @Test
    fun `deletes user clearing shared participant link while preserving history`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            manager.transaction { repositories ->
                val payer = assertNotNull(repositories.participants.findById(fixture.payer))
                repositories.users.save(buildUser(payer, "payer"))
                val author = assertNotNull(repositories.participants.findById(fixture.author))
                assertTrue(repositories.users.delete(fixture.user))
                assertFalse(author.hasUserId())
                assertSame(author, repositories.participants.findById(fixture.author))
                assertEquals(setOf(fixture.event), author.getEventIds())
                assertEquals(setOf(fixture.expense), author.getExpenseIds())
                assertNull(repositories.users.findById(fixture.user))
                assertFalse(repositories.users.delete(fixture.user))
            }
            manager.transaction { repositories ->
                assertFalse(assertNotNull(repositories.participants.findById(fixture.author)).hasUserId())
                assertNotNull(repositories.events.findById(fixture.event))
                assertNotNull(repositories.expenses.findById(fixture.expense))
            }
        }
    }

    @Test
    fun `deletes unreferenced participant and rejects deleting referenced participant`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            manager.transaction { repositories ->
                val participant = Participant.build("Unused", "Person")
                repositories.participants.save(participant)
                assertTrue(repositories.participants.delete(participant.getParticipantId()))
                assertNull(repositories.participants.findById(participant.getParticipantId()))
                assertFalse(repositories.participants.delete(participant.getParticipantId()))
            }
            val failure = assertFailsWith<ConflictFailure> {
                manager.transaction { it.participants.delete(fixture.author) }
            }
            assertEquals("Object is still referenced or a reference is missing.", failure.message)
        }
    }

    @Test
    fun `rolls back several saved entities on application failure or duplicate login`() {
        withTestTransactions { manager ->
            val failure = IllegalStateException("Cannot complete scenario.")
            assertSame(failure, assertFailsWith<IllegalStateException> {
                manager.transaction { repositories ->
                    saveFixture(repositories)
                    throw failure
                }
            })
            manager.transaction { repositories ->
                assertTrue(repositories.participants.findAll().isEmpty())
                assertTrue(repositories.users.findAll().isEmpty())
                assertTrue(repositories.events.findAll().isEmpty())
                assertTrue(repositories.expenses.findAll().isEmpty())
            }
            val fixture = manager.transaction(::saveFixture)
            assertEquals("Value is already in use.", assertFailsWith<ConflictFailure> {
                manager.transaction { repositories ->
                    val event = assertNotNull(repositories.events.findById(fixture.event))
                    event.update("Must roll back")
                    repositories.events.save(event)
                    val participant = assertNotNull(repositories.participants.findById(fixture.replacement))
                    repositories.users.save(buildUser(participant, fixture.login))
                }
            }.message)
            manager.transaction { repositories ->
                assertEquals("Trip", assertNotNull(repositories.events.findById(fixture.event)).getName())
                assertFalse(assertNotNull(repositories.participants.findById(fixture.replacement)).hasUserId())
            }
        }
    }

    @Test
    fun `rejects replacing loaded instances or moving user to another participant`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            assertFailsWith<IllegalStateException> {
                manager.transaction { repositories ->
                    val participant = assertNotNull(repositories.participants.findById(fixture.author))
                    val another = Participant.restore("Another", "Name", fixture.author, fixture.user, participant.getEventIds(), participant.getExpenseIds())
                    repositories.participants.save(another)
                }
            }
            assertFailsWith<IllegalStateException> {
                manager.transaction { repositories ->
                    val participant = Participant.restore("Another", "Name", fixture.replacement, fixture.user, setOf(fixture.event), emptySet())
                    val user = User.Builder().restore("another@example.test", "another", "hash", participant, fixture.user, UserRole.USER)
                    repositories.users.save(user)
                }
            }
            manager.transaction { repositories ->
                assertEquals(fixture.author, assertNotNull(repositories.users.findById(fixture.user)).getParticipantId())
                assertEquals("Author", assertNotNull(repositories.participants.findById(fixture.author)).getFirstName())
            }
        }
    }

    @Test
    fun `rejects moving restored expense to another event or author`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            for (changeEvent in listOf(true, false)) {
                assertFailsWith<IllegalStateException> {
                    manager.transaction { repositories ->
                        val participants = fixture.participantIds.associateWith { assertNotNull(repositories.participants.findById(it)) }
                        val author = if (changeEvent) participants.getValue(fixture.author) else participants.getValue(fixture.replacement)
                        if (!changeEvent) repositories.users.save(buildUser(author, "new-author"))
                        val event = if (changeEvent) {
                            Event.Builder().withName("Another").withParticipants(participants.values.toSet()).build().also { repositories.events.save(it) }
                        } else {
                            assertNotNull(repositories.events.findById(fixture.event))
                        }
                        val expense = Expense.Builder().restore(
                            "Lunch", Money(101u), participants.getValue(fixture.payer),
                            setOf(participants.getValue(fixture.payer), participants.getValue(fixture.debtor)),
                            author, event, fixture.shares, fixture.expense
                        )
                        repositories.expenses.save(expense)
                    }
                }
            }
            manager.transaction { repositories ->
                val expense = assertNotNull(repositories.expenses.findById(fixture.expense))
                assertEquals(fixture.event, expense.getEventId())
                assertEquals(fixture.author, expense.getAuthorId())
                assertEquals(fixture.shares, expense.getShares())
                assertEquals(1, repositories.events.findAll().size)
            }
        }
    }

    @Test
    fun `uses bound login parameters and prevents repository access after transaction`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            lateinit var leaked: Repositories
            manager.transaction { repositories ->
                val participant = assertNotNull(repositories.participants.findById(fixture.replacement))
                repositories.users.save(buildUser(participant, "o'connor"))
                assertNotNull(repositories.users.findByLogin("o'connor"))
                assertNull(repositories.users.findByLogin("x' OR '1'='1"))
                repositories.users.findById(fixture.user)
                repositories.events.findById(fixture.event)
                repositories.expenses.findById(fixture.expense)
                leaked = repositories
            }
            assertFailsWith<IllegalStateException> { leaked.participants.findById(fixture.author) }
            assertFailsWith<IllegalStateException> { leaked.users.findById(fixture.user) }
            assertFailsWith<IllegalStateException> { leaked.events.findById(fixture.event) }
            assertFailsWith<IllegalStateException> { leaked.expenses.findById(fixture.expense) }
        }
    }

    @Test
    fun `unlink and event history cleanup preserve unrelated participant state`() {
        val participant = Participant.build("First", "Last")
        val user = buildUser(participant, "name")
        val event = Event.Builder().withName("Event").addParticipant(participant).build()
        assertFailsWith<IllegalStateException> { participant.unlinkUser(Uuid.random()) }
        assertEquals(user.getId(), participant.getUserId())
        participant.removeEventHistory(Uuid.random())
        assertEquals(setOf(event.getId()), participant.getEventIds())
        participant.unlinkUser(user.getId())
        assertFalse(participant.hasUserId())
        assertEquals("First", participant.getFirstName())
        participant.removeEventHistory(event.getId())
        participant.removeEventHistory(event.getId())
        assertTrue(participant.getEventIds().isEmpty())
    }

    @Test
    fun `reads event and expenses from one snapshot across concurrent commit`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            manager.transaction { first ->
                val event = assertNotNull(first.events.findById(fixture.event))
                manager.transaction { second ->
                    val expense = assertNotNull(second.expenses.findById(fixture.expense))
                    expense.update(amount = Money(202u))
                    second.expenses.save(expense)
                }
                val expenses = first.expenses.findByEventId(event.getId())
                assertEquals(Money(101u), expenses.single().getAmount())
                assertEquals(fixture.shares, expenses.single().getShares())
                assertEquals(fixture.participantIds.size, SettlementCalculator().calculateBalances(event, expenses).size)
            }
            manager.transaction { repositories ->
                assertEquals(Money(202u), assertNotNull(repositories.expenses.findById(fixture.expense)).getAmount())
            }
        }
    }

    @Test
    fun `rejects conflicting concurrent edits without automatic retry`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            val read = CountDownLatch(2)
            val release = CountDownLatch(1)
            val committed = CountDownLatch(1)
            val attempts = AtomicInteger()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<Unit> {
                    manager.transaction { repositories ->
                        val participant = assertNotNull(repositories.participants.findById(fixture.author))
                        read.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Concurrent edit timed out." }
                        participant.updateName(firstName = "First")
                        repositories.participants.save(participant)
                    }
                    committed.countDown()
                }
                val second = executor.submit<Unit> {
                    manager.transaction { repositories ->
                        attempts.incrementAndGet()
                        val participant = assertNotNull(repositories.participants.findById(fixture.author))
                        read.countDown()
                        check(committed.await(10, TimeUnit.SECONDS)) { "Concurrent edit timed out." }
                        participant.updateName(firstName = "Second")
                        repositories.participants.save(participant)
                    }
                }
                assertTrue(read.await(10, TimeUnit.SECONDS))
                release.countDown()
                first.get(10, TimeUnit.SECONDS)
                val failure = assertFailsWith<ExecutionException> { second.get(10, TimeUnit.SECONDS) }
                assertEquals("Concurrent transaction conflict.", assertIs<ConflictFailure>(failure.cause).message)
                assertEquals(1, attempts.get())
                manager.transaction { repositories ->
                    assertEquals("First", assertNotNull(repositories.participants.findById(fixture.author)).getFirstName())
                }
            } finally {
                release.countDown()
                committed.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `rolls back deletions and restores complete graph in next transaction`() {
        withTestTransactions { manager ->
            val fixture = manager.transaction(::saveFixture)
            assertFailsWith<IllegalStateException> {
                manager.transaction { repositories ->
                    assertTrue(repositories.expenses.delete(fixture.expense))
                    assertTrue(repositories.events.delete(fixture.event))
                    assertTrue(repositories.users.delete(fixture.user))
                    assertTrue(repositories.participants.delete(fixture.author))
                    throw IllegalStateException("Cannot complete deletion.")
                }
            }
            manager.transaction { repositories ->
                assertNotNull(repositories.users.findById(fixture.user))
                val participant = assertNotNull(repositories.participants.findById(fixture.author))
                assertEquals(fixture.user, participant.getUserId())
                assertEquals(setOf(fixture.event), participant.getEventIds())
                assertEquals(setOf(fixture.expense), participant.getExpenseIds())
                assertEquals(fixture.participantIds, assertNotNull(repositories.events.findById(fixture.event)).getParticipantIds())
                assertEquals(fixture.shares, assertNotNull(repositories.expenses.findById(fixture.expense)).getShares())
            }
        }
    }

    private fun saveFixture(repositories: Repositories): Fixture {
        val author = Participant.build("Author", "Person")
        val payer = Participant.build("Payer", "Guest")
        val debtor = Participant.build("Debtor", "Guest")
        val replacement = Participant.build("Another", "Guest")
        val login = "author${Uuid.random()}"
        val user = buildUser(author, login).also { it.changeRole(UserRole.ADMIN) }
        val participants = setOf(author, payer, debtor, replacement)
        val event = Event.Builder().withName("Trip").withParticipants(participants).build()
        val expense = Expense.Builder().withName("Lunch").withAmount(Money(101u))
            .withAuthor(author).withPayer(payer).withDebtors(setOf(payer, debtor)).withEvent(event).build()
        for (participant in participants) repositories.participants.save(participant)
        repositories.users.save(user)
        repositories.events.save(event)
        repositories.expenses.save(expense)
        return Fixture(user.getId(), login, author.getParticipantId(), payer.getParticipantId(), debtor.getParticipantId(), replacement.getParticipantId(), event.getId(), expense.getId(), expense.getShares())
    }

    private fun buildUser(participant: Participant, login: String): User {
        return User.Builder().withParticipant(participant).withEmail("author@example.test")
            .withLogin(login).withPassword("test-hash").build()
    }

    private data class Fixture(
        val user: Uuid,
        val login: String,
        val author: Uuid,
        val payer: Uuid,
        val debtor: Uuid,
        val replacement: Uuid,
        val event: Uuid,
        val expense: Uuid,
        val shares: Map<Uuid, Money>
    ) {
        val participantIds: Set<Uuid>
            get() = setOf(author, payer, debtor, replacement)
    }
}
