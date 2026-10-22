package squareup.application.security

interface PasswordHasher {
    fun hash(password: String): String
    fun matches(password: String, encoded: String): Boolean
}
