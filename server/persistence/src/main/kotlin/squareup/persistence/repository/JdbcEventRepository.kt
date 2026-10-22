package squareup.persistence.repository

import squareup.application.repository.EventRepository
import squareup.domain.event.Event
import squareup.persistence.JdbcOperations
import squareup.persistence.checkIdentity
import squareup.persistence.uuid
import java.sql.ResultSet
import kotlin.uuid.Uuid

internal class JdbcEventRepository(
    private val sql: JdbcOperations,
    private val participants: JdbcParticipantRepository
) : EventRepository {
    private val loaded = mutableMapOf<Uuid, Event>()

    override fun findById(id: Uuid): Event? {
        sql.checkOpen()
        loaded[id]?.let { return it }
        return sql.queryOne("SELECT id, name FROM public.events WHERE id = ?", listOf(id), ::restore)
    }

    override fun findAll(): List<Event> {
        return sql.query("SELECT id, name FROM public.events ORDER BY id", emptyList(), ::restore)
    }

    override fun findByParticipantId(participantId: Uuid, activeOnly: Boolean): List<Event> {
        return sql.query(
            "SELECT e.id, e.name FROM public.events e JOIN public.event_participants p ON p.event_id = e.id WHERE p.participant_id = ? AND (NOT ? OR p.active) ORDER BY e.id",
            listOf(participantId, activeOnly), ::restore
        )
    }

    override fun save(event: Event) {
        val id = event.getId()
        sql.checkOpen()
        checkIdentity(loaded, id, event)
        sql.update("INSERT INTO public.events (id, name) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name", listOf(id, event.getName()))
        sql.update("UPDATE public.event_participants SET active = FALSE WHERE event_id = ?", listOf(id))
        for (participantId in event.getParticipantIds()) {
            sql.update(
                "INSERT INTO public.event_participants (event_id, participant_id, active) VALUES (?, ?, TRUE) ON CONFLICT (event_id, participant_id) DO UPDATE SET active = TRUE",
                listOf(id, participantId)
            )
        }
        loaded[id] = event
    }

    override fun delete(id: Uuid): Boolean {
        sql.update("DELETE FROM public.event_participants WHERE event_id = ?", listOf(id))
        val deleted = sql.update("DELETE FROM public.events WHERE id = ?", listOf(id)) > 0
        if (deleted) {
            participants.removeEventFromLoadedHistory(id)
            loaded.remove(id)
        }
        return deleted
    }

    private fun restore(row: ResultSet): Event {
        val id = row.uuid("id")
        loaded[id]?.let { return it }
        val members = sql.querySet(
            "SELECT participant_id FROM public.event_participants WHERE event_id = ? AND active ORDER BY participant_id", listOf(id)
        ) {
            checkNotNull(participants.findById(it.uuid("participant_id"))) { "Cannot restore event with missing participant." }
        }
        val expenseIds = sql.query("SELECT id FROM public.expenses WHERE event_id = ? ORDER BY id", listOf(id)) { it.uuid("id") }
        val event = Event.Builder().restore(row.getString("name"), members, id, expenseIds)
        loaded[id] = event
        return event
    }
}
