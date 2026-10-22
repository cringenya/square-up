package squareup.application.exception

sealed class ApplicationFailure(message: String) : RuntimeException(message)

class ValidationFailure(message: String) : ApplicationFailure(message)
class NotFoundFailure(message: String) : ApplicationFailure(message)
class AuthenticationFailure : ApplicationFailure("Valid credentials are required.")
class AccessDeniedFailure : ApplicationFailure("Access is denied.")
class ConflictFailure(message: String) : ApplicationFailure(message)
class StorageFailure : ApplicationFailure("Cannot complete storage operation.")
