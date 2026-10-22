package squareup.persistence

import squareup.application.repository.Repositories
import squareup.application.transaction.TransactionManager
import squareup.application.exception.ConflictFailure
import squareup.application.exception.StorageFailure
import squareup.application.exception.ValidationFailure
import squareup.persistence.repository.JdbcRepositories
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Level
import java.util.logging.Logger

class JdbcTransactionManager(private val database: JdbcDatabase) : TransactionManager {
    override fun <T> transaction(block: (Repositories) -> T): T {
        return try {
            database.transaction { connection ->
                connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
                block(JdbcRepositories(JdbcOperations(connection)))
            }
        } catch (failure: SQLException) {
            when (failure.sqlState) {
                "40001", "40P01" -> throw ConflictFailure("Concurrent transaction conflict.")
                "23505" -> throw ConflictFailure("Value is already in use.")
                "23503" -> throw ConflictFailure("Object is still referenced or a reference is missing.")
                "23514", "23502" -> throw ValidationFailure("Stored data violates a constraint.")
                else -> {
                    Logger.getLogger(JdbcTransactionManager::class.java.name).log(Level.SEVERE, "Storage transaction failed.", failure)
                    throw StorageFailure()
                }
            }
        }
    }
}
