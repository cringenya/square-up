package squareup.application.event

import kotlin.uuid.Uuid
import squareup.application.exception.ConflictFailure
import squareup.application.exception.NotFoundFailure
import squareup.application.participant.ParticipantProfile
import squareup.application.repository.Repositories
import squareup.application.security.AccessPolicy
import squareup.application.transaction.TransactionManager
import squareup.application.validation.validateInput
import squareup.domain.event.Event
import squareup.domain.event.SettlementCalculator
import squareup.domain.exception.LastRegisteredParticipantException
import squareup.domain.user.Participant
import squareup.domain.user.UserRole

class EventService(
    private val transactions: TransactionManager,
    private val access: AccessPolicy,
    private val calculator: SettlementCalculator = SettlementCalculator()
) {
    fun create(sessionId: String, name: String, participantIds: Set<Uuid> = emptySet()): EventProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val ids = participantIds.toMutableSet()
        if (actor.getRole() != UserRole.ADMIN) ids.add(actor.getParticipantId())
        val members = ids.mapTo(mutableSetOf()) { requireParticipant(repositories, it) }
        val event = validateInput { Event.Builder().withName(name).withParticipants(members).build() }
        repositories.events.save(event)
        EventProfile.from(event)
    }

    fun findById(sessionId: String, eventId: Uuid): EventProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val event = requireEvent(repositories, eventId)
        access.requireEventReader(repositories, actor, event)
        EventProfile.from(event)
    }

    fun findAll(sessionId: String): List<EventProfile> = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val events = if (actor.getRole() == UserRole.ADMIN) {
            repositories.events.findAll()
        } else {
            repositories.events.findByParticipantId(actor.getParticipantId())
        }
        events.map(EventProfile::from)
    }

    fun update(sessionId: String, eventId: Uuid, name: String): EventProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val event = requireEvent(repositories, eventId)
        access.requireEventEditor(actor, event)
        validateInput { event.update(name) }
        repositories.events.save(event)
        EventProfile.from(event)
    }

    fun addParticipant(sessionId: String, eventId: Uuid, participantId: Uuid): EventProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val event = requireEvent(repositories, eventId)
        access.requireEventEditor(actor, event)
        event.addParticipant(requireParticipant(repositories, participantId))
        repositories.events.save(event)
        EventProfile.from(event)
    }

    fun createGuest(sessionId: String, eventId: Uuid, firstName: String, lastName: String): ParticipantProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val event = requireEvent(repositories, eventId)
        access.requireEventEditor(actor, event)
        val participant = validateInput { Participant.build(firstName, lastName) }
        event.addParticipant(participant)
        repositories.participants.save(participant)
        repositories.events.save(event)
        ParticipantProfile.from(participant)
    }

    fun removeParticipant(sessionId: String, eventId: Uuid, participantId: Uuid): EventProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val event = requireEvent(repositories, eventId)
        access.requireEventEditor(actor, event)
        val participant = requireParticipant(repositories, participantId)
        try {
            event.removeParticipant(participant)
        } catch (failure: LastRegisteredParticipantException) {
            throw ConflictFailure("Event must retain a registered participant." + failure.message)
        }
        repositories.events.save(event)
        EventProfile.from(event)
    }

    fun delete(sessionId: String, eventId: Uuid) {
        transactions.transaction { repositories ->
            val actor = access.requireUser(repositories, sessionId)
            val event = requireEvent(repositories, eventId)
            access.requireEventEditor(actor, event)
            if (event.getExpenseIds().isNotEmpty()) throw ConflictFailure("Event still contains expenses.")
            if (!repositories.events.delete(eventId)) throw NotFoundFailure("Event was not found.")
        }
    }

    fun settlement(sessionId: String, eventId: Uuid): Settlement = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val event = requireEvent(repositories, eventId)
        access.requireEventReader(repositories, actor, event)
        val expenses = repositories.expenses.findByEventId(eventId)
        val balances = calculator.calculateBalances(event, expenses)
        Settlement(eventId, balances, calculator.calculateTransfers(balances))
    }

    fun participants(sessionId: String, eventId: Uuid): List<EventParticipant> = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        val event = requireEvent(repositories, eventId)
        access.requireEventReader(repositories, actor, event)
        event.getParticipantIds().sortedBy { it.toString() }.map { id ->
            val participant = requireParticipant(repositories, id)
            EventParticipant(id, participant.getFirstName(), participant.getLastName(), participant.hasUserId())
        }
    }

    private fun requireEvent(repositories: Repositories, eventId: Uuid): Event =
        repositories.events.findById(eventId) ?: throw NotFoundFailure("Event was not found.")

    private fun requireParticipant(repositories: Repositories, participantId: Uuid): Participant =
        repositories.participants.findById(participantId) ?: throw NotFoundFailure("Participant was not found.")
}
