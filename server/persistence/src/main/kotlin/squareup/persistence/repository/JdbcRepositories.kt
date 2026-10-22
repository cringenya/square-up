package squareup.persistence.repository

import squareup.application.repository.Repositories
import squareup.persistence.JdbcOperations

internal class JdbcRepositories(sql: JdbcOperations) : Repositories {
    override val participants = JdbcParticipantRepository(sql)
    override val users = JdbcUserRepository(sql, participants)
    override val events = JdbcEventRepository(sql, participants)
    override val expenses = JdbcExpenseRepository(sql, participants, events)
}
