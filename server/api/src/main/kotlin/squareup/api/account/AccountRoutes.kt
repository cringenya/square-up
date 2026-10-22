package squareup.api.account

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import squareup.api.clearAccountSession
import squareup.api.http.blocking
import squareup.api.http.parseId
import squareup.api.setAccountSession
import squareup.api.user.LoginRequest
import squareup.api.user.RegisterRequest
import squareup.api.user.toResponse
import squareup.application.account.AccountService
import squareup.application.account.Registration

internal fun Route.accountRoutes(accounts: AccountService) {
    route("/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()
            val user = blocking { accounts.register(Registration(parseId(request.participantId), request.email, request.login, request.password)) }
            call.respond(HttpStatusCode.Created, user.toResponse())
        }
        post("/login") {
            val request = call.receive<LoginRequest>()
            val login = blocking { accounts.login(request.login, request.password) }
            call.setAccountSession(login, accounts)
            call.respond(login.user.toResponse())
        }
        post("/logout") {
            call.clearAccountSession(accounts)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
