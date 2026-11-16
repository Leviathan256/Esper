# The Rust server is the single home of the game rules; the Kotlin engine is frozen, then deleted

The server is rewritten in Rust (axum, sqlx, webauthn-rs), and the game rules move with it: the Kotlin `engine/` is frozen (no new rules) and is deleted once server-authoritative combat ships and the Android app stops running combat locally. We chose this because Esper is a shared, online-only world, so the server is the authority anyway; until then the two copies overlap only on character rules, the save codec and content loading, guarded by golden save fixtures that both test suites read.

## Considered Options

- **Rust server only, the app keeps the Kotlin engine**: rejected. The rules would exist twice for good and drift.
- **Port the engine to Rust and call it from the app through UniFFI**: rejected for now. One copy of the rules, but it adds the Android NDK to the app build and rewrites the app's engine integration, all to keep client-side rules the shared world does not need.

## Consequences

- New mechanics (Flee, Absent and the AI Driver vote, Visitors, Rituals, Co-presence) are built in Rust only, so none is playable until combat runs on the server. Server combat is therefore moved to straight after the port (`docs/SERVER.md` §14).
- The API stays identical, so the app does not change for the port.
