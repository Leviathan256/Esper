# Esper server (step 2)

The Esper backend: a Ktor server that holds accounts, passkeys and characters,
receives player positions, and hosts the pure-JVM `:engine` so later steps can
run combat server-side. This document is the contract between the `:server`
module, the Android app and the ops files. Change the contract here first.

Status: step 2 ships sign-in with a passkey, a server-held character, and
position upload. Everything under "Later steps" is design only.

---

## 1. Decisions

| Topic | Decision | Why |
|---|---|---|
| Server stack | Ktor **2.3.13** on Netty, plain JDBC + HikariCP, Flyway, Postgres 16 | Ktor 2.3.x is the last line built for Kotlin 1.9; the repo pins Kotlin 1.9.25 (`settings.gradle.kts`). Ktor 3 needs Kotlin 2 and would break the Compose compiler pin. |
| Passkeys | Yubico `com.yubico:webauthn-server-core:2.6.0` | Emits the `publicKey` options JSON that `androidx.credentials` consumes, parses the response JSON it returns, validates the signature counter, and matches origins by exact string so `android:apk-key-hash:…` works. webauthn4j would need all of that written by hand. |
| Packaging | Gradle core `application` plugin, `installDist` | No new plugin version in `settings.gradle.kts`, no shadow jar. Output is `server/build/install/server/{bin,lib,content}`. |
| App HTTP | `HttpURLConnection`, the `GitHubClient` house pattern | No OkHttp/Ktor client. Step 2 needs five small JSON calls. |
| App WebSocket | **None in step 2.** Position goes by `POST /v1/location`. | A socket client is a new dependency with nothing to push yet. The server exposes an authenticated `/v1/ws` skeleton so step 3 attaches without an API change. |
| Position retention | Server keeps the **latest fix per account, in memory only**. Dropped after 10 minutes without an update, on logout, on restart. Never written to Postgres or logs. | Smallest honest rule for the design doc; enough for step 3 presence and anti-cheat (both need only the previous fix) on a single instance. |
| Character | One per account, `jsonb` column holding `SaveCodec.encode(CharacterState)` | `:server` depends on `:engine`, so validation and migration reuse `SaveCodec`/`SaveMigrations`. |
| Progress in step 2 | Client-reported: app `PUT`s the state after a victory | Server-run combat is step 5. Marked `shortcut:` in code. |
| Old local saves | Uploaded once on first sign-in, then the file is deleted | Current nightly testers keep their character. |
| Credential Manager | `androidx.credentials:credentials:1.3.0` only, no `credentials-play-services-auth` | The project forbids Play Services. Consequence: passkey sign-in needs **Android 14+** (framework Credential Manager). See §10. |
| Hosting | Oracle Always Free A1 (arm64, Ubuntu 24.04), Caddy from Ubuntu `universe`, Let's Encrypt via Caddy, DuckDNS or sslip.io name | All free. |

---

## 2. Architecture

```
Android app  ──HTTPS──▶  Caddy (:443, TLS)  ──▶  Ktor :8080 (127.0.0.1)  ──JDBC──▶  Postgres (local)
   │                                              │
   │  androidx.credentials ◀── RP ID leviathan256.github.io ──▶ GitHub Pages /.well-known/assetlinks.json
   │
   └──HTTPS──▶  GitHub Releases "nightly"  (UpdateChecker, unchanged, independent of the server)
```

- One VM, one JVM, one Postgres. No scale-out in step 2; in-memory stores
  (challenges, positions) are single-instance by design and say so in code.
- The server binds `127.0.0.1` only. Caddy terminates TLS and proxies
  `/` to `:8080` (WebSockets pass through with no extra config).
- The app never talks to the server without TLS: targetSdk 35 blocks cleartext.
- `UpdateChecker` and the `nightly` release are untouched. The server jar is
  never uploaded to any GitHub release (`UpdateChecker` picks the first `.apk`
  asset, but keep releases APK-only regardless).

### Server module layout (`server/src/main/kotlin/com/esper/server/`)

