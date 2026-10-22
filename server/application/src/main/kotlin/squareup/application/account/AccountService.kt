package squareup.application.account

import squareup.application.exception.AuthenticationFailure
import squareup.application.exception.ValidationFailure
import squareup.application.security.PasswordHasher
import squareup.application.security.SessionManager
import squareup.application.transaction.TransactionManager
import squareup.application.user.UserProfile
import squareup.application.validation.validateInput
import squareup.domain.user.Participant
import squareup.domain.user.UserRole

class AccountService(
    private val transactions: TransactionManager,
    private val passwords: PasswordHasher,
    private val sessions: SessionManager
) {
    fun register(registration: Registration): UserProfile = transactions.transaction { repositories ->
        UserProfile.from(createUser(repositories, registration, UserRole.USER, passwords))
    }

    fun login(login: String, password: String): AccountLogin = sessions.withLock {
        val user = transactions.transaction { repositories ->
            val user = repositories.users.findByLogin(login.trim()) ?: throw AuthenticationFailure()
            if (!passwords.matches(password, user.getPasswordHash())) throw AuthenticationFailure()
            UserProfile.from(user)
        }
        AccountLogin(sessions.create(user.id), user)
    }

    fun logout(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun bootstrapAdmin(account: AdminAccount?): UserProfile? = transactions.transaction { repositories ->
        if (repositories.users.findAll().isNotEmpty()) {
            null
        } else {
            val details = account ?: throw ValidationFailure("Initial admin configuration is required.")
            val participant = validateInput { Participant.build(details.firstName, details.lastName) }
            repositories.participants.save(participant)
            val registration = Registration(participant.getParticipantId(), details.email, details.login, details.password)
            UserProfile.from(createUser(repositories, registration, UserRole.ADMIN, passwords))
        }
    }
}
