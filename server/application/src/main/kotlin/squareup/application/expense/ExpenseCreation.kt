package squareup.application.expense

import kotlin.uuid.Uuid
import squareup.domain.money.Money

data class ExpenseCreation(val name: String, val amount: Money, val payerId: Uuid, val debtorIds: Set<Uuid>)
