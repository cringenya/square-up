package squareup.api.user

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import squareup.api.http.*
import squareup.api.requireAccountSession
import squareup.application.account.Registration
import squareup.application.user.UserService
import squareup.application.user.UserUpdate

internal fun Route.userRoutes(users: UserService) {
    route("/users") {
        get("/me") {
            val session = call.requireAccountSession()
            call.respond(blocking { users.current(session).toResponse() })
        }
        get {
            val session = call.requireAccountSession()
            call.respond(blocking { users.findAll(session).map { it.toResponse() } })
        }
        post {
            val session = call.requireAccountSession()
            val request = call.receive<CreateUserRequest>()
            val user = blocking {
                users.create(session, Registration(parseId(request.participantId), request.email, request.login, request.password), parseRole(request.role))
            }
            call.respond(HttpStatusCode.Created, user.toResponse())
        }
        get("/{userId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("userId")
            call.respond(blocking { users.findById(session, id).toResponse() })
        }
        patch("/{userId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("userId")
            val request = call.receive<UpdateUserRequest>()
            call.respond(blocking { users.update(session, id, UserUpdate(request.firstName, request.lastName, request.login, request.password)).toResponse() })
        }
        patch("/{userId}/role") {
            val session = call.requireAccountSession()
            val id = call.pathId("userId")
            val request = call.receive<RoleRequest>()
            call.respond(blocking { users.changeRole(session, id, parseRole(request.role)).toResponse() })
        }
        delete("/{userId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("userId")
            blocking { users.delete(session, id) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
