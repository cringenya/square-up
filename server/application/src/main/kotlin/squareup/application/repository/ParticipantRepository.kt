package squareup.application.repository

import kotlin.uuid.Uuid
import squareup.domain.user.Participant

interface ParticipantRepository {
    fun findById(id: Uuid): Participant?
    fun findAll(): List<Participant>
    fun save(participant: Participant)
    fun delete(id: Uuid): Boolean
}
