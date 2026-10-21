package com.esper.server.realtime

import com.esper.server.AccountPrincipal
import com.esper.server.location.LocationBody
import com.esper.server.location.Positions
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/** Every socket message, both directions: type, optional correlation id, payload. */
@Serializable
data class Envelope(val t: String, val id: Long? = null, val d: JsonElement? = null)

private val json = Json { ignoreUnknownKeys = true }

/**
 * `/v1/ws` skeleton (docs/SERVER.md §7). Must sit inside `authenticate("session")`.
 * The app does not open it in step 2; step 3 adds presence (realtime/Presence.kt)
 * and later steps add message types to the `when` below.
 */
fun Route.socket(positions: Positions) {
    webSocket("/v1/ws") {
        val accountId = call.principal<AccountPrincipal>()!!.accountId
        send(Frame.Text(json.encodeToString(Envelope.serializer(), Envelope("hello"))))
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val env = try {
                json.decodeFromString(Envelope.serializer(), frame.readText())
            } catch (e: IllegalArgumentException) { // SerializationException is one
                send(Frame.Text(json.encodeToString(Envelope.serializer(), err(null, "bad_request"))))
                continue
            }
            val reply = when (env.t) {
                "loc" -> {
                    val loc = try {
                        env.d?.let { json.decodeFromJsonElement<LocationBody>(it) }
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (loc == null || !Positions.validate(loc.lat, loc.lon, loc.accuracyM)) {
                        err(env.id, "invalid_fix")
                    } else {
                        positions.record(accountId, loc)
                        Envelope("ack", env.id)
                    }
                }
                else -> err(env.id, "unknown_type")
            }
            send(Frame.Text(json.encodeToString(Envelope.serializer(), reply)))
        }
    }
}

private fun err(id: Long?, code: String) = Envelope("err", id, buildJsonObject { put("error", code) })
