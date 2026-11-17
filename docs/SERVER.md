# Esper server

The Esper backend: a Rust (axum) server that holds accounts, passkeys and
characters, receives player positions, and is the **single home of the game
rules** (ADR-0002). It runs on single-node k3s behind Cloudflare at
`esper.media` (ADR-0001). This document is the contract between `server/`, the
Android app and the ops files. Change the contract here first.

Status: step 2 (passkey sign-in, a server-held character, position upload)
shipped as a Kotlin/Ktor server. The **Rust port** replaces it at feature
parity, with the same API (§5), in one PR that also deletes `server/**/*.kt`.
Everything under "Later steps" is design only.

---

## 1. Decisions

| Topic | Decision | Why |
|---|---|---|
| Server stack | Rust, **axum** on **tokio**, **sqlx** (Postgres, compile-checked queries), `tower` middleware | One static binary, no JVM. sqlx keeps plain SQL, as the Kotlin server's plain JDBC did. |
| Rules home | Game rules live in Rust (`server/rules`). The Kotlin `engine/` is **frozen**: no new rules. Deleted when server combat ships and the app stops running combat locally. | The world is shared and online-only, so the server is the authority. A cross-language build (UniFFI) costs more than a frozen interim copy. ADR-0002. |
| Passkeys | **webauthn-rs** 0.6, features `conditional-ui` (discoverable login) | `append_allowed_origin` is documented for Android's app origins; `android:apk-key-hash:…` parses as a URL. 0.6 is pure Rust (no OpenSSL), so the musl cross build needs no C toolchain. |
| Packaging | `x86_64`/`aarch64-unknown-linux-musl` static binary in a distroless `static` image, pushed to **GHCR** for `linux/amd64` and `linux/arm64` | A few MB, no runtime to patch, runs on any conformant cluster. |
| App HTTP | `HttpURLConnection`, the `GitHubClient` house pattern (unchanged) | The API does not change, so the app does not change. |
| App WebSocket | **None yet.** Position goes by `POST /v1/location`. | The server exposes an authenticated `/v1/ws` skeleton so a later step attaches without an API change. |
| Position retention | Server keeps the **latest fix per account, in memory only**. Dropped after 10 minutes without an update, on logout, on restart. Never written to Postgres or logs. | Unchanged from step 2. |
| Character | One per account, `jsonb` column holding the app's `CharacterState` JSON | The Rust `rules` crate ports `SaveCodec` + `SaveMigrations` and must read and write exactly what the Kotlin app does (golden fixtures, §11). |
| Progress | Client-reported: app `PUT`s the state after a victory | Until server combat (step 2 of §14). `shortcut:` comment in code. |
| Credential Manager | `androidx.credentials:credentials:1.3.0` only, no Play Services | Passkey sign-in needs **Android 14+**. See §10. |
| Hosting | k3s on Levi's server, Postgres 18 in-cluster, Traefik + cert-manager, Cloudflare proxy | ADR-0001. |

---

## 2. Architecture

```
Android app ─HTTPS─▶ Cloudflare (proxy, Full strict) ─HTTPS─▶ Traefik (k3s, :443) ─▶ esper-server pod :8080 ─sqlx─▶ postgres-0 (StatefulSet)
   │
   │  androidx.credentials ◀── RP ID esper.media ──▶ https://esper.media/.well-known/assetlinks.json (served by esper-server)
   │
   └─HTTPS─▶ GitHub Releases "nightly" (UpdateChecker, unchanged, independent of the server)
```

- One replica, one Postgres. In-memory stores (ceremonies, positions) are
  single-instance by design and say so in code. `replicas: 1` and
  `strategy: Recreate` follow from that.
- The pod binds `0.0.0.0:8080`; the pod network is the boundary. Only the
  Ingress reaches it from outside.
- The app never talks to the server without TLS: targetSdk 35 blocks cleartext.
- `UpdateChecker` and the `nightly` release are untouched. No server artefact
  is ever uploaded to a GitHub release.

### Workspace layout (`server/`)

