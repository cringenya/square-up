package squareup.api.expense

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import squareup.api.http.blocking
import squareup.api.http.parseId
import squareup.api.http.parseMoney
import squareup.api.http.pathId
import squareup.api.requireAccountSession
import squareup.application.expense.ExpenseService
import squareup.application.expense.ExpenseUpdate

internal fun Route.expenseRoutes(expenses: ExpenseService) {
    route("/expenses") {
        get {
            val session = call.requireAccountSession()
            call.respond(blocking { expenses.findAll(session).map { it.toResponse() } })
        }
        get("/{expenseId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("expenseId")
            call.respond(blocking { expenses.findById(session, id).toResponse() })
        }
        patch("/{expenseId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("expenseId")
            val request = call.receive<UpdateExpenseRequest>()
            call.respond(blocking {
                expenses.update(session, id, ExpenseUpdate(request.name, request.amountKopecks?.let(::parseMoney), request.payerId?.let(::parseId), request.debtorIds?.mapTo(mutableSetOf(), ::parseId))).toResponse()
            })
        }
        delete("/{expenseId}") {
            val session = call.requireAccountSession()
            val id = call.pathId("expenseId")
            blocking { expenses.delete(session, id) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
