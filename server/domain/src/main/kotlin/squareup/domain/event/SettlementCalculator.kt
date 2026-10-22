package squareup.domain.event

import squareup.domain.expense.Expense
import kotlin.uuid.Uuid

class SettlementCalculator {
    fun calculateBalances(event: Event, expenses: Collection<Expense>): List<Balance> {
        validateExpenses(event, expenses)
        val paid = mutableMapOf<Uuid, Long>()
        val shares = mutableMapOf<Uuid, Long>()
        for (participantId in event.getParticipantIds()) {
            paid[participantId] = 0L
        }

        for (expense in expenses) {
            val payerId = expense.getPayerId()
            paid[payerId] = Math.addExact(paid[payerId] ?: 0L, expense.getAmount().kopeck.toLong())
            for ((participantId, share) in expense.getShares()) {
                paid.putIfAbsent(participantId, 0L)
                shares[participantId] = Math.addExact(shares[participantId] ?: 0L, share.kopeck.toLong())
            }
        }

        return paid.keys.sortedBy { it.toString() }.map { participantId ->
            Balance(participantId, paid.getValue(participantId), shares[participantId] ?: 0L)
        }
    }

    fun calculateTransfers(balances: Collection<Balance>): List<Transfer> {
        validateBalances(balances)
        val ordered = balances.sortedBy { it.participantId.toString() }
        val debtors = ordered.filter { it.debtKopecks < 0 }
        val creditors = ordered.filter { it.debtKopecks > 0 }
        return matchTransfers(debtors, creditors)
    }

    private fun validateExpenses(event: Event, expenses: Collection<Expense>) {
        val storedIds = event.getExpenseIds()
        val expectedIds = storedIds.toSet()
        require(expectedIds.size == storedIds.size) { "Event must not contain duplicate expenses." }
        val actualIds = mutableSetOf<Uuid>()
        for (expense in expenses) {
            require(expense.getEventId() == event.getId()) { "Expense must belong to the calculated event." }
            require(actualIds.add(expense.getId())) { "Expenses must not contain duplicate ids." }
        }
        require(actualIds == expectedIds) { "Expenses must match all expenses of event." }
    }

    private fun validateBalances(balances: Collection<Balance>) {
        val participantIds = mutableSetOf<Uuid>()
        var paidTotal = 0L
        var shareTotal = 0L
        for (balance in balances) {
            require(participantIds.add(balance.participantId)) { "Balances must not contain duplicate participants." }
            paidTotal = Math.addExact(paidTotal, balance.paidKopecks)
            shareTotal = Math.addExact(shareTotal, balance.shareKopecks)
        }
        require(paidTotal == shareTotal) { "Total paid amount must match total shares." }
    }

    private fun matchTransfers(debtors: List<Balance>, creditors: List<Balance>): List<Transfer> {
        val transfers = mutableListOf<Transfer>()
        var debtorIndex = 0
        var creditorIndex = 0
        var debt = if (debtors.isEmpty()) 0L else -debtors[0].debtKopecks
        var credit = if (creditors.isEmpty()) 0L else creditors[0].debtKopecks

        while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
            val amount = minOf(debt, credit)
            transfers.add(Transfer(
                debtors[debtorIndex].participantId,
                creditors[creditorIndex].participantId,
                amount
            ))
            debt -= amount
            credit -= amount

            if (debt == 0L) {
                debtorIndex++
                if (debtorIndex < debtors.size) debt = -debtors[debtorIndex].debtKopecks
            }
            if (credit == 0L) {
                creditorIndex++
                if (creditorIndex < creditors.size) credit = creditors[creditorIndex].debtKopecks
            }
        }
        return transfers
    }
}