| Path | Holds |
|---|---|
| `Cargo.toml` | workspace: `rules`, `api`. Exact versions pinned, `Cargo.lock` committed. |
| `rust-toolchain.toml` | pinned stable toolchain + the two musl targets |
| `rules/` | pure, no I/O: `content` (load + validate `content/`), `character` (`CharacterState`, factory, `save_codec` + migrations), `combat` (ATB engine, battle builder, `SimpleAi`, `java.util.Random`-exact dice). Unit-tested. |
| `api/src/main.rs` | `Config` from env, pool + `sqlx::migrate!`, content load, router, minute-tick purge task |
| `api/src/config.rs` | §8. Fails fast on empty `ESPER_RP_ORIGINS`. |
| `api/src/auth/{passkeys,sessions}.rs` | webauthn-rs, ceremony store, the four ceremony routes; session issue / validate / slide / delete, bearer extractor |
| `api/src/character.rs` | `GET/POST/PUT /v1/character`, `CharacterRules` (pure, in `rules`) |
| `api/src/location.rs` | `record`, `remove`, `purge`; `Fix`; pure `validate(lat, lon, acc)`; `POST /v1/location` (`latest` arrives with presence) |
| `api/src/realtime.rs` | `/v1/ws`, `Envelope`, `loc` handler |
| `api/src/error.rs` | the §5 error body and codes |
| `migrations/0001_init.sql` | §4 |
| `Dockerfile` | build stage `rust:<pinned>` with musl, final `gcr.io/distroless/static`, copies the binary and `content/` |

Later steps add a module each (`combat`, `party`, `territory`, `guild`,
`social`), rules in `rules/`, routes in `main.rs`, socket types in
`realtime.rs`, and `migrations/000N_*.sql`.

Logging: `tracing` to stdout, method, path, status, latency only. **No request
bodies, no positions, ever.**

---

## 3. Build

- `settings.gradle.kts`: remove `include(":server")`. Gradle builds only `app/`
  and the frozen `engine/`. `:app:assembleRelease` never touches `server/`.
- Every dependency is pinned to an exact version in `Cargo.toml`
  (`=x.y.z`), per "New dependencies are pinned to an explicit version".
  Expected crates: `axum`, `tokio`, `tower`, `tower_governor`, `sqlx`
  (`runtime-tokio`, `tls-rustls`, `postgres`, `uuid`, `json`, `time`, `macros`,
  `migrate`), `webauthn-rs` (`conditional-ui`), `serde`, `serde_json`, `uuid`,
  `time`, `rand`, `sha2`, `base64`, `tracing`, `tracing-subscriber`.
- sqlx offline mode: `cargo sqlx prepare` output (`.sqlx/`) is committed so the
  image and CI build without a live database.

---

## 4. Schema (`migrations/0001_init.sql`)

Starts from an empty database: no production data exists. Two deliberate
changes from the Kotlin `V1__init.sql`, both forced by webauthn-rs: the
WebAuthn user handle is the account's UUID (webauthn-rs identifies a
discoverable login by `Uuid`), and a passkey is stored as the library's
serialized `Passkey` (it carries the COSE key and the counter together).

