package squareup

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import squareup.api.configureSquareUpApi
import squareup.application.account.AdminAccount
import squareup.bootstrap.ServerRuntime
import squareup.persistence.DatabaseConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ApiIntegrationTest {
    private val password = "A password for API tests"

    @Test
    fun `serves Ktor Swagger UI and complete resolvable OpenAPI without authentication`() {
        withRuntime { runtime ->
            testApplication {
                application { configureSquareUpApi(runtime.services, runtime.sessions, false, runtime::checkHealth) }
                for ((path, type) in listOf("/swagger" to "text/html", "/swagger/documentation.json" to "application/json")) {
                    val response = client.request(path)
                    assertEquals(HttpStatusCode.OK, response.status, path)
                    assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().contains(type), path)
                }
                assertTrue(client.request("/swagger").bodyAsText().contains("SwaggerUIBundle"))
                val document = client.request("/swagger/documentation.json").obj()
                assertEquals("3.0.3", document.string("openapi"))
                val paths = document.getValue("paths").jsonObject
                val operations = paths.values.flatMap { it.jsonObject.values }.map { it.jsonObject }
                assertEquals(33, operations.size)
                assertEquals(33, operations.map { it.string("operationId") }.toSet().size)
                for ((path, methods) in paths) {
                    for (operation in methods.jsonObject.values) {
                        val parameters = operation.jsonObject["parameters"]?.jsonArray.orEmpty().map { value ->
                            val parameter = value.jsonObject
                            val reference = parameter["\$ref"]?.jsonPrimitive?.content
                            if (reference == null) parameter else document.getValue("components").jsonObject
                                .getValue("parameters").jsonObject.getValue(reference.substringAfterLast('/')).jsonObject
                        }
                        val actualIds = parameters.map { it.jsonObject }.filter { it.string("in") == "path" }.map { it.string("name") }.toSet()
                        val expectedIds = Regex("\\{([^}]+)}").findAll(path).map { it.groupValues[1] }.toSet()
                        assertEquals(expectedIds, actualIds, path)
                        assertTrue(operation.jsonObject.getValue("responses").jsonObject.keys.any { it.startsWith("2") }, path)
                    }
                }
                fun checkReferences(value: JsonElement) {
                    when (value) {
                        is JsonObject -> {
                            val reference = value["\$ref"]?.jsonPrimitive?.content
                            if (reference != null) {
                                assertTrue(reference.startsWith("#/"))
                                var target: JsonElement = document
                                for (part in reference.removePrefix("#/").split('/')) target = target.jsonObject.getValue(part)
                            }
                            value.values.forEach(::checkReferences)
                        }
                        is JsonArray -> value.forEach(::checkReferences)
                        else -> Unit
                    }
                }
                checkReferences(document)
                assertEquals(HttpStatusCode.NotFound, client.request("/docs/").status)
                assertEquals(HttpStatusCode.NotFound, client.request("/docs/explorer.js").status)
                assertEquals(HttpStatusCode.Unauthorized, client.send(HttpMethod.Get, "/users/me").status)
            }
        }
    }

    @Test
    fun `performs registration event expense calculation profile editing and logout over HTTP`() {
        withRuntime { runtime ->
            testApplication {
                application { configureSquareUpApi(runtime.services, runtime.sessions, false, runtime::checkHealth) }
                val admin = createClient { install(HttpCookies) }
                assertEquals(HttpStatusCode.OK, admin.login("admin").status)
                val free = admin.send(HttpMethod.Post, "/participants", """{"firstName":"Анна","lastName":"Иванова"}""").created()
                val registered = client.send(HttpMethod.Post, "/auth/register", """{"participantId":"${free.id}","email":"anna@example.test","login":"anna","password":"$password"}""").created()
                val user = createClient { install(HttpCookies) }
                val loggedIn = user.login("anna")
                assertEquals(HttpStatusCode.OK, loggedIn.status)
                assertFalse(loggedIn.bodyAsText().contains("password", ignoreCase = true))
                assertEquals(registered.id, user.send(HttpMethod.Get, "/users/me").obj().id)
                assertEquals(HttpStatusCode.OK, user.send(HttpMethod.Patch, "/participants/${free.id}", """{"firstName":"Аня"}""").status)
                assertEquals("Аня", user.send(HttpMethod.Get, "/users/${registered.id}").obj().string("firstName"))
                val event = user.send(HttpMethod.Post, "/events", """{"name":"Поездка"}""").created()
                val guest = user.send(HttpMethod.Post, "/events/${event.id}/participants", """{"firstName":"Павел","lastName":"Петров"}""").created()
                assertEquals(2, user.send(HttpMethod.Get, "/events/${event.id}/participants").array().size)
                assertEquals(1, user.send(HttpMethod.Get, "/events").array().size)
                val expense = user.send(HttpMethod.Post, "/events/${event.id}/expenses", """{"name":"Обед","amountKopecks":100,"payerId":"${free.id}","debtorIds":["${free.id}","${guest.id}"]}""").created()
                assertEquals(expense, user.send(HttpMethod.Get, "/expenses/${expense.id}").obj())
                assertEquals(100, expense.getValue("shares").jsonObject.values.sumOf { it.jsonPrimitive.long })
                assertEquals(1, user.send(HttpMethod.Get, "/events/${event.id}/expenses").array().size)
                assertEquals(1, user.send(HttpMethod.Get, "/participants/${free.id}/expenses").array().size)
                val calculation = user.send(HttpMethod.Get, "/events/${event.id}/calculation").obj()
                val transfer = calculation.getValue("transfers").jsonArray.single().jsonObject
                assertEquals(guest.id, transfer.string("fromParticipantId"))
                assertEquals(free.id, transfer.string("toParticipantId"))
                assertEquals(50, transfer.getValue("amountKopecks").jsonPrimitive.long)
                val renamed = user.send(HttpMethod.Patch, "/expenses/${expense.id}", """{"name":"Ужин"}""").obj()
                assertEquals(expense.getValue("shares"), renamed.getValue("shares"))
                assertEquals(HttpStatusCode.OK, user.send(HttpMethod.Patch, "/events/${event.id}", """{"name":"Выходные"}""").status)
                assertEquals(HttpStatusCode.NoContent, user.send(HttpMethod.Post, "/auth/logout").status)
                assertEquals(HttpStatusCode.Unauthorized, user.send(HttpMethod.Get, "/users/me").status)
            }
        }
    }

    @Test
    fun `provides admin CRUD role changes and membership management without joining event`() {
        withRuntime { runtime ->
            testApplication {
                application { configureSquareUpApi(runtime.services, runtime.sessions, false, runtime::checkHealth) }
                val admin = createClient { install(HttpCookies) }
                val adminProfile = admin.login("admin").obj()
                val adminParticipant = adminProfile.string("participantId")
                val participant = admin.send(HttpMethod.Post, "/participants", """{"firstName":"Иван","lastName":"Иванов"}""").created()
                val account = admin.send(HttpMethod.Post, "/users", """{"participantId":"${participant.id}","email":"ivan@example.test","login":"ivan","password":"$password","role":"ADMIN"}""").created()
                assertEquals(2, admin.send(HttpMethod.Get, "/users").array().size)
                assertEquals(HttpStatusCode.OK, admin.send(HttpMethod.Patch, "/users/${account.id}/role", """{"role":"USER"}""").status)
                assertEquals(HttpStatusCode.OK, admin.send(HttpMethod.Patch, "/users/${account.id}", """{"login":"renamed","lastName":"Петров"}""").status)
                assertEquals("renamed", admin.send(HttpMethod.Get, "/users/${account.id}").obj().string("login"))
                assertEquals(HttpStatusCode.OK, admin.send(HttpMethod.Patch, "/participants/${participant.id}", """{"firstName":"Ваня"}""").status)
                assertEquals("Ваня", admin.send(HttpMethod.Get, "/participants/${participant.id}").obj().string("firstName"))
                assertEquals(2, admin.send(HttpMethod.Get, "/participants").array().size)
                val event = admin.send(HttpMethod.Post, "/events", """{"name":"Встреча","participantIds":["${participant.id}"]}""").created()
                assertFalse(event.getValue("participantIds").jsonArray.any { it.jsonPrimitive.content == adminParticipant })
                assertEquals(HttpStatusCode.OK, admin.send(HttpMethod.Put, "/events/${event.id}/participants/$adminParticipant").status)
                assertEquals(HttpStatusCode.NoContent, admin.send(HttpMethod.Delete, "/events/${event.id}/participants/$adminParticipant").status)
                val expense = admin.send(HttpMethod.Post, "/events/${event.id}/expenses", """{"name":"Такси","amountKopecks":500,"payerId":"${participant.id}","debtorIds":["${participant.id}"]}""").created()
                assertEquals(adminParticipant, expense.string("authorId"))
                assertEquals(1, admin.send(HttpMethod.Get, "/expenses").array().size)
                assertEquals(HttpStatusCode.Conflict, admin.send(HttpMethod.Delete, "/events/${event.id}").status)
                assertEquals(HttpStatusCode.NoContent, admin.send(HttpMethod.Delete, "/expenses/${expense.id}").status)
                assertEquals(HttpStatusCode.NoContent, admin.send(HttpMethod.Delete, "/events/${event.id}").status)
                assertEquals(HttpStatusCode.NotFound, admin.send(HttpMethod.Get, "/events/${event.id}").status)
                assertEquals(HttpStatusCode.NoContent, admin.send(HttpMethod.Delete, "/users/${account.id}").status)
                assertEquals(HttpStatusCode.NotFound, admin.send(HttpMethod.Get, "/users/${account.id}").status)
                assertEquals(HttpStatusCode.NoContent, admin.send(HttpMethod.Delete, "/participants/${participant.id}").status)
                assertEquals(HttpStatusCode.NotFound, admin.send(HttpMethod.Get, "/participants/${participant.id}").status)
                assertEquals(HttpStatusCode.Conflict, admin.send(HttpMethod.Patch, "/users/${adminProfile.id}/role", """{"role":"USER"}""").status)
            }
        }
    }

    @Test
    fun `validates JSON ids money immutable fields and content type`() {
        withRuntime { runtime ->
            testApplication {
                application { configureSquareUpApi(runtime.services, runtime.sessions, false, runtime::checkHealth) }
                val admin = createClient { install(HttpCookies) }
                val profile = admin.login("admin").obj()
                val participant = profile.string("participantId")
                assertEquals(HttpStatusCode.BadRequest, client.send(HttpMethod.Post, "/auth/login", "{").status)
                assertEquals(HttpStatusCode.BadRequest, client.send(HttpMethod.Post, "/auth/login", """{"login":"admin"}""").status)
                assertEquals(HttpStatusCode.BadRequest, client.send(HttpMethod.Post, "/auth/login", """{"login":"admin","password":"$password","role":"ADMIN"}""").status)
                assertEquals(HttpStatusCode.UnsupportedMediaType, client.request("/square-up/auth/login") { method = HttpMethod.Post; header(HttpHeaders.ContentType, "text/plain"); setBody("text") }.status)
                assertEquals(HttpStatusCode.Unauthorized, client.send(HttpMethod.Get, "/events").status)
                assertEquals(HttpStatusCode.Unauthorized, client.send(HttpMethod.Post, "/auth/login", """{"login":"admin","password":"Wrong password here"}""").status)
                assertEquals(HttpStatusCode.BadRequest, admin.send(HttpMethod.Get, "/events/invalid").status)
                assertEquals(HttpStatusCode.NotFound, admin.send(HttpMethod.Get, "/events/${Uuid.random()}").status)
                val event = admin.send(HttpMethod.Post, "/events", """{"name":"Проверка","participantIds":["$participant"]}""").created()
                for (amount in listOf("0", "-1", "4294967296", "1.5", "9223372036854775808")) {
                    val response = admin.send(HttpMethod.Post, "/events/${event.id}/expenses", """{"name":"Расход","amountKopecks":$amount,"payerId":"$participant","debtorIds":["$participant"]}""")
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals("INVALID_REQUEST", response.obj().string("code"))
                }
                val expense = admin.send(HttpMethod.Post, "/events/${event.id}/expenses", """{"name":"Большой расход","amountKopecks":4294967295,"payerId":"$participant","debtorIds":["$participant"]}""").created()
                assertEquals(4294967295, expense.getValue("amountKopecks").jsonPrimitive.long)
                for (field in listOf("authorId", "eventId")) assertEquals(HttpStatusCode.BadRequest, admin.send(HttpMethod.Patch, "/expenses/${expense.id}", """{"$field":"$participant"}""").status)
                val foreign = admin.request("/square-up/auth/logout") { method = HttpMethod.Post; header(HttpHeaders.Origin, "https://foreign.example") }
                assertEquals(HttpStatusCode.Forbidden, foreign.status)
                assertEquals(HttpStatusCode.OK, admin.send(HttpMethod.Get, "/users/me").status)
                val missing = client.send(HttpMethod.Get, "/missing")
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals("NOT_FOUND", missing.obj().string("code"))
            }
        }
    }

    @Test
    fun `enforces ownership unique accounts last registered member and read access after exit`() {
        withRuntime { runtime ->
            testApplication {
                application { configureSquareUpApi(runtime.services, runtime.sessions, false, runtime::checkHealth) }
                val admin = createClient { install(HttpCookies) }
                val adminProfile = admin.login("admin").obj()
                val first = admin.send(HttpMethod.Post, "/participants", """{"firstName":"Анна","lastName":"Иванова"}""").created()
                val second = admin.send(HttpMethod.Post, "/participants", """{"firstName":"Павел","lastName":"Петров"}""").created()
                val registration = """{"participantId":"${first.id}","email":"anna@example.test","login":"anna","password":"$password"}"""
                assertEquals(HttpStatusCode.Created, client.send(HttpMethod.Post, "/auth/register", registration).status)
                assertEquals(HttpStatusCode.Conflict, client.send(HttpMethod.Post, "/auth/register", registration).status)
                assertEquals(HttpStatusCode.Conflict, client.send(HttpMethod.Post, "/auth/register", """{"participantId":"${second.id}","email":"other@example.test","login":"anna","password":"$password"}""").status)
                val other = admin.send(HttpMethod.Post, "/users", """{"participantId":"${second.id}","email":"pavel@example.test","login":"pavel","password":"$password"}""").created()
                val user = createClient { install(HttpCookies) }
                user.login("anna")
                assertEquals(HttpStatusCode.Forbidden, user.send(HttpMethod.Get, "/users").status)
                assertEquals(HttpStatusCode.Forbidden, user.send(HttpMethod.Post, "/participants", """{"firstName":"Нельзя","lastName":"Создать"}""").status)
                assertEquals(HttpStatusCode.Forbidden, user.send(HttpMethod.Patch, "/participants/${second.id}", """{"firstName":"Нельзя"}""").status)
                assertEquals(HttpStatusCode.Forbidden, user.send(HttpMethod.Patch, "/users/${other.id}/role", """{"role":"ADMIN"}""").status)
                val event = user.send(HttpMethod.Post, "/events", """{"name":"Выход"}""").created()
                assertEquals(HttpStatusCode.Conflict, user.send(HttpMethod.Delete, "/events/${event.id}/participants/${first.id}").status)
                assertEquals(HttpStatusCode.OK, user.send(HttpMethod.Put, "/events/${event.id}/participants/${second.id}").status)
                assertEquals(HttpStatusCode.NoContent, user.send(HttpMethod.Delete, "/events/${event.id}/participants/${first.id}").status)
                assertEquals(HttpStatusCode.OK, user.send(HttpMethod.Get, "/events/${event.id}").status)
                assertEquals(HttpStatusCode.OK, user.send(HttpMethod.Get, "/events/${event.id}/calculation").status)
                assertEquals(HttpStatusCode.Forbidden, user.send(HttpMethod.Patch, "/events/${event.id}", """{"name":"Нельзя"}""").status)
                assertEquals(HttpStatusCode.Forbidden, user.send(HttpMethod.Delete, "/events/${event.id}").status)
                assertEquals(HttpStatusCode.Forbidden, user.send(HttpMethod.Get, "/participants/${adminProfile.string("participantId")}/expenses").status)
            }
        }
    }

    @Test
    fun `reports database readiness and invalidates all sessions after password change`() {
        withRuntime { runtime ->
            testApplication {
                application { configureSquareUpApi(runtime.services, runtime.sessions, false, runtime::checkHealth) }
                assertEquals("UP", client.request("/health").obj().string("status"))
                val first = createClient { install(HttpCookies) }
                val second = createClient { install(HttpCookies) }
                val profile = first.login("admin").obj()
                second.login("admin")
                val changed = first.send(HttpMethod.Patch, "/users/${profile.id}", """{"password":"A changed API password"}""")
                assertEquals(HttpStatusCode.OK, changed.status)
                assertEquals(HttpStatusCode.Unauthorized, first.send(HttpMethod.Get, "/users/me").status)
                assertEquals(HttpStatusCode.Unauthorized, second.send(HttpMethod.Get, "/users/me").status)
                assertEquals(HttpStatusCode.OK, second.send(HttpMethod.Post, "/auth/login", """{"login":"admin","password":"A changed API password"}""").status)
                runtime.close()
                val health = client.request("/health")
                assertEquals(HttpStatusCode.ServiceUnavailable, health.status)
                assertEquals("UNAVAILABLE", health.obj().string("code"))
                val failure = second.send(HttpMethod.Get, "/users/me")
                assertEquals(HttpStatusCode.InternalServerError, failure.status)
                assertFalse(failure.bodyAsText().contains("SQLException"))
            }
        }
    }

    private suspend fun HttpClient.login(login: String): HttpResponse = send(HttpMethod.Post, "/auth/login", """{"login":"$login","password":"$password"}""")

    private suspend fun HttpClient.send(verb: HttpMethod, path: String, body: String? = null): HttpResponse = request("/square-up$path") {
        method = verb
        if (body != null) {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.array(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray
    private suspend fun HttpResponse.created(): JsonObject {
        assertEquals(HttpStatusCode.Created, status, bodyAsText())
        return obj()
    }
    private val JsonObject.id: String get() = string("id")
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun withRuntime(block: (ServerRuntime) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            ServerRuntime(DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "", listOf("classpath:db/migration")), AdminAccount("Admin", "Person", "admin@example.test", "admin", password)).use(block)
        }
    }
}
