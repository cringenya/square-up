package squareup.application.event

import kotlin.uuid.Uuid
import squareup.domain.event.Event

data class EventProfile(val id: Uuid, val name: String, val participantIds: Set<Uuid>, val expenseIds: List<Uuid>) {
    companion object {
        fun from(event: Event): EventProfile = EventProfile(event.getId(), event.getName(), event.getParticipantIds(), event.getExpenseIds())
    }
}
