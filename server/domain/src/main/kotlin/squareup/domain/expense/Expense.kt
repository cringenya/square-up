package squareup.domain.expense

import squareup.domain.event.Event
import squareup.domain.money.Money
import squareup.domain.user.Participant
import kotlin.uuid.Uuid

class Expense private constructor(
    private var name: String,
    private var amount: Money,
    private var payerId: Uuid,
    private var debtorIds: Set<Uuid>,
    private val authorId: Uuid,
    private val event: Event,
    private var shares: Map<Uuid, Money>,
    hasRegisteredParticipant: Boolean,
    private val expenseId: Uuid = Uuid.random(),
    ) {

    companion object {
        private fun calculateShares(amount: Money, debtorIds: Set<Uuid>): Map<Uuid, Money> {
            val distribution = amount.splitEvenly(debtorIds.size).iterator()
            return debtorIds.associateWith { distribution.next() }
        }
    }

    init {
        require (name.isNotBlank()) { "Name of expense must not be blank." }
        require(hasRegisteredParticipant) {"At least one registered user must have access to the expense."}
    }

    class Builder() {
        private var name: String = ""
        private var amount: Money? = null
        private var payer: Participant? = null
        private var debtors = mutableSetOf<Participant>()
        private var author: Participant? = null
        private var event: Event? = null

        fun withName(name: String): Builder {
            require (name.isNotBlank()) { "Name of expense must not be blank." }
            this.name = name
            return this
        }

        fun withAmount(amount: Money): Builder {
            require(amount.kopeck > 0u) { "Amount of expense must be positive." }
            this.amount = amount
            return this
        }

        fun withPayer(payer: Participant): Builder {
            this.payer = payer
            return this
        }

        fun withDebtor(debtor: Participant): Builder {
            this.debtors.add(debtor)
            return this
        }

        fun withDebtors(debtors: Set<Participant>): Builder {
            require (debtors.isNotEmpty()) { "Debtors of expense must not be empty." }
            this.debtors = debtors.toMutableSet()
            return this
        }

        fun withAuthor(author: Participant): Builder {
            this.author = author
            return this
        }

        fun withEvent(event: Event): Builder {
            this.event = event
            return this
        }

        fun build(): Expense {
            require (name.isNotBlank()) { "Name of expense must be set and not blank before initializing." }
            require(debtors.isNotEmpty()) { "Debtors of expense must be set before initializing." }

            val expenseAmount = requireNotNull(amount) { "Amount of expense must be set before initializing." }
            require(expenseAmount.kopeck > 0u) { "Amount of expense mustn't be zero." }
            val expenseEvent = requireNotNull(event) { "Expense's event must not be null." }
            val expensePayer = requireNotNull(payer) { "Payer of expense must be set before initializing." }
            val expenseAuthor = requireNotNull(author) { "Author of expense must be set before initializing." }
            require(expenseEvent.hasParticipant(expenseAuthor) || expenseAuthor.hasUserId()) {
                "Author outside event must be registered."
            }
            val debtorIds = debtors.mapTo(mutableSetOf()) { it.getParticipantId() }
            val shares = calculateShares(expenseAmount, debtorIds)
            val expense = Expense(
                name,
                expenseAmount,
                expensePayer.getParticipantId(),
                debtorIds,
                expenseAuthor.getParticipantId(),
                expenseEvent,
                shares,
                expensePayer.hasUserId() || expenseAuthor.hasUserId() || debtors.any { it.hasUserId() }
            )
            expenseEvent.addExpense(expense)
            expenseAuthor.addExpense(expense)
            return expense
        }

        fun restore(
            name: String,
            amount: Money,
            payer: Participant,
            debtors: Set<Participant>,
            author: Participant,
            event: Event,
            shares: Map<Uuid, Money>,
            id: Uuid
        ): Expense {
            require (name.isNotBlank()) { "Cannot restore expense with blank name." }
            require(amount.kopeck > 0u) { "Cannot restore expense with zero amount." }
            require(debtors.isNotEmpty()) { "Cannot restore expense having empty debtors set." }
            val debtorIds = debtors.mapTo(mutableSetOf()) { it.getParticipantId() }
            require(shares.keys == debtorIds) { "Cannot restore shares with different debtors included." }
            require(shares.values.sumOf { it.kopeck.toULong() } == amount.kopeck.toULong()) { "Cannot restore expense with shares having different amount." }
            return Expense(
                name,
                amount,
                payer.getParticipantId(),
                debtorIds,
                author.getParticipantId(),
                event,
                shares.toMap(),
                payer.hasUserId() || author.hasUserId() || debtors.any { it.hasUserId() },
                id
            )
        }
    }

    fun getEventId(): Uuid = event.getId()
    fun getId(): Uuid = expenseId
    fun getName() = name
    fun getAmount() = amount
    fun getPayerId(): Uuid = payerId
    fun getDebtorIds(): Set<Uuid> = debtorIds.toSet()
    fun getAuthorId(): Uuid = authorId
    fun getShares(): Map<Uuid, Money> = shares.toMap()

    fun getParticipantIds(): Set<Uuid> = buildSet {
        add(authorId)
        add(payerId)
        addAll(debtorIds)
    }

    fun hasParticipant(participantId: Uuid): Boolean {
        return participantId == payerId || participantId == authorId || participantId in debtorIds
    }

    fun update(
        name: String = this.name,
        amount: Money = this.amount,
        payerId: Uuid = this.payerId,
        debtorIds: Set<Uuid> = this.debtorIds,
        participants: Map<Uuid, Participant> = emptyMap()
    ) {
        validateFields(name, amount, debtorIds)

        val debtorsChanged = debtorIds != this.debtorIds
        val participantsChanged = payerId != this.payerId || debtorsChanged
        val updatedDebtorIds = if (debtorsChanged) {
            debtorIds.toSet()
        } else {
            this.debtorIds
        }

        val affectedParticipants = if (participantsChanged) {
            prepareParticipants(payerId, updatedDebtorIds, participants)
        } else {
            emptyMap()
        }

        if (amount != this.amount || debtorsChanged) {
            updateShares(amount, updatedDebtorIds)
        }

        this.name = name
        this.payerId = payerId

        for (participant in affectedParticipants.values) {
            participant.updateExpenseHistory(this)
        }
    }

    private fun validateFields(name: String, amount: Money, debtorIds: Set<Uuid>) {
        require(name.isNotBlank()) { "Name of expense must not be blank." }
        require(amount.kopeck > 0u) { "Amount of expense must be positive." }
        require(debtorIds.isNotEmpty()) { "Debtors of expense must not be empty." }
    }

    private fun prepareParticipants(
        updatedPayerId: Uuid,
        updatedDebtorIds: Set<Uuid>,
        participants: Map<Uuid, Participant>
    ): Map<Uuid, Participant> {
        val context = participants.toMap()
        val requiredIds = buildSet {
            add(authorId)
            add(payerId)
            addAll(debtorIds)
            add(updatedPayerId)
            addAll(updatedDebtorIds)
        }

        require(context.keys == requiredIds) {
            "Participants must match previous and updated expense participants."
        }
        require(context.all { (id, participant) -> id == participant.getParticipantId() }) {
            "Participant id must match its map key."
        }
        validateNewParticipants(updatedPayerId, updatedDebtorIds)
        validateRegisteredParticipant(context, updatedPayerId, updatedDebtorIds)
        return context
    }

    private fun validateNewParticipants(updatedPayerId: Uuid, updatedDebtorIds: Set<Uuid>) {
        require(updatedPayerId == payerId || event.hasParticipant(updatedPayerId)) {
            "Payer of expense must be in event."
        }
        require(updatedDebtorIds.all { it in debtorIds || event.hasParticipant(it) }) {
            "New debtors of expense must be in event."
        }
    }

    private fun validateRegisteredParticipant(
        participants: Map<Uuid, Participant>,
        updatedPayerId: Uuid,
        updatedDebtorIds: Set<Uuid>
    ) {
        require(
            participants.getValue(authorId).hasUserId() ||
                participants.getValue(updatedPayerId).hasUserId() ||
                updatedDebtorIds.any { participants.getValue(it).hasUserId() }
        ) {
            "At least one registered user must have access to the expense."
        }
    }

    private fun updateShares(amount: Money, debtorIds: Set<Uuid>) {
        val updatedShares = calculateShares(amount, debtorIds)
        this.amount = amount
        this.debtorIds = debtorIds
        shares = updatedShares
    }

    fun addDebtor(debtor: Participant) {
        require(event.hasParticipant(debtor)) { "Debtor of expense must be in event." }
        val debtorId = debtor.getParticipantId()
        if (debtorId in debtorIds) return

        updateShares(amount, debtorIds + debtorId)
        debtor.addExpense(this)
    }
}
