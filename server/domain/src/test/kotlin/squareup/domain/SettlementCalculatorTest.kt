package squareup.domain

import squareup.domain.event.Balance
import squareup.domain.event.Event
import squareup.domain.event.SettlementCalculator
import squareup.domain.event.Transfer
import squareup.domain.expense.Expense
import squareup.domain.money.Money
import squareup.domain.user.Participant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SettlementCalculatorTest {
    private val calculator = SettlementCalculator()

    @Test
    fun eventWithoutExpensesHasZeroBalancesAndNoTransfers() {
        val author = registeredParticipant()
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)

        val balances = calculator.calculateBalances(event, emptyList())

        assertEquals(setOf(author.getParticipantId(), guest.getParticipantId()), balances.mapTo(mutableSetOf()) { it.participantId })
        assertTrue(balances.all { it.paidKopecks == 0L && it.shareKopecks == 0L && it.debtKopecks == 0L })
        assertTrue(calculator.calculateTransfers(balances).isEmpty())
    }

    @Test
    fun mutualOffsetsProduceNetBalancesAndTransfers() {
        val author = registeredParticipant()
        val second = Participant.build("Pavel", "Petrov")
        val third = Participant.build("Maria", "Petrova")
        val event = createEvent(author, second, third)
        val dinner = createExpense(event, author, author, setOf(author, second, third), Money(900u))
        val taxi = createExpense(event, second, author, setOf(third), Money(300u))

        val balances = calculator.calculateBalances(event, listOf(dinner, taxi))
        val byId = balances.associateBy { it.participantId }

        assertEquals(Balance(author.getParticipantId(), 900L, 300L), byId.getValue(author.getParticipantId()))
        assertEquals(Balance(second.getParticipantId(), 300L, 300L), byId.getValue(second.getParticipantId()))
        assertEquals(Balance(third.getParticipantId(), 0L, 600L), byId.getValue(third.getParticipantId()))
        assertEquals(listOf(Transfer(third.getParticipantId(), author.getParticipantId(), 600L)), calculator.calculateTransfers(balances))
    }

    @Test
    fun payerCanBeOutsideDebtorsAndAuthorCanHaveZeroBalance() {
        val author = registeredParticipant()
        val payer = Participant.build("Pavel", "Petrov")
        val debtor = Participant.build("Maria", "Petrova")
        val event = createEvent(author, payer, debtor)
        val expense = createExpense(event, payer, author, setOf(debtor), Money(200u))

        val balances = calculator.calculateBalances(event, listOf(expense))

        assertEquals(Balance(author.getParticipantId(), 0L, 0L), balances.first { it.participantId == author.getParticipantId() })
        assertEquals(listOf(Transfer(debtor.getParticipantId(), payer.getParticipantId(), 200L)), calculator.calculateTransfers(balances))
    }

    @Test
    fun departedDebtorIsStillIncludedInBalancesAndTransfers() {
        val author = registeredParticipant()
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(guest), Money(100u))
        event.removeParticipant(guest)

        val balances = calculator.calculateBalances(event, listOf(expense))

        assertEquals(-100L, balances.first { it.participantId == guest.getParticipantId() }.debtKopecks)
        assertEquals(listOf(Transfer(guest.getParticipantId(), author.getParticipantId(), 100L)), calculator.calculateTransfers(balances))
    }

    @Test
    fun repeatedCalculationPreservesSavedRoundingAndDoesNotChangeEntities() {
        val author = registeredParticipant()
        val second = Participant.build("Pavel", "Petrov")
        val third = Participant.build("Maria", "Petrova")
        val event = createEvent(author, second, third)
        val expense = createExpense(event, author, author, setOf(author, second, third), Money(100u))
        val shares = expense.getShares()
        val first = calculator.calculateBalances(event, listOf(expense))

        val secondResult = calculator.calculateBalances(event, listOf(expense))

        assertEquals(first, secondResult)
        assertEquals(shares, expense.getShares())
        assertEquals(0L, first.sumOf { it.debtKopecks })
        assertEquals(calculator.calculateTransfers(first), calculator.calculateTransfers(secondResult))
        assertFullySettled(first, calculator.calculateTransfers(first))
    }

    @Test
    fun totalsAndTransfersCanExceedTheRangeOfOneMoney() {
        val author = registeredParticipant()
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val first = createExpense(event, author, author, setOf(guest), Money(UInt.MAX_VALUE))
        val second = createExpense(event, author, author, setOf(guest), Money(UInt.MAX_VALUE))
        val total = UInt.MAX_VALUE.toLong() + UInt.MAX_VALUE.toLong()

        val balances = calculator.calculateBalances(event, listOf(first, second))

        assertEquals(total, balances.first { it.participantId == author.getParticipantId() }.debtKopecks)
        assertEquals(-total, balances.first { it.participantId == guest.getParticipantId() }.debtKopecks)
        assertEquals(listOf(Transfer(guest.getParticipantId(), author.getParticipantId(), total)), calculator.calculateTransfers(balances))
    }

    @Test
    fun missingDuplicateOrForeignExpensesAreRejected() {
        val author = registeredParticipant()
        val event = createEvent(author)
        val expense = createExpense(event, author, author, setOf(author), Money(100u))
        val otherEvent = createEvent(author)
        val foreign = createExpense(otherEvent, author, author, setOf(author), Money(200u))

        assertFailsWith<IllegalArgumentException> { calculator.calculateBalances(event, emptyList()) }
        assertFailsWith<IllegalArgumentException> { calculator.calculateBalances(event, listOf(expense, expense)) }
        assertFailsWith<IllegalArgumentException> { calculator.calculateBalances(event, listOf(foreign)) }

        assertEquals(listOf(expense.getId()), event.getExpenseIds())
        assertEquals(mapOf(author.getParticipantId() to Money(100u)), expense.getShares())
    }

    @Test
    fun extraExpenseWithTheSameEventIsRejected() {
        val author = registeredParticipant()
        val event = createEvent(author)
        val extra = Expense.Builder().restore(
            "Extra", Money(100u), author, setOf(author), author, event,
            mapOf(author.getParticipantId() to Money(100u)), Uuid.random()
        )

        assertFailsWith<IllegalArgumentException> { calculator.calculateBalances(event, listOf(extra)) }

        assertTrue(event.getExpenseIds().isEmpty())
    }

    @Test
    fun eventWithDuplicateStoredExpenseIdsIsRejected() {
        val author = registeredParticipant()
        val expenseId = Uuid.random()
        val event = Event.Builder().restore("Trip", setOf(author), Uuid.random(), listOf(expenseId, expenseId))

        assertFailsWith<IllegalArgumentException> { calculator.calculateBalances(event, emptyList()) }
    }

    @Test
    fun transfersFollowStableUuidOrderAndSplitOnlyWhenNeeded() {
        val balances = listOf(
            Balance(id(1), 0L, 70L),
            Balance(id(2), 0L, 30L),
            Balance(id(3), 60L, 0L),
            Balance(id(4), 40L, 0L)
        )
        val expected = listOf(
            Transfer(id(1), id(3), 60L),
            Transfer(id(1), id(4), 10L),
            Transfer(id(2), id(4), 30L)
        )

        assertEquals(expected, calculator.calculateTransfers(balances))
        assertEquals(expected, calculator.calculateTransfers(balances.reversed()))
        assertFullySettled(balances, expected)
    }

    @Test
    fun zeroBalancesDoNotCreateTransfers() {
        assertTrue(calculator.calculateTransfers(emptyList()).isEmpty())
        assertTrue(calculator.calculateTransfers(listOf(Balance(id(1), 100L, 100L))).isEmpty())
    }

    @Test
    fun inconsistentOrDuplicateBalancesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            calculator.calculateTransfers(listOf(Balance(id(1), 100L, 0L)))
        }
        assertFailsWith<IllegalArgumentException> {
            calculator.calculateTransfers(listOf(Balance(id(1), 100L, 100L), Balance(id(1), 0L, 0L)))
        }
    }

    @Test
    fun longBoundaryWorksAndOverflowIsRejected() {
        val balances = listOf(Balance(id(1), Long.MAX_VALUE, 0L), Balance(id(2), 0L, Long.MAX_VALUE))

        assertEquals(listOf(Transfer(id(2), id(1), Long.MAX_VALUE)), calculator.calculateTransfers(balances))
        assertFailsWith<ArithmeticException> {
            calculator.calculateTransfers(listOf(
                Balance(id(1), Long.MAX_VALUE, 0L),
                Balance(id(2), 1L, Long.MAX_VALUE),
                Balance(id(3), 0L, 1L)
            ))
        }
    }

    @Test
    fun balanceAndTransferRejectInvalidValues() {
        assertFailsWith<IllegalArgumentException> { Balance(id(1), -1L, 0L) }
        assertFailsWith<IllegalArgumentException> { Balance(id(1), 0L, -1L) }
        assertFailsWith<IllegalArgumentException> { Transfer(id(1), id(1), 1L) }
        assertFailsWith<IllegalArgumentException> { Transfer(id(1), id(2), 0L) }
        assertFailsWith<IllegalArgumentException> { Transfer(id(1), id(2), -1L) }
    }

    @Test
    fun allBalancesAreSettledWithoutChangingTheInput() {
        val balances = listOf(
            Balance(id(1), 150L, 20L),
            Balance(id(2), 30L, 20L),
            Balance(id(3), 0L, 50L),
            Balance(id(4), 0L, 90L)
        )
        val original = balances.toList()

        val transfers = calculator.calculateTransfers(balances)

        assertFullySettled(balances, transfers)
        assertEquals(original, balances)
        assertTrue(transfers.size <= balances.size - 1)
    }

    @Test
    fun deletedExpenseNoLongerAffectsCalculation() {
        val author = registeredParticipant()
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(guest), Money(100u))
        event.removeExpense(expense, mapOf(author.getParticipantId() to author, guest.getParticipantId() to guest))

        val balances = calculator.calculateBalances(event, emptyList())

        assertTrue(balances.all { it.debtKopecks == 0L })
        assertTrue(calculator.calculateTransfers(balances).isEmpty())
    }

    private fun assertFullySettled(balances: List<Balance>, transfers: List<Transfer>) {
        val remaining = balances.associate { it.participantId to it.debtKopecks }.toMutableMap()
        for (transfer in transfers) {
            remaining[transfer.fromParticipantId] = Math.addExact(
                remaining.getValue(transfer.fromParticipantId), transfer.amountKopecks
            )
            remaining[transfer.toParticipantId] = Math.subtractExact(
                remaining.getValue(transfer.toParticipantId), transfer.amountKopecks
            )
        }
        assertTrue(remaining.values.all { it == 0L })
        assertTrue(transfers.all { it.amountKopecks > 0L && it.fromParticipantId != it.toParticipantId })
    }

    private fun id(value: Int): Uuid = Uuid.parse("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")

}
