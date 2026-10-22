package squareup.application.account

import squareup.application.exception.ConflictFailure
import squareup.application.exception.NotFoundFailure
import squareup.application.repository.Repositories
import squareup.application.security.PasswordHasher
import squareup.application.validation.validateInput
import squareup.domain.user.User
import squareup.domain.user.UserRole

internal fun createUser(repositories: Repositories, registration: Registration, role: UserRole, passwords: PasswordHasher): User {
    val participant = repositories.participants.findById(registration.participantId)
        ?: throw NotFoundFailure("Participant was not found.")
    if (participant.hasUserId()) throw ConflictFailure("Participant is already linked to a user.")
    if (repositories.users.findByLogin(registration.login.trim()) != null) throw ConflictFailure("Login is already in use.")
    val user = validateInput {
        User.Builder().withParticipant(participant).withEmail(registration.email).withLogin(registration.login)
            .withPassword(passwords.hash(registration.password)).withRole(role).build()
    }
    repositories.users.save(user)
    return user
}
