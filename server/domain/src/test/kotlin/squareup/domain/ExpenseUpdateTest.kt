package squareup.domain

import squareup.domain.expense.Expense
import squareup.domain.money.Money
import squareup.domain.user.Participant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpenseUpdateTest {
    @Test
    fun changingNamePreservesDistributionAndOtherFieldsWithoutContext() {
        val author = registeredParticipant()
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest), Money(101u))
        val shares = expense.getShares()

        expense.update(name = "Hotel")

        assertEquals("Hotel", expense.getName())
        assertEquals(Money(101u), expense.getAmount())
        assertEquals(shares, expense.getShares())
        assertEquals(author.getParticipantId(), expense.getPayerId())
        assertEquals(author.getParticipantId(), expense.getAuthorId())
        assertEquals(event.getId(), expense.getEventId())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertEquals(setOf(expense.getId()), guest.getExpenseIds())
    }

    @Test
    fun changingAmountPreservesDepartedDebtorWithoutContext() {
        val author = registeredParticipant()
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest))
        event.removeParticipant(guest)

        expense.update(amount = Money(200u))

        assertEquals(Money(200u), expense.getAmount())
        assertEquals(
            mapOf(author.getParticipantId() to Money(100u), guest.getParticipantId() to Money(100u)),
            expense.getShares()
        )
        assertFalse(event.hasParticipant(guest))
        assertEquals(setOf(event.getId()), guest.getEventIds())
        assertEquals(setOf(expense.getId()), guest.getExpenseIds())
    }

    @Test
    fun unchangedValuesAndReorderedDebtorsPreserveExactShares() {
        val author = registeredParticipant()
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest), Money(101u))
        val previousState = stateOf(expense)

        expense.update()
        expense.update(amount = Money(101u))
        expense.update(debtorIds = linkedSetOf(guest.getParticipantId(), author.getParticipantId()))

        assertEquals(previousState, stateOf(expense))
    }

    @Test
    fun defaultArgumentsUseCurrentValuesAfterPreviousUpdates() {
        val author = registeredParticipant()
        val event = createEvent(author)
        val expense = createExpense(event, author, author, setOf(author))

        expense.update(name = "Hotel")
        expense.update(amount = Money(300u))
        expense.update()

        assertEquals("Hotel", expense.getName())
        assertEquals(Money(300u), expense.getAmount())
        assertEquals(mapOf(author.getParticipantId() to Money(300u)), expense.getShares())
    }

    @Test
    fun changingPayerUpdatesHistoryAndPreservesOtherExpenses() {
        val author = registeredParticipant()
        val previousPayer = Participant.build("Pavel", "Petrov")
        val newPayer = Participant.build("Maria", "Petrova")
        val debtor = Participant.build("Ivan", "Ivanov")
        val event = createEvent(author, previousPayer, newPayer, debtor)
        val expense = createExpense(event, previousPayer, author, setOf(debtor))
        val anotherExpense = createExpense(event, previousPayer, author, setOf(previousPayer))
        val shares = expense.getShares()

        expense.update(
            payerId = newPayer.getParticipantId(),
            participants = contextOf(author, previousPayer, newPayer, debtor)
        )

        assertEquals(newPayer.getParticipantId(), expense.getPayerId())
        assertEquals(shares, expense.getShares())
        assertEquals(author.getParticipantId(), expense.getAuthorId())
        assertEquals(event.getId(), expense.getEventId())
        assertEquals(setOf(anotherExpense.getId()), previousPayer.getExpenseIds())
        assertEquals(setOf(expense.getId()), newPayer.getExpenseIds())
        assertEquals(setOf(expense.getId()), debtor.getExpenseIds())
        assertEquals(listOf(expense.getId(), anotherExpense.getId()), event.getExpenseIds())
    }

    @Test
    fun previousPayerKeepsHistoryWhenStillAuthor() {
        val author = registeredParticipant()
        val newPayer = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, newPayer)
        val expense = createExpense(event, author, author, setOf(newPayer))

        expense.update(
            payerId = newPayer.getParticipantId(),
            participants = contextOf(author, newPayer)
        )

        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertEquals(setOf(expense.getId()), newPayer.getExpenseIds())
    }

    @Test
    fun replacingDebtorsUpdatesHistoryAndCopiesTheInputSet() {
        val author = registeredParticipant()
        val previousDebtor = Participant.build("Pavel", "Petrov")
        val newDebtor = Participant.build("Maria", "Petrova")
        val event = createEvent(author, previousDebtor, newDebtor)
        val expense = createExpense(event, author, author, setOf(previousDebtor))
        val newDebtorIds = mutableSetOf(newDebtor.getParticipantId())
        val participants = contextOf(author, previousDebtor, newDebtor).toMutableMap()

        expense.update(debtorIds = newDebtorIds, participants = participants)
        newDebtorIds.clear()
        participants.clear()

        assertEquals(setOf(newDebtor.getParticipantId()), expense.getDebtorIds())
        assertEquals(mapOf(newDebtor.getParticipantId() to Money(100u)), expense.getShares())
        assertTrue(previousDebtor.getExpenseIds().isEmpty())
        assertEquals(setOf(expense.getId()), newDebtor.getExpenseIds())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
    }

    @Test
    fun removingAuthorFromDebtorsPreservesTheirExpenseHistory() {
        val author = registeredParticipant()
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(author, guest))

        expense.update(
            debtorIds = setOf(guest.getParticipantId()),
            participants = contextOf(author, guest)
        )

        assertEquals(mapOf(guest.getParticipantId() to Money(100u)), expense.getShares())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
    }

    @Test
    fun removingOneDebtorClearsTheirShareAndHistoryUsingPreviousParticipant() {
        val author = registeredParticipant()
        val removedDebtor = Participant.build("Pavel", "Petrov")
        val remainingDebtor = Participant.build("Maria", "Petrova")
        val event = createEvent(author, removedDebtor, remainingDebtor)
        val expense = createExpense(event, author, author, setOf(removedDebtor, remainingDebtor))

        expense.update(
            debtorIds = setOf(remainingDebtor.getParticipantId()),
            participants = contextOf(author, removedDebtor, remainingDebtor)
        )

        assertEquals(setOf(remainingDebtor.getParticipantId()), expense.getDebtorIds())
        assertEquals(mapOf(remainingDebtor.getParticipantId() to Money(100u)), expense.getShares())
        assertTrue(removedDebtor.getExpenseIds().isEmpty())
        assertEquals(setOf(expense.getId()), remainingDebtor.getExpenseIds())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertTrue(event.hasParticipant(removedDebtor))
        assertEquals(setOf(event.getId()), removedDebtor.getEventIds())
    }

    @Test
    fun updatingSeveralFieldsPreservesAuthorEventAndExactTotal() {
        val author = registeredParticipant()
        val previousParticipant = Participant.build("Pavel", "Petrov")
        val newPayer = Participant.build("Maria", "Petrova")
        val newDebtor = Participant.build("Ivan", "Ivanov")
        val event = createEvent(author, previousParticipant, newPayer, newDebtor)
        val expense = createExpense(event, previousParticipant, author, setOf(previousParticipant))

        expense.update(
            name = "Hotel",
            amount = Money(999u),
            payerId = newPayer.getParticipantId(),
            debtorIds = setOf(author.getParticipantId(), newDebtor.getParticipantId()),
            participants = contextOf(author, previousParticipant, newPayer, newDebtor)
        )

        assertEquals("Hotel", expense.getName())
        assertEquals(Money(999u), expense.getAmount())
        assertEquals(newPayer.getParticipantId(), expense.getPayerId())
        assertEquals(author.getParticipantId(), expense.getAuthorId())
        assertEquals(event.getId(), expense.getEventId())
        assertEquals(999uL, expense.getShares().values.sumOf { it.kopeck.toULong() })
        assertTrue(expense.getShares().values.all { it.kopeck == 499u || it.kopeck == 500u })
        assertTrue(previousParticipant.getExpenseIds().isEmpty())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertEquals(setOf(expense.getId()), newPayer.getExpenseIds())
        assertEquals(setOf(expense.getId()), newDebtor.getExpenseIds())
    }

    @Test
    fun invalidBasicFieldsPreserveTheWholeExpenseAndHistory() {
        val author = registeredParticipant()
        val event = createEvent(author)
        val expense = createExpense(event, author, author, setOf(author))
        val previousState = stateOf(expense)
        val operations = listOf<() -> Unit>(
            { expense.update(name = " ", amount = Money(200u)) },
            { expense.update(name = "Hotel", amount = Money(0u)) },
            { expense.update(name = "Hotel", debtorIds = emptySet()) }
        )

        for (operation in operations) {
            assertFailsWith<IllegalArgumentException> { operation() }
            assertEquals(previousState, stateOf(expense))
            assertEquals(setOf(expense.getId()), author.getExpenseIds())
            assertEquals(listOf(expense.getId()), event.getExpenseIds())
        }
    }

    @Test
    fun missingMismatchedOrExtraContextIsRejectedBeforeAnyChanges() {
        val author = registeredParticipant()
        val newPayer = Participant.build("Pavel", "Petrov")
        val outsider = Participant.build("Maria", "Petrova")
        val event = createEvent(author, newPayer)
        val expense = createExpense(event, author, author, setOf(author))
        val previousState = stateOf(expense)
        val contexts = listOf(
            emptyMap(),
            contextOf(author),
            mapOf(author.getParticipantId() to author, newPayer.getParticipantId() to outsider),
            contextOf(author, newPayer, outsider)
        )

        for (participants in contexts) {
            assertFailsWith<IllegalArgumentException> {
                expense.update(
                    name = "Hotel",
                    amount = Money(200u),
                    payerId = newPayer.getParticipantId(),
                    participants = participants
                )
            }
            assertEquals(previousState, stateOf(expense))
            assertEquals(setOf(expense.getId()), author.getExpenseIds())
            assertTrue(newPayer.getExpenseIds().isEmpty())
            assertTrue(outsider.getExpenseIds().isEmpty())
        }
    }

    @Test
    fun contextMustIncludeParticipantRemovedFromTheExpense() {
        val author = registeredParticipant()
        val previousDebtor = Participant.build("Pavel", "Petrov")
        val newDebtor = Participant.build("Maria", "Petrova")
        val event = createEvent(author, previousDebtor, newDebtor)
        val expense = createExpense(event, author, author, setOf(previousDebtor))
        val previousState = stateOf(expense)

        assertFailsWith<IllegalArgumentException> {
            expense.update(
                debtorIds = setOf(newDebtor.getParticipantId()),
                participants = contextOf(author, newDebtor)
            )
        }

        assertEquals(previousState, stateOf(expense))
        assertEquals(setOf(expense.getId()), previousDebtor.getExpenseIds())
        assertTrue(newDebtor.getExpenseIds().isEmpty())
    }

    @Test
    fun newPayerOutsideEventIsRejectedBeforeAnyChanges() {
        val author = registeredParticipant()
        val outsider = Participant.build("Pavel", "Petrov")
        val event = createEvent(author)
        val expense = createExpense(event, author, author, setOf(author))
        val previousState = stateOf(expense)

        assertFailsWith<IllegalArgumentException> {
            expense.update(
                name = "Hotel",
                amount = Money(200u),
                payerId = outsider.getParticipantId(),
                participants = contextOf(author, outsider)
            )
        }

        assertEquals(previousState, stateOf(expense))
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertTrue(outsider.getExpenseIds().isEmpty())
    }

    @Test
    fun newDebtorOutsideEventIsRejectedBeforeAnyChanges() {
        val author = registeredParticipant()
        val outsider = Participant.build("Pavel", "Petrov")
        val event = createEvent(author)
        val expense = createExpense(event, author, author, setOf(author))
        val previousState = stateOf(expense)

        assertFailsWith<IllegalArgumentException> {
            expense.update(
                name = "Hotel",
                amount = Money(200u),
                debtorIds = setOf(outsider.getParticipantId()),
                participants = contextOf(author, outsider)
            )
        }

        assertEquals(previousState, stateOf(expense))
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertTrue(outsider.getExpenseIds().isEmpty())
    }

    @Test
    fun retainingDepartedParticipantsWhileAddingCurrentDebtorIsAllowed() {
        val author = registeredParticipant()
        val otherRegistered = registeredParticipant()
        val previousDebtor = Participant.build("Pavel", "Petrov")
        val newDebtor = Participant.build("Maria", "Petrova")
        val event = createEvent(author, otherRegistered, previousDebtor, newDebtor)
        val expense = createExpense(event, author, author, setOf(previousDebtor))
        event.removeParticipant(author)
        event.removeParticipant(previousDebtor)

        expense.update(
            amount = Money(200u),
            debtorIds = setOf(previousDebtor.getParticipantId(), newDebtor.getParticipantId()),
            participants = contextOf(author, previousDebtor, newDebtor)
        )

        assertEquals(author.getParticipantId(), expense.getPayerId())
        assertEquals(author.getParticipantId(), expense.getAuthorId())
        assertEquals(
            mapOf(previousDebtor.getParticipantId() to Money(100u), newDebtor.getParticipantId() to Money(100u)),
            expense.getShares()
        )
        assertEquals(setOf(expense.getId()), previousDebtor.getExpenseIds())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertEquals(setOf(expense.getId()), newDebtor.getExpenseIds())
        assertTrue(event.hasParticipant(otherRegistered))
    }

    @Test
    fun losingTheOnlyRegisteredRelatedParticipantIsRejected() {
        val previousPayer = registeredParticipant()
        val author = Participant.build("Pavel", "Petrov")
        val debtor = Participant.build("Maria", "Petrova")
        val newPayer = Participant.build("Ivan", "Ivanov")
        val event = createEvent(previousPayer, author, debtor, newPayer)
        val expense = createExpense(event, previousPayer, author, setOf(debtor))
        val previousState = stateOf(expense)

        assertFailsWith<IllegalArgumentException> {
            expense.update(
                name = "Hotel",
                amount = Money(200u),
                payerId = newPayer.getParticipantId(),
                participants = contextOf(previousPayer, author, debtor, newPayer)
            )
        }

        assertEquals(previousState, stateOf(expense))
        assertEquals(setOf(expense.getId()), previousPayer.getExpenseIds())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertEquals(setOf(expense.getId()), debtor.getExpenseIds())
        assertTrue(newPayer.getExpenseIds().isEmpty())
    }

    @Test
    fun repeatedHistorySynchronizationKeepsOnlyCurrentRelatedParticipants() {
        val author = registeredParticipant()
        val previousDebtor = Participant.build("Pavel", "Petrov")
        val newDebtor = Participant.build("Maria", "Petrova")
        val event = createEvent(author, previousDebtor, newDebtor)
        val expense = createExpense(event, author, author, setOf(previousDebtor))
        expense.update(
            debtorIds = setOf(newDebtor.getParticipantId()),
            participants = contextOf(author, previousDebtor, newDebtor)
        )

        repeat(2) {
            author.updateExpenseHistory(expense)
            previousDebtor.updateExpenseHistory(expense)
            newDebtor.updateExpenseHistory(expense)
        }

        assertEquals(setOf(expense.getId()), author.getExpenseIds())
        assertTrue(previousDebtor.getExpenseIds().isEmpty())
        assertEquals(setOf(expense.getId()), newDebtor.getExpenseIds())
    }

    private fun stateOf(expense: Expense): List<Any> = listOf(
        expense.getId(),
        expense.getName(),
        expense.getAmount(),
        expense.getPayerId(),
        expense.getAuthorId(),
        expense.getEventId(),
        expense.getDebtorIds(),
        expense.getShares()
    )

}
