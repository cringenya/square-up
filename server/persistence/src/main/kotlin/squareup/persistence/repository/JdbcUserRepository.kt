package squareup.persistence.repository

import squareup.application.repository.UserRepository
import squareup.domain.user.User
import squareup.domain.user.UserRole
import squareup.persistence.JdbcOperations
import squareup.persistence.checkIdentity
import squareup.persistence.uuid
import java.sql.ResultSet
import kotlin.uuid.Uuid

internal class JdbcUserRepository(
    private val sql: JdbcOperations,
    private val participants: JdbcParticipantRepository
) : UserRepository {
    private val loaded = mutableMapOf<Uuid, User>()
    private val select = "SELECT id, participant_id, email, login, password_hash, role FROM public.users"

    override fun findById(id: Uuid): User? {
        sql.checkOpen()
        loaded[id]?.let { return it }
        return sql.queryOne("$select WHERE id = ?", listOf(id), ::restore)
    }

    override fun findByLogin(login: String): User? {
        return sql.queryOne("$select WHERE login = ?", listOf(login), ::restore)
    }

    override fun findAll(): List<User> {
        return sql.query("$select ORDER BY id", emptyList(), ::restore)
    }

    override fun save(user: User) {
        val id = user.getId()
        sql.checkOpen()
        checkIdentity(loaded, id, user)
        val count = sql.update(
            "INSERT INTO public.users (id, participant_id, email, login, password_hash, role) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, login = EXCLUDED.login, password_hash = EXCLUDED.password_hash, role = EXCLUDED.role WHERE users.participant_id = EXCLUDED.participant_id",
            listOf(id, user.getParticipantId(), user.getEmail(), user.getLogin(), user.getPasswordHash(), user.getRole().name)
        )
        check(count == 1) { "Cannot change user's participant." }
        loaded[id] = user
    }

    override fun delete(id: Uuid): Boolean {
        val participantId = sql.queryOne(
            "DELETE FROM public.users WHERE id = ? RETURNING participant_id", listOf(id)
        ) { it.uuid("participant_id") } ?: return false
        participants.unlinkLoadedUser(participantId, id)
        loaded.remove(id)
        return true
    }

    private fun restore(row: ResultSet): User {
        val id = row.uuid("id")
        loaded[id]?.let { return it }
        val participant = checkNotNull(participants.findById(row.uuid("participant_id"))) {
            "Cannot restore user with missing participant."
        }
        val user = User.Builder().restore(
            row.getString("email"), row.getString("login"), row.getString("password_hash"),
            participant, id, UserRole.valueOf(row.getString("role"))
        )
        loaded[id] = user
        return user
    }
}
