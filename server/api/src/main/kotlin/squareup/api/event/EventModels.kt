package squareup.api.event

import kotlinx.serialization.Serializable
import squareup.application.event.EventProfile
import squareup.application.event.Settlement

@Serializable
data class CreateEventRequest(val name: String, val participantIds: Set<String> = emptySet())

@Serializable
data class UpdateEventRequest(val name: String)

@Serializable
data class EventResponse(val id: String, val name: String, val participantIds: Set<String>, val expenseIds: List<String>)

@Serializable
data class BalanceResponse(val participantId: String, val paidKopecks: Long, val shareKopecks: Long, val debtKopecks: Long)

@Serializable
data class TransferResponse(val fromParticipantId: String, val toParticipantId: String, val amountKopecks: Long)

@Serializable
data class CalculationResponse(val eventId: String, val balances: List<BalanceResponse>, val transfers: List<TransferResponse>)

internal fun EventProfile.toResponse(): EventResponse = EventResponse(
    id.toString(), name, participantIds.mapTo(mutableSetOf()) { it.toString() }, expenseIds.map { it.toString() }
)

internal fun Settlement.toResponse(): CalculationResponse = CalculationResponse(
    eventId.toString(), balances.map { BalanceResponse(it.participantId.toString(), it.paidKopecks, it.shareKopecks, it.debtKopecks) },
    transfers.map { TransferResponse(it.fromParticipantId.toString(), it.toParticipantId.toString(), it.amountKopecks) }
)
