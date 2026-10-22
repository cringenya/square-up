package squareup.application.user

import kotlin.uuid.Uuid
import squareup.application.account.Registration
import squareup.application.account.createUser
import squareup.application.exception.ConflictFailure
import squareup.application.exception.NotFoundFailure
import squareup.application.exception.StorageFailure
import squareup.application.repository.Repositories
import squareup.application.security.AccessPolicy
import squareup.application.security.PasswordHasher
import squareup.application.security.SessionManager
import squareup.application.transaction.TransactionManager
import squareup.application.validation.validateInput
import squareup.domain.user.User
import squareup.domain.user.UserRole

class UserService(
    private val transactions: TransactionManager,
    private val passwords: PasswordHasher,
    private val sessions: SessionManager,
    private val access: AccessPolicy
) {
    fun current(sessionId: String): UserProfile = transactions.transaction { repositories ->
        UserProfile.from(access.requireUser(repositories, sessionId))
    }

    fun findById(sessionId: String, userId: Uuid): UserProfile = transactions.transaction { repositories ->
        access.requireSelfOrAdmin(repositories, sessionId, userId)
        UserProfile.from(requireUser(repositories, userId))
    }

    fun findAll(sessionId: String): List<UserProfile> = transactions.transaction { repositories ->
        access.requireAdmin(repositories, sessionId)
        repositories.users.findAll().map(UserProfile::from)
    }

    fun create(sessionId: String, registration: Registration, role: UserRole = UserRole.USER): UserProfile = transactions.transaction { repositories ->
        access.requireAdmin(repositories, sessionId)
        UserProfile.from(createUser(repositories, registration, role, passwords))
    }

    fun update(sessionId: String, userId: Uuid, update: UserUpdate): UserProfile = sessions.withLock {
        val updated = transactions.transaction { repositories ->
            access.requireSelfOrAdmin(repositories, sessionId, userId)
            val user = requireUser(repositories, userId)
            val login = update.login?.trim() ?: user.getLogin()
            val otherUser = repositories.users.findByLogin(login)
            if (otherUser != null && otherUser.getId() != userId) throw ConflictFailure("Login is already in use.")
            validateInput {
                val passwordHash = update.password?.let(passwords::hash) ?: user.getPasswordHash()
                user.update(update.firstName ?: user.getFirstName(), update.lastName ?: user.getLastName(), login, passwordHash)
            }
            val participant = repositories.participants.findById(user.getParticipantId()) ?: throw StorageFailure()
            repositories.participants.save(participant)
            repositories.users.save(user)
            UserProfile.from(user)
        }
        if (update.password != null) sessions.removeByUserId(userId)
        updated
    }

    fun changeRole(sessionId: String, userId: Uuid, role: UserRole): UserProfile = sessions.withLock {
        var changed = false
        val updated = transactions.transaction { repositories ->
            access.requireAdmin(repositories, sessionId)
            val user = requireUser(repositories, userId)
            if (user.getRole() != role) {
                if (user.getRole() == UserRole.ADMIN) requireAnotherAdmin(repositories, userId)
                user.changeRole(role)
                repositories.users.save(user)
                changed = true
            }
            UserProfile.from(user)
        }
        if (changed) sessions.removeByUserId(userId)
        updated
    }

    fun delete(sessionId: String, userId: Uuid) {
        sessions.withLock {
            transactions.transaction { repositories ->
                access.requireAdmin(repositories, sessionId)
                val user = requireUser(repositories, userId)
                if (user.getRole() == UserRole.ADMIN) requireAnotherAdmin(repositories, userId)
                requireRegisteredParticipantsAfterDeletion(repositories, user)
                if (!repositories.users.delete(userId)) throw NotFoundFailure("User was not found.")
            }
            sessions.removeByUserId(userId)
        }
    }

    private fun requireUser(repositories: Repositories, userId: Uuid): User =
        repositories.users.findById(userId) ?: throw NotFoundFailure("User was not found.")

    private fun requireAnotherAdmin(repositories: Repositories, userId: Uuid) {
        if (repositories.users.findAll().none { it.getId() != userId && it.getRole() == UserRole.ADMIN }) {
            throw ConflictFailure("At least one admin must remain.")
        }
    }

    private fun requireRegisteredParticipantsAfterDeletion(repositories: Repositories, user: User) {
        val participantId = user.getParticipantId()
        val participant = repositories.participants.findById(participantId) ?: throw StorageFailure()
        for (eventId in participant.getEventIds()) {
            val event = repositories.events.findById(eventId) ?: throw StorageFailure()
            if (event.hasParticipant(participantId) && !hasAnotherRegistered(repositories, event.getParticipantIds(), participantId)) {
                throw ConflictFailure("Event must retain a registered participant.")
            }
        }
        for (expenseId in participant.getExpenseIds()) {
            val expense = repositories.expenses.findById(expenseId) ?: throw StorageFailure()
            if (!hasAnotherRegistered(repositories, expense.getParticipantIds(), participantId)) {
                throw ConflictFailure("Expense must retain a registered author, payer or debtor.")
            }
        }
    }

    private fun hasAnotherRegistered(repositories: Repositories, participantIds: Set<Uuid>, excluded: Uuid): Boolean =
        participantIds.any { id ->
            id != excluded && (repositories.participants.findById(id) ?: throw StorageFailure()).hasUserId()
        }
}
