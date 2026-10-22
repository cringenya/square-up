package squareup.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection

class JdbcDatabase(config: DatabaseConfig) : AutoCloseable {
    private val dataSource: HikariDataSource

    init {
        Flyway.configure()
            .dataSource(config.jdbcUrl, config.username, config.password)
            .configuration(mapOf("flyway.locations" to config.migrationLocations.joinToString(",")))
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .load()
            .migrate()

        val poolConfig = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.maximumPoolSize
            connectionTimeout = config.connectionTimeoutMillis
        }
        dataSource = HikariDataSource(poolConfig)
    }

    fun <T> transaction(block: (Connection) -> T): T {
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                try {
                    connection.rollback()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
    }

    override fun close() {
        dataSource.close()
    }
}
