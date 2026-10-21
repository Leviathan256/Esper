package com.esper.server

import com.esper.engine.content.ContentCatalog
import com.esper.server.auth.Passkeys
import com.esper.server.auth.Sessions
import com.esper.server.character.CharacterRules
import com.esper.server.character.characterRoutes
import com.esper.server.location.LocationBody
import com.esper.server.location.Positions
import com.esper.server.realtime.socket
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Every non-2xx body (docs/SERVER.md §5). `message` is safe to show the player. */
@Serializable
data class ErrorBody(val error: String, val message: String)

/** Thrown anywhere in a route; StatusPages turns it into [ErrorBody]. */
class ApiError(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)

/** The bearer token is kept so logout can delete exactly this session. */
data class AccountPrincipal(val accountId: UUID, val token: String) : Principal

fun ApplicationCall.accountId(): UUID = principal<AccountPrincipal>()!!.accountId

private const val MAX_BODY = 64 * 1024L
private val log = LoggerFactory.getLogger("com.esper.server")

fun main() {
    val config = Config.fromEnv()
    val catalog = Content.load(config.contentDir)
    val db = Db(config.dbUrl, config.dbUser, config.dbPassword)
    log.info("content: ${catalog.jobs.size} jobs, ${catalog.monsters.size} monsters; rp ${config.rpId}")
    // Loopback only: Caddy terminates TLS and is the only thing that can reach us.
    embeddedServer(Netty, port = config.port, host = "127.0.0.1") { esper(config, db, catalog) }.start(wait = true)
}

fun Application.esper(config: Config, db: Db, catalog: ContentCatalog) {
    val sessions = Sessions(db)
    val positions = Positions()
    val passkeys = Passkeys(config, db, sessions)
    val rules = CharacterRules(catalog)

    // Safe only because :8080 is bound to loopback behind Caddy; otherwise clients could spoof their IP.
    install(XForwardedHeaders)
    install(CallLogging) {
        // Method, path, status. Never bodies, query strings or headers.
        format { "${it.request.httpMethod.value} ${it.request.path()} ${it.response.status()?.value}" }
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(30)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = MAX_BODY // default is Long.MAX_VALUE: one huge frame would OOM the JVM
    }
    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { it.request.origin.remoteHost }
        }
    }
    install(StatusPages) {
        exception<ApiError> { call, e -> call.respond(e.status, ErrorBody(e.code, e.message)) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "The request was malformed."))
        }
        exception<Throwable> { call, e ->
            log.error("unhandled ${call.request.httpMethod.value} ${call.request.path()}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal", "Something went wrong on the server."))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ErrorBody("unauthorized", "Please sign in again."))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorBody("rate_limited", "Too many attempts. Wait a minute and try again."))
        }
    }
    install(Authentication) {
        bearer("session") {
            authenticate { cred -> sessions.validate(cred.token)?.let { AccountPrincipal(it, cred.token) } }
        }
    }

    // Defence in depth: trusts Content-Length, so a chunked body skips this; Caddy's request_body max_size bounds those.
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        if ((call.request.contentLength() ?: 0) > MAX_BODY) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "Request body is too large."))
            finish()
        }
    }

    routing {
        get("/healthz") {
            val ok = runCatching { db.tx { it.createStatement().use { s -> s.execute("SELECT 1") } } }.isSuccess
            if (ok) {
                call.respondText("ok", ContentType.Text.Plain)
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody("unavailable", "Database is unreachable."))
            }
        }
        rateLimit(RateLimitName("auth")) { passkeys.routes(this) }
        authenticate("session") {
            rateLimit(RateLimitName("auth")) {
                post("/v1/auth/logout") {
                    val p = call.principal<AccountPrincipal>()!!
                    sessions.delete(p.token)
                    positions.remove(p.accountId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            characterRoutes(db, rules)
            post("/v1/location") {
                val body = call.receive<LocationBody>()
                if (!Positions.validate(body.lat, body.lon, body.accuracyM)) {
                    throw ApiError(HttpStatusCode.BadRequest, "invalid_fix", "That location is out of range.")
                }
                positions.record(call.accountId(), body)
                call.respond(HttpStatusCode.NoContent)
            }
            socket(positions)
        }
    }

    // Minute tick: expired ceremonies, stale positions, expired sessions.
    launch {
        while (isActive) {
            delay(1.minutes)
            passkeys.purge()
            positions.purge()
            runCatching { sessions.purgeExpired() }.onFailure { log.warn("session purge failed", it) }
        }
    }
}
