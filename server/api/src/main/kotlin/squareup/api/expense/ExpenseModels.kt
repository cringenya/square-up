package squareup.api.expense

import kotlinx.serialization.Serializable
import squareup.application.expense.ExpenseProfile

@Serializable
data class CreateExpenseRequest(val name: String, val amountKopecks: Long, val payerId: String, val debtorIds: Set<String>)

@Serializable
data class UpdateExpenseRequest(val name: String? = null, val amountKopecks: Long? = null, val payerId: String? = null, val debtorIds: Set<String>? = null)

@Serializable
data class ExpenseResponse(val id: String, val eventId: String, val name: String, val amountKopecks: Long, val payerId: String, val authorId: String, val shares: Map<String, Long>)

internal fun ExpenseProfile.toResponse(): ExpenseResponse = ExpenseResponse(
    id.toString(), eventId.toString(), name, amount.kopeck.toLong(), payerId.toString(), authorId.toString(),
    shares.entries.associate { it.key.toString() to it.value.kopeck.toLong() }
)
