package squareup.domain

import squareup.domain.event.Event
import squareup.domain.expense.Expense
import squareup.domain.money.Money
import squareup.domain.user.Participant
import squareup.domain.user.User
import squareup.domain.user.UserRole
import kotlin.uuid.Uuid

internal fun contextOf(vararg participants: Participant): Map<Uuid, Participant> =
    participants.associateBy { it.getParticipantId() }

internal fun createUser(participant: Participant, role: UserRole = UserRole.USER): User = User.Builder()
    .withEmail("${participant.getParticipantId()}@example.test")
    .withLogin("user${participant.getParticipantId()}")
    .withPassword("test-password-hash")
    .withParticipant(participant)
    .withRole(role)
    .build()

internal fun registeredParticipant(firstName: String = "Anna"): Participant =
    Participant.build(firstName, "Ivanova").also { createUser(it) }

internal fun createEvent(vararg participants: Participant): Event = Event.Builder()
    .withName("Trip")
    .withParticipants(participants.toSet())
    .build()

internal fun createExpense(
    event: Event,
    payer: Participant,
    author: Participant,
    debtors: Set<Participant>,
    amount: Money = Money(100u)
): Expense = Expense.Builder()
    .withName("Dinner")
    .withAmount(amount)
    .withPayer(payer)
    .withAuthor(author)
    .withEvent(event)
    .withDebtors(debtors)
    .build()
