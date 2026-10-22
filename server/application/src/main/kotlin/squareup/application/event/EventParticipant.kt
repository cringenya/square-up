package squareup.application.event

import kotlin.uuid.Uuid

data class EventParticipant(val id: Uuid, val firstName: String, val lastName: String, val registered: Boolean)