| File | Holds |
|---|---|
| `Main.kt` | `Config` from env, `Db.start()`, content load, `embeddedServer(Netty, host="127.0.0.1")`, plugins (ContentNegotiation, StatusPages, Authentication bearer `"session"`, WebSockets, RateLimit), routing, minute-tick purge coroutine |
| `Config.kt` | `data class Config` read from env (§8). Fails fast on empty `ESPER_RP_ORIGINS`. |
| `Db.kt` | Hikari pool, Flyway migrate, `fun <T> tx(block: (Connection) -> T): T` |
| `Content.kt` | Reads `jobs/*.json`, `monsters/*.json` from `contentDir`, calls `ContentLoader.load` (mirrors `ShippedContentTest`) |
| `auth/Passkeys.kt` | `RelyingParty`, ceremony store, the four ceremony routes |
| `auth/CredentialStore.kt` | Yubico `CredentialRepository` over `passkeys` + `accounts` |
| `auth/Sessions.kt` | issue / validate / slide / delete session tokens; bearer `validate` hook |
| `character/CharacterRoutes.kt` | `GET/POST/PUT /v1/character`, `CharacterRules` (pure validation) |
| `location/Positions.kt` | `record`, `latest`, `purge`; `Fix` data class; pure `validate(lat, lon, acc)` |
| `realtime/Socket.kt` | `/v1/ws`, `Envelope`, `loc` handler |
| `resources/db/migration/V1__init.sql` | §4 |
| `resources/logback.xml` | INFO to stdout (journald). No request bodies, ever. |

Later steps add a package each (`combat/`, `party/`, `territory/`, `guild/`,
`social/`), register routes in `Main.kt`, add socket message types to the
`when (env.t)` in `Socket.kt`, and add `V<n>__*.sql`. Nothing in step 2 changes
shape for them.

---

## 3. Gradle

`settings.gradle.kts`: add `include(":server")`. No plugin or repository change.

`server/build.gradle.kts` mirrors `engine/build.gradle.kts` (Java 17 via
`java {}` + `compilerOptions`, **no toolchain**):

```kotlin
plugins {
    application
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}
application { mainClass.set("com.esper.server.MainKt") }
// Ship content/ beside bin/ and lib/; systemd WorkingDirectory makes "content" resolve.
distributions { main { contents { from("../content") { into("content") } } } }

val ktor = "2.3.13"
dependencies {
    implementation(project(":engine"))
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktor")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("io.ktor:ktor-server-rate-limit-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("com.yubico:webauthn-server-core:2.6.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
}
tasks.test {
    useJUnitPlatform()
    systemProperty("esper.contentDir", rootProject.file("content").absolutePath)
    System.getenv("ESPER_TEST_DB_URL")?.let { environment("ESPER_TEST_DB_URL", it) }
}
```

`:app:assembleRelease` never depends on `:server`, so the nightly APK build is
unchanged. If a patch version above does not resolve, use the nearest on the
same minor line; Ktor must stay on 2.3.x.

`app/build.gradle.kts` additions:

- `implementation("androidx.credentials:credentials:1.3.0")` (needs compileSdk ≥ 34; repo has 35). **Not** `credentials-play-services-auth`.
- `buildConfigField("String", "SERVER_URL", "\"${System.getenv("ESPER_SERVER_URL")?.takeIf { it.isNotBlank() } ?: ""}\"")` beside `GIT_SHA`.

---

## 4. Schema (`V1__init.sql`)

