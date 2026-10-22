package squareup

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import squareup.api.clearAccountSession
import squareup.api.configureAccountSessions
import squareup.api.requireAccountSession
import squareup.api.setAccountSession
import squareup.application.exception.AccessDeniedFailure
import squareup.application.security.AccessPolicy
import squareup.application.account.AccountService
import squareup.application.account.AdminAccount
import squareup.application.exception.ApplicationFailure
import squareup.application.exception.AuthenticationFailure
import squareup.application.security.Pbkdf2PasswordHasher
import squareup.application.security.SessionManager
import squareup.application.user.UserService
import squareup.application.user.UserUpdate
import squareup.persistence.DatabaseConfig
import squareup.persistence.JdbcDatabase
import squareup.persistence.JdbcTransactionManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountSessionHttpTest {
    private val password = "Initial password here"
    private val changedPassword = "A different password here"

    @Test
    fun `browser sends cookie automatically and logout invalidates a copied cookie`() {
        withDatabase { database ->
            val services = Services(database)
            testApplication {
                application { configureTestRoutes(services, secureCookies = false) }
                val browser = createClient { install(HttpCookies) }
                assertEquals(HttpStatusCode.Unauthorized, browser.get("/test/me").status)
                val response = browser.post("/test/login")
                assertEquals(HttpStatusCode.OK, response.status)
                val header = assertNotNull(response.headers[HttpHeaders.SetCookie])
                assertTrue(header.contains("HttpOnly", ignoreCase = true))
                assertTrue(header.contains("SameSite=Strict", ignoreCase = true))
                assertTrue(header.contains("Max-Age=3600", ignoreCase = true))
                assertFalse(header.contains(password))
                assertFalse(header.contains(services.adminId))
                assertFalse(header.contains("ADMIN"))
                val copiedCookie = header.substringBefore(';')
                assertEquals("admin", browser.get("/test/me").bodyAsText())
                assertEquals(HttpStatusCode.Unauthorized, client.get("/test/me").status)
                assertEquals(HttpStatusCode.NoContent, browser.post("/test/logout").status)
                assertEquals(HttpStatusCode.Unauthorized, browser.get("/test/me").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/test/me") { header(HttpHeaders.Cookie, copiedCookie) }.status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/test/me") { header(HttpHeaders.Cookie, "squareup_session=invalid") }.status)
            }
        }
    }

    @Test
    fun `rejects foreign origins and form requests before changing session state`() {
        withDatabase { database ->
            val services = Services(database)
            testApplication {
                application { configureTestRoutes(services, secureCookies = false) }
                val browser = createClient { install(HttpCookies) }
                browser.post("/test/login")
                for (origin in listOf("https://other.example", "http://localhost:8888", "https://localhost", "null", "http://localhost/path")) {
                    assertEquals(HttpStatusCode.Forbidden, browser.post("/test/logout") { header(HttpHeaders.Origin, origin) }.status)
                    assertEquals(HttpStatusCode.OK, browser.get("/test/me").status)
                }
                assertEquals(HttpStatusCode.Forbidden, browser.post("/test/logout") { header("Sec-Fetch-Site", "same-site") }.status)
                assertEquals(HttpStatusCode.Forbidden, browser.post("/test/logout") { header("Sec-Fetch-Site", "cross-site") }.status)
                assertEquals(HttpStatusCode.UnsupportedMediaType, browser.post("/test/logout") { header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString()) }.status)
                assertEquals(HttpStatusCode.OK, browser.get("/test/me").status)
                assertEquals(HttpStatusCode.NoContent, browser.post("/test/logout") {
                    header(HttpHeaders.Origin, "http://localhost")
                    header("Sec-Fetch-Site", "same-origin")
                }.status)
                assertEquals(HttpStatusCode.Unauthorized, browser.get("/test/me").status)
            }
        }
    }

    @Test
    fun `enforces expiry despite a retained cookie and discards sessions on restart`() {
        withDatabase { database ->
            val clock = MutableClock()
            val services = Services(database, SessionManager(Duration.ofHours(1), clock))
            var copiedCookie = ""
            testApplication {
                application { configureTestRoutes(services, secureCookies = false) }
                val browser = createClient { install(HttpCookies) }
                val response = browser.post("/test/login")
                copiedCookie = assertNotNull(response.headers[HttpHeaders.SetCookie]).substringBefore(';')
                assertEquals(HttpStatusCode.OK, browser.get("/test/me").status)
                clock.now = clock.now.plusSeconds(3600)
                assertEquals(HttpStatusCode.Unauthorized, browser.get("/test/me").status)
                val renewed = browser.post("/test/login")
                copiedCookie = assertNotNull(renewed.headers[HttpHeaders.SetCookie]).substringBefore(';')
            }
            val restarted = Services(database)
            testApplication {
                application { configureTestRoutes(restarted, secureCookies = false) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/test/me") { header(HttpHeaders.Cookie, copiedCookie) }.status)
                assertEquals(HttpStatusCode.OK, client.post("/test/login").status)
            }
        }
    }

    @Test
    fun `sets secure cookie and rejects every previous session after password change`() {
        withDatabase { database ->
            val services = Services(database)
            testApplication {
                application { configureTestRoutes(services, secureCookies = true) }
                val response = client.post("/test/login")
                val header = assertNotNull(response.headers[HttpHeaders.SetCookie])
                assertTrue(header.contains("; Secure", ignoreCase = true))
            }
            testApplication {
                application { configureTestRoutes(services, secureCookies = false) }
                val first = createClient { install(HttpCookies) }
                val second = createClient { install(HttpCookies) }
                first.post("/test/login")
                second.post("/test/login")
                assertEquals(HttpStatusCode.OK, first.post("/test/password").status)
                assertEquals(HttpStatusCode.Unauthorized, first.get("/test/me").status)
                assertEquals(HttpStatusCode.Unauthorized, second.get("/test/me").status)
                assertEquals(HttpStatusCode.Unauthorized, second.post("/test/login").status)
            }
        }
    }

    private fun Application.configureTestRoutes(services: Services, secureCookies: Boolean) {
        install(StatusPages) {
            exception<ApplicationFailure> { call, failure ->
                val status = when (failure) {
                    is AuthenticationFailure -> HttpStatusCode.Unauthorized
                    is AccessDeniedFailure -> HttpStatusCode.Forbidden
                    else -> HttpStatusCode.BadRequest
                }
                call.respond(status)
            }
        }
        configureAccountSessions(services.sessions, secureCookies)
        routing {
            post("/test/login") {
                call.setAccountSession(services.accounts.login("admin", password), services.accounts)
                call.respondText("ok")
            }
            get("/test/me") {
                call.respondText(services.users.current(call.requireAccountSession()).login)
            }
            post("/test/logout") {
                call.clearAccountSession(services.accounts)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/test/password") {
                val session = call.requireAccountSession()
                val user = services.users.current(session)
                services.users.update(session, user.id, UserUpdate(password = changedPassword))
                call.respondText("ok")
            }
        }
    }

    private fun withDatabase(block: (JdbcDatabase) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            JdbcDatabase(DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "", listOf("classpath:db/migration"))).use(block)
        }
    }

    private inner class Services(database: JdbcDatabase, val sessions: SessionManager = SessionManager()) {
        private val transactions = JdbcTransactionManager(database)
        private val passwords = Pbkdf2PasswordHasher()
        val accounts = AccountService(transactions, passwords, sessions)
        val users = UserService(transactions, passwords, sessions, AccessPolicy(sessions))
        val adminId: String

        init {
            accounts.bootstrapAdmin(AdminAccount("Admin", "Person", "admin@example.test", "admin", password))
            adminId = accounts.login("admin", password).user.id.toString()
        }
    }

    private class MutableClock : Clock() {
        var now: Instant = Instant.now()
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
    }
}
