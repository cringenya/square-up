package squareup.domain.event

import kotlin.uuid.Uuid

data class Transfer(
    val fromParticipantId: Uuid,
    val toParticipantId: Uuid,
    val amountKopecks: Long
) {
    init {
        require(fromParticipantId != toParticipantId) { "Transfer participants must be different." }
        require(amountKopecks > 0) { "Transfer amount must be positive." }
    }
}
