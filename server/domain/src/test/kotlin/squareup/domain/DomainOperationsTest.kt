package squareup.domain

import squareup.domain.user.Participant
import squareup.domain.user.User
import squareup.domain.user.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DomainOperationsTest {
    @Test
    fun userUpdateChangesOwnProfileAndCredentialsWithoutChangingIdentityOrHistory() {
        val participant = Participant.build("Anna", "Ivanova")
        val user = createUser(participant)
        val event = createEvent(participant)
        val expense = createExpense(event, participant, participant, setOf(participant))
        val userId = user.getId()

        user.update("Maria", "Petrova", " NewLogin ", "new-password-hash")

        assertEquals("Maria", user.getFirstName())
        assertEquals("Petrova", user.getLastName())
        assertEquals("Maria", participant.getFirstName())
        assertEquals("NewLogin", user.getLogin())
        assertEquals("new-password-hash", user.getPasswordHash())
        assertEquals(userId, user.getId())
        assertEquals(userId, participant.getUserId())
        assertEquals(participant.getParticipantId(), user.getParticipantId())
        assertEquals(setOf(event.getId()), participant.getEventIds())
        assertEquals(setOf(expense.getId()), participant.getExpenseIds())
        assertEquals(UserRole.USER, user.getRole())
    }

    @Test
    fun userDefaultArgumentsPreserveCurrentValues() {
        val participant = Participant.build("Anna", "Ivanova")
        val user = createUser(participant)
        val passwordHash = user.getPasswordHash()

        user.update(login = "ChangedLogin")
        user.update(firstName = "Maria")
        user.update()

        assertEquals("ChangedLogin", user.getLogin())
        assertEquals("Maria", user.getFirstName())
        assertEquals("Ivanova", user.getLastName())
        assertEquals(passwordHash, user.getPasswordHash())
    }

    @Test
    fun invalidUserUpdatesPreserveAllFields() {
        val participant = Participant.build("Anna", "Ivanova")
        val user = createUser(participant)
        val previous = userState(user)
        val operations = listOf<() -> Unit>(
            { user.update(firstName = "Maria", login = " ") },
            { user.update(firstName = "Maria", passwordHash = " ") },
            { user.update(firstName = " ", login = "Changed") },
            { user.update(firstName = "Maria", lastName = " ", login = "Changed") }
        )

        for (operation in operations) {
            assertFailsWith<IllegalArgumentException> { operation() }
            assertEquals(previous, userState(user))
        }
    }

    @Test
    fun userRoleIsExplicitlyCreatedRestoredAndChanged() {
        val participant = Participant.build("Anna", "Ivanova")
        val user = createUser(participant, UserRole.ADMIN)

        val restored = User.Builder().restore(
            user.getEmail(), user.getLogin(), user.getPasswordHash(), participant, user.getId(), UserRole.ADMIN
        )

        assertEquals(UserRole.ADMIN, restored.getRole())
        assertEquals(user.getId(), restored.getId())
        assertEquals(user.getId(), participant.getUserId())
        restored.changeRole(UserRole.USER)
        assertEquals(UserRole.USER, restored.getRole())
        assertEquals(UserRole.ADMIN, user.getRole())
    }

    @Test
    fun restoringUserWithUnlinkedParticipantIsRejected() {
        val participant = Participant.build("Anna", "Ivanova")

        assertFailsWith<IllegalArgumentException> {
            User.Builder().restore("anna@example.test", "Anna", "hash", participant, Uuid.random(), UserRole.USER)
        }

        assertTrue(!participant.hasUserId())
    }

    @Test
    fun participantNameUpdateValidatesBothNamesBeforeChangingEither() {
        val participant = Participant.build("Anna", "Ivanova")

        assertFailsWith<IllegalArgumentException> { participant.updateName("Maria", " ") }

        assertEquals("Anna", participant.getFirstName())
        assertEquals("Ivanova", participant.getLastName())
        participant.updateName(lastName = "Petrova")
        assertEquals("Anna", participant.getFirstName())
        assertEquals("Petrova", participant.getLastName())
    }

    @Test
    fun eventUpdatePreservesMembershipExpensesAndIdentity() {
        val participant = Participant.build("Anna", "Ivanova")
        createUser(participant)
        val event = createEvent(participant)
        val expense = createExpense(event, participant, participant, setOf(participant))
        val eventId = event.getId()

        event.update(" New trip ")
        assertFailsWith<IllegalArgumentException> { event.update(" ") }

        assertEquals("New trip", event.getName())
        assertEquals(eventId, event.getId())
        assertEquals(setOf(participant.getParticipantId()), event.getParticipantIds())
        assertEquals(listOf(expense.getId()), event.getExpenseIds())
    }

    @Test
    fun deletingExpenseClearsHistoryIncludingDepartedParticipants() {
        val author = Participant.build("Anna", "Ivanova")
        createUser(author)
        val payer = Participant.build("Pavel", "Petrov")
        val debtor = Participant.build("Maria", "Petrova")
        val event = createEvent(author, payer, debtor)
        val expense = createExpense(event, payer, author, setOf(debtor))
        event.removeParticipant(debtor)

        event.removeExpense(expense, contextOf(author, payer, debtor))
        event.removeExpense(expense, emptyMap())

        assertTrue(event.getExpenseIds().isEmpty())
        assertTrue(author.getExpenseIds().isEmpty())
        assertTrue(payer.getExpenseIds().isEmpty())
        assertTrue(debtor.getExpenseIds().isEmpty())
        assertEquals(setOf(event.getId()), debtor.getEventIds())
    }

    @Test
    fun deletingOneExpensePreservesOtherExpensesAndHistory() {
        val author = Participant.build("Anna", "Ivanova")
        createUser(author)
        val guest = Participant.build("Pavel", "Petrov")
        val event = createEvent(author, guest)
        val removed = createExpense(event, author, author, setOf(guest))
        val retained = createExpense(event, guest, author, setOf(author))

        event.removeExpense(removed, contextOf(author, guest))

        assertEquals(listOf(retained.getId()), event.getExpenseIds())
        assertEquals(setOf(retained.getId()), author.getExpenseIds())
        assertEquals(setOf(retained.getId()), guest.getExpenseIds())
    }

    @Test
    fun invalidDeletionContextPreservesExpenseAndAllHistory() {
        val author = Participant.build("Anna", "Ivanova")
        createUser(author)
        val guest = Participant.build("Pavel", "Petrov")
        val outsider = Participant.build("Maria", "Petrova")
        val event = createEvent(author, guest)
        val expense = createExpense(event, author, author, setOf(guest))
        val contexts = listOf(
            contextOf(author),
            contextOf(author, guest, outsider),
            mapOf(author.getParticipantId() to author, guest.getParticipantId() to outsider)
        )

        for (context in contexts) {
            assertFailsWith<IllegalArgumentException> { event.removeExpense(expense, context) }
            assertEquals(listOf(expense.getId()), event.getExpenseIds())
            assertEquals(setOf(expense.getId()), author.getExpenseIds())
            assertEquals(setOf(expense.getId()), guest.getExpenseIds())
            assertTrue(outsider.getExpenseIds().isEmpty())
        }
    }

    @Test
    fun deletingExpenseThroughAnotherEventIsRejected() {
        val author = Participant.build("Anna", "Ivanova")
        createUser(author)
        val event = createEvent(author)
        val anotherEvent = createEvent(author)
        val expense = createExpense(event, author, author, setOf(author))

        assertFailsWith<IllegalArgumentException> { anotherEvent.removeExpense(expense, contextOf(author)) }

        assertEquals(listOf(expense.getId()), event.getExpenseIds())
        assertTrue(anotherEvent.getExpenseIds().isEmpty())
        assertEquals(setOf(expense.getId()), author.getExpenseIds())
    }

    private fun userState(user: User): List<Any> = listOf(
        user.getId(), user.getParticipantId(), user.getFirstName(), user.getLastName(),
        user.getEmail(), user.getLogin(), user.getPasswordHash(), user.getRole()
    )

}