```sql
CREATE TABLE accounts (
  id          uuid PRIMARY KEY,                    -- also the WebAuthn user handle
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE passkeys (
  credential_id bytea PRIMARY KEY,
  account_id    uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  passkey       jsonb NOT NULL,                    -- webauthn_rs::prelude::Passkey
  created_at    timestamptz NOT NULL DEFAULT now(),
  last_used_at  timestamptz
);
CREATE INDEX passkeys_account ON passkeys(account_id);
CREATE TABLE sessions (
  token_hash   bytea PRIMARY KEY,                  -- SHA-256 of the bearer token; raw token never stored
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
  state      jsonb NOT NULL,                     -- CharacterState JSON, as the app's SaveCodec writes it
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

No `positions` and no `challenges` table: both live in memory (§6, §7).

Later: multiple characters = drop the `UNIQUE` on `characters.account_id`.
Parties, guilds, friends, claims = `0002_…` onward keyed on `characters.id`.
Territory = H3 cell as `bigint`.

---

## 5. API contract

**Unchanged from the Kotlin server**, plus one read-only route
(`/.well-known/assetlinks.json`). All other routes except `/healthz` are under
`/v1`. Bodies are JSON (`Content-Type: application/json`). Authenticated
routes take `Authorization: Bearer <token>`.

### Errors

Every non-2xx response has this body:

```json
{ "error": "<code>", "message": "<human text, safe to show>" }
```

| HTTP | `error` | When |
|---|---|---|
| 400 | `bad_request` | malformed JSON, missing field, body over 64 KB |
| 400 | `invalid_ceremony` | unknown, expired or already-used `ceremonyId` |
| 400 | `passkey_rejected` | webauthn-rs `finish_*` failed (bad origin, RP ID, signature, counter) |
| 400 | `invalid_name` | name blank or > 24 chars after trim |
| 400 | `invalid_job` | `startingJobId` not a starting job, or PUT contains an id not in the catalog |
| 400 | `invalid_state` | PUT fails the character rules (schema, name change, level decrease) |
| 400 | `invalid_fix` | lat/lon/accuracy out of range or NaN |
| 401 | `unauthorized` | missing, unknown or expired token. **App clears its token and returns to sign-in on any 401.** |
| 404 | `no_character` | `GET /v1/character` before creation |
| 404 | `not_found` | `/.well-known/assetlinks.json` when `ESPER_ASSETLINKS_FILE` cannot be read |
| 409 | `character_exists` | second `POST /v1/character` |
| 429 | `rate_limited` | ceremony routes, 20/min per client IP |
| 503 | `unavailable` | `/healthz` DB check failed |

### Routes

| Method, path | Auth | Request | Success |
|---|---|---|---|
| `GET /healthz` | none | – | `200` text `ok` after `SELECT 1`; else `503` |
| `GET /.well-known/assetlinks.json` | none | – | `200` the file at `ESPER_ASSETLINKS_FILE` (§9), `application/json` |
| `POST /v1/auth/register/options` | none | `{}` | `200 {"ceremonyId": "<b64url>", "publicKey": {…}}` |
| `POST /v1/auth/register/verify` | none | `{"ceremonyId": "…", "response": "<registrationResponseJson string>"}` | `200 {"token": "<b64url>"}` |
| `POST /v1/auth/login/options` | none | `{}` | `200 {"ceremonyId": "…", "publicKey": {…}}` |
| `POST /v1/auth/login/verify` | none | `{"ceremonyId": "…", "response": "<authenticationResponseJson string>"}` | `200 {"token": "…"}` |
| `POST /v1/auth/logout` | bearer | – | `204`; deletes this session and the caller's position |
| `GET /v1/character` | bearer | – | `200` body = `CharacterState` JSON |
| `POST /v1/character` | bearer | `{"name": "…", "startingJobId": "…"}` | `201` body = state JSON |
| `PUT /v1/character` | bearer | `CharacterState` JSON | `204` |
| `POST /v1/location` | bearer | `{"lat": 0.0, "lon": 0.0, "accuracyM": 0.0}` | `204` |
| `GET /v1/ws` | bearer header | WebSocket upgrade | see §7 |

`publicKey` is the **inner** object: webauthn-rs serializes
`CreationChallengeResponse` / `RequestChallengeResponse` as
`{"publicKey": {…}}`, and the server sends only the value, because
`CreatePublicKeyCredentialRequest(requestJson)` and
`GetPublicKeyCredentialOption(requestJson)` want the inner object. `response`
is the string `androidx.credentials` hands back, passed through verbatim as a
JSON string value; the server parses it into `RegisterPublicKeyCredential` /
`PublicKeyCredential`.

### Character rules (`rules::character`, pure, unit-tested)

- `POST`: name trimmed, 1–24 chars. `startingJobId` ∈ the catalog's starting
  jobs. Server builds the new character (port of `CharacterFactory.newCharacter`),
  encodes, inserts.
- `PUT`: body through `save_codec::decode` (runs migrations). Reject unless:
  `schemaVersion == CURRENT_SCHEMA_VERSION`; every `currentJobId`,
  `unlockedJobIds`, `jobPoints` key and `learnedAbilityIds` entry exists in the
  catalog; when a row exists, `name` unchanged and `level >= stored.level`.
  Upsert. `shortcut: client-authoritative progress, replaced when server combat
  ships and PUT goes away`.
- `GET` returns `encode(decode(stored))` (`jsonb` reorders keys; re-encoding
  also migrates old rows).

---

## 6. Auth flow

### Relying party

```rust
let rp_origin = Url::parse(&format!("https://{}", config.rp_id))?;
let mut builder = WebauthnBuilder::new(&config.rp_id, &rp_origin)?.rp_name("Esper");
for origin in &config.rp_origins {            // Vec<Url>, parsed in Config
    builder = builder.append_allowed_origin(origin);
}
let webauthn = builder.build()?;
```

- `ESPER_RP_ID` default `esper.media`. **Passkeys bind to it forever**: changing
  it later makes every player register again.
- `ESPER_RP_ORIGINS` comma-separated, e.g. `android:apk-key-hash:<b64url>`. More
  than one entry is allowed (release key + a debug key). Empty = server refuses
  to start, logs why. `allow_subdomains` and `allow_any_port` stay false.
- Android computes the origin as `android:apk-key-hash:` + base64url(SHA-256(signing
  cert DER)) with no padding. The value for the release key appears in every
  `build-apk` run summary (§9).
- Android checks `https://esper.media/.well-known/assetlinks.json` lists the
  package + cert fingerprint before it will create or use a passkey for that RP
  ID. Relation needed: `delegate_permission/common.get_login_creds` only.

