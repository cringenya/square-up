package squareup.domain.user

import kotlin.uuid.Uuid

class User private constructor(
    private var email: String,
    private var login: String,
    private var passwordHash: String,
    private val participant: Participant,
    private val userId: Uuid = Uuid.random(),
    private var role: UserRole = UserRole.USER
) {
    class Builder {
        private var email: String = ""
        private var login: String = ""
        private var passwordHash: String = ""
        private var participant: Participant? = null
        private var role: UserRole = UserRole.USER

        fun withEmail(email: String): Builder {
            require (email.isNotBlank() && email.trim().matches(Regex("""^[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+$"""))) { "Email must not be blank and must be of valid email format." }
            this.email = email.trim()
            return this
        }
        fun withLogin(login: String): Builder {
            require (login.isNotBlank()) { "Login must not be blank." }
            this.login = login.trim()
            return this
        }
        fun withPassword(passwordHash: String): Builder {
            require (passwordHash.isNotBlank()) { "Password must not be blank." }
            this.passwordHash = passwordHash.trim()
            return this
        }

        fun withParticipant(participant: Participant): Builder {
            this.participant = participant
            return this
        }

        fun withRole(role: UserRole): Builder {
            this.role = role
            return this
        }

        fun build(): User {
            require (email.isNotBlank()) { "Email must not be blank." }
            require (login.isNotBlank()) { "Login must not be blank." }
            require (passwordHash.isNotBlank()) { "Password must not be blank." }
            val userParticipant = requireNotNull(participant) { "Participant must not be null." }
            val user = User(email, login, passwordHash, userParticipant, role = role)
            userParticipant.linkUser(user)
            return user
        }

        fun restore(
            email: String,
            login: String,
            passwordHash: String,
            participant: Participant,
            userId: Uuid,
            role: UserRole
        ): User {
            require (email.isNotBlank()) { "Cannot restore user with blank email." }
            require (login.isNotBlank()) { "Cannot restore user with blank login." }
            require (passwordHash.isNotBlank()) { "Cannot restore user with blank password." }
            require(participant.hasUserId() && participant.getUserId() == userId) { "Cannot restore user with unlinked participant." }

            return User(email, login, passwordHash, participant, userId, role)
        }
    }

    fun getParticipantId(): Uuid {
        return participant.getParticipantId()
    }

    fun getId(): Uuid {
        return userId
    }

    fun getFirstName(): String {
        return participant.getFirstName()
    }

    fun getLastName(): String {
        return participant.getLastName()
    }

    fun getEmail(): String = email

    fun getLogin(): String = login

    fun getPasswordHash(): String = passwordHash

    fun getRole(): UserRole = role

    fun update(
        firstName: String = participant.getFirstName(),
        lastName: String = participant.getLastName(),
        login: String = this.login,
        passwordHash: String = this.passwordHash
    ) {
        require(login.isNotBlank()) { "Login must not be blank." }
        require(passwordHash.isNotBlank()) { "Password must not be blank." }
        val updatedLogin = login.trim()
        val updatedPasswordHash = passwordHash.trim()

        participant.updateName(firstName, lastName)
        this.login = updatedLogin
        this.passwordHash = updatedPasswordHash
    }

    fun changeRole(role: UserRole) {
        this.role = role
    }

}
