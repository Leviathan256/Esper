package com.esper.server.auth

import com.esper.server.Db
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Opaque bearer sessions (docs/SERVER.md §6). The raw token goes to the app once
 * and is never stored or logged here: the table holds only its SHA-256.
 *
 * shortcut: no token rotation; add it if a token ever leaks in practice.
 */
class Sessions(private val db: Db) {
    private val random = SecureRandom()

    /** Inside the caller's transaction, so account + passkey + session commit together. */
    fun issue(c: Connection, accountId: UUID, now: Instant = Instant.now()): String {
        val raw = ByteArray(32).also(random::nextBytes)
        c.prepareStatement(
            "INSERT INTO sessions (token_hash, account_id, created_at, last_used_at, expires_at) VALUES (?, ?, ?, ?, ?)",
        ).use {
            it.setBytes(1, sha256(raw))
            it.setObject(2, accountId)
            it.setTimestamp(3, Timestamp.from(now))
            it.setTimestamp(4, Timestamp.from(now))
            it.setTimestamp(5, Timestamp.from(now.plus(TTL)))
            it.executeUpdate()
        }
        // Keep only the newest MAX_PER_ACCOUNT, so scripted sign-ins cannot fill the disk.
        c.prepareStatement(
            """
            DELETE FROM sessions WHERE account_id = ? AND token_hash NOT IN
              (SELECT token_hash FROM sessions WHERE account_id = ? ORDER BY created_at DESC LIMIT ?)
            """.trimIndent(),
        ).use {
            it.setObject(1, accountId)
            it.setObject(2, accountId)
            it.setInt(3, MAX_PER_ACCOUNT)
            it.executeUpdate()
        }
        return B64.encodeToString(raw)
    }

    /** The account, or null for an unknown, malformed or expired token. Slides expiry at most once a day. */
    fun validate(token: String, now: Instant = Instant.now()): UUID? {
        val hash = hashOf(token) ?: return null
        return db.tx { c ->
            val (accountId, lastUsed) = c.prepareStatement(
                "SELECT account_id, last_used_at FROM sessions WHERE token_hash = ? AND expires_at > ?",
            ).use {
                it.setBytes(1, hash)
                it.setTimestamp(2, Timestamp.from(now))
                it.executeQuery().use { rs ->
                    if (!rs.next()) return@tx null
                    rs.getObject(1, UUID::class.java) to rs.getTimestamp(2).toInstant()
                }
            }
            if (lastUsed.isBefore(now.minus(SLIDE_AFTER))) {
                c.prepareStatement("UPDATE sessions SET last_used_at = ?, expires_at = ? WHERE token_hash = ?").use {
                    it.setTimestamp(1, Timestamp.from(now))
                    it.setTimestamp(2, Timestamp.from(now.plus(TTL)))
                    it.setBytes(3, hash)
                    it.executeUpdate()
                }
            }
            accountId
        }
    }

    fun delete(token: String) {
        val hash = hashOf(token) ?: return
        db.tx { c ->
            c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use {
                it.setBytes(1, hash)
                it.executeUpdate()
            }
        }
    }

    fun purgeExpired(now: Instant = Instant.now()) {
        db.tx { c ->
            c.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?").use {
                it.setTimestamp(1, Timestamp.from(now))
                it.executeUpdate()
            }
        }
    }

    private fun hashOf(token: String): ByteArray? =
        try {
            sha256(B64D.decode(token))
        } catch (e: IllegalArgumentException) {
            null
        }

    companion object {
        val TTL: Duration = Duration.ofDays(90)
        val SLIDE_AFTER: Duration = Duration.ofDays(1)
        const val MAX_PER_ACCOUNT = 10
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64D = Base64.getUrlDecoder()

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
