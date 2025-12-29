# Esper

Minimal Android app scaffold that launches directly into a map view (first screen).

## Build

```bash
# If the Gradle wrapper is unavailable in your checkout, use a local Gradle install.
gradle :app:assembleDebug
```

## Install via Obtainium

- Add this repo in **Obtainium** using the **GitHub Releases** source.
- Install from the latest release asset:
  - **Stable tags**: `Esper.apk`
  - **Nightly** (rolling `nightly` prerelease): `Esper-nightly.apk`

## What’s in the app

- **First view on launch**: Map screen (osmdroid + OpenStreetMap tiles).
- **In-app “Prompts”**: An Assistant-style screen containing copyable prompt templates for Codex/Copilot/ChatGPT to manage code changes, PR summaries, and merge/conflict flow.