```sql
CREATE TABLE accounts (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_handle bytea NOT NULL UNIQUE,              -- WebAuthn user.id, 32 random bytes
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE passkeys (
  credential_id bytea PRIMARY KEY,
  account_id    uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  public_key    bytea NOT NULL,                   -- COSE
  sign_count    bigint NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  last_used_at  timestamptz
);
CREATE INDEX passkeys_account ON passkeys(account_id);
CREATE TABLE sessions (
  token_hash   bytea PRIMARY KEY,                 -- SHA-256 of the bearer token; raw token never stored
  account_id   uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  created_at   timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL
);
CREATE INDEX sessions_account ON sessions(account_id);
CREATE INDEX sessions_expires ON sessions(expires_at);
CREATE TABLE characters (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),   -- party/guild/claim tables reference this later
  account_id uuid NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
  state      jsonb NOT NULL,                     -- SaveCodec.encode(CharacterState)
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

No `positions` table and no `challenges` table: both live in memory (§6, §7).
`gen_random_uuid()` is built in from Postgres 13; Ubuntu 24.04 ships 16.

Later: multiple characters = drop the `UNIQUE` on `characters.account_id`.
Parties, guilds, friends, claims = `V2__…` onward keyed on `characters.id`.
Territory = H3 cell as `bigint`.

---

## 5. API contract

All routes except `/healthz` are under `/v1`. Bodies are JSON
(`Content-Type: application/json`). Authenticated routes take
`Authorization: Bearer <token>`.

### Errors

Every non-2xx response has this body:

```json
{ "error": "<code>", "message": "<human text, safe to show>" }
```

| HTTP | `error` | When |
|---|---|---|
| 400 | `bad_request` | malformed JSON, missing field, body over 64 KB |
| 400 | `invalid_ceremony` | unknown, expired or already-used `ceremonyId` |
| 400 | `passkey_rejected` | Yubico `finishRegistration`/`finishAssertion` failed (bad origin, RP ID, signature, counter) |
| 400 | `invalid_name` | name blank or > 24 chars after trim |
| 400 | `invalid_job` | `startingJobId` not a starting job, or PUT contains an id not in the catalog |
| 400 | `invalid_state` | PUT fails §5 rules (schema, name change, level decrease) |
| 400 | `invalid_fix` | lat/lon/accuracy out of range or NaN |
| 401 | `unauthorized` | missing, unknown or expired token. **App clears its token and returns to sign-in on any 401.** |
| 404 | `no_character` | `GET /v1/character` before creation |
| 409 | `character_exists` | second `POST /v1/character` |
| 429 | `rate_limited` | ceremony routes, 20/min per IP |
| 503 | `unavailable` | `/healthz` DB check failed |

### Routes

| Method, path | Auth | Request | Success |
|---|---|---|---|
| `GET /healthz` | none | – | `200` text `ok` after `SELECT 1`; else `503` |
| `POST /v1/auth/register/options` | none | `{}` | `200 {"ceremonyId": "<b64url>", "publicKey": {…}}` |
| `POST /v1/auth/register/verify` | none | `{"ceremonyId": "…", "response": "<registrationResponseJson string>"}` | `200 {"token": "<b64url>"}` |
| `POST /v1/auth/login/options` | none | `{}` | `200 {"ceremonyId": "…", "publicKey": {…}}` |
| `POST /v1/auth/login/verify` | none | `{"ceremonyId": "…", "response": "<authenticationResponseJson string>"}` | `200 {"token": "…"}` |
| `POST /v1/auth/logout` | bearer | – | `204`; deletes this session and the caller's position |
| `GET /v1/character` | bearer | – | `200` body = `CharacterState` JSON (exactly `SaveCodec.encode` output) |
| `POST /v1/character` | bearer | `{"name": "…", "startingJobId": "…"}` | `201` body = state JSON |
| `PUT /v1/character` | bearer | `CharacterState` JSON | `204` |
| `POST /v1/location` | bearer | `{"lat": 0.0, "lon": 0.0, "accuracyM": 0.0}` | `204` |
| `GET /v1/ws` | bearer header | WebSocket upgrade | see §7 |

`publicKey` is the **inner** `publicKey` object from Yubico's
`toCredentialsCreateJson()` / `toCredentialsGetJson()`, unwrapped, because
`CreatePublicKeyCredentialRequest(requestJson)` and `GetPublicKeyCredentialOption(requestJson)`
want the inner object. `response` is the string `androidx.credentials` hands
back (`CreatePublicKeyCredentialResponse.registrationResponseJson`,
`PublicKeyCredential.authenticationResponseJson`), passed through verbatim as a
JSON string value; the server parses it with
`PublicKeyCredential.parseRegistrationResponseJson` / `parseAssertionResponseJson`.

### Character rules (`CharacterRules`, pure, unit-tested)

- `POST`: name trimmed, 1–24 chars (the app's own rule is `trim().isNotEmpty()`; the server adds the upper bound). `startingJobId` ∈ `CharacterFactory.startingJobs(catalog.jobs)`. Server runs `CharacterFactory.newCharacter(name, job)`, encodes with `SaveCodec`, inserts.
- `PUT`: body through `SaveCodec.decode` (runs migrations). Reject unless: `schemaVersion == CURRENT_SCHEMA_VERSION`; every `currentJobId`, `unlockedJobIds`, `jobPoints` key and `learnedAbilityIds` entry exists in the catalog; when a row exists, `name` unchanged and `level >= stored.level`. Upsert. Code comment: `shortcut: client-authoritative progress, replaced when step 5 runs combat server-side and PUT goes away`.
- `GET` returns `SaveCodec.encode(SaveCodec.decode(stored))`. (Deviation from "stored text as-is", made in implementation: `jsonb` reorders keys and drops whitespace, so the raw column is not `SaveCodec.encode` output; re-encoding also runs migrations on old rows.)

---

## 6. Auth flow

### Relying party

```kotlin
RelyingParty.builder()
    .identity(RelyingPartyIdentity.builder().id(config.rpId).name("Esper").build())
    .credentialRepository(CredentialStore(db))
    .origins(config.rpOrigins)          // exact-match set
    .build()
