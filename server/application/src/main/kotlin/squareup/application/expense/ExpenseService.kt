package squareup.application.expense

import kotlin.uuid.Uuid
import squareup.application.exception.AccessDeniedFailure
import squareup.application.exception.NotFoundFailure
import squareup.application.repository.Repositories
import squareup.application.security.AccessPolicy
import squareup.application.transaction.TransactionManager
import squareup.application.validation.validateInput
import squareup.domain.event.Event
import squareup.domain.expense.Expense
import squareup.domain.user.Participant
import squareup.domain.user.UserRole

class ExpenseService(private val transactions: TransactionManager, private val access: AccessPolicy) {
    fun create(sessionId: String, eventId: Uuid, creation: ExpenseCreation): ExpenseProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val event = requireEvent(repositories, eventId)
        access.requireEventEditor(actor, event)
        val author = requireParticipant(repositories, actor.getParticipantId())
        val payer = requireParticipant(repositories, creation.payerId)
        val debtors = creation.debtorIds.mapTo(mutableSetOf()) { requireParticipant(repositories, it) }
        val expense = validateInput {
            Expense.Builder().withEvent(event).withAuthor(author).withName(creation.name).withAmount(creation.amount)
                .withPayer(payer).withDebtors(debtors).build()
        }
        repositories.expenses.save(expense)
        ExpenseProfile.from(expense)
    }

    fun findById(sessionId: String, expenseId: Uuid): ExpenseProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val expense = requireExpense(repositories, expenseId)
        if (!expense.hasParticipant(actor.getParticipantId())) {
            access.requireEventReader(repositories, actor, requireEvent(repositories, expense.getEventId()))
        }
        ExpenseProfile.from(expense)
    }

    fun findAll(sessionId: String): List<ExpenseProfile> = transactions.transaction { repositories ->
        access.requireAdmin(repositories, sessionId)
        repositories.expenses.findAll().map(ExpenseProfile::from)
    }

    fun findByEvent(sessionId: String, eventId: Uuid): List<ExpenseProfile> = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val event = requireEvent(repositories, eventId)
        access.requireEventReader(repositories, actor, event)
        repositories.expenses.findByEventId(eventId).map(ExpenseProfile::from)
    }

    fun findByParticipant(sessionId: String, participantId: Uuid): List<ExpenseProfile> = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        if (actor.getRole() != UserRole.ADMIN && actor.getParticipantId() != participantId) throw AccessDeniedFailure()
        requireParticipant(repositories, participantId)
        repositories.expenses.findByParticipantId(participantId).map(ExpenseProfile::from)
    }

    fun update(sessionId: String, expenseId: Uuid, update: ExpenseUpdate): ExpenseProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val expense = requireExpense(repositories, expenseId)
        access.requireEventEditor(actor, requireEvent(repositories, expense.getEventId()))
        val payerId = update.payerId ?: expense.getPayerId()
        val debtorIds = update.debtorIds ?: expense.getDebtorIds()
        val participants = updateContext(repositories, expense, payerId, debtorIds)
        validateInput {
            expense.update(update.name ?: expense.getName(), update.amount ?: expense.getAmount(), payerId, debtorIds, participants)
        }
        repositories.expenses.save(expense)
        ExpenseProfile.from(expense)
    }

    fun delete(sessionId: String, expenseId: Uuid) {
        transactions.transaction { repositories ->
            val actor = access.requireUser(repositories, sessionId)
            val expense = requireExpense(repositories, expenseId)
            access.requireEventEditor(actor, requireEvent(repositories, expense.getEventId()))
            if (!repositories.expenses.delete(expenseId)) throw NotFoundFailure("Expense was not found.")
        }
    }

    private fun updateContext(repositories: Repositories, expense: Expense, payerId: Uuid, debtorIds: Set<Uuid>): Map<Uuid, Participant> {
        if (payerId == expense.getPayerId() && debtorIds == expense.getDebtorIds()) return emptyMap()
        val ids = expense.getParticipantIds().toMutableSet()
        ids.add(payerId)
        ids.addAll(debtorIds)
        return ids.associateWith { requireParticipant(repositories, it) }
    }

    private fun requireExpense(repositories: Repositories, expenseId: Uuid): Expense =
        repositories.expenses.findById(expenseId) ?: throw NotFoundFailure("Expense was not found.")

    private fun requireEvent(repositories: Repositories, eventId: Uuid): Event =
        repositories.events.findById(eventId) ?: throw NotFoundFailure("Event was not found.")

    private fun requireParticipant(repositories: Repositories, participantId: Uuid): Participant =
        repositories.participants.findById(participantId) ?: throw NotFoundFailure("Participant was not found.")
}
