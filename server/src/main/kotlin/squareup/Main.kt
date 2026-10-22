package squareup

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import squareup.api.configureSquareUpApi
import squareup.bootstrap.ServerRuntime
import squareup.config.ServerConfiguration

fun main() {
    val configuration = ServerConfiguration.fromEnvironment()
    System.setProperty("LOG_LEVEL", configuration.logLevel)
    ServerRuntime(configuration.database, AdminConfiguration.fromEnvironment(), configuration.sessionLifetime).use { runtime ->
        val server = embeddedServer(Netty, host = configuration.host, port = configuration.port) {
            configureSquareUpApi(runtime.services, runtime.sessions, configuration.secureCookies, runtime::checkHealth)
        }
        Runtime.getRuntime().addShutdownHook(Thread({
            server.stop(1_000, 5_000)
            runtime.close()
        }, "squareup-shutdown"))
        server.start(wait = true)
    }
}
