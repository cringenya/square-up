package squareup.api.participant

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import squareup.api.ApiServices
import squareup.api.expense.toResponse
import squareup.api.http.blocking
import squareup.api.http.pathId
import squareup.api.requireAccountSession

internal fun Route.participantRoutes(services: ApiServices) {
    route("/participants") {
        get {
            val session = call.requireAccountSession()
            call.respond(blocking { services.participants.findAll(session).map { it.toResponse() } })
        }
        post {
            val session = call.requireAccountSession()
            val request = call.receive<CreateParticipantRequest>()
            call.respond(HttpStatusCode.Created, blocking { services.participants.create(session, request.firstName, request.lastName).toResponse() })
        }
        get("/{participantId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("participantId")
            call.respond(blocking { services.participants.findById(session, id).toResponse() })
        }
        patch("/{participantId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("participantId")
            val request = call.receive<UpdateParticipantRequest>()
            call.respond(blocking { services.participants.update(session, id, request.firstName, request.lastName).toResponse() })
        }
        delete("/{participantId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("participantId")
            blocking { services.participants.delete(session, id) }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{participantId}/expenses") {
            val session = call.requireAccountSession()
            val id = call.pathId("participantId")
            call.respond(blocking { services.expenses.findByParticipant(session, id).map { it.toResponse() } })
        }
    }
}