```

- `ESPER_RP_ID` default `leviathan256.github.io`.
- `ESPER_RP_ORIGINS` comma-separated, e.g. `android:apk-key-hash:<b64url>`. More than one entry is allowed (release key + a debug key). Empty = server refuses to start, logs why. Yubico checks `allowedOrigins.contains(origin)` before any URL parsing, so the non-URL Android origin matches by exact string. `allowOriginPort`/`allowOriginSubdomain` stay false.
- Android computes the origin as `android:apk-key-hash:` + base64url(SHA-256(signing cert DER)) with no padding. It is deterministic from the release keystore; the value for the nightly key appears in every `build-apk` run summary (§9).
- Android checks `https://<rpId>/.well-known/assetlinks.json` lists the package + cert fingerprint before it will create or use a passkey for that RP ID. That file must be at the **root** of the user site `Leviathan256/leviathan256.github.io` (a project page under `/Esper/` cannot serve `/.well-known/`). Relation needed: `delegate_permission/common.get_login_creds` only.

### Registration

1. `register/options`: new 32-byte `user_handle`; `user.name = "esper-" + first 8 hex of handle`, `displayName = "Esper player"`; `residentKey = REQUIRED`, `userVerification = REQUIRED`, `attestation = NONE`. Store `(ceremonyId → PublicKeyCredentialCreationOptions, expiresAt)`.
2. App: `CredentialManager.create(activity).createCredential(activity, CreatePublicKeyCredentialRequest(publicKeyJson))`.
3. `register/verify`: `remove` the ceremony (single use), `finishRegistration`, then one transaction: insert `accounts`, insert `passkeys` (credential id, COSE key, sign count), issue session. Return `{token}`.

### Login

1. `login/options`: `startAssertion` with no username (discoverable credential, no `allowCredentials`), `userVerification = REQUIRED`.
2. App: `getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(publicKeyJson))))`.
3. `login/verify`: `remove` the ceremony, `finishAssertion`. Yubico resolves the account via `CredentialStore.lookup(credentialId, userHandle)`. `validateSignatureCounter` stays on: it only fails when the stored and reported counters are both non-zero and the reported one did not increase; synced passkeys report 0 and pass. Update `sign_count`, `last_used_at`. Issue session.

### Ceremony store

`ConcurrentHashMap<String, Pending>`; key = 16 random bytes base64url; TTL 5 min;
`remove` on use; swept by the minute tick. Code comment:
`shortcut: in-memory, restart drops in-flight ceremonies, single instance only; move to a table if we ever run two`.

### Sessions

- Token: 32 bytes `SecureRandom`, base64url no padding. Server stores `sha256(token)`. Raw token lives only in the app's `EncryptedSharedPreferences` (`esper_secure`, same place as the GitHub token) and is never logged or put in `AppStateSnapshot`.
- `expires_at = now + 90 days`, sliding: on validate, if `last_used_at` is older than 1 day, set `last_used_at = now`, `expires_at = now + 90 days`.
- Logout deletes the row. Expired rows deleted by the minute tick. No rotation in step 2 (`shortcut:` comment).
- Bearer `validate` returns `UserIdPrincipal(accountId.toString())`.

### Rate limit

`ktor-server-rate-limit`, 20 requests/min per client IP on `/v1/auth/*`.
`XForwardedHeaders` is installed so the IP is the client's, not Caddy's; safe
because only Caddy can reach `:8080`.

---

## 7. Location

### Rule (also in `docs/GAME_DESIGN.md`, which is the player-facing statement)

- The app sends its latest fix (`lat`, `lon`, `accuracyM`) **only while the map screen is in the foreground**, at most **once per 30 s**, plus whenever the player has moved **≥ 50 m** since the last send. If location permission is declined, **nothing is sent**.
- The server keeps **only the latest fix per account, in memory** (`Positions`: `ConcurrentHashMap<accountId, Fix(lat, lon, accuracyM, receivedAt)>`). It is overwritten by the next fix, removed after **10 minutes** without one, on logout, and on restart. It is **never** written to Postgres, logs, or anywhere else.
- `receivedAt` is server time. Client time is not accepted.
- Validation (`Positions.validate`, pure): `lat ∈ [-90, 90]`, `lon ∈ [-180, 180]`, `accuracyM ∈ [0, 10000]`, no NaN. Otherwise `400 invalid_fix` (HTTP) or `err` (socket).
- Logback pattern logs method, path, status only. No `CallLogging` body logging. Caddy access log off.

Step 3 reads `Positions` for the ~300 m query and the speed check
(previous fix vs new fix: > 300 km/h reject, > 25 km/h passenger mode). If a
later step needs history or an H3 index on disk, the rule above changes **in
`GAME_DESIGN.md` first**.

### `/v1/ws` skeleton

