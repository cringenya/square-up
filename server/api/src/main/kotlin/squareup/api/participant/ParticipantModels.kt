package squareup.api.participant

import kotlinx.serialization.Serializable
import squareup.application.event.EventParticipant
import squareup.application.participant.ParticipantProfile

@Serializable
data class CreateParticipantRequest(val firstName: String, val lastName: String)

@Serializable
data class UpdateParticipantRequest(val firstName: String? = null, val lastName: String? = null)

@Serializable
data class ParticipantResponse(val id: String, val firstName: String, val lastName: String, val userId: String?, val eventIds: Set<String>, val expenseIds: Set<String>)

@Serializable
data class EventParticipantResponse(val id: String, val firstName: String, val lastName: String, val registered: Boolean)

internal fun ParticipantProfile.toResponse(): ParticipantResponse = ParticipantResponse(
    id.toString(), firstName, lastName, userId?.toString(), eventIds.mapTo(mutableSetOf()) { it.toString() }, expenseIds.mapTo(mutableSetOf()) { it.toString() }
)

internal fun EventParticipant.toResponse(): EventParticipantResponse = EventParticipantResponse(id.toString(), firstName, lastName, registered)
