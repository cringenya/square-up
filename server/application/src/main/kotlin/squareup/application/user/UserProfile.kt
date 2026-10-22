package squareup.application.user

import kotlin.uuid.Uuid
import squareup.domain.user.User
import squareup.domain.user.UserRole

data class UserProfile(
    val id: Uuid,
    val participantId: Uuid,
    val firstName: String,
    val lastName: String,
    val email: String,
    val login: String,
    val role: UserRole
) {
    companion object {
        fun from(user: User): UserProfile = UserProfile(
            user.getId(), user.getParticipantId(), user.getFirstName(), user.getLastName(),
            user.getEmail(), user.getLogin(), user.getRole()
        )
    }
}
