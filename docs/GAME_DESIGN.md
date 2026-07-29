# Esper — game design

> **This file is load-bearing.** Every Claude run dispatched from inside the app
> is told to read it first and to keep changes consistent with it. It is the
> only thing standing between "players vibe-code the game" and "players
> vibe-code fifty mutually contradictory games".
>
> It is currently a skeleton. Fill in the sections below and the runs get
> sharper immediately.

## Premise

A location-based tactical RPG. Three named influences, each owning a different
part of the game:

| From | We take |
| --- | --- |
| **Pokémon Go** | The real-world map, and social play: encounters seeded by physical location, meeting other players, co-op and trading |
| **D&D** | Monsters and the bestiary, dice-based resolution, character building — the fiction and the stat model |
| **Final Fantasy Tactics** | Grid-based tactical combat and the job system: a party of units, turn order, positioning, classes you change between and learn abilities across |

Read that table as a division of labour, not a shopping list. When a change
touches combat, FFT is the reference. When it touches the map or other players,
Pokémon Go is. When it touches what a creature *is* or how an outcome is
decided, D&D is.

The game is **vibe-coded by its players**: the in-app "Ask Claude" screen sends
a request plus the live app state to a cloud Claude Code run, which opens a pull
request. Merged changes ship to everyone's phone through Obtainium.

## Pillars

<!-- 3–5 statements that settle arguments. Anything conflicting with these gets rejected. -->

1. **The real map is the overworld; the tactical grid is the battlefield.**
   Movement in the world is physical. Movement in a fight is on a grid. Keep
   the two clearly separated — this is the single most load-bearing decision in
   the design.
2. **Combat is positional and turn-based, not tap-to-attack.** If a proposed
   feature would work identically without the grid, it belongs somewhere else.
3. **Progression is job-based, not level-only.** A character is defined by the
   jobs they've worked and the abilities carried between them.
4. _TODO — a social pillar. What do two players standing in the same place get
   that one player doesn't?_

## Core loop

<!-- What does a player do in a 5-minute session? -->

Rough shape, to be tightened:

1. Walk; the map seeds encounters near you.
2. Enter an encounter → the tactical grid opens.
3. Fight it out with your party: position, act, resolve with dice.
4. Win → loot, job points, bestiary entry.
5. Between fights: manage the party, change jobs, spend points.

_TODO: which of these is the hook? Which could be cut?_

## Systems

| System | Status | Notes |
| --- | --- | --- |
| Map / world | implemented | osmdroid + OSM tiles, `ui/MapScreen.kt` |
| Encounter seeding | not started | Where do monsters come from, and is placement shared between players? See open questions |
| Tactical grid combat | not started | FFT-style. Needs a decision on grid shape and whether height/elevation exists |
| Job system | not started | FFT-style: jobs unlock jobs, abilities carry across |
| Character sheet / stats | not started | D&D-flavoured; interacts with jobs for stat growth |
| Dice / resolution | not started | Define the core roll before anything depends on it |
| Bestiary | not started | D&D monsters as the content backbone |
| Party management | not started | Party size caps most combat design — decide early |
| Inventory / loot | not started | |
| Social / co-op | not started | Pokémon Go's domain: friends, trading, shared encounters |

## Rules for changes

These apply to every Claude run, whoever dispatched it:

- The map stays the first screen on launch.
- No paid services, no API keys baked into the app.
- No new runtime permission without a stated in-game reason.
- Location handling: request only what a feature actually needs, and never
  transmit a player's position off-device without saying so here first.
- Existing player-facing features are not removed unless the request says to.
- New dependencies are pinned to an explicit version.

## Who is allowed to vibe-code

Right now the answer is "whoever you give a token to". The app dispatches with
a GitHub PAT that the player enters themselves, so the ability to start a
Claude run is exactly the set of people you have granted repo access to. The
real safety gate is that Claude opens a **pull request** — a human still merges
before anything reaches a phone. Keep that gate.

This does not scale to open enrolment as-is. Handing PATs to arbitrary players
would let any of them drive code into an APK that everyone installs. Opening it
up means a mediating service that holds the only token, authenticates players,
rate-limits them, and dispatches on their behalf — plus a rule that
player-originated PRs never auto-merge. Treat that as a prerequisite for public
launch, not a later polish item.

## Open questions

<!-- Park undecided things here so runs don't silently decide them for you. -->

- How much of a player's real location leaves the device, and where does it go?
- Is the world shared (everyone sees the same encounter at the same place) or
  per-player? This decision blocks most of the systems table, and it is the
  difference between needing a backend and not.
- **Party size.** One unit or several? FFT assumes a squad; Pokémon Go assumes a
  lone player. This caps every combat decision downstream, so settle it first.
- **Does the grid have elevation?** FFT's height rules are a large chunk of what
  makes its combat feel like FFT, and also a large chunk of the work.
- Is combat real-time-adjacent (FFT's charge-time turn order) or strictly
  alternating turns?
- What happens to a player's character when a vibe-coded change alters the
  rules underneath it? Job data will need a migration story before the first
  release that has jobs.
