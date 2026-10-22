package squareup.application.transaction

import squareup.application.repository.Repositories

interface TransactionManager {
    fun <T> transaction(block: (Repositories) -> T): T
}