- `authenticate("session") { webSocket("/v1/ws") { … } }`. Bearer in the upgrade request header.
- Envelope: `@Serializable data class Envelope(val t: String, val id: Long? = null, val d: JsonElement? = null)`.
- On connect server sends `{"t":"hello"}`.
- Client → server (step 2): `{"t":"loc","id":1,"d":{"lat":..,"lon":..,"accuracyM":..}}` → same validation and `Positions.record` as the POST; reply `{"t":"ack","id":1}` or `{"t":"err","id":1,"d":{"error":"invalid_fix"}}`.
- Unknown `t` → `{"t":"err","id":..,"d":{"error":"unknown_type"}}`, connection stays open.
- Ping 30 s, timeout 60 s. No connection registry yet (step 3 adds `realtime/Presence.kt`).
- The app does not open this socket in step 2.

---

## 8. Configuration

Environment variables only (no config file). `/etc/esper/server.env` on the VM.

| Variable | Default | Meaning |
|---|---|---|
| `ESPER_PORT` | `8080` | bind port, always on `127.0.0.1` |
| `ESPER_DB_URL` | `jdbc:postgresql://127.0.0.1/esper` | |
| `ESPER_DB_USER` | `esper` | |
| `ESPER_DB_PASSWORD` | (required) | |
| `ESPER_RP_ID` | `leviathan256.github.io` | |
| `ESPER_RP_ORIGINS` | (required, comma list) | `android:apk-key-hash:<b64url>[,…]` |
| `ESPER_CONTENT_DIR` | sysprop `esper.contentDir`, else `content` | directory holding `jobs/` and `monsters/` |
| `JAVA_OPTS` | `-Xmx512m` (set in env file) | consumed by the `application` start script |

Content loads once at startup; a `ContentValidationException` fails startup.

---

## 9. Deploy topology (all free)

**VM:** Oracle Always Free A1 Flex (arm64), Ubuntu 24.04, 1 OCPU / 6 GB is plenty.
VCN security list must allow ingress TCP 80 and 443 (Levi, in the console).

**Name:** DuckDNS (`esper-<x>.duckdns.org`, free account) preferred: it is on the
Public Suffix List so Let's Encrypt rate limits are per-name. `sslip.io`
(`<a-b-c-d>.sslip.io`) needs no account but shares LE quota with everyone;
Caddy falls back to ZeroSSL automatically if LE refuses. Either works.

**`deploy/setup.sh`** (idempotent, run once as `ubuntu` with `sudo`, takes the host name as `$1`):

1. `apt-get install -y openjdk-17-jre-headless postgresql caddy rsync`.
2. `useradd --system --home /opt/esper esper`; `mkdir -p /opt/esper/server /etc/esper`; add Levi's deploy public key to `/opt/esper/.ssh/authorized_keys` for user `esper` (path passed as `$2` or pasted).
3. Create Postgres role + database `esper` with a random password; write `/etc/esper/server.env` (mode 0600, owner esper) with every §8 variable; `ESPER_RP_ORIGINS=` left for Levi to fill.
4. Oracle images drop everything but 22: `iptables -I INPUT 6 -p tcp -m multiport --dports 80,443 -m state --state NEW -j ACCEPT && netfilter-persistent save`.
5. Install `deploy/esper-server.service` into `/etc/systemd/system/`, `deploy/Caddyfile` into `/etc/caddy/Caddyfile` with `ESPER_HOST=$1` set through a systemd drop-in `caddy.service.d/esper.conf`, `systemctl enable --now esper-server caddy`.
6. Sudoers: `esper ALL=(root) NOPASSWD: /usr/bin/systemctl restart esper-server`.

**`deploy/esper-server.service`:** `User=esper`, `WorkingDirectory=/opt/esper/server`,
`EnvironmentFile=/etc/esper/server.env`, `ExecStart=/opt/esper/server/bin/server`,
`Restart=on-failure`, `After=postgresql.service`.

**`deploy/Caddyfile`:** `{$ESPER_HOST} { reverse_proxy 127.0.0.1:8080 }`.

**`deploy/server.env.example`:** every §8 variable with its default.

**`.github/workflows/deploy-server.yml`:**

- Triggers: push to `main` touching `server/**`, `engine/**`, `content/**`, `deploy/**`; `workflow_dispatch`.
- Step 1: if `secrets.ESPER_SERVER_HOST` is empty, write "deploy skipped: no ESPER_SERVER_HOST" to the step summary and exit 0. CI stays green until Levi sets secrets.
- Then: setup-java 17 temurin, setup-gradle 8.7, `gradle :server:test :server:installDist --console=plain` (with a `postgres:16` service as in `ci.yml`).
- `rsync -az --delete server/build/install/server/ esper@$HOST:/opt/esper/server/` using `secrets.ESPER_SERVER_SSH_KEY` and `secrets.ESPER_SERVER_KNOWN_HOSTS` (pinned, no `StrictHostKeyChecking=no`), then `ssh esper@$HOST sudo systemctl restart esper-server`, then `curl -fsS --retry 10 --retry-delay 3 https://$HOST/healthz`.
- **Never touches the `nightly` tag or any release.**

