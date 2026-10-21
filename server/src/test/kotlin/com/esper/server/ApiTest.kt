package com.esper.server

import com.esper.engine.character.SaveCodec
import com.esper.server.auth.Sessions
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.CloseReason
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * HTTP-level contract tests against a real Postgres (docs/SERVER.md §11).
 * Skipped unless ESPER_TEST_DB_URL is set; CI sets it. Wipes that database.
 * A full passkey ceremony is not covered: there is no software authenticator.
 */
class ApiTest {
    private val url: String? = System.getenv("ESPER_TEST_DB_URL")
    private lateinit var db: Db
    private val catalog = Content.load(System.getProperty("esper.contentDir"))
    private val config = Config(
        port = 0, dbUrl = "", dbUser = null, dbPassword = null,
        rpId = "leviathan256.github.io", rpOrigins = setOf("android:apk-key-hash:test"), contentDir = "",
    )

    @BeforeEach
    fun setUp() {
        assumeTrue(url != null, "ESPER_TEST_DB_URL not set")
        Flyway.configure().dataSource(url, null, null).cleanDisabled(false).load().clean()
        db = Db(url!!, null, null)
    }

    @AfterEach
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { esper(config, db, catalog) }
        block(createClient { install(WebSockets) })
    }

    private fun newAccount(): UUID = db.tx { c ->
        c.prepareStatement("INSERT INTO accounts (user_handle) VALUES (?) RETURNING id").use {
            it.setBytes(1, UUID.randomUUID().toString().toByteArray())
            it.executeQuery().use { rs -> rs.next(); rs.getObject(1, UUID::class.java) }
        }
    }

    private fun seedSession(now: Instant = Instant.now()): String = db.tx { c -> Sessions(db).issue(c, newAccount(), now) }

    private suspend fun HttpResponse.error(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null) = post(path) {
        token?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `healthz is ok`() = api { client ->
        val r = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("ok", r.bodyAsText())
    }

    @Test
    fun `register options carry the rp id and require a resident key`() = api { client ->
        val r = client.postJson("/v1/auth/register/options", "{}")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body.getValue("ceremonyId").jsonPrimitive.content.isNotEmpty())
        val pk = body.getValue("publicKey").jsonObject
        assertEquals("leviathan256.github.io", pk.getValue("rp").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("required", pk.getValue("authenticatorSelection").jsonObject.getValue("residentKey").jsonPrimitive.content)
        assertEquals("required", pk.getValue("authenticatorSelection").jsonObject.getValue("userVerification").jsonPrimitive.content)
        assertTrue(pk.getValue("user").jsonObject.getValue("name").jsonPrimitive.content.startsWith("esper-"))
    }

    @Test
    fun `login options are usernameless`() = api { client ->
        val r = client.postJson("/v1/auth/login/options", "{}")
        assertEquals(HttpStatusCode.OK, r.status)
        val pk = Json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("publicKey") as JsonObject
        assertEquals("leviathan256.github.io", pk.getValue("rpId").jsonPrimitive.content)
        assertNull(pk["allowCredentials"])
    }

    @Test
    fun `verify with an unknown or reused ceremony is invalid_ceremony`() = api { client ->
        val r = client.postJson("/v1/auth/register/verify", """{"ceremonyId":"nope","response":"{}"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("invalid_ceremony", r.error())

        // A real ceremony with a garbage response is rejected, and then it is used up.
        val id = Json.parseToJsonElement(client.postJson("/v1/auth/login/options", "{}").bodyAsText())
            .jsonObject.getValue("ceremonyId").jsonPrimitive.content
        val first = client.postJson("/v1/auth/login/verify", """{"ceremonyId":"$id","response":"{}"}""")
        assertEquals("passkey_rejected", first.error())
        val again = client.postJson("/v1/auth/login/verify", """{"ceremonyId":"$id","response":"{}"}""")
        assertEquals("invalid_ceremony", again.error())
    }

    @Test
    fun `ceremony routes are rate limited per client`() = api { client ->
        repeat(20) { assertEquals(HttpStatusCode.OK, client.postJson("/v1/auth/login/options", "{}").status) }
        val r = client.postJson("/v1/auth/login/options", "{}")
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        assertEquals("rate_limited", r.error())
    }

    @Test
    fun `malformed body is bad_request`() = api { client ->
        val r = client.postJson("/v1/auth/register/verify", """{"ceremonyId":1""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("bad_request", r.error())
        val big = client.postJson("/v1/auth/register/verify", "x".repeat(65 * 1024))
        assertEquals("bad_request", big.error())
    }

    @Test
    fun `no or unknown token is 401 unauthorized`() = api { client ->
        val r = client.get("/v1/character")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals("unauthorized", r.error())
        val bogus = client.get("/v1/character") { bearerAuth("not-a-token") }
        assertEquals(HttpStatusCode.Unauthorized, bogus.status)
    }

    @Test
    fun `character create, conflict, load, save`() = api { client ->
        val token = seedSession()
        assertEquals("no_character", client.get("/v1/character") { bearerAuth(token) }.error())

        val created = client.postJson("/v1/character", """{"name":"Ramza","startingJobId":"squire"}""", token)
        assertEquals(HttpStatusCode.Created, created.status)
        val state = SaveCodec.decode(created.bodyAsText())
        assertEquals("Ramza", state.name)

        val again = client.postJson("/v1/character", """{"name":"Ramza","startingJobId":"squire"}""", token)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("character_exists", again.error())

        val got = client.get("/v1/character") { bearerAuth(token) }
        assertEquals(SaveCodec.encode(state), got.bodyAsText())

        val up = state.copy(level = 2, xp = 5)
        val put = client.put("/v1/character") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(SaveCodec.encode(up))
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        assertEquals(up, SaveCodec.decode(client.get("/v1/character") { bearerAuth(token) }.bodyAsText()))

        val down = client.put("/v1/character") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(SaveCodec.encode(up.copy(level = 1)))
        }
        assertEquals("invalid_state", down.error())
    }

    @Test
    fun `bad name and job on create`() = api { client ->
        val token = seedSession()
        assertEquals("invalid_name", client.postJson("/v1/character", """{"name":" ","startingJobId":"squire"}""", token).error())
        assertEquals("invalid_job", client.postJson("/v1/character", """{"name":"A","startingJobId":"knight"}""", token).error())
    }

    @Test
    fun `expired session is 401`() = api { client ->
        val token = seedSession(Instant.now().minus(Sessions.TTL).minusSeconds(60))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/character") { bearerAuth(token) }.status)
    }

    @Test
    fun `session slides after a day of use and expires after 90 idle days`() {
        val sessions = Sessions(db)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val token = db.tx { c -> sessions.issue(c, newAccount(), start) }
        fun expiresAt(): Instant = db.tx { c ->
            c.prepareStatement("SELECT expires_at FROM sessions WHERE token_hash = ?").use {
                it.setBytes(1, Sessions.sha256(Base64.getUrlDecoder().decode(token)))
                it.executeQuery().use { rs -> rs.next(); rs.getTimestamp(1).toInstant() }
            }
        }
        assertNotNull(sessions.validate(token, start.plusSeconds(3600)))
        assertEquals(start.plus(Sessions.TTL), expiresAt()) // under a day: no write
        val later = start.plus(Sessions.SLIDE_AFTER).plusSeconds(1)
        assertNotNull(sessions.validate(token, later))
        assertEquals(later.plus(Sessions.TTL), expiresAt())
        assertNull(sessions.validate(token, later.plus(Sessions.TTL)))
        sessions.purgeExpired(later.plus(Sessions.TTL))
        assertEquals(0, db.tx { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM sessions").use { it.next(); it.getInt(1) } } })
    }

    @Test
    fun `logout deletes the session`() = api { client ->
        val token = seedSession()
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/auth/logout") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/character") { bearerAuth(token) }.status)
    }

    @Test
    fun `sessions are capped per account, oldest dropped`() {
        val account = newAccount()
        val start = Instant.now()
        val tokens = (0 until Sessions.MAX_PER_ACCOUNT + 2).map { i ->
            db.tx { c -> Sessions(db).issue(c, account, start.plusSeconds(i.toLong())) }
        }
        assertNull(Sessions(db).validate(tokens[0]))
        assertNull(Sessions(db).validate(tokens[1]))
        assertEquals(account, Sessions(db).validate(tokens.last()))
        assertEquals(account, Sessions(db).validate(tokens[2]))
    }

    @Test
    fun `websocket closes on an oversized frame`() = api { client ->
        val ws = client.webSocketSession("/v1/ws") { bearerAuth(seedSession()) }
        assertEquals("""{"t":"hello"}""", (ws.incoming.receive() as Frame.Text).readText())
        ws.send(Frame.Text("x".repeat(64 * 1024 + 1)))
        // Closed by the reader before Socket.kt ever sees the frame.
        assertEquals(CloseReason.Codes.TOO_BIG.code, withTimeout(10_000) { ws.closeReason.await() }?.code)
        ws.cancel()
    }

    @Test
    fun `location validates and accepts`() = api { client ->
        val token = seedSession()
        val bad = client.postJson("/v1/location", """{"lat":91.0,"lon":0.0,"accuracyM":5.0}""", token)
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("invalid_fix", bad.error())
        val ok = client.postJson("/v1/location", """{"lat":51.5,"lon":-0.12,"accuracyM":5.0}""", token)
        assertEquals(HttpStatusCode.NoContent, ok.status)
    }

    @Test
    fun `websocket needs a token, says hello, acks loc, rejects unknown types`() = api { client ->
        assertThrows<Exception> { client.webSocket("/v1/ws") {} }
        val token = seedSession()
        client.webSocket("/v1/ws", request = { bearerAuth(token) }) {
            assertEquals("""{"t":"hello"}""", (incoming.receive() as Frame.Text).readText())
            send(Frame.Text("""{"t":"loc","id":1,"d":{"lat":1.0,"lon":2.0,"accuracyM":3.0}}"""))
            assertEquals("""{"t":"ack","id":1}""", (incoming.receive() as Frame.Text).readText())
            send(Frame.Text("""{"t":"loc","id":2,"d":{"lat":100.0,"lon":2.0,"accuracyM":3.0}}"""))
            assertEquals("""{"t":"err","id":2,"d":{"error":"invalid_fix"}}""", (incoming.receive() as Frame.Text).readText())
            send(Frame.Text("""{"t":"what","id":3}"""))
            assertEquals("""{"t":"err","id":3,"d":{"error":"unknown_type"}}""", (incoming.receive() as Frame.Text).readText())
        }
    }
}
