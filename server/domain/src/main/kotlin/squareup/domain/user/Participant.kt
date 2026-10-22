package squareup.domain.user

import squareup.domain.event.Event
import squareup.domain.expense.Expense
import kotlin.uuid.Uuid

class Participant private constructor(
    private var firstName: String,
    private var lastName: String,
    private val participantId: Uuid = Uuid.random(),
    private var userId: Uuid? = null,
    eventIds: Set<Uuid> = emptySet(),
    expenseIds: Set<Uuid> = emptySet()
    ) {
    private val eventIds = eventIds.toMutableSet()
    private val expenseIds = expenseIds.toMutableSet()

    companion object {
        fun build(firstName: String, lastName: String): Participant {
            require(firstName.isNotBlank()) { "Participant's firstname must not be blank." }
            require(lastName.isNotBlank()) { "Participant's lastname must not be blank." }
            return Participant(firstName, lastName)
        }

        fun restore(
            firstName: String,
            lastName: String,
            participantId: Uuid,
            userId: Uuid?,
            eventIds: Set<Uuid>,
            expenseIds: Set<Uuid>
        ): Participant {
            require(firstName.isNotBlank()) { "Participant's first name must not be blank." }
            require(lastName.isNotBlank()) { "Participant's last name must not be blank." }
            return Participant(firstName, lastName, participantId, userId, eventIds, expenseIds)
        }
    }

    fun getParticipantId(): Uuid {
        return participantId
    }

    fun hasUserId() : Boolean {
        return userId != null
    }

    fun getUserId(): Uuid {
        return userId ?: throw IllegalStateException("No User has registered as Participant $firstName $lastName.")
    }

    fun linkUser(user: User) {
        require(user.getParticipantId() == participantId) { "User must reference this participant." }
        check(userId == null || userId == user.getId()) { "Participant is already linked to another user." }
        this.userId = user.getId()
    }

    fun unlinkUser(userId: Uuid) {
        check(this.userId == userId) { "Participant must be linked to the deleted user." }
        this.userId = null
    }

    fun removeEventHistory(eventId: Uuid) {
        eventIds.remove(eventId)
    }

    fun addEvent(event: Event) {
        require(event.hasParticipant(this)) { "Event must contain this participant." }
        eventIds.add(event.getId())
    }

    fun addExpense(expense: Expense) {
        require(expense.hasParticipant(participantId)) { "Expense must contain this participant as payer, author or debtor." }
        expenseIds.add(expense.getId())
    }

    fun updateExpenseHistory(expense: Expense) {
        if (expense.hasParticipant(participantId)) {
            expenseIds.add(expense.getId())
        } else {
            expenseIds.remove(expense.getId())
        }
    }

    fun removeExpense(expense: Expense) {
        require(expense.hasParticipant(participantId)) { "Expense must contain this participant as payer, author or debtor." }
        expenseIds.remove(expense.getId())
    }

    fun updateName(firstName: String = this.firstName, lastName: String = this.lastName) {
        require(firstName.isNotBlank()) { "Participant's first name must not be blank." }
        require(lastName.isNotBlank()) { "Participant's last name must not be blank." }
        this.firstName = firstName
        this.lastName = lastName
    }

    fun getEventIds(): Set<Uuid> = eventIds.toSet()

    fun getExpenseIds(): Set<Uuid> = expenseIds.toSet()

    fun getFirstName(): String {
        return firstName
    }

    fun getLastName(): String {
        return lastName
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Participant

        return participantId == other.participantId
    }

    override fun hashCode(): Int {
        return participantId.hashCode()
    }

    override fun toString(): String {
        return "Participant $firstName $lastName"
    }
}
