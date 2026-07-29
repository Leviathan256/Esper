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

Add this repo in **Obtainium** using the **GitHub Releases** source:

- **Stable tags** — asset `Esper.apk`
- **Nightly** (rolling `nightly` prerelease) — asset `Esper-nightly.apk`; enable
  *Include prereleases*

For the shortest push-to-phone time, set Obtainium's background check interval
to 15 minutes and turn on background updates. The app's own update banner is
faster still — it checks when you open the Ask Claude screen and offers a
one-tap install.

## Signing key — set this up before anyone installs the app

Android only installs an update over an existing install when **both are signed
by the same key**. If the key ever changes, every player has to uninstall and
reinstall, losing local data. So the key must be durable.

Create one and store it as repository secrets:

```bash
PW='<choose-one-password>'      # store and key password must be the SAME, see below

keytool -genkeypair \
  -keystore esper-release.jks \
  -alias esper \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PW" -keypass "$PW" \
  -dname "CN=Esper, O=Esper, C=US"

base64 -w0 esper-release.jks   # macOS: base64 -i esper-release.jks
```

Use **one password for both** `-storepass` and `-keypass`. Since JDK 9 keytool
creates PKCS12 keystores, which cannot store a key password different from the
store password — it ignores `-keypass` with only a warning, and the mismatch
then surfaces much later as `Given final block not properly padded` in the
middle of an APK packaging task.

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

```bash
./gradlew :app:assembleDebug
```

## What's in the app

- **Map** (first screen on launch) — osmdroid + OpenStreetMap tiles
- **Ask Claude** — prompt box, app-state preview, recent runs, update banner
- **Settings** — repo target, release channel, GitHub token
- **Prompts** — copyable templates for driving an assistant outside the app
