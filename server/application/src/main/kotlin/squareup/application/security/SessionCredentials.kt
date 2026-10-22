package squareup.application.security

import java.time.Instant

class SessionCredentials(val id: String, val expiresAt: Instant)
