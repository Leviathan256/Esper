# Esper — game design

> **This file is load-bearing.** Every Claude run dispatched from inside the app
> is told to read it first and to keep changes consistent with it. It is the
> only thing standing between "players vibe-code the game" and "players
> vibe-code fifty mutually contradictory games".
>
> It is currently a skeleton. Fill in the sections below and the runs get
> sharper immediately.

## Premise

A location-based mobile RPG: Pokémon Go's real-world map crossed with Dungeons &
Dragons' characters, encounters, and dice. Players explore physically, meet
what the world has placed near them, and resolve it with RPG mechanics.

The game is **vibe-coded by its players**: the in-app "Ask Claude" screen sends
a request plus the live app state to a cloud Claude Code run, which opens a pull
request. Merged changes ship to everyone's phone through Obtainium.

## Pillars

<!-- 3–5 statements that settle arguments. Anything conflicting with these gets rejected. -->

1. _TODO — e.g. "The real map is the board; nothing important happens off it."_
2. _TODO_
3. _TODO_

## Core loop

<!-- What does a player do in a 5-minute session? -->

_TODO_

## Systems

| System | Status | Notes |
| --- | --- | --- |
| Map / world | implemented | osmdroid + OSM tiles, `ui/MapScreen.kt` |
| Character sheet | not started | |
| Encounters | not started | |
| Dice / resolution | not started | |
| Inventory | not started | |
| Progression | not started | |
| Multiplayer / party | not started | |

## Rules for changes

These apply to every Claude run, whoever dispatched it:

- The map stays the first screen on launch.
- No paid services, no API keys baked into the app.
- No new runtime permission without a stated in-game reason.
- Location handling: request only what a feature actually needs, and never
  transmit a player's position off-device without saying so here first.
- Existing player-facing features are not removed unless the request says to.
- New dependencies are pinned to an explicit version.

## Open questions

<!-- Park undecided things here so runs don't silently decide them for you. -->

- _TODO_
