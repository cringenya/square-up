package squareup.application.security

import kotlin.uuid.Uuid
import squareup.application.exception.AccessDeniedFailure
import squareup.application.exception.AuthenticationFailure
import squareup.application.exception.StorageFailure
import squareup.application.repository.Repositories
import squareup.domain.event.Event
import squareup.domain.user.User
import squareup.domain.user.UserRole

class AccessPolicy(private val sessions: SessionManager) {
    fun requireUser(repositories: Repositories, sessionId: String): User {
        val userId = sessions.findUserId(sessionId) ?: throw AuthenticationFailure()
        return repositories.users.findById(userId) ?: throw AuthenticationFailure()
    }

    fun requireAdmin(repositories: Repositories, sessionId: String): User {
        val actor = requireUser(repositories, sessionId)
        if (actor.getRole() != UserRole.ADMIN) throw AccessDeniedFailure()
        return actor
    }

    fun requireSelfOrAdmin(repositories: Repositories, sessionId: String, userId: Uuid): User {
        val actor = requireUser(repositories, sessionId)
        if (actor.getId() != userId && actor.getRole() != UserRole.ADMIN) throw AccessDeniedFailure()
        return actor
    }

    fun requireEventEditor(actor: User, event: Event) {
        if (actor.getRole() != UserRole.ADMIN && !event.hasParticipant(actor.getParticipantId())) throw AccessDeniedFailure()
    }

    fun requireEventReader(repositories: Repositories, actor: User, event: Event) {
        if (actor.getRole() == UserRole.ADMIN || event.hasParticipant(actor.getParticipantId())) return
        val participant = repositories.participants.findById(actor.getParticipantId()) ?: throw StorageFailure()
        if (event.getId() !in participant.getEventIds()) throw AccessDeniedFailure()
    }
}
