package squareup.api.event

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import squareup.api.ApiServices
import squareup.api.expense.CreateExpenseRequest
import squareup.api.expense.toResponse
import squareup.api.http.blocking
import squareup.api.http.parseId
import squareup.api.http.parseMoney
import squareup.api.http.pathId
import squareup.api.participant.CreateParticipantRequest
import squareup.api.participant.toResponse
import squareup.api.requireAccountSession
import squareup.application.expense.ExpenseCreation

internal fun Route.eventRoutes(services: ApiServices) {
    route("/events") {
        get {
            val session = call.requireAccountSession()
            call.respond(blocking { services.events.findAll(session).map { it.toResponse() } })
        }
        post {
            val session = call.requireAccountSession()
            val request = call.receive<CreateEventRequest>()
            call.respond(HttpStatusCode.Created, blocking { services.events.create(session, request.name, request.participantIds.mapTo(mutableSetOf(), ::parseId)).toResponse() })
        }
        get("/{eventId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("eventId")
            call.respond(blocking { services.events.findById(session, id).toResponse() })
        }
        patch("/{eventId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("eventId")
            val request = call.receive<UpdateEventRequest>()
            call.respond(blocking { services.events.update(session, id, request.name).toResponse() })
        }
        delete("/{eventId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("eventId")
            blocking { services.events.delete(session, id) }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{eventId}/participants") {
            val session = call.requireAccountSession()
            val id = call.pathId("eventId")
            call.respond(blocking { services.events.participants(session, id).map { it.toResponse() } })
        }
        post("/{eventId}/participants") {
            val session = call.requireAccountSession()
            val id = call.pathId("eventId")
            val request = call.receive<CreateParticipantRequest>()
            call.respond(HttpStatusCode.Created, blocking { services.events.createGuest(session, id, request.firstName, request.lastName).toResponse() })
        }
        put("/{eventId}/participants/{participantId}") {
            val session = call.requireAccountSession()
            val eventId = call.pathId("eventId")
            val participantId = call.pathId("participantId")
            call.respond(blocking { services.events.addParticipant(session, eventId, participantId).toResponse() })
        }
        delete("/{eventId}/participants/{participantId}") {
            val session = call.requireAccountSession()
            val eventId = call.pathId("eventId")
            val participantId = call.pathId("participantId")
            blocking { services.events.removeParticipant(session, eventId, participantId) }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{eventId}/expenses") {
            val session = call.requireAccountSession()
            val id = call.pathId("eventId")
            call.respond(blocking { services.expenses.findByEvent(session, id).map { it.toResponse() } })
        }
        post("/{eventId}/expenses") {
            val session = call.requireAccountSession()
            val id = call.pathId("eventId")
            val request = call.receive<CreateExpenseRequest>()
            val expense = blocking {
                services.expenses.create(session, id, ExpenseCreation(request.name, parseMoney(request.amountKopecks), parseId(request.payerId), request.debtorIds.mapTo(mutableSetOf(), ::parseId)))
            }
            call.respond(HttpStatusCode.Created, expense.toResponse())
        }
        get("/{eventId}/calculation") {
            val session = call.requireAccountSession()
            val id = call.pathId("eventId")
            call.respond(blocking { services.events.settlement(session, id).toResponse() })
        }
    }
}
