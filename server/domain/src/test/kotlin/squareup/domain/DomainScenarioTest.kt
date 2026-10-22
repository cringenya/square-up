package squareup.domain

import squareup.domain.event.Event
import squareup.domain.exception.LastRegisteredParticipantException
import squareup.domain.expense.Expense
import squareup.domain.money.Money
import squareup.domain.user.Participant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DomainScenarioTest {
    @Test
    fun linkingTheSameUserKeepsTheOriginalLink() {
        val participant = Participant.build("Anna", "Ivanova")
        val user = createUser(participant)

        participant.linkUser(user)

        assertEquals(user.getId(), participant.getUserId())
        assertEquals(participant.getParticipantId(), user.getParticipantId())
    }

    @Test
    fun creatingAnotherUserForOccupiedParticipantPreservesTheOriginalLink() {
        val participant = Participant.build("Anna", "Ivanova")
        val user = createUser(participant)

        val failure = assertFailsWith<IllegalStateException> { createUser(participant) }

        assertEquals("Participant is already linked to another user.", failure.message)
        assertEquals(user.getId(), participant.getUserId())
        assertEquals(participant.getParticipantId(), user.getParticipantId())
    }

    @Test
    fun linkingUserOfAnotherParticipantIsRejectedWithoutChangingTheTarget() {
        val participant = Participant.build("Anna", "Ivanova")
        val otherParticipant = Participant.build("Pavel", "Petrov")
        val user = createUser(otherParticipant)

        assertFailsWith<IllegalArgumentException> { participant.linkUser(user) }

        assertFalse(participant.hasUserId())
        assertEquals(user.getId(), otherParticipant.getUserId())
    }

    @Test
    fun registrationPreservesParticipantIdentityAndHistory() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, guest, author, setOf(guest))
        val participantId = guest.getParticipantId()

        val user = createUser(guest)

        assertEquals(participantId, user.getParticipantId())
        assertEquals(setOf(event.getId()), guest.getEventIds())
        assertEquals(setOf(expense.getId()), guest.getExpenseIds())
        assertEquals("Pavel", user.getFirstName())
        assertEquals("Petrov", user.getLastName())
    }

    @Test
    fun participantRestoreCopiesHistoryAndPreservesIdentifiers() {
        val participantId = Uuid.random()
        val userId = Uuid.random()
        val eventId = Uuid.random()
        val expenseId = Uuid.random()
        val eventIds = mutableSetOf(eventId)
        val expenseIds = mutableSetOf(expenseId)

        val participant = Participant.restore(
            "Anna", "Ivanova", participantId, userId, eventIds, expenseIds
        )
        eventIds.clear()
        expenseIds.clear()

        assertEquals(participantId, participant.getParticipantId())
        assertEquals(userId, participant.getUserId())
        assertEquals(setOf(eventId), participant.getEventIds())
        assertEquals(setOf(expenseId), participant.getExpenseIds())
    }

    @Test
    fun eventCreationAndAddingParticipantRecordHistory() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author)

        event.addParticipant(guest)
        event.addParticipant(guest)

        assertEquals(setOf(event.getId()), author.getEventIds())
        assertEquals(setOf(event.getId()), guest.getEventIds())
        assertTrue(event.hasParticipant(guest))
    }

    @Test
    fun participantCannotRecordAnEventWithoutMembership() {
        val author = registeredParticipant("Anna")
        val outsider = Participant.build("Pavel", "Petrov")
        val event = createEvent(author)

        assertFailsWith<IllegalArgumentException> { outsider.addEvent(event) }

        assertTrue(outsider.getEventIds().isEmpty())
    }

    @Test
    fun leavingEventPreservesHistoricalExpensesAndShares() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest))
        val shares = expense.getShares()

        event.removeParticipant(guest)

        assertFalse(event.hasParticipant(guest))
        assertEquals(setOf(event.getId()), guest.getEventIds())
        assertEquals(setOf(expense.getId()), guest.getExpenseIds())
        assertEquals(shares, expense.getShares())
        assertTrue(guest.getParticipantId() in expense.getDebtorIds())
    }

    @Test
    fun removingTheLastRegisteredParticipantIsRejected() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)

        assertFailsWith<LastRegisteredParticipantException> { event.removeParticipant(author) }

        assertTrue(event.hasParticipant(author))
        assertTrue(event.hasParticipant(guest))
        assertEquals(setOf(event.getId()), author.getEventIds())
    }

    @Test
    fun addingTheSameExpenseTwiceDoesNotDuplicateItOrChangeShares() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest), Money(101u))
        val shares = expense.getShares()

        event.addExpense(expense)

        assertEquals(listOf(expense.getId()), event.getExpenseIds())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertEquals(setOf(expense.getId()), guest.getExpenseIds())
        assertEquals(shares, expense.getShares())
    }

    @Test
    fun repeatingExpenseAdditionAfterDebtorLeavesPreservesHistory() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest))
        val shares = expense.getShares()
        event.removeParticipant(guest)

        event.addExpense(expense)

        assertEquals(listOf(expense.getId()), event.getExpenseIds())
        assertEquals(setOf(expense.getId()), guest.getExpenseIds())
        assertEquals(shares, expense.getShares())
        assertFalse(event.hasParticipant(guest))
    }

    @Test
    fun restoringExpenseAfterDebtorLeavesKeepsTheirSavedShare() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest), Money(101u))
        val shares = expense.getShares()
        event.removeParticipant(guest)

        val restored = Expense.Builder().restore(
            expense.getName(),
            expense.getAmount(),
            author,
            setOf(author, guest),
            author,
            event,
            shares,
            expense.getId()
        )

        assertEquals(shares, restored.getShares())
        assertEquals(setOf(author.getParticipantId(), guest.getParticipantId()), restored.getDebtorIds())
        assertFalse(event.hasParticipant(guest))
        assertEquals(listOf(expense.getId()), event.getExpenseIds())
    }

    @Test
    fun returnedSharesAndDebtorIdsCannotModifyExpenseState() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest))
        val shares = expense.getShares()
        val debtorIds = expense.getDebtorIds()

        runCatching { (shares as MutableMap<Uuid, Money>).clear() }
        runCatching { (debtorIds as MutableSet<Uuid>).clear() }

        assertEquals(
            mapOf(author.getParticipantId() to Money(50u), guest.getParticipantId() to Money(50u)),
            expense.getShares()
        )
        assertEquals(setOf(author.getParticipantId(), guest.getParticipantId()), expense.getDebtorIds())
    }

    @Test
    fun removingAbsentParticipantDoesNotChangeEvent() {
        val author = registeredParticipant("Anna")
        val outsider = Participant.build("Pavel", "Petrov")
        val event = createEvent(author)

        event.removeParticipant(outsider)

        assertTrue(event.hasParticipant(author))
        assertFalse(event.hasParticipant(outsider))
        assertTrue(outsider.getEventIds().isEmpty())
    }

    @Test
    fun guestOnlyExpenseIsAccessibleThroughRegisteredAuthor() {
        val author = registeredParticipant("Anna")
        val payer = Participant.build("Pavel", "Petrov")
        val debtor = Participant.build("Ivan", "Ivanov")
        val observer = Participant.build("Maria", "Petrova")
        val event = createEvent(author, payer, debtor, observer)

        val expense = createExpense(event, payer, author, setOf(debtor))

        assertEquals(payer.getParticipantId(), expense.getPayerId())
        assertEquals(author.getParticipantId(), expense.getAuthorId())
        assertEquals(setOf(debtor.getParticipantId()), expense.getDebtorIds())
        assertEquals(listOf(expense.getId()), event.getExpenseIds())
        assertEquals(setOf(expense.getId()), payer.getExpenseIds())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertEquals(setOf(expense.getId()), debtor.getExpenseIds())
        assertTrue(observer.getExpenseIds().isEmpty())
        assertEquals(mapOf(debtor.getParticipantId() to Money(100u)), expense.getShares())
    }

    @Test
    fun expenseWithoutAnyRegisteredAuthorPayerOrDebtorIsRejected() {
        val registered = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(registered, guest)

        assertFailsWith<IllegalArgumentException> {
            createExpense(event, guest, guest, setOf(guest))
        }

        assertTrue(event.getExpenseIds().isEmpty())
        assertTrue(guest.getExpenseIds().isEmpty())
        assertTrue(registered.getExpenseIds().isEmpty())
    }

    @Test
    fun unrelatedParticipantCannotRecordExpenseInHistory() {
        val author = registeredParticipant("Anna")
        val outsider = Participant.build("Pavel", "Petrov")
        val event = createEvent(author)
        val expense = createExpense(event, author, author, setOf(author))

        assertFailsWith<IllegalArgumentException> { outsider.addExpense(expense) }

        assertTrue(outsider.getExpenseIds().isEmpty())
    }

    @Test
    fun addingDebtorRecalculatesSharesAndRecordsHistory() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author))

        expense.addDebtor(guest)

        assertEquals(setOf(author.getParticipantId(), guest.getParticipantId()), expense.getDebtorIds())
        assertEquals(setOf(Money(50u)), expense.getShares().values.toSet())
        assertEquals(100uL, expense.getShares().values.sumOf { it.kopeck.toULong() })
        assertEquals(setOf(expense.getId()), guest.getExpenseIds())
    }

    @Test
    fun addingExistingDebtorPreservesTheExactSavedShares() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest), Money(101u))
        val shares = expense.getShares()
        val debtorIds = expense.getDebtorIds()

        expense.addDebtor(guest)

        assertEquals(shares, expense.getShares())
        assertEquals(debtorIds, expense.getDebtorIds())
        assertEquals(listOf(expense.getId()), event.getExpenseIds())
    }

    @Test
    fun addingOutsiderDebtorPreservesExpenseAndHistory() {
        val author = registeredParticipant("Anna")
        val outsider = Participant.build("Pavel", "Petrov")
        val event = createEvent(author)
        val expense = createExpense(event, author, author, setOf(author))
        val shares = expense.getShares()
        val debtorIds = expense.getDebtorIds()

        assertFailsWith<IllegalArgumentException> { expense.addDebtor(outsider) }

        assertEquals(shares, expense.getShares())
        assertEquals(debtorIds, expense.getDebtorIds())
        assertTrue(outsider.getExpenseIds().isEmpty())
    }

    @Test
    fun expenseCreationRejectsOutsidePayerDebtorAndUnregisteredAuthorWithoutRecordingHistory() {
        val author = registeredParticipant("Anna")
        val outsider = Participant.build("Pavel", "Petrov")
        val event = createEvent(author)

        assertFailsWith<IllegalArgumentException> {
            createExpense(event, outsider, author, setOf(author))
        }
        assertFailsWith<IllegalArgumentException> {
            createExpense(event, author, outsider, setOf(author))
        }
        assertFailsWith<IllegalArgumentException> {
            createExpense(event, author, author, setOf(outsider))
        }

        assertTrue(event.getExpenseIds().isEmpty())
        assertTrue(author.getExpenseIds().isEmpty())
        assertTrue(outsider.getExpenseIds().isEmpty())
    }

    @Test
    fun registeredAuthorOutsideEventKeepsExpenseHistoryWithoutJoiningEvent() {
        val owner = registeredParticipant("Anna")
        val author = registeredParticipant("Admin")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(owner, guest)

        val expense = createExpense(event, guest, author, setOf(guest))

        assertEquals(author.getParticipantId(), expense.getAuthorId())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertTrue(author.getEventIds().isEmpty())
        assertFalse(event.hasParticipant(author))
        assertEquals(listOf(expense.getId()), event.getExpenseIds())
    }

    @Test
    fun expenseRestorePreservesExactSharesWithoutChangingEventOrHistory() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expenseId = Uuid.random()
        val debtors = mutableSetOf(author, guest)
        val shares = mutableMapOf(author.getParticipantId() to Money(50u), guest.getParticipantId() to Money(51u))

        val expense = Expense.Builder().restore(
            "Dinner", Money(101u), author, debtors, author, event, shares, expenseId
        )
        debtors.clear()
        shares.clear()

        val expected = mapOf(author.getParticipantId() to Money(50u), guest.getParticipantId() to Money(51u))
        assertEquals(expenseId, expense.getId())
        assertEquals(expected, expense.getShares())
        assertEquals(expected, expense.getShares())
        assertTrue(event.getExpenseIds().isEmpty())
        assertTrue(author.getExpenseIds().isEmpty())
        assertTrue(guest.getExpenseIds().isEmpty())
    }

    @Test
    fun expenseRestoreRejectsInconsistentOrOverflowingShares() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val debtors = mutableSetOf(author, guest)

        val invalidShares = listOf(
            mutableMapOf(author.getParticipantId() to Money(1u)),
            mutableMapOf(author.getParticipantId() to Money(1u), guest.getParticipantId() to Money(1u)),
            mutableMapOf(author.getParticipantId() to Money(UInt.MAX_VALUE), guest.getParticipantId() to Money(2u))
        )
        for (shares in invalidShares) {
            assertFailsWith<IllegalArgumentException> {
                Expense.Builder().restore(
                    "Dinner", Money(1u), author, debtors, author, event, shares, Uuid.random()
                )
            }
        }

        assertTrue(event.getExpenseIds().isEmpty())
    }

    @Test
    fun eventRestoreCopiesExpenseIdsWithoutChangingParticipantHistory() {
        val author = registeredParticipant("Anna")
        val eventId = Uuid.random()
        val expenseId = Uuid.random()
        val participants = mutableSetOf(author)
        val expenseIds = mutableListOf(expenseId)

        val event = Event.Builder().restore("Trip", participants, eventId, expenseIds)
        participants.clear()
        expenseIds.clear()

        assertEquals(eventId, event.getId())
        assertEquals(listOf(expenseId), event.getExpenseIds())
        assertTrue(event.hasParticipant(author))
        assertTrue(author.getEventIds().isEmpty())
    }

    @Test
    fun builderCollectionsDoNotChangePreviouslyCreatedEntities() {
        val author = registeredParticipant("Anna")
        val guest = Participant.build("Pavel", "Petrov")
        val participants = mutableSetOf(author)
        val eventBuilder = Event.Builder().withName("Trip").withParticipants(participants)
        val event = eventBuilder.build()
        participants.add(guest)
        eventBuilder.addParticipant(guest)

        assertFalse(event.hasParticipant(guest))

        event.addParticipant(guest)
        val debtors = mutableSetOf(author)
        val expenseBuilder = Expense.Builder()
            .withName("Dinner")
            .withAmount(Money(100u))
            .withPayer(author)
            .withAuthor(author)
            .withEvent(event)
            .withDebtors(debtors)
        debtors.add(guest)
        val expense = expenseBuilder.build()
        expenseBuilder.withDebtor(guest)

        assertEquals(setOf(author.getParticipantId()), expense.getDebtorIds())
        assertEquals(mapOf(author.getParticipantId() to Money(100u)), expense.getShares())
    }

    @Test
    fun equalSplittingPreservesEveryKopeckAtBoundaries() {
        for (amount in listOf(0u, 1u, 2u, 100u, 101u, UInt.MAX_VALUE)) {
            for (count in listOf(1, 2, 3, 7, 20)) {
                val shares = Money(amount).splitEvenly(count)
                val base = amount / count.toUInt()

                assertEquals(count, shares.size)
                assertEquals(amount.toULong(), shares.sumOf { it.kopeck.toULong() })
                assertTrue(shares.all { it.kopeck == base || it.kopeck == base + 1u })
            }
        }
    }

    @Test
    fun equalSplittingRejectsNonPositiveCount() {
        assertFailsWith<IllegalArgumentException> { Money(100u).splitEvenly(0) }
        assertFailsWith<IllegalArgumentException> { Money(100u).splitEvenly(-1) }
    }

    @Test
    fun moneyArithmeticRejectsOverflowAndNegativeResults() {
        assertFailsWith<ArithmeticException> { Money(UInt.MAX_VALUE) + Money(1u) }
        assertFailsWith<ArithmeticException> { Money(0u) - Money(1u) }
        assertFailsWith<ArithmeticException> { Money(UInt.MAX_VALUE).times(2) }
        assertEquals(Money(UInt.MAX_VALUE), Money(UInt.MAX_VALUE) + Money(0u))
        assertEquals(Money(0u), Money(1u) - Money(1u))
        assertEquals(Money(0u), Money(UInt.MAX_VALUE).times(0))
    }

}
