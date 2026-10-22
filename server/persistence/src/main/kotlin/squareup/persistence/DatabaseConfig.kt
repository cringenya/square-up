package squareup.persistence

class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    migrationLocations: List<String>,
    val maximumPoolSize: Int = 10,
    val connectionTimeoutMillis: Long = 30_000
) {
    val migrationLocations: List<String> = migrationLocations.toList()

    init {
        require(jdbcUrl.startsWith("jdbc:postgresql:")) { "Database URL must use PostgreSQL JDBC." }
        require(username.isNotBlank()) { "Database username must not be blank." }
        require(this.migrationLocations.isNotEmpty()) { "Migration locations must not be empty." }
        require(this.migrationLocations.all { it.isNotBlank() }) { "Migration location must not be blank." }
        require(maximumPoolSize > 0) { "Maximum pool size must be positive." }
        require(connectionTimeoutMillis >= 250) { "Connection timeout must be at least 250 milliseconds." }
    }
}
