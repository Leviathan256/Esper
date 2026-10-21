package com.esper.server.auth

import com.esper.server.Db
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import java.sql.ResultSet
import java.util.Optional

/**
 * Yubico's view of `passkeys` + `accounts`.
 *
 * Esper is usernameless (discoverable credentials only), so the username is
 * derived from the user handle and the username lookups that only serve
 * username-first flows return nothing.
 */
class CredentialStore(private val db: Db) : CredentialRepository {

    // Only feeds excludeCredentials on registration; every registration is a new account.
    override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> = emptySet()

    override fun getUserHandleForUsername(username: String): Optional<ByteArray> = Optional.empty()

    override fun getUsernameForUserHandle(userHandle: ByteArray): Optional<String> =
        db.tx { c ->
            c.prepareStatement("SELECT 1 FROM accounts WHERE user_handle = ?").use {
                it.setBytes(1, userHandle.bytes)
                it.executeQuery().use { rs -> if (rs.next()) Optional.of(usernameFor(userHandle.bytes)) else Optional.empty() }
            }
        }

    override fun lookup(credentialId: ByteArray, userHandle: ByteArray): Optional<RegisteredCredential> =
        Optional.ofNullable(query(credentialId).firstOrNull { it.userHandle == userHandle })

    override fun lookupAll(credentialId: ByteArray): Set<RegisteredCredential> = query(credentialId).toSet()

    private fun query(credentialId: ByteArray): List<RegisteredCredential> =
        db.tx { c ->
            c.prepareStatement(
                """
                SELECT p.credential_id, a.user_handle, p.public_key, p.sign_count
                FROM passkeys p JOIN accounts a ON a.id = p.account_id
                WHERE p.credential_id = ?
                """.trimIndent(),
            ).use {
                it.setBytes(1, credentialId.bytes)
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) toCredential(rs) else null }.toList() }
            }
        }

    private fun toCredential(rs: ResultSet): RegisteredCredential =
        RegisteredCredential.builder()
            .credentialId(ByteArray(rs.getBytes(1)))
            .userHandle(ByteArray(rs.getBytes(2)))
            .publicKeyCose(ByteArray(rs.getBytes(3)))
            .signatureCount(rs.getLong(4))
            .build()

    companion object {
        /** "esper-" + first 8 hex of the handle: what the passkey provider shows. */
        fun usernameFor(userHandle: kotlin.ByteArray): String =
            "esper-" + userHandle.take(4).joinToString("") { "%02x".format(it) }
    }
}
