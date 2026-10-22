package squareup.persistence

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import squareup.application.transaction.TransactionManager

internal fun withTestDatabase(maximumPoolSize: Int = 2, block: (JdbcDatabase) -> Unit) {
    EmbeddedPostgres.start().use { postgres ->
        val config = DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "", listOf("classpath:db/migration"), maximumPoolSize = maximumPoolSize)
        JdbcDatabase(config).use(block)
    }
}

internal fun withTestTransactions(block: (TransactionManager) -> Unit) =
    withTestDatabase { block(JdbcTransactionManager(it)) }
