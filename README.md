# Esper

A location-based RPG — Pokémon Go crossed with D&D — that its players
vibe-code from inside the app.

Tap **Ask Claude**, describe what the game should do, and the app dispatches a
Claude Code run on GitHub Actions along with a snapshot of its own state. Claude
opens a pull request. Merge it, and a new APK is published within minutes for
Obtainium to install.

## The loop

```
you type a request in the app
  → GitHub Actions runs Claude Code (claude-dispatch.yml)
  → Claude opens a PR
  → you merge to main
  → nightly build publishes Esper-nightly.apk
  → the app notices the new build, or Obtainium installs it in the background
```

### What makes it fast

| | |
| --- | --- |
| No redundant SDK setup | The runner image already has the Android SDK; CI only installs a component if one is genuinely missing. |
| Warm Gradle cache | `setup-gradle` restores `~/.gradle`; `main` writes the cache, other refs read it so concurrent runs don't thrash. |
| Shared build path | Nightly and tag builds use one composite action, in-job — no second job to spin up. |
| Nothing wasted | Docs-only pushes skip the build; a newer push to `main` cancels the in-flight one. |
| Shallow checkout | Version metadata only needs `HEAD`. |
| In-app update check | The app polls the release itself, so you don't wait on Obtainium's background interval. |

## Install via Obtainium

In **Obtainium**, tap **Add App** and paste `https://github.com/Leviathan256/Esper`
(source: GitHub). Then pick a channel:

- **Nightly** (rolling `nightly` prerelease, every merge to `main`) — asset
  `Esper-nightly.apk`. Turn on **Include prereleases** and **Use release title
  as version**. The tag is always `nightly`; the title carries the real version
  (`nightly-<sha>`), so without the second toggle Obtainium never sees an update.
- **Stable tags** (`v*`) — asset `Esper.apk`. Default settings.

For the shortest push-to-phone time, set Obtainium's background check interval
to 15 minutes and turn on background updates. The app's own update banner is
faster still — it checks when you open the Ask Claude screen and offers a
one-tap install.

## Signing key — set this up before anyone installs the app

Android only installs an update over an existing install when **both are signed
by the same key**. If the key ever changes, every player has to uninstall and
reinstall, losing local data. So the key must be durable.

Create one using **whichever tool you already have**. All three produce the same
thing: a PKCS12 keystore holding one RSA key under the alias `esper`. Pick one.

Whichever you use, choose **one password and use it everywhere**. A PKCS12
keystore cannot hold a key password different from the store password — keytool
ignores a differing `-keypass` with only a warning, and the mismatch surfaces
much later as `Given final block not properly padded` in the middle of an APK
packaging task.

### Option A — openssl (no Java needed)

`keytool` only exists if you have a JDK installed. openssl is far more commonly
present, and works fine:

```bash
PW='<choose-one-password>'

openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem \
  -days 10000 -nodes -subj "/CN=Esper, O=Esper, C=US"

openssl pkcs12 -export -inkey key.pem -in cert.pem \
  -name esper -out esper-release.jks -passout pass:"$PW"

rm key.pem cert.pem      # the keystore now holds both
```

### Option B — Docker (no local install at all)

Runs keytool inside a throwaway container and leaves the keystore in the
current directory:

```bash
PW='<choose-one-password>'

docker run --rm -v "$PWD:/w" -w /w eclipse-temurin:17 \
  keytool -genkeypair -keystore esper-release.jks -alias esper \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PW" -keypass "$PW" -dname "CN=Esper, O=Esper, C=US"
```

### Option C — Android Studio

**Build → Generate Signed App Bundle / APK → APK → Create new…**, then fill in
the form. Use the same value for both password fields and set the alias to
`esper`.

### Then encode it

```bash
base64 -w0 esper-release.jks          # Linux
base64 -i esper-release.jks           # macOS
# PowerShell:
# [Convert]::ToBase64String([IO.File]::ReadAllBytes("esper-release.jks"))
```

Then add, under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `ESPER_KEYSTORE_BASE64` | the base64 output above |
| `ESPER_KEYSTORE_PASSWORD` | your `$PW` |
| `ESPER_KEY_ALIAS` | `esper` |
| `ESPER_KEY_PASSWORD` | your `$PW` (optional — defaults to the store password) |