### Registration

1. `register/options`: new account UUID; `start_passkey_registration(uuid,
   "esper-" + first 8 hex of uuid, "Esper player", None)`. The options must carry
   `residentKey = required` and `userVerification = required`; if webauthn-rs
   emits a weaker `residentKey`, the server sets it to `required` in the JSON it
   sends (Android passkeys are discoverable regardless; §15). Store
   `(ceremonyId → PasskeyRegistration, expiresAt)`.
2. App: `CredentialManager.createCredential(activity, CreatePublicKeyCredentialRequest(publicKeyJson))`.
3. `register/verify`: remove the ceremony (single use), `finish_passkey_registration`,
   then one transaction: insert `accounts`, insert `passkeys`, issue session.
   Return `{token}`.

### Login

1. `login/options`: `start_discoverable_authentication()` (no username, no
   `allowCredentials`), `userVerification = required`.
2. App: `getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(publicKeyJson))))`.
3. `login/verify`: remove the ceremony; `identify_discoverable_authentication`
   gives `(account uuid, credential id)`; load that passkey;
   `finish_discoverable_authentication`. Apply the result with
   `Passkey::update_credential` (counter), save, set `last_used_at`. Issue session.

### Ceremony store

`Mutex<HashMap<String, Pending>>`; key = 16 random bytes base64url; TTL 5 min;
removed on use; swept by the minute tick.
`shortcut: in-memory, restart drops in-flight ceremonies, single instance only; move to a table if we ever run two`.

### Sessions

- Token: 32 bytes from the OS CSPRNG, base64url no padding. Server stores
  `sha256(token)`. Raw token lives only in the app's `EncryptedSharedPreferences`
  and is never logged.
- `expires_at = now + 90 days`, sliding: on validate, if `last_used_at` is older
  than 1 day, set `last_used_at = now`, `expires_at = now + 90 days`.
- Logout deletes the row. Expired rows deleted by the minute tick. An account
  keeps only its 10 newest sessions, so scripted sign-ins cannot fill the disk.
  No rotation yet (`shortcut:` comment).

### Rate limit

`tower_governor`, 20 requests/min per client IP on `/v1/auth/*`. The key is
the **`CF-Connecting-IP`** header: every request passes through Cloudflare, so
the TCP peer is Traefik and `X-Forwarded-For`'s first hop is a Cloudflare IP.
Trusting the header is safe because the host firewall admits 80/443 only from
Cloudflare's ranges (§9). A request with no header (in-cluster) falls back to
the peer address.