**Server URL into the APK:** add job-level `env: ESPER_SERVER_URL: ${{ vars.ESPER_SERVER_URL }}` to the APK jobs in `release-nightly.yml`, `release-tag.yml` and `ci.yml` (`build` job). Composite-action steps inherit job env, so `action.yml` needs no new input. Unset = empty string = app shows "Server not configured" and a Settings field.

**Signer rows in the `build-apk` summary** (`action.yml`, next to the existing `signer` row; `hex` is the existing lower-case digest):

- `assetlinks`: `echo "$hex" | sed 's/../&:/g;s/:$//' | tr a-f A-F`
- `passkey origin`: `android:apk-key-hash:$(echo "$hex" | xxd -r -p | base64 | tr '+/' '-_' | tr -d '=\n')`

`pages/assetlinks.json` is the template Levi copies to the user site:

```json
[{"relation":["delegate_permission/common.get_login_creds"],
  "target":{"namespace":"android_app","package_name":"com.esper.app",
            "sha256_cert_fingerprints":["AA:BB:…:FF"]}}]
```

Only builds signed with the real `ESPER_KEYSTORE_*` key can sign in. The
throwaway-key fallback produces a different hash; the build still succeeds, so
a missing secret shows up only as "passkey_rejected" on device.

---

## 10. Android

### Files

- **New** `core/ServerClient.kt`: `GitHubClient` pattern (`HttpURLConnection` in `withContext(Dispatchers.IO)`, `runCatching` → `Result`, `org.json`, connect 15 s / read 20 s, `disconnect()` in `finally`). Base URL = `settings.serverUrl`. `class Unauthorized : IOException()` on 401. Methods: `registerOptions()`, `registerVerify(ceremonyId, responseJson)`, `loginOptions()`, `loginVerify(…)`, `logout()`, `getCharacter(): CharacterState?` (null on 404), `createCharacter(name, jobId)`, `putCharacter(state)`, `postLocation(lat, lon, accuracyM)`. State JSON via `SaveCodec` (already on the app classpath through `:engine`).
- **New** `core/Passkeys.kt`: `suspend fun register(activity)` and `signIn(activity)` as in §6. Maps `NoCredentialException`, `CreateCredentialNoCreateOptionException`, `CreateCredentialUnsupportedException`, and `Build.VERSION.SDK_INT < 34` to: "Passkeys need Android 14 or newer and a passkey provider (e.g. Google Password Manager or Bitwarden)."
- **New** `ui/SignInScreen.kt`: one line on why location is sent (link to the doc rule); buttons "Create account" (passkey registration), "Sign in with passkey", text button "Settings & updates" (opens `SettingsScreen` standalone so the updater stays reachable while signed out or while the server is down). Busy + error states. Blank `serverUrl` → "Server not configured. Set it in Settings."
- `core/Settings.kt`: `sessionToken: String?` in the encrypted prefs; `serverUrl` in plain prefs defaulting to `BuildConfig.SERVER_URL` (same shape as `repoOwner`). `AppStateSnapshot` unchanged.
- `ui/SettingsScreen.kt`: server URL field; "Sign out" = best-effort `logout()`, clear token, `GameSession.reset()`.
- `MainActivity.kt` `EsperApp()`: replaces `remember { ensureCharacter }`. No token → `SignInScreen`. Else `LaunchedEffect(token) { GameSession.loadCharacter(context, settings) }` and branch: loading → spinner; error → message + Retry + Settings + Sign out; `needsCharacter` → `CharacterCreatorScreen`; else `EsperScaffold`. `Unauthorized` anywhere clears the token.
- `game/GameSession.kt`: `ensureCharacter` → `suspend fun loadCharacter(context, settings)`: load catalog as today; `getCharacter()`; on null, if `GameStorage(context).load()` finds a local save, `putCharacter` it then `GameStorage.clear()` (one-time import, commented), else `needsCharacter = true`. `createCharacter` becomes `suspend` and POSTs. `endBattle` keeps the in-memory update, then `scope.launch { putCharacter }` on a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` owned by `GameSession`; failure sets `syncError` (one-line banner on the map), next `endBattle` or app resume retries. `onLocationFix` applies the 30 s / 50 m throttle and calls `postLocation` fire-and-forget. `beginBattle` only requires `character != null`.
- Remove `ensureCharacter` calls in `ui/MapScreen.kt:155` and `ui/game/CharacterSheetScreen.kt:37`; the gate guarantees `character != null`.
- `ui/game/CharacterCreatorScreen.kt`: Begin launches `createCharacter` in `rememberCoroutineScope()`, with busy flag and error text.
- `game/GameStorage.kt`: keep `load()`, add `clear()`, delete `save()` (grep confirms `endBattle` and `createCharacter` were its only callers and both move to the server).
- `AndroidManifest.xml`: rewrite the location comment: the latest fix is sent to the Esper server; the rule lives in `docs/GAME_DESIGN.md`. No manifest entry is needed for passkeys.

### Device support (Levi decision)

Without `credentials-play-services-auth`, passkeys work only on **Android 14+**.
Android 8–13 devices cannot sign in, so they cannot play. Adding that library
restores 9–13 only on devices that have Google Play Services and breaks the
no-Play-Services rule. A later alternative is a recovery-code sign-in.
Recommendation: ship Android 14+ only for now; `minSdk` stays 26.

---

## 11. CI and tests

`ci.yml` `engine` job: add a `postgres:16` service
(`POSTGRES_PASSWORD=esper`, `POSTGRES_DB=esper_test`, `pg_isready` health),
job env `ESPER_TEST_DB_URL=jdbc:postgresql://localhost:5432/esper_test?user=postgres&password=esper`,
and change the run line to `gradle :engine:test :server:test --console=plain`.
Rename the step "Run engine and server tests".