Keep `esper-release.jks` somewhere safe and **out of the repo** — losing it
means never being able to update an installed Esper again.

Without those secrets the build still works, but falls back to a throwaway key
kept in an Actions cache, and prints a warning saying so. That fallback is not
safe for real distribution: cache entries are evicted after 7 days without a
hit, and a regenerated key breaks updates for everyone already running the app.
Each build prints its signing certificate's SHA-256 in the job summary, so a
key change is visible in the log rather than only on players' phones.

## Setting up the Claude dispatch

**1. Repo secret.** Add one of:

- `ANTHROPIC_API_KEY` — an Anthropic API key, or
- `CLAUDE_CODE_OAUTH_TOKEN` — a Claude Code OAuth token

**2. Token on the device.** In the app: **Settings → GitHub token**. Use a
fine-grained personal access token scoped to this repository only, with
**Actions: read & write**. That is exactly enough to start a run and read its
status. It is stored in keystore-backed encrypted preferences and is never
included in the context sent to Claude.

**3. Ask for something.** The app sends your prompt plus:

- version name/code, channel, and commit sha of the running build
- device model, Android version, locale
- current map centre and zoom
- the stack trace from the last crash, if there was one
- a short breadcrumb log from the session

Tap **Show context** to see exactly what goes out before you send it. The
snapshot deliberately contains no tokens or account identifiers — assume it ends
up readable in the Actions log.

## Server

Esper is online-only. A Ktor server (`:server`, which runs the pure-JVM
`:engine`) holds accounts, passkeys, characters and the latest player position.
Players sign in with a passkey (Android 14+ and a passkey provider); the
relying-party ID is `leviathan256.github.io`. The contract is
`docs/SERVER.md`; what location data is sent and kept is in
`docs/GAME_DESIGN.md` ("Why the app asks for location").

Everything is free: an Oracle Cloud Always Free ARM VM, Postgres and Caddy on it
(Let's Encrypt TLS on a DuckDNS or sslip.io name), and GitHub Actions. `deploy/`
holds the systemd unit, Caddyfile, env example and `setup.sh`; `pages/` holds the
`assetlinks.json` template and `fingerprint.sh`. `deploy-server.yml` tests,
builds and rsyncs the server on pushes to `main`, and only builds and tests until
the deploy secrets exist. It never touches the `nightly` release.

The APK reads its default server address from the `ESPER_SERVER_URL` repository
variable at build time (blank is fine; it can be set in Settings). One-time
account steps are in `docs/BETA_SETUP_LEVI.md`.

## Versioning

`scripts/ci/version.sh` derives `versionCode` from the commit timestamp
(minutes since 2020) for **every** channel, so it always increases and Android
will accept an install over whatever is on the device — including switching
between nightly and stable. `versionName` carries the meaning: `1.2.3` for tags,
`nightly-abc1234` otherwise.

## Design doc

`docs/GAME_DESIGN.md` is what keeps player-driven changes coherent. Every
dispatched run is instructed to read it first and to respect it over the
request. It is currently a skeleton — filling it in directly improves what comes
back.

## Build locally

There is no committed Gradle wrapper jar, so use a `gradle` binary, not
`./gradlew`.

```bash
# Game logic lives in the pure-JVM :engine module and is testable with no Android SDK:
gradle :engine:test --configure-on-demand

# The server's API tests need Postgres; without ESPER_TEST_DB_URL they skip:
gradle :server:test

# The Android app needs the Android SDK, and CI builds it:
gradle :app:assembleRelease
```

`:engine` is where jobs, monsters, dice, the hex grid and combat live, which is
why almost everything can be checked without a device. `--configure-on-demand`
keeps Gradle from configuring `:app` — and therefore from needing the Android
SDK — when you only want engine tests. Plugin versions are declared in
`settings.gradle.kts`, not the root build file, so the root project resolves no
plugin artifacts at all.

## What's in the app

- **Map** (first screen on launch) — osmdroid + OpenStreetMap tiles
- **Encounters and combat** — a hex grid on the real map, ATB turn order, dice resolution
- **Character sheet** — job-derived stats, job points, bestiary
- **Ask Claude** — prompt box, app-state preview, recent runs, update banner
- **Settings** — repo target, release channel, GitHub token
- **Prompts** — copyable templates for driving an assistant outside the app
