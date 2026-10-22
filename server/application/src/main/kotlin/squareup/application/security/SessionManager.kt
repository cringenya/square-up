package squareup.application.security

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class SessionManager(val lifetime: Duration = Duration.ofHours(1), private val clock: Clock = Clock.systemUTC()) {
    private val monitor = Any()
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    init {
        require(lifetime.seconds > 0 && lifetime.nano == 0) { "Session lifetime must be a positive number of seconds." }
    }

    fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }

    fun create(userId: Uuid): SessionCredentials = withLock {
        val now = clock.instant()
        sessions.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        var id: String
        do {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } while (sessions.containsKey(id))
        val expiresAt = now.plus(lifetime)
        sessions[id] = SessionRecord(userId, expiresAt)
        SessionCredentials(id, expiresAt)
    }

    fun findUserId(id: String): Uuid? {
        val record = sessions[id]
        return if (record == null || !record.expiresAt.isAfter(clock.instant())) {
            if (record != null) sessions.remove(id, record)
            null
        } else {
            record.userId
        }
    }

    fun remove(id: String) {
        withLock { sessions.remove(id) }
    }

    fun removeByUserId(userId: Uuid) {
        withLock { sessions.entries.removeIf { it.value.userId == userId } }
    }

    private class SessionRecord(val userId: Uuid, val expiresAt: Instant)
}