`server/src/test/kotlin/com/esper/server/`:

- `ConfigTest`: `ESPER_RP_ORIGINS` splits on commas, trims, drops blanks; empty fails.
- `PositionsTest`: bounds reject bad lat, lon, accuracy, NaN; `purge` drops a fix older than 10 min; logout removes it.
- `CharacterRulesTest` (reads `content/`): POST rejects non-starting job, blank name, 25-char name; PUT rejects renamed character, unknown job/ability id, wrong schema version, lower level; accepts a valid round-trip.
- `ApiTest` (`testApplication`, gated by `assumeTrue(System.getenv("ESPER_TEST_DB_URL") != null)`, Flyway clean+migrate per test): `/healthz` 200; register options carry `rp.id == leviathan256.github.io`, `authenticatorSelection.residentKey == "required"`; verify with unknown `ceremonyId` → 400 `invalid_ceremony`; no token → 401; seeded session: POST 201, second POST 409, GET round-trips, PUT 204, expired session → 401; `/v1/location` lat 91 → 400, valid → 204; WebSocket without token refused, `loc` → `ack`.

Not unit-tested: a full passkey ceremony (no software authenticator). Verified
on device in Levi's step 7 (§13).

---

## 12. File ownership

Three disjoint sets. Each is implementable in parallel against the contract in
§5–§8 without touching another set's files.

**SERVER**
- `settings.gradle.kts` (add `include(":server")` only)
- `server/build.gradle.kts`
- `server/src/main/kotlin/com/esper/server/{Main,Config,Db,Content}.kt`
- `server/src/main/kotlin/com/esper/server/auth/{Passkeys,CredentialStore,Sessions}.kt`
- `server/src/main/kotlin/com/esper/server/character/CharacterRoutes.kt`
- `server/src/main/kotlin/com/esper/server/location/Positions.kt`
- `server/src/main/kotlin/com/esper/server/realtime/Socket.kt`
- `server/src/main/resources/db/migration/V1__init.sql`, `server/src/main/resources/logback.xml`
- `server/src/test/kotlin/com/esper/server/{ConfigTest,PositionsTest,CharacterRulesTest,ApiTest}.kt`

