package squareup.persistence

import squareup.application.event.EventService
import squareup.application.exception.AccessDeniedFailure
import squareup.application.exception.AuthenticationFailure
import squareup.application.exception.ConflictFailure
import squareup.application.exception.NotFoundFailure
import squareup.application.exception.ValidationFailure
import squareup.application.expense.ExpenseCreation
import squareup.application.expense.ExpenseProfile
import squareup.application.expense.ExpenseService
import squareup.application.expense.ExpenseUpdate
import squareup.application.participant.ParticipantService
import squareup.application.security.AccessPolicy
import squareup.application.security.SessionManager
import squareup.application.transaction.TransactionManager
import squareup.application.repository.Repositories
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class EventExpenseServiceTest {
    @Test
    fun `creator joins event automatically while admin can create for other participants`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, " Trip ", setOf(fixture.second.participantId))
            assertEquals("Trip", event.name)
            assertEquals(setOf(fixture.owner.participantId, fixture.second.participantId), event.participantIds)
            val createdByAdmin = fixture.events.create(fixture.admin.session, "Other", setOf(fixture.second.participantId))
            assertEquals(setOf(fixture.second.participantId), createdByAdmin.participantIds)
            assertTrue(fixture.participants.findById(fixture.admin.session, fixture.admin.participantId).eventIds.isEmpty())
            assertFailsWith<ValidationFailure> { fixture.events.create(fixture.admin.session, "Empty") }
            assertFailsWith<ValidationFailure> { fixture.events.create(fixture.owner.session, " ") }
            assertFailsWith<NotFoundFailure> { fixture.events.create(fixture.owner.session, "Unknown", setOf(Uuid.random())) }
            assertEquals(2, fixture.events.findAll(fixture.admin.session).size)
        }
    }

    @Test
    fun `current member creates guest inside event without global participant permissions`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip")
            val guest = fixture.events.createGuest(fixture.owner.session, event.id, "Guest", "Person")
            assertNull(guest.userId)
            assertEquals(setOf(event.id), guest.eventIds)
            assertTrue(guest.expenseIds.isEmpty())
            assertTrue(guest.id in fixture.events.findById(fixture.owner.session, event.id).participantIds)
            val count = fixture.participants.findAll(fixture.admin.session).size
            assertFailsWith<ValidationFailure> { fixture.events.createGuest(fixture.owner.session, event.id, "", "Person") }
            assertFailsWith<AccessDeniedFailure> { fixture.events.createGuest(fixture.outsider.session, event.id, "Other", "Person") }
            assertFailsWith<AccessDeniedFailure> { fixture.participants.create(fixture.owner.session, "Global", "Person") }
            assertEquals(count, fixture.participants.findAll(fixture.admin.session).size)
            val another = fixture.events.createGuest(fixture.admin.session, event.id, "Admin guest", "Person")
            assertTrue(another.id in fixture.events.findById(fixture.owner.session, event.id).participantIds)
        }
    }

    @Test
    fun `event editing is shared by members and denied to outsiders`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip", setOf(fixture.second.participantId))
            assertEquals("Changed", fixture.events.update(fixture.second.session, event.id, "Changed").name)
            assertEquals("Admin edit", fixture.events.update(fixture.admin.session, event.id, "Admin edit").name)
            assertFailsWith<AccessDeniedFailure> { fixture.events.update(fixture.outsider.session, event.id, "Forbidden") }
            assertFailsWith<AccessDeniedFailure> { fixture.events.findById(fixture.outsider.session, event.id) }
            assertFailsWith<AccessDeniedFailure> { fixture.events.settlement(fixture.outsider.session, event.id) }
            assertFailsWith<AuthenticationFailure> { fixture.events.findAll("unknown") }
            assertFailsWith<ValidationFailure> { fixture.events.update(fixture.owner.session, event.id, " ") }
            assertEquals("Admin edit", fixture.events.findById(fixture.owner.session, event.id).name)
            assertFailsWith<NotFoundFailure> { fixture.events.findById(fixture.owner.session, Uuid.random()) }
            fixture.events.create(fixture.outsider.session, "Private")
            assertEquals(1, fixture.events.findAll(fixture.owner.session).size)
            assertEquals(2, fixture.events.findAll(fixture.admin.session).size)
        }
    }

    @Test
    fun `membership changes are idempotent and exiting preserves read access and history`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip")
            fixture.events.addParticipant(fixture.owner.session, event.id, fixture.second.participantId)
            val repeated = fixture.events.addParticipant(fixture.owner.session, event.id, fixture.second.participantId)
            assertEquals(2, repeated.participantIds.size)
            fixture.events.removeParticipant(fixture.owner.session, event.id, fixture.owner.participantId)
            fixture.events.removeParticipant(fixture.second.session, event.id, fixture.owner.participantId)
            assertEquals(setOf(fixture.second.participantId), fixture.events.findById(fixture.owner.session, event.id).participantIds)
            assertEquals(setOf(event.id), fixture.participants.findById(fixture.owner.session, fixture.owner.participantId).eventIds)
            assertEquals(listOf(event.id), fixture.events.findAll(fixture.owner.session).map { it.id })
            assertTrue(fixture.events.settlement(fixture.owner.session, event.id).transfers.isEmpty())
            assertFailsWith<AccessDeniedFailure> { fixture.events.update(fixture.owner.session, event.id, "Forbidden") }
            assertFailsWith<AccessDeniedFailure> { fixture.events.addParticipant(fixture.owner.session, event.id, fixture.outsider.participantId) }
            assertFailsWith<ConflictFailure> { fixture.events.removeParticipant(fixture.admin.session, event.id, fixture.second.participantId) }
            assertFailsWith<NotFoundFailure> { fixture.events.addParticipant(fixture.second.session, event.id, Uuid.random()) }
            fixture.events.addParticipant(fixture.second.session, event.id, fixture.owner.participantId)
            assertEquals("Back", fixture.events.update(fixture.owner.session, event.id, "Back").name)
            assertEquals(setOf(event.id), fixture.participants.findById(fixture.owner.session, fixture.owner.participantId).eventIds)
        }
    }

    @Test
    fun `creates guest expenses with actor as author and permits admin author outside event`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip")
            val payer = fixture.events.createGuest(fixture.owner.session, event.id, "Payer", "Guest")
            val debtor = fixture.events.createGuest(fixture.owner.session, event.id, "Debtor", "Guest")
            val expense = fixture.expenses.create(fixture.owner.session, event.id, ExpenseCreation("Lunch", Money(101u), payer.id, setOf(payer.id, debtor.id)))
            assertEquals(fixture.owner.participantId, expense.authorId)
            assertEquals(101uL, expense.shares.values.sumOf { it.kopeck.toULong() })
            assertEquals(setOf(expense.id), fixture.participants.findById(fixture.owner.session, fixture.owner.participantId).expenseIds)
            val adminExpense = fixture.expenses.create(fixture.admin.session, event.id, ExpenseCreation("Dinner", Money(50u), payer.id, setOf(debtor.id)))
            assertEquals(fixture.admin.participantId, adminExpense.authorId)
            assertFalse(fixture.admin.participantId in fixture.events.findById(fixture.admin.session, event.id).participantIds)
            val admin = fixture.participants.findById(fixture.admin.session, fixture.admin.participantId)
            assertTrue(admin.eventIds.isEmpty())
            assertEquals(setOf(adminExpense.id), admin.expenseIds)
            assertEquals(adminExpense, fixture.expenses.findById(fixture.admin.session, adminExpense.id))
            assertEquals(setOf(expense.id, adminExpense.id), fixture.participants.findById(fixture.admin.session, debtor.id).expenseIds)
        }
    }

    @Test
    fun `rejects invalid expense creation without changing event or histories`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip")
            val guest = fixture.events.createGuest(fixture.owner.session, event.id, "Guest", "Person")
            val valid = ExpenseCreation("Lunch", Money(101u), guest.id, setOf(guest.id))
            assertFailsWith<AccessDeniedFailure> { fixture.expenses.create(fixture.outsider.session, event.id, valid) }
            val invalid = listOf(
                valid.copy(name = " "), valid.copy(amount = Money(0u)), valid.copy(debtorIds = emptySet()),
                valid.copy(payerId = fixture.outsider.participantId), valid.copy(debtorIds = setOf(fixture.outsider.participantId))
            )
            for (creation in invalid) assertFailsWith<ValidationFailure> { fixture.expenses.create(fixture.owner.session, event.id, creation) }
            assertFailsWith<NotFoundFailure> { fixture.expenses.create(fixture.owner.session, event.id, valid.copy(payerId = Uuid.random())) }
            assertTrue(fixture.events.findById(fixture.owner.session, event.id).expenseIds.isEmpty())
            assertTrue(fixture.participants.findById(fixture.owner.session, fixture.owner.participantId).expenseIds.isEmpty())
            assertTrue(fixture.participants.findById(fixture.admin.session, guest.id).expenseIds.isEmpty())
        }
    }

    @Test
    fun `another current member can edit all expense fields except event and author`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip", setOf(fixture.second.participantId))
            val guest = fixture.events.createGuest(fixture.owner.session, event.id, "Guest", "Person")
            val expense = fixture.expenses.create(fixture.owner.session, event.id, ExpenseCreation("Lunch", Money(101u), guest.id, setOf(guest.id)))
            val edited = fixture.expenses.update(fixture.second.session, expense.id, ExpenseUpdate("Dinner", Money(202u), fixture.second.participantId, setOf(fixture.second.participantId)))
            assertEquals("Dinner", edited.name)
            assertEquals(Money(202u), edited.amount)
            assertEquals(fixture.second.participantId, edited.payerId)
            assertEquals(mapOf(fixture.second.participantId to Money(202u)), edited.shares)
            assertEquals(event.id, edited.eventId)
            assertEquals(fixture.owner.participantId, edited.authorId)
            assertTrue(fixture.participants.findById(fixture.admin.session, guest.id).expenseIds.isEmpty())
            assertEquals(setOf(expense.id), fixture.participants.findById(fixture.owner.session, fixture.owner.participantId).expenseIds)
            assertEquals(setOf(expense.id), fixture.participants.findById(fixture.second.session, fixture.second.participantId).expenseIds)
            assertEquals(edited, fixture.expenses.findById(fixture.owner.session, expense.id))
        }
    }

    @Test
    fun `retains exited debtor on amount change and rejects newly introduced outsiders`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip", setOf(fixture.second.participantId))
            val expense = fixture.expenses.create(fixture.owner.session, event.id, ExpenseCreation("Lunch", Money(101u), fixture.owner.participantId, setOf(fixture.second.participantId)))
            fixture.events.removeParticipant(fixture.second.session, event.id, fixture.second.participantId)
            val edited = fixture.expenses.update(fixture.owner.session, expense.id, ExpenseUpdate(amount = Money(202u)))
            assertEquals(mapOf(fixture.second.participantId to Money(202u)), edited.shares)
            assertEquals(edited, fixture.expenses.findById(fixture.second.session, expense.id))
            assertEquals(listOf(edited), fixture.expenses.findByEvent(fixture.second.session, event.id))
            assertFailsWith<AccessDeniedFailure> { fixture.expenses.update(fixture.second.session, expense.id, ExpenseUpdate(name = "Forbidden")) }
            assertFailsWith<AccessDeniedFailure> { fixture.expenses.delete(fixture.second.session, expense.id) }
            assertFailsWith<AccessDeniedFailure> { fixture.expenses.create(fixture.second.session, event.id, ExpenseCreation("Forbidden", Money(1u), fixture.owner.participantId, setOf(fixture.owner.participantId))) }
            assertFailsWith<ValidationFailure> { fixture.expenses.update(fixture.owner.session, expense.id, ExpenseUpdate(debtorIds = setOf(fixture.outsider.participantId))) }
            assertFailsWith<ValidationFailure> { fixture.expenses.update(fixture.admin.session, expense.id, ExpenseUpdate(payerId = fixture.admin.participantId)) }
            assertEquals(edited, fixture.expenses.findById(fixture.owner.session, expense.id))
        }
    }

    @Test
    fun `keeps exact saved shares for no-op name and payer changes`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip", setOf(fixture.second.participantId))
            val guest = fixture.events.createGuest(fixture.owner.session, event.id, "Guest", "Person")
            val expense = fixture.expenses.create(fixture.admin.session, event.id, ExpenseCreation("Lunch", Money(101u), guest.id, setOf(guest.id, fixture.owner.participantId)))
            assertEquals(expense, fixture.expenses.update(fixture.owner.session, expense.id, ExpenseUpdate()))
            assertEquals(expense.shares, fixture.expenses.update(fixture.owner.session, expense.id, ExpenseUpdate(name = "Renamed")).shares)
            val edited = fixture.expenses.update(fixture.second.session, expense.id, ExpenseUpdate(payerId = fixture.second.participantId))
            assertEquals(expense.shares, edited.shares)
            assertEquals(expense.authorId, edited.authorId)
            assertEquals(expense.eventId, edited.eventId)
            assertEquals(edited, fixture.expenses.findById(fixture.owner.session, expense.id))
            assertFailsWith<ValidationFailure> { fixture.expenses.update(fixture.owner.session, expense.id, ExpenseUpdate(name = "Invalid change", amount = Money(0u))) }
            assertFailsWith<ValidationFailure> { fixture.expenses.update(fixture.owner.session, expense.id, ExpenseUpdate(debtorIds = emptySet())) }
            assertFailsWith<NotFoundFailure> { fixture.expenses.update(fixture.owner.session, expense.id, ExpenseUpdate(payerId = Uuid.random())) }
            assertEquals(edited, fixture.expenses.findById(fixture.owner.session, expense.id))
        }
    }

    @Test
    fun `reads event and own expense history while restricting global lists and outsiders`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip", setOf(fixture.second.participantId))
            val expense = fixture.expenses.create(fixture.owner.session, event.id, ExpenseCreation("Lunch", Money(100u), fixture.owner.participantId, setOf(fixture.owner.participantId)))
            assertEquals(expense, fixture.expenses.findById(fixture.second.session, expense.id))
            assertEquals(listOf(expense), fixture.expenses.findByEvent(fixture.second.session, event.id))
            assertEquals(listOf(expense), fixture.expenses.findByParticipant(fixture.owner.session, fixture.owner.participantId))
            assertTrue(fixture.expenses.findByParticipant(fixture.second.session, fixture.second.participantId).isEmpty())
            assertEquals(listOf(expense), fixture.expenses.findByParticipant(fixture.admin.session, fixture.owner.participantId))
            assertEquals(listOf(expense), fixture.expenses.findAll(fixture.admin.session))
            assertFailsWith<AccessDeniedFailure> { fixture.expenses.findAll(fixture.owner.session) }
            assertFailsWith<AccessDeniedFailure> { fixture.expenses.findByParticipant(fixture.second.session, fixture.owner.participantId) }
            assertFailsWith<AccessDeniedFailure> { fixture.expenses.findById(fixture.outsider.session, expense.id) }
            assertFailsWith<AccessDeniedFailure> { fixture.expenses.findByEvent(fixture.outsider.session, event.id) }
            assertFailsWith<AccessDeniedFailure> { fixture.expenses.delete(fixture.outsider.session, expense.id) }
            assertFailsWith<NotFoundFailure> { fixture.expenses.findByParticipant(fixture.admin.session, Uuid.random()) }
            assertFailsWith<NotFoundFailure> { fixture.expenses.findById(fixture.owner.session, Uuid.random()) }
            assertFailsWith<AuthenticationFailure> { fixture.expenses.findByEvent("unknown", event.id) }
        }
    }

    @Test
    fun `settlement uses all expenses including exited participants and completely settles balances`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip", setOf(fixture.second.participantId))
            val guest = fixture.events.createGuest(fixture.owner.session, event.id, "Guest", "Person")
            fixture.expenses.create(fixture.owner.session, event.id, ExpenseCreation("Lunch", Money(100u), guest.id, setOf(guest.id, fixture.owner.participantId)))
            fixture.expenses.create(fixture.second.session, event.id, ExpenseCreation("Taxi", Money(40u), fixture.second.participantId, setOf(guest.id)))
            fixture.events.removeParticipant(fixture.owner.session, event.id, guest.id)
            val settlement = fixture.events.settlement(fixture.owner.session, event.id)
            val balances = settlement.balances.associate { it.participantId to it.debtKopecks }
            assertEquals(mapOf(fixture.owner.participantId to -50L, fixture.second.participantId to 40L, guest.id to 10L), balances)
            assertEquals(settlement, fixture.events.settlement(fixture.admin.session, event.id))
            val remaining = balances.toMutableMap()
            for (transfer in settlement.transfers) {
                remaining[transfer.fromParticipantId] = remaining.getValue(transfer.fromParticipantId) + transfer.amountKopecks
                remaining[transfer.toParticipantId] = remaining.getValue(transfer.toParticipantId) - transfer.amountKopecks
            }
            assertTrue(remaining.values.all { it == 0L })
        }
    }

    @Test
    fun `lists and calculation include every saved expense`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip")
            val guest = fixture.events.createGuest(fixture.owner.session, event.id, "Guest", "Person")
            repeat(51) {
                fixture.expenses.create(fixture.owner.session, event.id, ExpenseCreation("Expense $it", Money(1u), fixture.owner.participantId, setOf(guest.id)))
            }
            assertEquals(51, fixture.expenses.findByEvent(fixture.owner.session, event.id).size)
            assertEquals(51, fixture.expenses.findAll(fixture.admin.session).size)
            val balances = fixture.events.settlement(fixture.owner.session, event.id).balances.associate { it.participantId to it.debtKopecks }
            assertEquals(mapOf(fixture.owner.participantId to 51L, guest.id to -51L), balances)
            assertEquals(51, fixture.expenses.findByParticipant(fixture.owner.session, fixture.owner.participantId).size)
        }
    }

    @Test
    fun `deleting expense clears current and historical roles including outside admin author`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip")
            val guest = fixture.events.createGuest(fixture.owner.session, event.id, "Guest", "Person")
            val expense = fixture.expenses.create(fixture.admin.session, event.id, ExpenseCreation("Lunch", Money(100u), guest.id, setOf(guest.id)))
            fixture.events.removeParticipant(fixture.owner.session, event.id, guest.id)
            fixture.expenses.delete(fixture.owner.session, expense.id)
            assertTrue(fixture.events.findById(fixture.owner.session, event.id).expenseIds.isEmpty())
            assertTrue(fixture.participants.findById(fixture.admin.session, guest.id).expenseIds.isEmpty())
            assertTrue(fixture.participants.findById(fixture.admin.session, fixture.admin.participantId).expenseIds.isEmpty())
            assertTrue(fixture.expenses.findByEvent(fixture.owner.session, event.id).isEmpty())
            assertFailsWith<NotFoundFailure> { fixture.expenses.delete(fixture.admin.session, expense.id) }
            assertTrue(fixture.events.settlement(fixture.owner.session, event.id).transfers.isEmpty())
        }
    }

    @Test
    fun `event deletion requires no expenses and clears historical memberships`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip", setOf(fixture.second.participantId))
            val guest = fixture.events.createGuest(fixture.owner.session, event.id, "Guest", "Person")
            val expense = fixture.expenses.create(fixture.owner.session, event.id, ExpenseCreation("Lunch", Money(100u), guest.id, setOf(guest.id)))
            fixture.events.removeParticipant(fixture.owner.session, event.id, fixture.second.participantId)
            assertFailsWith<ConflictFailure> { fixture.events.delete(fixture.admin.session, event.id) }
            assertFailsWith<AccessDeniedFailure> { fixture.events.delete(fixture.outsider.session, event.id) }
            fixture.expenses.delete(fixture.admin.session, expense.id)
            fixture.events.delete(fixture.owner.session, event.id)
            assertFailsWith<NotFoundFailure> { fixture.events.findById(fixture.admin.session, event.id) }
            assertFailsWith<NotFoundFailure> { fixture.events.delete(fixture.admin.session, event.id) }
            assertTrue(fixture.participants.findById(fixture.owner.session, fixture.owner.participantId).eventIds.isEmpty())
            assertTrue(fixture.participants.findById(fixture.second.session, fixture.second.participantId).eventIds.isEmpty())
            assertTrue(fixture.participants.findById(fixture.admin.session, guest.id).eventIds.isEmpty())
            fixture.participants.delete(fixture.admin.session, guest.id)
        }
    }

    @Test
    fun `rolls back guest creation and expense edits with all history changes`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip", setOf(fixture.second.participantId))
            val guest = fixture.events.createGuest(fixture.owner.session, event.id, "Guest", "Person")
            val expense = fixture.expenses.create(fixture.owner.session, event.id, ExpenseCreation("Lunch", Money(101u), guest.id, setOf(guest.id)))
            val failing = object : TransactionManager {
                override fun <T> transaction(block: (Repositories) -> T): T = fixture.transactions.transaction { repositories ->
                    block(repositories)
                    throw ConflictFailure("Cannot commit this scenario.")
                }
            }
            val count = fixture.participants.findAll(fixture.admin.session).size
            assertFailsWith<ConflictFailure> { EventService(failing, fixture.access).createGuest(fixture.owner.session, event.id, "Unsaved", "Guest") }
            assertEquals(count, fixture.participants.findAll(fixture.admin.session).size)
            assertEquals(setOf(fixture.owner.participantId, fixture.second.participantId, guest.id), fixture.events.findById(fixture.owner.session, event.id).participantIds)
            assertFailsWith<ConflictFailure> {
                ExpenseService(failing, fixture.access).update(fixture.owner.session, expense.id, ExpenseUpdate("Unsaved", Money(202u), fixture.second.participantId, setOf(fixture.second.participantId)))
            }
            assertEquals(expense, fixture.expenses.findById(fixture.owner.session, expense.id))
            assertEquals(setOf(expense.id), fixture.participants.findById(fixture.admin.session, guest.id).expenseIds)
            assertTrue(fixture.participants.findById(fixture.second.session, fixture.second.participantId).expenseIds.isEmpty())
        }
    }

    @Test
    fun `conflicting expense updates produce one commit and one conflict without retry`() {
        withServices { fixture ->
            val event = fixture.events.create(fixture.owner.session, "Trip")
            val expense = fixture.expenses.create(fixture.owner.session, event.id, ExpenseCreation("Lunch", Money(101u), fixture.owner.participantId, setOf(fixture.owner.participantId)))
            val read = CountDownLatch(2)
            val release = CountDownLatch(1)
            val attempts = AtomicInteger()
            val concurrent = object : TransactionManager {
                override fun <T> transaction(block: (Repositories) -> T): T = fixture.transactions.transaction { repositories ->
                    attempts.incrementAndGet()
                    assertNotNull(repositories.expenses.findById(expense.id))
                    read.countDown()
                    check(release.await(15, TimeUnit.SECONDS)) { "Concurrent edit timed out." }
                    block(repositories)
                }
            }
            val service = ExpenseService(concurrent, fixture.access)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<ExpenseProfile> { service.update(fixture.owner.session, expense.id, ExpenseUpdate(amount = Money(202u))) }
                val second = executor.submit<ExpenseProfile> { service.update(fixture.owner.session, expense.id, ExpenseUpdate(amount = Money(303u))) }
                assertTrue(read.await(15, TimeUnit.SECONDS))
                release.countDown()
                var committed = 0
                for (future in listOf(first, second)) {
                    try {
                        future.get(15, TimeUnit.SECONDS)
                        committed++
                    } catch (failure: ExecutionException) {
                        assertIs<ConflictFailure>(failure.cause)
                    }
                }
                assertEquals(1, committed)
                assertEquals(2, attempts.get())
                val saved = fixture.expenses.findById(fixture.owner.session, expense.id)
                assertTrue(saved.amount in setOf(Money(202u), Money(303u)))
                assertEquals(saved.amount, saved.shares.getValue(fixture.owner.participantId))
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            }
        }
    }

    private fun withServices(block: (Fixture) -> Unit) =
        withTestTransactions { block(Fixture(it)) }

    private class Actor(val participantId: Uuid, val session: String)

    private class Fixture(val transactions: TransactionManager) {
        private val sessions = SessionManager()
        val access = AccessPolicy(sessions)
        val participants = ParticipantService(transactions, access)
        val events = EventService(transactions, access)
        val expenses = ExpenseService(transactions, access)
        val admin = actor("Admin", UserRole.ADMIN)
        val owner = actor("Owner")
        val second = actor("Second")
        val outsider = actor("Outsider")

        private fun actor(name: String, role: UserRole = UserRole.USER): Actor {
            val user = transactions.transaction { repositories ->
                val participant = Participant.build(name, "Person")
                val user = User.Builder().withParticipant(participant).withEmail("$name@example.test").withLogin(name)
                    .withPassword("fixture-hash").withRole(role).build()
                repositories.participants.save(participant)
                repositories.users.save(user)
                user
            }
            return Actor(user.getParticipantId(), sessions.create(user.getId()).id)
        }
    }
}
