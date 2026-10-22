package squareup.application.repository

import kotlin.uuid.Uuid
import squareup.domain.user.User

interface UserRepository {
    fun findById(id: Uuid): User?
    fun findByLogin(login: String): User?
    fun findAll(): List<User>
    fun save(user: User)
    fun delete(id: Uuid): Boolean
}
