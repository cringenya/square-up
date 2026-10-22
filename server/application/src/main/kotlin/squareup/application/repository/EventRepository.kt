package squareup.application.repository

import kotlin.uuid.Uuid
import squareup.domain.event.Event

interface EventRepository {
    fun findById(id: Uuid): Event?
    fun findAll(): List<Event>
    fun findByParticipantId(participantId: Uuid, activeOnly: Boolean = false): List<Event>
    fun save(event: Event)
    fun delete(id: Uuid): Boolean
}
