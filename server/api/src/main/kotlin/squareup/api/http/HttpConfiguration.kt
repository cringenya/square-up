package squareup.api.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import squareup.application.exception.AccessDeniedFailure
import squareup.application.exception.ApplicationFailure
import squareup.application.exception.AuthenticationFailure
import squareup.application.exception.ConflictFailure
import squareup.application.exception.NotFoundFailure
import squareup.application.exception.StorageFailure
import squareup.application.exception.ValidationFailure

@Serializable
internal data class ErrorResponse(val code: String, val message: String)

internal fun Application.configureHttp() {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true })
    }
    install(StatusPages) {
        exception<ApplicationFailure> { call, failure ->
            val status = when (failure) {
                is AuthenticationFailure -> HttpStatusCode.Unauthorized
                is AccessDeniedFailure -> HttpStatusCode.Forbidden
                is NotFoundFailure -> HttpStatusCode.NotFound
                is ConflictFailure -> HttpStatusCode.Conflict
                is ValidationFailure -> HttpStatusCode.BadRequest
                is StorageFailure -> HttpStatusCode.InternalServerError
            }
            call.error(status, statusCode(status), failure.message ?: "Cannot complete request.")
        }
        exception<BadRequestException> { call, _ -> call.error(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Request body or parameters are invalid.") }
        exception<UnsupportedMediaTypeException> { call, _ -> call.error(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "Content type must be application/json.") }
        exception<Exception> { call, failure ->
            if (failure is CancellationException) throw failure
            call.application.log.error("Request failed.", failure)
            call.error(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "Cannot complete request.")
        }
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed, HttpStatusCode.NotAcceptable) { call, status ->
            call.error(status, statusCode(status), status.description)
        }
    }
}

internal suspend fun ApplicationCall.error(status: HttpStatusCode, code: String, message: String) {
    respondText(Json.encodeToString(ErrorResponse.serializer(), ErrorResponse(code, message)), ContentType.Application.Json, status)
}

private fun statusCode(status: HttpStatusCode): String = when (status) {
    HttpStatusCode.BadRequest -> "INVALID_REQUEST"
    HttpStatusCode.Unauthorized -> "UNAUTHENTICATED"
    HttpStatusCode.Forbidden -> "ACCESS_DENIED"
    HttpStatusCode.NotFound -> "NOT_FOUND"
    HttpStatusCode.Conflict -> "CONFLICT"
    HttpStatusCode.MethodNotAllowed -> "METHOD_NOT_ALLOWED"
    HttpStatusCode.NotAcceptable -> "NOT_ACCEPTABLE"
    else -> "INTERNAL_ERROR"
}
