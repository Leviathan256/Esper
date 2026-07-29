# Esper — game design

> **This file is load-bearing.** Every Claude run dispatched from inside the app
> is told to read it first and to keep changes consistent with it. It is the
> only thing standing between "players vibe-code the game" and "players
> vibe-code fifty mutually contradictory games".
>
> Pillars and the Combat section are **settled decisions**, not suggestions.
> Open questions are genuinely open — a run that needs one answered should say
> so rather than quietly picking.

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

1. **The real map IS the battlefield.** There is no separate battle scene. The
   tactical grid is projected onto real geography, at the place the player is
   actually standing. This is settled, and it is deliberately the hard road —
   occasional jank is an accepted cost, not a bug to be designed away by
   retreating to an abstract arena. Any proposal that resolves an encounter on
   a detached board is rejected on principle.
2. **Combat is positional and turn-based, not tap-to-attack.** If a proposed
   feature would work identically without the grid, it belongs somewhere else.
3. **Turn order is ATB.** A speed stat charges each unit's turn gauge; units act
   when their gauge fills. Not strict alternating turns.
4. **Progression is job-based, not level-only.** A character is defined by the
   jobs they've worked and the abilities carried between them.
5. _TODO — a social pillar. What do two players standing in the same place get
   that one player doesn't?_

## Core loop

<!-- What does a player do in a 5-minute session? -->

Rough shape, to be tightened:

1. Walk; the map seeds encounters near you.
2. Enter an encounter → the grid overlays the map where you're standing.
3. Fight it out: position, act, resolve with dice, ATB gauges filling.
4. Win → loot, job points, bestiary entry.
5. Between fights: manage your unit, change jobs, spend points.

_TODO: which of these is the hook? Which could be cut?_

## Combat

**Battlefield.** A grid overlaid on the real map (an osmdroid overlay), anchored
where the encounter was seeded. Consequences that follow from pillar 1 and must
be respected:

- **Cell size must exceed GPS error.** Consumer GPS is roughly 5m at best and
  much worse under tree cover or between buildings. A cell smaller than that
  makes position jitter between cells and the game unplayable. Pick the cell
  size from the accuracy figure Android reports, not from what looks tidy.
- **Physical movement cannot be the only way to move.** A player may be unable
  to walk, blocked by a fence or a road, indoors, or somewhere unsafe. Real-world
  movement can be *a* way to reposition, never the only one. Treat "the player
  did not move an inch" as a supported way to play, not a degraded one.
- **Never direct a player somewhere dangerous or private.** Do not seed or
  require movement onto roads, railways, water, or private property. If a
  target cell can't be reached safely, the game gives another way, or the
  encounter is winnable without it.

**Elevation.** Use real terrain elevation where data is available, falling back
to flat when it isn't — elevation is a modifier on a working flat system, never
a prerequisite for one. Prefer a source that needs no API key and no payment
(AWS Terrain Tiles / terrarium-encoded PNGs are the obvious candidate, and are
tile-shaped so they cache like map tiles). Every elevation-aware rule needs a
defined behaviour for "no data here".

**Turn order.** ATB: each unit has a turn gauge that charges at a rate set by a
speed stat. When it fills, the unit acts, and the gauge resets. Actions may cost
different amounts of gauge. Whether a slower action costs more, and whether
charge continues during another unit's action, are open.

**Party composition.** One player character, plus:

- optionally **one pet**, and/or
- optionally **one NPC companion**.

The NPC companion is a solo-play crutch: it is **removed while the player is
partied with other real players**. The pet is not. So the largest friendly force
in a solo fight is 3 units, and party size scales with real players plus their
pets. Combat maths should assume 1–3 friendly units solo, and be checked against
larger co-op groups before anything depends on a fixed count.

## Jobs: a data pipeline, not a pile of classes

Jobs get a **full pipeline framework**, not hand-written Kotlin per job. This is
the single highest-leverage decision for a game vibe-coded by its players: if
adding a job means editing engine code, every new job is a merge conflict and a
regression risk. If adding a job means adding a data file, players can add jobs
safely and in parallel.

The framework needs, in this order:

1. **A schema.** A versioned definition of what a job is — stat growth, abilities
   granted, prerequisites, what unlocks it, what it unlocks. Committed as a
   schema file, not just implied by the loader.
2. **Content as data.** One file per job under a content directory, validated
   against the schema. Never Kotlin.
3. **Validation in CI.** Malformed or contradictory job data fails the PR check
   rather than the player's phone. Include cycle detection on the unlock graph.
4. **A loader** that turns validated data into runtime objects, with defined
   behaviour for unknown fields (forward compatibility — an older APK must not
   crash on newer content).
5. **A migration story.** When the schema changes, existing characters carrying
   old job data must survive. Write the migration in the same PR as the schema
   change; a change that orphans saved characters is not done.

Abilities carry between jobs, FFT-style, so ability ownership lives with the
character and not with the job instance.

## Systems

| System | Status | Notes |
| --- | --- | --- |
| Map / world | implemented | osmdroid + OSM tiles, `ui/MapScreen.kt` |
| Grid overlay on real map | not started | Pillar 1. Cell size derived from reported GPS accuracy |
| Elevation | not started | Real terrain data, keyless source, graceful flat fallback |
| ATB turn order | not started | Speed stat charges the gauge |
| Encounter seeding | not started | Shared vs per-player placement is still open |
| Job pipeline | not started | Schema → data → CI validation → loader → migrations, per above |
| Character sheet / stats | not started | D&D-flavoured; jobs drive stat growth |
| Pet | not started | Persists in co-op |
| NPC companion | not started | Solo only; removed when partied with real players |
| Dice / resolution | not started | Define the core roll before anything depends on it |
| Bestiary | not started | D&D monsters as the content backbone. Data-driven, same argument as jobs |
| Inventory / loot | not started | |
| Social / co-op | not started | Pokémon Go's domain: friends, trading, shared encounters |

## Rules for changes

These apply to every Claude run, whoever dispatched it:

- The map stays the first screen on launch.
- No paid services, no API keys baked into the app.
- No new runtime permission without a stated in-game reason.
- Location handling: request only what a feature actually needs, and never
  transmit a player's position off-device without saying so here first.
- Never require a player to walk somewhere to keep playing, and never route
  them onto roads, railways, water, or private property.
- Content (jobs, monsters, abilities) is data validated in CI, not Kotlin.
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

Settled: battlefield is the real map, party is player + optional pet + optional
solo-only NPC companion, elevation from real terrain where available, ATB turn
order, jobs as a data pipeline. Still open:

- **How does a unit reposition during a fight?** Physical walking is one way but
  cannot be the only one (see Combat). What is the other way, and what stops it
  from making walking pointless? This is the biggest remaining combat question.
- **Grid shape and cell size.** Square or hex, and how many metres per cell.
  Constrained from below by GPS error.
- **Is the world shared** (everyone sees the same encounter at the same place)
  or per-player? Difference between needing a backend and not.
- How much of a player's real location leaves the device, and where does it go?
- Does ATB charge continue while another unit is acting, and do heavier actions
  cost more gauge?
- **Does elevation come from terrain only, or do buildings count?** Terrain data
  won't know a player is on the fourth floor.
- Where does one speed stat end and several begin (move speed vs act speed)?
