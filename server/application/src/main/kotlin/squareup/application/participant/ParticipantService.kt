package squareup.application.participant

import kotlin.uuid.Uuid
import squareup.application.exception.AccessDeniedFailure
import squareup.application.exception.ConflictFailure
import squareup.application.exception.NotFoundFailure
import squareup.application.repository.Repositories
import squareup.application.security.AccessPolicy
import squareup.application.transaction.TransactionManager
import squareup.application.validation.validateInput
import squareup.domain.user.Participant
import squareup.domain.user.UserRole

class ParticipantService(private val transactions: TransactionManager, private val access: AccessPolicy) {
    fun findById(sessionId: String, participantId: Uuid): ParticipantProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        if (actor.getRole() != UserRole.ADMIN && actor.getParticipantId() != participantId) throw AccessDeniedFailure()
        ParticipantProfile.from(requireParticipant(repositories, participantId))
    }

    fun findAll(sessionId: String): List<ParticipantProfile> = transactions.transaction { repositories ->
        access.requireAdmin(repositories, sessionId)
        repositories.participants.findAll().map(ParticipantProfile::from)
    }

    fun create(sessionId: String, firstName: String, lastName: String): ParticipantProfile = transactions.transaction { repositories ->
        access.requireAdmin(repositories, sessionId)
        val participant = validateInput { Participant.build(firstName, lastName) }
        repositories.participants.save(participant)
        ParticipantProfile.from(participant)
    }

    fun update(sessionId: String, participantId: Uuid, firstName: String? = null, lastName: String? = null): ParticipantProfile = transactions.transaction { repositories ->
        val actor = access.requireUser(repositories, sessionId)
        if (actor.getRole() != UserRole.ADMIN && actor.getParticipantId() != participantId) throw AccessDeniedFailure()
        val participant = requireParticipant(repositories, participantId)
        validateInput { participant.updateName(firstName ?: participant.getFirstName(), lastName ?: participant.getLastName()) }
        repositories.participants.save(participant)
        ParticipantProfile.from(participant)
    }

    fun delete(sessionId: String, participantId: Uuid) {
        transactions.transaction { repositories ->
            access.requireAdmin(repositories, sessionId)
            val participant = requireParticipant(repositories, participantId)
            if (participant.hasUserId() || participant.getEventIds().isNotEmpty() || participant.getExpenseIds().isNotEmpty()) {
                throw ConflictFailure("Participant is still referenced.")
            }
            if (!repositories.participants.delete(participantId)) throw NotFoundFailure("Participant was not found.")
        }
    }

    private fun requireParticipant(repositories: Repositories, participantId: Uuid): Participant =
        repositories.participants.findById(participantId) ?: throw NotFoundFailure("Participant was not found.")
}
