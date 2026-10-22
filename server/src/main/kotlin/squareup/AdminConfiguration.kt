package squareup

import squareup.application.account.AdminAccount

object AdminConfiguration {
    fun fromEnvironment(environment: Map<String, String> = System.getenv()): AdminAccount? {
        val names = listOf("ADMIN_FIRST_NAME", "ADMIN_LAST_NAME", "ADMIN_EMAIL", "ADMIN_LOGIN", "ADMIN_PASSWORD")
        if (names.none { environment.containsKey(it) }) return null
        for (name in names) {
            require(!environment[name].isNullOrBlank()) { "$name must not be blank." }
        }
        return AdminAccount(
            environment.getValue("ADMIN_FIRST_NAME"), environment.getValue("ADMIN_LAST_NAME"),
            environment.getValue("ADMIN_EMAIL"), environment.getValue("ADMIN_LOGIN"), environment.getValue("ADMIN_PASSWORD")
        )
    }
}