**APP** (`app/` only)
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` (comment)
- New: `core/ServerClient.kt`, `core/Passkeys.kt`, `ui/SignInScreen.kt`
- Edit: `core/Settings.kt`, `ui/SettingsScreen.kt`, `MainActivity.kt`, `game/GameSession.kt`, `game/GameStorage.kt`, `ui/MapScreen.kt`, `ui/game/CharacterSheetScreen.kt`, `ui/game/CharacterCreatorScreen.kt`

**OPS**
- `.github/workflows/deploy-server.yml` (new)
- `.github/workflows/{ci.yml,release-nightly.yml,release-tag.yml}` (job env + server test)
- `.github/actions/build-apk/action.yml` (two summary rows)
- `deploy/{setup.sh,esper-server.service,Caddyfile,server.env.example}`
- `pages/assetlinks.json`
- `docs/GAME_DESIGN.md` amendment (below), `README.md` "Server" section (Levi's steps, §13)

Review weight: `auth/Passkeys.kt`, `auth/Sessions.kt`, `location/Positions.kt`
and the `GameSession.loadCharacter` import path are security- or
data-loss-sensitive and get the strongest review. The rest is routine.

### `docs/GAME_DESIGN.md` amendment (OPS owns; wording is the contract)

- "Why the app asks for location" (lines ~179–187): replace "Nothing derived from a fix leaves the device." with:
  > Esper is online-only. While the map is open, the app sends your precise position (latitude, longitude, accuracy) to the Esper server over an encrypted connection, at most every 30 seconds or whenever you have moved 50 m. The server keeps only your latest position, in memory: it is overwritten by the next one, dropped 10 minutes after your last update, on sign-out, and whenever the server restarts. It is never written to the database or to logs, and never shared outside the game. Today it exists so the server can place you in the shared world; later steps use it for showing you to players within about 300 m, movement checks and territory.

  Decline sentence: "If you decline, the game stays playable anchored to the map view, and the server receives no position at all."
- Persistence row (~456): "Character saved on the Esper server (Postgres), tied to your passkey account; an on-device save from before the server existed is uploaded once on first sign-in and then deleted from the phone."
- Rule (~472–473): "Never transmit, store or retain a player's position beyond what 'Why the app asks for location' states; any change to what is sent, why, or how long it is kept is written there first."
- Open question ~559: resolved, point at the paragraph. ~561 ("Is the world shared…"): settled, shared world on a single Esper server, online required.
- ~364–366 (H3 counters on-device) and ~550–553 (anti-cheat): unchanged for now; add one sentence that the server's anti-cheat will use only the previous position, per the rule above.
- Grep `offline` / `on-device` for anything else stale.

---

## 13. Levi's handoff (code never blocks on these)

1. Oracle: create an Always Free A1 Flex VM, Ubuntu 24.04 (arm64). In the VCN security list add ingress TCP 80 and 443. Note the public IP.
2. Name: create `esper-<x>.duckdns.org` pointing at the IP (or use `<a-b-c-d>.sslip.io`).
3. SSH in as `ubuntu`, clone or copy `deploy/`, run `sudo ./setup.sh <host> <path-to-deploy-pubkey>`.
4. Repo secrets: `ESPER_SERVER_HOST` (the name), `ESPER_SERVER_SSH_KEY` (private key for user `esper`), `ESPER_SERVER_KNOWN_HOSTS` (`ssh-keyscan <host>`). Repo variable: `ESPER_SERVER_URL=https://<host>`.
5. Open any nightly run's step summary. Copy `assetlinks` into `pages/assetlinks.json`, and create repo `Leviathan256/leviathan256.github.io` (Pages on, default branch) with `.well-known/assetlinks.json` and an empty `.nojekyll` at the root. Check `https://leviathan256.github.io/.well-known/assetlinks.json` returns it.
6. Copy `passkey origin` into `/etc/esper/server.env` as `ESPER_RP_ORIGINS=…`, then `sudo systemctl restart esper-server`.
7. Push to `main` (or dispatch `deploy-server`). `curl https://<host>/healthz` → `ok`. Install the nightly via Obtainium on an Android 14+ phone with a passkey provider: create account, create character, win a fight, kill and reopen the app; the character must persist.
8. Decide: Android 14+ only (recommended) or accept the Play Services dependency for 9–13.

---

## 14. Later steps (design only)

| Step | Attaches to |
|---|---|
| 3 shared avatars ≤ 300 m, public encounters | `realtime/Presence.kt` over `Positions`; app gains a WebSocket client (OkHttp is the no-GMS option); server pushes `{"t":"nearby"}` |
| 4 parties (4) | `V2__parties.sql` on `characters.id`; `party/` routes; socket `party.*` types |
| 5 server-authoritative combat | `combat/` calls `CombatEngine`/`BattleBuilder` with a server `RandomSource`; `PUT /v1/character` removed; XP awarded server-side |
| 6 PvP tags, H3 claims, guilds + chat, friends | `territory/` (H3 `bigint`), `guild/`, `social/`; chat over the socket |
| 7 teleport, anti-cheat | speed check in `Positions.record` using the previous `Fix` (> 300 km/h reject, > 25 km/h passenger mode); if history must persist, change §7 and `GAME_DESIGN.md` first |

## 15. Known risks

- Versions are from memory or a single Maven Central check; nothing was built here (no Android SDK, Central throttled). First CI run proves them.
- Yubico accepting Android's extra `androidPackageName` field in `clientDataJSON` and the `android:apk-key-hash:` origin is from reading its source, not from a run; the device check in §13 step 7 is the proof.
- `PUT /v1/character` trusts the client until step 5.
- Oracle reclaims idle Always Free instances; systemd brings everything back on boot.
- Android < 14 cannot sign in (§10).
