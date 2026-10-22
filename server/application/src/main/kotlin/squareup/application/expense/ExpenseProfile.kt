package squareup.application.expense

import kotlin.uuid.Uuid
import squareup.domain.expense.Expense
import squareup.domain.money.Money

data class ExpenseProfile(
    val id: Uuid,
    val eventId: Uuid,
    val name: String,
    val amount: Money,
    val payerId: Uuid,
    val authorId: Uuid,
    val shares: Map<Uuid, Money>
) {
    companion object {
        fun from(expense: Expense): ExpenseProfile = ExpenseProfile(
            expense.getId(), expense.getEventId(), expense.getName(), expense.getAmount(),
            expense.getPayerId(), expense.getAuthorId(), expense.getShares()
        )
    }
}
