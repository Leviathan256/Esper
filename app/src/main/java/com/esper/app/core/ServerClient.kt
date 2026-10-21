package com.esper.app.core

import com.esper.engine.character.CharacterState
import com.esper.engine.character.SaveCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The server rejected or no longer knows the session token (HTTP 401). The app signs out on this. */
class Unauthorized : IOException("Your session has expired. Sign in again.")

/** A non-2xx answer from the Esper server. [message] is the server's own text, safe to show. */
class ServerError(val status: Int, val code: String, message: String) : IOException(message)

/** A passkey ceremony in progress: the server's id for it and the `publicKey` options JSON. */
data class Ceremony(val id: String, val publicKeyJson: String)

/**
 * Client for the Esper server, contract in docs/SERVER.md §5.
 *
 * Same shape as [GitHubClient]: [HttpURLConnection] on [Dispatchers.IO], every
 * call a [Result]. [token] is the session bearer token, null before sign-in.
 */
class ServerClient(baseUrl: String, private val token: String?) {
    private val base = baseUrl.trim().trimEnd('/')

    suspend fun registerOptions(): Result<Ceremony> =
        request("POST", "/v1/auth/register/options", "{}").mapCatching(::ceremony)

    suspend fun registerVerify(ceremonyId: String, responseJson: String): Result<String> =
        verify("/v1/auth/register/verify", ceremonyId, responseJson)

    suspend fun loginOptions(): Result<Ceremony> =
        request("POST", "/v1/auth/login/options", "{}").mapCatching(::ceremony)

    suspend fun loginVerify(ceremonyId: String, responseJson: String): Result<String> =
        verify("/v1/auth/login/verify", ceremonyId, responseJson)

    suspend fun logout(): Result<Unit> = request("POST", "/v1/auth/logout").map { }

    /** The account's character, or null when it has none yet (404). */
    suspend fun getCharacter(): Result<CharacterState?> =
        request("GET", "/v1/character")
            .mapCatching { SaveCodec.decode(it.orEmpty()) }
            .recoverCatching { if (it is ServerError && it.status == 404) null else throw it }

    suspend fun createCharacter(name: String, startingJobId: String): Result<CharacterState> =
        request(
            "POST",
            "/v1/character",
            JSONObject().put("name", name).put("startingJobId", startingJobId).toString(),
        ).mapCatching { SaveCodec.decode(it.orEmpty()) }

    suspend fun putCharacter(state: CharacterState): Result<Unit> =
        request("PUT", "/v1/character", SaveCodec.encode(state)).map { }

    suspend fun postLocation(lat: Double, lon: Double, accuracyM: Double): Result<Unit> =
        request(
            "POST",
            "/v1/location",
            JSONObject().put("lat", lat).put("lon", lon).put("accuracyM", accuracyM).toString(),
        ).map { }

    private suspend fun verify(path: String, ceremonyId: String, responseJson: String): Result<String> =
        request(
            "POST",
            path,
            // responseJson goes through as a JSON string value, verbatim (SERVER.md §5).
            JSONObject().put("ceremonyId", ceremonyId).put("response", responseJson).toString(),
        ).mapCatching { JSONObject(it.orEmpty()).getString("token") }

    private fun ceremony(body: String?): Ceremony {
        val json = JSONObject(body.orEmpty())
        return Ceremony(json.getString("ceremonyId"), json.getJSONObject("publicKey").toString())
    }

    private suspend fun request(method: String, path: String, body: String? = null): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (base.isBlank()) throw IOException("Server not configured. Set it in Settings.")
                val connection = try {
                    (URL(base + path).openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = 15_000
                        readTimeout = 20_000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "Esper-Android")
                        if (token != null) setRequestProperty("Authorization", "Bearer $token")
                        if (body != null) {
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                    }
                } catch (e: Exception) {
                    throw IOException("Server address \"$base\" is not a valid URL. Fix it in Settings.", e)
                }

                try {
                    val code = try {
                        if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
                        connection.responseCode
                    } catch (e: IOException) {
                        // DNS, refused connection, timeout, TLS or cleartext refusal: the
                        // server itself never answered.
                        throw IOException("Can't reach the Esper server. Check your connection and try again. (${e.message})", e)
                    }
                    if (code == 401) throw Unauthorized()
                    if (code !in 200..299) {
                        val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        val error = runCatching { JSONObject(detail.orEmpty()) }.getOrNull()
                        throw ServerError(
                            status = code,
                            code = error?.optString("error").orEmpty(),
                            message = error?.optString("message")?.takeIf { it.isNotBlank() }
                                ?: "The Esper server returned $code for $path.",
                        )
                    }
                    if (code == 204) null else connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }
        }
}
