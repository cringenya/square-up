package squareup.application.security

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class SessionManagerTest {
    @Test
    fun `creates separate sessions and revokes one session or all sessions of a user`() {
        val sessions = SessionManager()
        val firstUser = Uuid.random()
        val secondUser = Uuid.random()
        val first = sessions.create(firstUser)
        val second = sessions.create(firstUser)
        val unrelated = sessions.create(secondUser)
        assertEquals(43, first.id.length)
        assertNotEquals(first.id, second.id)
        assertEquals(firstUser, sessions.findUserId(first.id))
        sessions.remove(first.id)
        sessions.remove(first.id)
        assertNull(sessions.findUserId(first.id))
        assertEquals(firstUser, sessions.findUserId(second.id))
        sessions.removeByUserId(firstUser)
        assertNull(sessions.findUserId(second.id))
        assertEquals(secondUser, sessions.findUserId(unrelated.id))
        assertNull(sessions.findUserId("unknown"))
        assertNull(SessionManager().findUserId(unrelated.id))
    }

    @Test
    fun `enforces absolute expiry on the server without extending it on reads`() {
        val clock = MutableClock()
        val sessions = SessionManager(Duration.ofSeconds(10), clock)
        val userId = Uuid.random()
        val session = sessions.create(userId)
        assertEquals(clock.instant().plusSeconds(10), session.expiresAt)
        clock.now = clock.now.plusSeconds(9)
        assertEquals(userId, sessions.findUserId(session.id))
        clock.now = clock.now.plusSeconds(1)
        assertNull(sessions.findUserId(session.id))
        sessions.create(userId)
        assertNull(sessions.findUserId(session.id))
    }

    @Test
    fun `rejects nonpositive or fractional session lifetimes`() {
        for (duration in listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(1500))) {
            assertFailsWith<IllegalArgumentException> { SessionManager(duration) }
        }
    }

    private class MutableClock : Clock() {
        var now: Instant = Instant.parse("2026-01-01T00:00:00Z")
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
    }
}
