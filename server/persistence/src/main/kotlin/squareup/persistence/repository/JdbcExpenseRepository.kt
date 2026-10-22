package squareup.persistence.repository

import squareup.application.repository.ExpenseRepository
import squareup.domain.expense.Expense
import squareup.domain.money.Money
import squareup.persistence.JdbcOperations
import squareup.persistence.checkIdentity
import squareup.persistence.uuid
import java.sql.ResultSet
import kotlin.collections.iterator
import kotlin.uuid.Uuid

internal class JdbcExpenseRepository(
    private val sql: JdbcOperations,
    private val participants: JdbcParticipantRepository,
    private val events: JdbcEventRepository
) : ExpenseRepository {
    private val loaded = mutableMapOf<Uuid, Expense>()
    private val select = "SELECT e.id, e.event_id, e.name, e.amount_kopecks, e.payer_id, e.author_id FROM public.expenses e"

    override fun findById(id: Uuid): Expense? {
        sql.checkOpen()
        loaded[id]?.let { return it }
        return sql.queryOne("$select WHERE e.id = ?", listOf(id), ::restore)
    }

    override fun findAll(): List<Expense> {
        return sql.query("$select ORDER BY e.id", emptyList(), ::restore)
    }

    override fun findByEventId(eventId: Uuid): List<Expense> {
        return sql.query("$select WHERE e.event_id = ? ORDER BY e.id", listOf(eventId), ::restore)
    }

    override fun findByParticipantId(participantId: Uuid): List<Expense> {
        return sql.query(
            "$select WHERE e.author_id = ? OR e.payer_id = ? OR EXISTS (SELECT 1 FROM public.expense_shares s WHERE s.expense_id = e.id AND s.participant_id = ?) ORDER BY e.id",
            listOf(participantId, participantId, participantId), ::restore
        )
    }

    override fun save(expense: Expense) {
        val id = expense.getId()
        sql.checkOpen()
        checkIdentity(loaded, id, expense)
        val count = sql.update(
            "INSERT INTO public.expenses (id, event_id, name, amount_kopecks, payer_id, author_id) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, amount_kopecks = EXCLUDED.amount_kopecks, payer_id = EXCLUDED.payer_id WHERE expenses.event_id = EXCLUDED.event_id AND expenses.author_id = EXCLUDED.author_id",
            listOf(id, expense.getEventId(), expense.getName(), expense.getAmount().kopeck.toLong(), expense.getPayerId(), expense.getAuthorId())
        )
        check(count == 1) { "Cannot change expense's event or author." }
        sql.update("DELETE FROM public.expense_shares WHERE expense_id = ?", listOf(id))
        for ((participantId, share) in expense.getShares()) {
            sql.update(
                "INSERT INTO public.expense_shares (expense_id, participant_id, amount_kopecks) VALUES (?, ?, ?)",
                listOf(id, participantId, share.kopeck.toLong())
            )
        }
        loaded[id] = expense
    }

    override fun delete(id: Uuid): Boolean {
        val expense = findById(id) ?: return false
        val event = checkNotNull(events.findById(expense.getEventId())) { "Expense must have an event." }
        val affectedParticipants = expense.getParticipantIds().associateWith {
            checkNotNull(participants.findById(it)) { "Expense must have existing participants." }
        }
        sql.update("DELETE FROM public.expense_shares WHERE expense_id = ?", listOf(id))
        val deleted = sql.update("DELETE FROM public.expenses WHERE id = ?", listOf(id)) > 0
        if (deleted) {
            event.removeExpense(expense, affectedParticipants)
            loaded.remove(id)
        }
        return deleted
    }

    private fun restore(row: ResultSet): Expense {
        val id = row.uuid("id")
        loaded[id]?.let { return it }
        val event = checkNotNull(events.findById(row.uuid("event_id"))) { "Cannot restore expense with missing event." }
        val payer = checkNotNull(participants.findById(row.uuid("payer_id"))) { "Cannot restore expense with missing payer." }
        val author = checkNotNull(participants.findById(row.uuid("author_id"))) { "Cannot restore expense with missing author." }
        val shares = sql.queryMap(
            "SELECT participant_id, amount_kopecks FROM public.expense_shares WHERE expense_id = ? ORDER BY participant_id", listOf(id),
            { it.uuid("participant_id") }, { Money(it.getLong("amount_kopecks").toUInt()) }
        )
        val debtors = shares.keys.mapTo(mutableSetOf()) {
            checkNotNull(participants.findById(it)) { "Cannot restore expense with missing debtor." }
        }
        val expense = Expense.Builder().restore(
            row.getString("name"), Money(row.getLong("amount_kopecks").toUInt()), payer, debtors, author, event, shares, id
        )
        loaded[id] = expense
        return expense
    }
}
