package squareup.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import squareup.api.account.accountRoutes
import squareup.api.event.eventRoutes
import squareup.api.expense.expenseRoutes
import squareup.api.http.blocking
import squareup.api.http.configureHttp
import squareup.api.http.error
import squareup.api.participant.participantRoutes
import squareup.api.user.userRoutes
import squareup.application.security.SessionManager

@Serializable
private data class HealthResponse(val status: String)

fun Application.configureSquareUpApi(services: ApiServices, sessions: SessionManager, secureCookies: Boolean = true, checkHealth: () -> Unit) {
    configureHttp()
    configureAccountSessions(sessions, secureCookies)
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.json")
        get("/health") {
            try {
                blocking(checkHealth)
                call.respond(HealthResponse("UP"))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                call.application.log.error("Readiness check failed.", failure)
                call.error(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE", "Database is unavailable.")
            }
        }
        route("/square-up") {
            accountRoutes(services.accounts)
            userRoutes(services.users)
            participantRoutes(services)
            eventRoutes(services)
            expenseRoutes(services.expenses)
        }
    }
}
