package squareup

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerProcessTest {
    private val password = "A password for process test"

    @Test
    fun `real server persists event shares and calculation after process restart while sessions expire`() {
        EmbeddedPostgres.start().use { postgres ->
            var eventId = ""
            var expenseId = ""
            var savedExpense = ""
            var savedCalculation = ""
            val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
            val client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(2)).build()
            withServer(postgres.getJdbcUrl("postgres", "postgres")) { base ->
                val admin = send(client, base, "POST", "/auth/login", """{"login":"admin","password":"$password"}""").obj()
                val participantId = admin.getValue("participantId").jsonPrimitive.content
                val event = send(client, base, "POST", "/events", """{"name":"Поездка","participantIds":["$participantId"]}""")
                assertEquals(201, event.statusCode())
                eventId = event.obj().id
                val guest = send(client, base, "POST", "/events/$eventId/participants", """{"firstName":"Павел","lastName":"Петров"}""").obj()
                val expense = send(client, base, "POST", "/events/$eventId/expenses", """{"name":"Обед","amountKopecks":101,"payerId":"$participantId","debtorIds":["$participantId","${guest.id}"]}""")
                assertEquals(201, expense.statusCode())
                expenseId = expense.obj().id
                savedExpense = expense.body()
                savedCalculation = send(client, base, "GET", "/events/$eventId/calculation").body()
                assertEquals(200, send(client, base, "GET", "/users/me").statusCode())
            }
            assertTrue(cookies.cookieStore.cookies.isNotEmpty())
            withServer(postgres.getJdbcUrl("postgres", "postgres")) { base ->
                assertEquals(401, send(client, base, "GET", "/users/me").statusCode())
                assertEquals(200, send(client, base, "POST", "/auth/login", """{"login":"admin","password":"$password"}""").statusCode())
                assertEquals(200, send(client, base, "GET", "/events/$eventId").statusCode())
                assertEquals(Json.parseToJsonElement(savedExpense), Json.parseToJsonElement(send(client, base, "GET", "/expenses/$expenseId").body()))
                assertEquals(Json.parseToJsonElement(savedCalculation), Json.parseToJsonElement(send(client, base, "GET", "/events/$eventId/calculation").body()))
            }
        }
    }

    private fun withServer(jdbcUrl: String, block: (String) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val output = Files.createTempFile("squareup-server-", ".log")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(java, "-cp", System.getProperty("squareup.runtime.classpath"), "squareup.MainKt")
            .redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment().putAll(mapOf(
            "DB_URL" to jdbcUrl, "DB_USER" to "postgres", "DB_PASSWORD" to "test-only-password",
            "SERVER_HOST" to "127.0.0.1", "SERVER_PORT" to port.toString(), "SESSION_SECONDS" to "3600", "COOKIE_SECURE" to "false", "LOG_LEVEL" to "WARN",
            "ADMIN_LOGIN" to "admin", "ADMIN_EMAIL" to "admin@example.test", "ADMIN_PASSWORD" to password, "ADMIN_FIRST_NAME" to "Admin", "ADMIN_LAST_NAME" to "Person"
        ))
        val process = builder.start()
        try {
            val base = "http://127.0.0.1:$port"
            val probe = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var ready = false
            while (!ready && process.isAlive && System.nanoTime() < deadline) {
                try {
                    ready = probe.send(HttpRequest.newBuilder(URI("$base/health")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200
                } catch (failure: IOException) {
                    ready = false
                }
                if (!ready) Thread.sleep(50)
            }
            assertTrue(ready, Files.readString(output))
            block(base)
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            }
            assertFalse(Files.readString(output).contains(password))
            Files.deleteIfExists(output)
        }
    }

    private fun send(client: HttpClient, base: String, method: String, path: String, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("$base/square-up$path")).timeout(Duration.ofSeconds(5))
        request.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (body != null) request.header("Content-Type", "application/json")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<String>.obj(): JsonObject = Json.parseToJsonElement(body()).jsonObject
    private val JsonObject.id: String get() = getValue("id").jsonPrimitive.content
}
