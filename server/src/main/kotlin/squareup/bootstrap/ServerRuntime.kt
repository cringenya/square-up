package squareup.bootstrap

import squareup.api.ApiServices
import squareup.application.account.AccountService
import squareup.application.account.AdminAccount
import squareup.application.event.EventService
import squareup.application.exception.StorageFailure
import squareup.application.expense.ExpenseService
import squareup.application.participant.ParticipantService
import squareup.application.security.AccessPolicy
import squareup.application.security.Pbkdf2PasswordHasher
import squareup.application.security.SessionManager
import squareup.application.user.UserService
import squareup.persistence.DatabaseConfig
import squareup.persistence.JdbcDatabase
import squareup.persistence.JdbcTransactionManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class ServerRuntime(databaseConfig: DatabaseConfig, admin: AdminAccount?, sessionLifetime: Duration = Duration.ofHours(1)) : AutoCloseable {
    val sessions = SessionManager(sessionLifetime)
    private val database = JdbcDatabase(databaseConfig)
    private val closed = AtomicBoolean(false)
    val services: ApiServices

    init {
        try {
            val transactions = JdbcTransactionManager(database)
            val passwords = Pbkdf2PasswordHasher()
            val access = AccessPolicy(sessions)
            services = ApiServices(
                AccountService(transactions, passwords, sessions),
                UserService(transactions, passwords, sessions, access),
                ParticipantService(transactions, access),
                EventService(transactions, access),
                ExpenseService(transactions, access)
            )
            services.accounts.bootstrapAdmin(admin)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun checkHealth() {
        database.transaction { connection ->
            if (!connection.isValid(2)) throw StorageFailure()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) database.close()
    }
}
