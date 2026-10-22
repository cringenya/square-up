package squareup.application.event

import kotlin.uuid.Uuid
import squareup.domain.event.Balance
import squareup.domain.event.Transfer

data class Settlement(val eventId: Uuid, val balances: List<Balance>, val transfers: List<Transfer>)
