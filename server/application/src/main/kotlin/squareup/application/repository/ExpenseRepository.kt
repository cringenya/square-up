package squareup.application.repository

import kotlin.uuid.Uuid
import squareup.domain.expense.Expense

interface ExpenseRepository {
    fun findById(id: Uuid): Expense?
    fun findAll(): List<Expense>
    fun findByEventId(eventId: Uuid): List<Expense>
    fun findByParticipantId(participantId: Uuid): List<Expense>
    fun save(expense: Expense)
    fun delete(id: Uuid): Boolean
}
