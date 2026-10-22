package squareup.domain.event

import kotlin.uuid.Uuid

data class Balance(
    val participantId: Uuid,
    val paidKopecks: Long,
    val shareKopecks: Long
) {
    init {
        require(paidKopecks >= 0) { "Paid amount must not be negative." }
        require(shareKopecks >= 0) { "Share amount must not be negative." }
    }

    val debtKopecks: Long
        get() = paidKopecks - shareKopecks
}
