package com.esper.server.auth

import com.esper.server.ApiError
import com.esper.server.Config
import com.esper.server.Db
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AttestationConveyancePreference
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import com.yubico.webauthn.exception.AssertionFailedException
import com.yubico.webauthn.exception.RegistrationFailedException
import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.io.IOException
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Serializable
data class VerifyBody(val ceremonyId: String, val response: String)

@Serializable
data class TokenBody(val token: String)

/**
 * Pending ceremonies by id, single use, [TTL] 5 minutes.
 *
 * shortcut: in-memory, restart drops in-flight ceremonies, single instance only; move to a table if we ever run two
 */
class Ceremonies<T : Any> {
    private class Pending<T>(val request: T, val expiresAt: Instant)

    private val pending = ConcurrentHashMap<String, Pending<T>>()
    private val random = SecureRandom()

    fun put(request: T, now: Instant = Instant.now()): String {
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
        pending[id] = Pending(request, now.plus(TTL))
        return id
    }

    /** Removes on read, so a replayed ceremony id fails even inside its TTL. */
    fun take(id: String, now: Instant = Instant.now()): T? =
        pending.remove(id)?.takeIf { now.isBefore(it.expiresAt) }?.request

    fun purge(now: Instant = Instant.now()) {
        pending.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(5)
    }
}

/** Passkey registration and sign-in (docs/SERVER.md §6). */
class Passkeys(config: Config, private val db: Db, private val sessions: Sessions) {
    // Origins match by exact string, which is what lets `android:apk-key-hash:…` through.
    private val rp = RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(config.rpId).name("Esper").build())
        .credentialRepository(CredentialStore(db))
        .origins(config.rpOrigins)
        .attestationConveyancePreference(AttestationConveyancePreference.NONE)
        .build()
    private val random = SecureRandom()

    val registrations = Ceremonies<PublicKeyCredentialCreationOptions>()
    val assertions = Ceremonies<AssertionRequest>()

    fun routes(route: Route) = with(route) {
        post("/v1/auth/register/options") {
            val handle = kotlin.ByteArray(32).also(random::nextBytes)
            val options = rp.startRegistration(
                StartRegistrationOptions.builder()
                    .user(
                        UserIdentity.builder()
                            .name(CredentialStore.usernameFor(handle))
                            .displayName("Esper player")
                            .id(ByteArray(handle))
                            .build(),
                    )
                    .authenticatorSelection(
                        AuthenticatorSelectionCriteria.builder()
                            .residentKey(ResidentKeyRequirement.REQUIRED)
                            .userVerification(UserVerificationRequirement.REQUIRED)
                            .build(),
                    )
                    .build(),
            )
            call.respondOptions(registrations.put(options), options.toCredentialsCreateJson())
        }

        post("/v1/auth/register/verify") {
            val body = call.receive<VerifyBody>()
            val options = registrations.take(body.ceremonyId) ?: throw invalidCeremony()
            val result = try {
                rp.finishRegistration(
                    FinishRegistrationOptions.builder()
                        .request(options)
                        .response(PublicKeyCredential.parseRegistrationResponseJson(body.response))
                        .build(),
                )
            } catch (e: RegistrationFailedException) {
                throw rejected()
            } catch (e: IOException) {
                throw rejected()
            } catch (e: IllegalArgumentException) {
                throw rejected()
            }
            val token = db.tx { c ->
                val accountId = c.prepareStatement("INSERT INTO accounts (user_handle) VALUES (?) RETURNING id").use {
                    it.setBytes(1, options.user.id.bytes)
                    it.executeQuery().use { rs -> rs.next(); rs.getObject(1, UUID::class.java) }
                }
                c.prepareStatement(
                    "INSERT INTO passkeys (credential_id, account_id, public_key, sign_count) VALUES (?, ?, ?, ?)",
                ).use {
                    it.setBytes(1, result.keyId.id.bytes)
                    it.setObject(2, accountId)
                    it.setBytes(3, result.publicKeyCose.bytes)
                    it.setLong(4, result.signatureCount)
                    it.executeUpdate()
                }
                sessions.issue(c, accountId)
            }
            call.respond(TokenBody(token))
        }

        post("/v1/auth/login/options") {
            // No username: discoverable credential, no allowCredentials.
            val request = rp.startAssertion(
                StartAssertionOptions.builder().userVerification(UserVerificationRequirement.REQUIRED).build(),
            )
            call.respondOptions(assertions.put(request), request.toCredentialsGetJson())
        }

        post("/v1/auth/login/verify") {
            val body = call.receive<VerifyBody>()
            val request = assertions.take(body.ceremonyId) ?: throw invalidCeremony()
            // validateSignatureCounter stays on (the default); synced passkeys report 0 and pass.
            val result = try {
                rp.finishAssertion(
                    FinishAssertionOptions.builder()
                        .request(request)
                        .response(PublicKeyCredential.parseAssertionResponseJson(body.response))
                        .build(),
                )
            } catch (e: AssertionFailedException) {
                throw rejected()
            } catch (e: IOException) {
                throw rejected()
            } catch (e: IllegalArgumentException) {
                throw rejected()
            }
            if (!result.isSuccess) throw rejected()
            val token = db.tx { c ->
                val accountId = c.prepareStatement(
                    "UPDATE passkeys SET sign_count = ?, last_used_at = ? WHERE credential_id = ? RETURNING account_id",
                ).use {
                    it.setLong(1, result.signatureCount)
                    it.setTimestamp(2, Timestamp.from(Instant.now()))
                    it.setBytes(3, result.credential.credentialId.bytes)
                    it.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                } ?: throw rejected()
                sessions.issue(c, accountId)
            }
            call.respond(TokenBody(token))
        }
    }

    fun purge() {
        registrations.purge()
        assertions.purge()
    }

    /** `{ceremonyId, publicKey}` with Yubico's inner `publicKey` object, which is what androidx.credentials wants. */
    private suspend fun io.ktor.server.application.ApplicationCall.respondOptions(ceremonyId: String, credentialsJson: String) {
        val inner = Json.parseToJsonElement(credentialsJson).jsonObject.getValue("publicKey")
        val out = buildJsonObject {
            put("ceremonyId", ceremonyId)
            put("publicKey", inner)
        }
        respondText(out.toString(), ContentType.Application.Json)
    }

    private fun invalidCeremony() =
        ApiError(HttpStatusCode.BadRequest, "invalid_ceremony", "Sign-in took too long. Please try again.")

    private fun rejected() = ApiError(HttpStatusCode.BadRequest, "passkey_rejected", "The passkey was not accepted.")
}
