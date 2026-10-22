package squareup.application.validation

import squareup.application.exception.ValidationFailure

internal fun <T> validateInput(block: () -> T): T {
    return try {
        block()
    } catch (failure: IllegalArgumentException) {
        throw ValidationFailure(failure.message ?: "Invalid input.")
    }
}
