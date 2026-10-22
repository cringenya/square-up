package squareup.application.participant

import kotlin.uuid.Uuid
import squareup.domain.user.Participant

data class ParticipantProfile(
    val id: Uuid,
    val firstName: String,
    val lastName: String,
    val userId: Uuid?,
    val eventIds: Set<Uuid>,
    val expenseIds: Set<Uuid>
) {
    companion object {
        fun from(participant: Participant): ParticipantProfile = ParticipantProfile(
            participant.getParticipantId(), participant.getFirstName(), participant.getLastName(),
            if (participant.hasUserId()) participant.getUserId() else null,
            participant.getEventIds(), participant.getExpenseIds()
        )
    }
}
