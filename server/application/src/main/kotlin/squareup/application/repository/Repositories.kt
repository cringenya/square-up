package squareup.application.repository

interface Repositories {
    val participants: ParticipantRepository
    val users: UserRepository
    val events: EventRepository
    val expenses: ExpenseRepository
}