---

## 7. Location

### Rule (also in `docs/GAME_DESIGN.md`, which is the player-facing statement)

- The app sends its latest fix (`lat`, `lon`, `accuracyM`) **only while the map
  screen is in the foreground**, at most **once per 30 s**, plus whenever the
  player has moved **≥ 50 m** since the last send. If location permission is
  declined, **nothing is sent**.
- The server keeps **only the latest fix per account, in memory**
  (`HashMap<AccountId, Fix { lat, lon, accuracy_m, received_at }>`). It is
  overwritten by the next fix, removed after **10 minutes** without one, on
  logout, and on restart. It is **never** written to Postgres, logs, or anywhere
  else.
- `received_at` is server time. Client time is not accepted.
- Validation (`location::validate`, pure): `lat ∈ [-90, 90]`, `lon ∈ [-180, 180]`,
  `accuracyM ∈ [0, 10000]`, finite. Otherwise `400 invalid_fix` (HTTP) or `err`
  (socket).
- Traefik access logging stays off for the Esper Ingress.

Presence and the speed check read this map (previous fix vs new fix: > 300 km/h
reject, > 25 km/h passenger mode). If a later step needs history or an H3 index
on disk, the rule above changes **in `GAME_DESIGN.md` first**.

### `/v1/ws` skeleton

- axum `WebSocketUpgrade` behind the bearer extractor. Bearer in the upgrade
  request header.
- Envelope: `struct Envelope { t: String, id: Option<i64>, d: Option<serde_json::Value> }`.
- On connect the server sends `{"t":"hello"}`.
- Client → server: `{"t":"loc","id":1,"d":{"lat":..,"lon":..,"accuracyM":..}}` →
  same validation and `record` as the POST; reply `{"t":"ack","id":1}` or
  `{"t":"err","id":1,"d":{"error":"invalid_fix"}}`.
- Unknown `t` → `{"t":"err","id":..,"d":{"error":"unknown_type"}}`, connection
  stays open.
- Ping 30 s, timeout 60 s. No connection registry yet.
- The app does not open this socket yet.

---

## 8. Configuration

Environment variables only. In the cluster, non-secret values come from the
`esper-server` ConfigMap and secrets from the `esper-server` Secret (§9).

| Variable | Default | Meaning |
|---|---|---|
| `ESPER_BIND` | `0.0.0.0:8080` | listen address inside the pod |
| `ESPER_DB_URL` | (required, Secret) | `postgres://esper:<pw>@postgres:5432/esper` |
| `ESPER_RP_ID` | `esper.media` | |
| `ESPER_RP_ORIGINS` | (required, comma list) | `android:apk-key-hash:<b64url>[,…]` |
| `ESPER_CONTENT_DIR` | `content` | directory holding `jobs/` and `monsters/`; baked into the image |
| `ESPER_ASSETLINKS_FILE` | `/etc/esper/assetlinks.json` | mounted from the `esper-assetlinks` ConfigMap |
| `RUST_LOG` | `info` | |

Content loads once at startup; a validation error fails startup, so a bad
content change never reaches players.

---

## 9. Deploy topology

**Host:** Levi's own server, Ubuntu, single-node **k3s** (bundled Traefik and
local-path storage kept). ServiceLB is **disabled**: it DNATs 80/443 on every
local address, the Tailscale one included, which would hijack `tailscale serve`.
Traefik's Service is `ClusterIP` with the host's public IPv4 as its `externalIP`
instead. Flannel uses `host-gw`, so no VXLAN port is open. The Postgres pod runs
without AppArmor: on Ubuntu 26.04 containerd's default profile denies signals
inside a container, which hangs Postgres.

**Firewall (host, nftables; not built yet, #12. Until it is, 80/443 accept any
source and `CF-Connecting-IP` can be spoofed by calling the origin directly):**

| Source | Open |
|---|---|
| `tailscale0` | everything (cluster API 6443, kubelet, SSH) |
| public | 22/tcp (SSH, unchanged), 41641/udp (Tailscale direct), 80/443 **from Cloudflare's published IP ranges only** |
| public | everything else denied, including 6443, 10250 and Postgres |

