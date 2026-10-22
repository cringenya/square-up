package squareup.application.expense

import kotlin.uuid.Uuid
import squareup.domain.money.Money

data class ExpenseUpdate(
    val name: String? = null,
    val amount: Money? = null,
    val payerId: Uuid? = null,
    val debtorIds: Set<Uuid>? = null
)
