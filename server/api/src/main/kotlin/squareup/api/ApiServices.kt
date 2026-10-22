package squareup.api

import squareup.application.account.AccountService
import squareup.application.event.EventService
import squareup.application.expense.ExpenseService
import squareup.application.participant.ParticipantService
import squareup.application.user.UserService

class ApiServices(
    val accounts: AccountService,
    val users: UserService,
    val participants: ParticipantService,
    val events: EventService,
    val expenses: ExpenseService
)