The Cloudflare range list is refreshed by a weekly timer from
`https://www.cloudflare.com/ips-v4` and `/ips-v6`.

**Name and TLS:** zone `esper.media` on Cloudflare, `A`/`AAAA` for the apex to
the host, **proxied**, SSL mode **Full (strict)**. cert-manager issues the
origin certificate from Let's Encrypt with a **DNS-01** `ClusterIssuer` using a
Cloudflare API token scoped to `Zone:DNS:Edit` on `esper.media` only. This
issuer and the firewall are the only Cloudflare-specific parts.

**Manifests: `deploy/k8s/` (Kustomize)**

| Path | Holds |
|---|---|
| `base/namespace.yaml` | `esper` |
| `base/server.yaml` | `Deployment` (1 replica, `Recreate`, image `ghcr.io/leviathan256/esper-server:<sha>`, liveness + readiness `GET /healthz`, non-root, read-only root fs), `Service`, `ConfigMap esper-server` |
| `base/ingress.yaml` | standard `networking.k8s.io/v1` `Ingress` for `esper.media`, TLS secret from cert-manager. **No Traefik CRDs.** |
| `base/postgres.yaml` | `StatefulSet` `postgres:18` (pinned major), 1 replica, `PersistentVolumeClaim` 10 Gi (local-path), `Service postgres` (ClusterIP only) |
| `base/backup.yaml` | `CronJob` nightly `pg_dump -Fc` to a hostPath `/var/backups/esper`, keeps 14 days |
| `base/assetlinks.yaml` | `ConfigMap esper-assetlinks` holding `assetlinks.json` |
| `base/cert-issuer.yaml` | `ClusterIssuer letsencrypt-dns` (Cloudflare DNS-01) |
| `overlays/prod/kustomization.yaml` | image tag, host name |

Secrets are **never in git**. `deploy/host-setup.sh` creates them once with
`kubectl create secret generic`, under the names and keys the manifests read:

| Secret | Namespace | Keys |
|---|---|---|
| `esper-server` | `esper` | `ESPER_DB_URL`, `ESPER_RP_ORIGINS` |
| `postgres` | `esper` | `password` (the one in `ESPER_DB_URL`) |
| `cloudflare-api-token` | `cert-manager` (a `ClusterIssuer` reads its Secret there) | `api-token` |

**`deploy/host-setup.sh`** (idempotent, run once with `sudo`): install k3s
(pinned version), install cert-manager (pinned manifest), apply the firewall and
the Cloudflare-range timer, create the namespace and the three Secrets (prompts
for the token and RP origins; generates the Postgres password).

Postgres major upgrades are deliberate: dump, bump the pinned major, restore.
Never a floating tag.

**`.github/workflows/deploy-server.yml`:**

- Triggers: push to `main` touching `server/**`, `content/**`, `deploy/**`; `workflow_dispatch`.
- Job 1 `test`: `cargo fmt --check`, `cargo clippy -- -D warnings`, `cargo test`
  with a `postgres:18` service.
- Job 2 `image`: `docker buildx` for `linux/amd64,linux/arm64`, push
  `ghcr.io/leviathan256/esper-server:<sha>` with `GITHUB_TOKEN` (`packages: write`).
