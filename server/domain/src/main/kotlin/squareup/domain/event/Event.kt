package squareup.domain.event

import squareup.domain.exception.LastRegisteredParticipantException
import squareup.domain.expense.Expense
import squareup.domain.user.Participant
import kotlin.uuid.Uuid

class Event private constructor(
    private var name: String,
    private val participants: MutableSet<Participant>,
    private val id : Uuid = Uuid.random(),
    expenseIds: List<Uuid>,
) {
    private val expenseIds = expenseIds.toMutableList()

    init {
        require(participants.any {participant -> participant.hasUserId()}) { "At least one registered participant must be in event." }
    }

    class Builder() {
        private var name: String = ""
        private var participants: MutableSet<Participant> = mutableSetOf<Participant>()

        fun withName(name: String): Builder {
            require (name.isNotBlank()) { "Name of event must not be blank." }
            this.name = name.trim()
            return this
        }

        fun addParticipant(participant: Participant): Builder {
            participants += participant
            return this
        }

        fun withParticipants(participants: Set<Participant>): Builder {
            require (participants.isNotEmpty()) { "Participants set must not be empty." }
            this.participants = participants.toMutableSet()
            return this
        }

        fun build(): Event {
            require (name.isNotBlank()) { "Event name must be set and not blank before initializing." }
            require (participants.isNotEmpty()) { "You must specify at least one participant of event." }
            val event = Event(name, participants.toMutableSet(), expenseIds = emptyList())
            participants.forEach { it.addEvent(event) }
            return event
        }

        fun restore(
            name: String,
            participants: Set<Participant>,
            id: Uuid,
            expenseIds: List<Uuid>
        ): Event {
            require (name.isNotBlank()) { "Cannot restore event with blank name." }
            require(participants.isNotEmpty()) { "Cannot restore event with empty participants set." }
            return Event(name, participants.toMutableSet(), id, expenseIds)
        }
    }

    fun addParticipant(participant: Participant): Event {
        if (participants.contains(participant)) return this
        participants.add(participant)
        participant.addEvent(this)
        return this
    }

    fun removeParticipant(participant: Participant): Event {
        if (!participants.contains(participant)) return this
        if (participants.any { it.hasUserId() && it != participant }) {
            participants.remove(participant)
        } else {
            throw LastRegisteredParticipantException()
        }
        return this
    }

    fun addExpense(expense: Expense) {
        require (expense.getEventId() == this.id) { "Event's expense must have the event id." }
        if (expense.getId() in expenseIds) return
        require(hasParticipant(expense.getPayerId())) { "Event's expense must have payer that is in event." }
        require(expense.getDebtorIds().all { hasParticipant(it) }) { "Event's expense must have debtors that are in event." }
        expenseIds.add(expense.getId())
        for (participant in participants) {
            if (expense.hasParticipant(participant.getParticipantId())) {
                participant.addExpense(expense)
            }
        }
    }

    fun hasParticipant(participant: Participant): Boolean = participants.contains(participant)

    fun hasParticipant(participantId: Uuid): Boolean = participants.any { it.getParticipantId() == participantId }

    fun removeExpense(expense: Expense, participants: Map<Uuid, Participant>) {
        require(expense.getEventId() == id) { "Event's expense must have the event id." }
        if (expense.getId() !in expenseIds) return
        val context = participants.toMap()
        require(context.keys == expense.getParticipantIds()) { "Participants must match expense participants." }
        require(context.all { (participantId, participant) -> participantId == participant.getParticipantId() }) {
            "Participant id must match its map key."
        }

        expenseIds.remove(expense.getId())
        for (participant in context.values) {
            participant.removeExpense(expense)
        }
    }

    fun update(name: String) {
        require(name.isNotBlank()) { "Name of event must not be blank." }
        this.name = name.trim()
    }

    fun getId(): Uuid = id

    fun getName(): String = name

    fun getParticipantIds(): Set<Uuid> = participants.mapTo(mutableSetOf()) { it.getParticipantId() }

    fun getExpenseIds(): List<Uuid> = expenseIds.toList()

}
