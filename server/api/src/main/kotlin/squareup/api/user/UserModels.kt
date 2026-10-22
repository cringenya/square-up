package squareup.api.user

import kotlinx.serialization.Serializable
import squareup.application.user.UserProfile

@Serializable
class LoginRequest(val login: String, val password: String)

@Serializable
class RegisterRequest(val participantId: String, val email: String, val login: String, val password: String)

@Serializable
class CreateUserRequest(val participantId: String, val email: String, val login: String, val password: String, val role: String = "USER")

@Serializable
class UpdateUserRequest(val firstName: String? = null, val lastName: String? = null, val login: String? = null, val password: String? = null)

@Serializable
data class RoleRequest(val role: String)

@Serializable
data class UserResponse(val id: String, val participantId: String, val firstName: String, val lastName: String, val email: String, val login: String, val role: String)

internal fun UserProfile.toResponse(): UserResponse = UserResponse(id.toString(), participantId.toString(), firstName, lastName, email, login, role.name)
