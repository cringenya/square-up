package squareup.persistence.repository

import squareup.application.repository.ParticipantRepository
import squareup.domain.user.Participant
import squareup.persistence.JdbcOperations
import squareup.persistence.checkIdentity
import squareup.persistence.optionalUuid
import squareup.persistence.uuid
import java.sql.ResultSet
import kotlin.uuid.Uuid

internal class JdbcParticipantRepository(private val sql: JdbcOperations) : ParticipantRepository {
    private val loaded = mutableMapOf<Uuid, Participant>()
    private val select = "SELECT p.id, p.first_name, p.last_name, u.id AS user_id FROM public.participants p LEFT JOIN public.users u ON u.participant_id = p.id"

    override fun findById(id: Uuid): Participant? {
        sql.checkOpen()
        loaded[id]?.let { return it }
        return sql.queryOne("$select WHERE p.id = ?", listOf(id), ::restore)
    }

    override fun findAll(): List<Participant> {
        return sql.query("$select ORDER BY p.id", emptyList(), ::restore)
    }

    override fun save(participant: Participant) {
        val id = participant.getParticipantId()
        sql.checkOpen()
        checkIdentity(loaded, id, participant)
        sql.update(
            "INSERT INTO public.participants (id, first_name, last_name) VALUES (?, ?, ?) ON CONFLICT (id) DO UPDATE SET first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name",
            listOf(id, participant.getFirstName(), participant.getLastName())
        )
        loaded[id] = participant
    }

    override fun delete(id: Uuid): Boolean {
        val deleted = sql.update("DELETE FROM public.participants WHERE id = ?", listOf(id)) > 0
        if (deleted) loaded.remove(id)
        return deleted
    }

    fun unlinkLoadedUser(participantId: Uuid, userId: Uuid) {
        loaded[participantId]?.unlinkUser(userId)
    }

    fun removeEventFromLoadedHistory(eventId: Uuid) {
        for (participant in loaded.values) participant.removeEventHistory(eventId)
    }

    private fun restore(row: ResultSet): Participant {
        val id = row.uuid("id")
        loaded[id]?.let { return it }
        val eventIds = sql.querySet(
            "SELECT event_id FROM public.event_participants WHERE participant_id = ? ORDER BY event_id",
            listOf(id)
        ) { it.uuid("event_id") }
        val expenseIds = sql.querySet(
            "SELECT e.id FROM public.expenses e WHERE e.author_id = ? OR e.payer_id = ? OR EXISTS (SELECT 1 FROM public.expense_shares s WHERE s.expense_id = e.id AND s.participant_id = ?) ORDER BY e.id",
            listOf(id, id, id)
        ) { it.uuid("id") }
        val participant = Participant.restore(
            row.getString("first_name"), row.getString("last_name"), id, row.optionalUuid("user_id"), eventIds, expenseIds
        )
        loaded[id] = participant
        return participant
    }
}
