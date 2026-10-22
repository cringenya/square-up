package squareup.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.contentType
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.port
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import squareup.application.exception.AccessDeniedFailure
import squareup.application.account.AccountLogin
import squareup.application.account.AccountService
import squareup.application.exception.AuthenticationFailure
import squareup.application.security.SessionManager
import java.net.URI
import java.net.URISyntaxException

@Serializable
data class SessionCookie(val id: String)

fun Application.configureAccountSessions(sessions: SessionManager, secureCookies: Boolean = true) {
    install(Sessions) {
        cookie<SessionCookie>("squareup_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = secureCookies
            cookie.maxAgeInSeconds = sessions.lifetime.seconds
            cookie.extensions["SameSite"] = "Strict"
        }
    }
    install(AccountRequestProtection)
}

fun ApplicationCall.requireAccountSession(): String =
    accountSessionId() ?: throw AuthenticationFailure()

fun ApplicationCall.setAccountSession(login: AccountLogin, accounts: AccountService) {
    val previous = accountSessionId()
    if (previous != null) accounts.logout(previous)
    sessions.set(SessionCookie(login.session.id))
}

fun ApplicationCall.clearAccountSession(accounts: AccountService) {
    val previous = accountSessionId()
    if (previous != null) accounts.logout(previous)
    sessions.clear<SessionCookie>()
}

private fun ApplicationCall.accountSessionId(): String? {
    return try {
        sessions.get<SessionCookie>()?.id
    } catch (failure: IllegalArgumentException) {
        null
    }
}

private val AccountRequestProtection = createApplicationPlugin("AccountRequestProtection") {
    val safeMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)
    onCall { call ->
        if (call.request.httpMethod !in safeMethods) {
            val origin = call.request.headers[HttpHeaders.Origin]
            val site = call.request.headers["Sec-Fetch-Site"]
            if ((origin != null && !hasSameOrigin(call, origin)) || (site != null && site !in setOf("same-origin", "none"))) {
                throw AccessDeniedFailure()
            }
            val contentType = call.request.headers[HttpHeaders.ContentType]
            if (contentType != null && call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                throw UnsupportedMediaTypeException(call.request.contentType())
            }
        }
    }
}

private fun hasSameOrigin(call: ApplicationCall, value: String): Boolean {
    return try {
        val origin = URI(value)
        val port = if (origin.port != -1) origin.port else if (origin.scheme == "https") 443 else 80
        origin.scheme == call.request.origin.scheme && origin.host.equals(call.request.host(), ignoreCase = true) &&
            port == call.request.port() && origin.rawUserInfo == null && origin.rawQuery == null &&
            origin.rawFragment == null && origin.rawPath.isNullOrEmpty()
    } catch (failure: URISyntaxException) {
        false
    }
}
