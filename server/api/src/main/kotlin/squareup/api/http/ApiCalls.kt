package squareup.api.http

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import squareup.application.exception.ValidationFailure
import squareup.domain.money.Money
import squareup.domain.user.UserRole
import kotlin.uuid.Uuid

internal suspend fun <T> blocking(block: () -> T): T = withContext(Dispatchers.IO) { block() }

internal fun parseId(value: String): Uuid = try {
    Uuid.parse(value)
} catch (failure: IllegalArgumentException) {
    throw ValidationFailure("UUID must have valid format: " + failure.message)
}

internal fun ApplicationCall.pathId(name: String): Uuid = parseId(parameters[name] ?: throw ValidationFailure("UUID must be specified."))

internal fun parseMoney(value: Long): Money {
    if (value < 1 || value > UInt.MAX_VALUE.toLong()) throw ValidationFailure("Amount must be between 1 and 4294967295 kopecks.")
    return Money(value.toUInt())
}

internal fun parseRole(value: String): UserRole = UserRole.entries.find { it.name == value }
    ?: throw ValidationFailure("Role must be USER or ADMIN.")