- Job 3 `deploy`: if `secrets.TS_OAUTH_CLIENT_ID` is empty, write "deploy
  skipped: no Tailscale credentials" to the summary and exit 0. Else join the
  tailnet (`tailscale/github-action`, tag `tag:esper-ci`), write the kubeconfig from
  `secrets.ESPER_KUBECONFIG` (server address = the host's tailnet name),
  `kustomize edit set image` to `<sha>`, `kubectl apply -k deploy/k8s/overlays/prod`,
  `kubectl -n esper rollout status deploy/esper-server`, then
  `curl -fsS --retry 10 https://esper.media/healthz`.
- **Never touches the `nightly` tag or any release.**

**Server URL into the APK:** job-level `env: ESPER_SERVER_URL: ${{ vars.ESPER_SERVER_URL }}`
on the APK jobs (unchanged). Set the variable to `https://esper.media`.

**Signer rows in the `build-apk` summary** (unchanged):

- `assetlinks`: `echo "$hex" | sed 's/../&:/g;s/:$//' | tr a-f A-F`
- `passkey origin`: `android:apk-key-hash:$(echo "$hex" | xxd -r -p | base64 | tr '+/' '-_' | tr -d '=\n')`

`pages/assetlinks.json` stays the template; its filled-in copy goes into
`deploy/k8s/base/assetlinks.yaml`. The GitHub Pages user-site copy is retired.

Only builds signed with the real `ESPER_KEYSTORE_*` key can sign in. The
throwaway-key fallback produces a different hash; the build still succeeds, so
a missing secret shows up only as `passkey_rejected` on device.

---

## 10. Android

No change for the Rust port: the API (§5) is identical and the RP ID comes from
the server. The app work below shipped with step 2.

- `core/ServerClient.kt`: `GitHubClient` pattern (`HttpURLConnection` in
  `withContext(Dispatchers.IO)`, `runCatching` → `Result`, `org.json`, connect
  15 s / read 20 s). Base URL = `settings.serverUrl`. `Unauthorized` on 401.
  State JSON via `SaveCodec` (frozen `:engine`).
- `core/Passkeys.kt`: register and sign in as in §6. Maps the Credential Manager
  failures and `SDK_INT < 34` to: "Passkeys need Android 14 or newer and a
  passkey provider (e.g. Google Password Manager or Bitwarden)."
- `ui/SignInScreen.kt`, `core/Settings.kt` (`sessionToken` encrypted,
  `serverUrl`), `ui/SettingsScreen.kt` (server URL, sign out),
  `MainActivity.kt` gate, `game/GameSession.kt` (`loadCharacter`, one-time
  upload of an old local save, `PUT` after victory, throttled `postLocation`).

When server combat ships, the app stops running combat locally, its display
types move into `app/`, and `engine/` is deleted (ADR-0002).

### Device support (Levi decision)

Without `credentials-play-services-auth`, passkeys work only on **Android 14+**.
Android 8–13 devices cannot sign in, so they cannot play. A later alternative
is a recovery-code sign-in. `minSdk` stays 26.

---

## 11. CI and tests

`ci.yml`:

- `server` job: pinned toolchain, `cargo fmt --check`, `cargo clippy -- -D warnings`,
  `cargo test` with a `postgres:18` service (`ESPER_TEST_DB_URL=postgres://postgres:esper@localhost:5432/esper_test`).
- `engine` job: `gradle :engine:test` only, while the frozen engine exists.
  `ShippedContentTest` keeps running there because the app still loads content
  locally. **CI fails if either the Rust loader or `ShippedContentTest` rejects
  `content/`.**

`server/rules` tests:

- `content`: every file in `content/` loads; strict serde types
  (`deny_unknown_fields`) reject unknown fields; the same invalid cases
  `ShippedContentTest` rejects are rejected here.
- `save_codec`: **golden fixtures** in `content/fixtures/save/` (written by the
  Kotlin `SaveCodec`, one per schema version) decode, migrate and re-encode to
  the expected JSON. The Kotlin `engine` job round-trips the same files, so the
  two codecs cannot drift silently.
- `combat`: **recordings** in `content/fixtures/combat/` (seeded fights written
  by the Kotlin `CombatRecordingsTest`: solo, with a pet, with an NPC companion)
  replay turn for turn: actor, every gauge, `SimpleAi`'s choice, events, result.
  The Kotlin `engine` job checks the engine still plays the same files.
- `character_rules`: POST rejects non-starting job, blank name, 25-char name; PUT
  rejects renamed character, unknown job/ability id, wrong schema version, lower
  level; accepts a valid round-trip.

`server/api` tests (skipped unless `ESPER_TEST_DB_URL` is set; fresh migrate per test):

- `config`: `ESPER_RP_ORIGINS` splits on commas, trims, drops blanks; empty fails.
- `location`: bounds reject bad lat, lon, accuracy, NaN; purge drops a fix older
  than 10 min; logout removes it.
- `api`: `/healthz` 200; register options carry `rp.id == esper.media` and
  `authenticatorSelection.residentKey == "required"`; verify with unknown
  `ceremonyId` → 400 `invalid_ceremony`; no token → 401; seeded session: POST
  201, second POST 409, GET round-trips, PUT 204, expired session → 401;
  `/v1/location` lat 91 → 400, valid → 204; `/.well-known/assetlinks.json` 200;
  WebSocket without token refused, `loc` → `ack`; rate-limit keys on
  `CF-Connecting-IP`.

Not unit-tested: a full passkey ceremony (no software authenticator). Verified
on device (`BETA_SETUP_LEVI.md` §9).

---

## 12. File ownership (the Rust port PR)

**SERVER**
- `server/**` (new Rust workspace; delete `server/build.gradle.kts` and `server/src/**`)
- `settings.gradle.kts` (remove `include(":server")` only)
- `content/fixtures/save/*.json` (golden fixtures) and the matching Kotlin test in `engine/src/test/`

**OPS**
- `.github/workflows/{ci.yml,deploy-server.yml}`
- `deploy/k8s/**`, `deploy/host-setup.sh`; delete `deploy/{setup.sh,esper-server.service,Caddyfile,server.env.example}`
- `docs/BETA_SETUP_LEVI.md`

**APP**: none.

Review weight: `auth/passkeys.rs`, `auth/sessions.rs`, `location.rs`,
`save_codec` and the firewall in `host-setup.sh` are security- or
data-loss-sensitive and get the strongest review.

---

## 13. Levi's handoff

See `docs/BETA_SETUP_LEVI.md`. Code never blocks on it: until the Tailscale
secrets exist, the deploy workflow tests and builds the image, then stops green.

---

## 14. Later steps (design only)

Reordered: every rule settled for combat and co-operation needs combat on the
server, so it comes straight after the port.

| Step | Attaches to |
|---|---|
| 1 Rust port at parity | this document |
| 2 server-authoritative combat, **Flee**, **Absent** state | `rules::combat` (port of `CombatEngine`/`BattleBuilder`, server `RandomSource`); socket `enc.*` types; `PUT /v1/character` removed; XP awarded server-side; app renders server state; then delete `engine/` |
| 3 shared avatars ≤ 300 m, public encounters | `realtime` presence over the positions map; app gains a WebSocket client (OkHttp is the no-GMS option); server pushes `{"t":"nearby"}` |
| 4 parties, the **Absent vote** and **AI Driver** | `0002_parties.sql` on `characters.id`; `party` routes; socket `party.*` types |
| 5 PvP tags, H3 claims, guilds + chat, friends, **Rituals** and **Co-presence**, **Teleportation Ritual** and **Visitors**, anti-cheat | `territory` (H3 `bigint`), `guild`, `social`, `ritual`; speed check on the previous fix (> 300 km/h reject, > 25 km/h passenger mode); if history must persist, change §7 and `GAME_DESIGN.md` first |

## 15. Known risks

- Crate versions are chosen and pinned at implementation time; nothing here was
  built. The first CI run proves them.
- webauthn-rs with Android: `append_allowed_origin` is documented for native app
  origins, but acceptance of `android:apk-key-hash:` and of Android's extra
  `androidPackageName` field in `clientDataJSON` is unproven until the device
  check. If `start_passkey_registration` emits `residentKey` weaker than
  `required`, the server rewrites it (§6); confirm Credential Manager still
  creates a discoverable passkey.
- The Rust and Kotlin `SaveCodec`s must agree until `engine/` is deleted; the
  golden fixtures are the only guard.
- `PUT /v1/character` trusts the client until server combat.
- Backups live on the same disk as the database: losing the host loses both.
  Off-site copies are owed.
- If Cloudflare changes its IP ranges before the weekly refresh, new edge IPs
  are refused until it runs.
- Android < 14 cannot sign in (§10).
