package com.esper.server

/**
 * Everything the server reads from its environment (docs/SERVER.md §8).
 * `/etc/esper/server.env` on the VM; no config file.
 */
data class Config(
    val port: Int,
    val dbUrl: String,
    val dbUser: String?,
    val dbPassword: String?,
    val rpId: String,
    /** Exact-match WebAuthn origins, e.g. `android:apk-key-hash:<b64url>`. */
    val rpOrigins: Set<String>,
    val contentDir: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun get(name: String) = env[name]?.takeIf { it.isNotBlank() }
            val origins = parseOrigins(get("ESPER_RP_ORIGINS"))
            // Fail fast: with no origin every passkey ceremony would fail on device
            // as an opaque "passkey_rejected".
            require(origins.isNotEmpty()) {
                "ESPER_RP_ORIGINS is empty: set it to the 'passkey origin' row of a build-apk run summary"
            }
            return Config(
                port = get("ESPER_PORT")?.toInt() ?: 8080,
                dbUrl = get("ESPER_DB_URL") ?: "jdbc:postgresql://127.0.0.1/esper",
                dbUser = get("ESPER_DB_USER") ?: "esper",
                dbPassword = requireNotNull(get("ESPER_DB_PASSWORD")) { "ESPER_DB_PASSWORD is not set" },
                rpId = get("ESPER_RP_ID") ?: "leviathan256.github.io",
                rpOrigins = origins,
                contentDir = get("ESPER_CONTENT_DIR") ?: System.getProperty("esper.contentDir") ?: "content",
            )
        }

        /** Comma list, trimmed, blanks dropped. */
        fun parseOrigins(raw: String?): Set<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
