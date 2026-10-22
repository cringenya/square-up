package squareup.config

import squareup.persistence.DatabaseConfig
import java.time.Duration

class ServerConfiguration(
    val database: DatabaseConfig,
    val host: String,
    val port: Int,
    val sessionLifetime: Duration,
    val secureCookies: Boolean,
    val logLevel: String
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfiguration {

            fun required(name: String): String = environment[name]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("$name must not be blank.")

            val port = (environment["SERVER_PORT"] ?: "8080").toIntOrNull()
            require(port != null && port in 1..65535) { "SERVER_PORT must be between 1 and 65535." }

            val seconds = (environment["SESSION_SECONDS"] ?: "3600").toLongOrNull()
            require(seconds != null && seconds > 0) { "SESSION_SECONDS must be positive." }

            val secure = when (environment["COOKIE_SECURE"] ?: "true") {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("COOKIE_SECURE must be true or false.")
            }

            val host = environment["SERVER_HOST"] ?: "127.0.0.1"
            require(host.isNotBlank()) { "SERVER_HOST must not be blank." }

            val level = environment["LOG_LEVEL"] ?: "INFO"
            require(level in setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")) { "LOG_LEVEL must be a supported logging level." }

            val database = DatabaseConfig(
                required("DB_URL"),
                required("DB_USER"),
                required("DB_PASSWORD"),
                listOf("classpath:db/migration"))
            return ServerConfiguration(database, host, port, Duration.ofSeconds(seconds), secure, level)
        }
    }
}
