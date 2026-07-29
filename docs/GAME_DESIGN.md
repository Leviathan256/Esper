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

- **GPS error moves the leash circle, never the avatar.** Consumer GPS is
  roughly 5 m at best and much worse under tree cover or between buildings —
  far coarser than a 1 m cell. That is only survivable because the avatar is
  not pinned to the GPS fix: see Movement below. Never introduce a mechanic
  that derives the avatar's cell directly from a location reading.
- **Physical movement cannot be the only way to move.** A player may be unable
  to walk, blocked by a fence or a road, indoors, or somewhere unsafe. Real-world
  movement can be *a* way to reposition, never the only one. Treat "the player
  did not move an inch" as a supported way to play, not a degraded one.
- **Never direct a player somewhere dangerous or private.** Do not seed or
  require movement onto roads, railways, water, or private property. If a
  target cell can't be reached safely, the game gives another way, or the
  encounter is winnable without it.

**Grid.** Hexagonal, **1 metre across**. Use H3 resolution 15 (0.92 m² average
area, 0.59 m edge, 1.03 m flat-to-flat) rather than rolling a bespoke grid:
hexagons do not tile a sphere, and H3 already solves that, is Apache-2.0 and
keyless, and gives globally stable cell IDs — which the territory system needs
anyway. Its hierarchy is the real win: a res-15 combat cell's parent at res 10
or 12 is a cheap lookup, so "is this cell inside guild territory?" is a parent
comparison rather than a polygon test. Cost to weigh before committing:
`h3-java` is JNI, so it adds a native library per ABI to the APK.

Territory claims use coarser resolutions from the same hierarchy:

| H3 res | avg area | flat-to-flat | use |
| --- | --- | --- | --- |
| 15 | 0.92 m² | 1.03 m | combat cell |
| 13 | 44.97 m² | 7.21 m | room / building footprint |
| 12 | 314.82 m² | 19.07 m | residence claim |
| 10 | 15,425.95 m² | 133.46 m | guild territory |

Note what a 1 m cell buys and costs. `Move 4` becomes literally four metres,
which reads correctly for a game played outdoors. But cell count scales with the
square of the radius: a 10–15 m radius gives a 300–900 cell board, the same
order as an FFT map, while a 100 m radius would be ~35,000 cells and no longer a
tactical board at all. **The radius, not the cell size, is what keeps combat
tactical.** Rendering needs zoom 20–21 (8.7 px and 17.5 px per cell at mid
latitudes), which is at or beyond the maximum the standard OSM raster layer
serves — confirm the exact cap against whichever tile source is chosen, and
expect an overzoomed, blurry base map at combat zoom either way.

**Tile source — must change before launch.** OSM *data* is fine to use: it is
ODbL, and the obligation is visible attribution (now rendered over the map).
Share-alike only applies if a derived *database* is distributed, which would
become relevant if building footprints or elevation are ever extracted into
game data.

The public tile servers are a different matter. The app currently points at
`tile.openstreetmap.org` via osmdroid's `MAPNIK` source, which is
donation-funded community infrastructure with a
[usage policy](https://operations.osmfoundation.org/policies/tiles/) that
blocks heavy users without notice. Esper is close to a worst case for it:

- a location-based game means constant panning by every player, which is heavy
  use by definition;
- 1 m cells want zoom 20–21, at or past what the standard layer serves;
- prefetching and "download this area for later" are explicitly prohibited —
  but a game played outdoors on patchy signal genuinely needs offline tiles.

Being blocked would take the map away from every player simultaneously, with no
warning. Move to a self-hosted source before real players arrive. Protomaps /
PMTiles is the recommended option: a single-file archive served from any static
host or CDN, no API key (so it satisfies the no-paid-services rule, unlike
MapTiler or Stadia free tiers), and it makes offline caching a design choice
rather than a policy breach.

Until then: keep the identifying User-Agent osmdroid is configured with, honour
cache headers, and **do not add prefetch, bulk download, or offline-area
features against the OSM servers.**

**Movement.** A radius of allowed territory travels with the player. The avatar
moves freely within it; the player's GPS position sets where the circle is
centred, *not* which cell the avatar occupies. This is what makes a 1 m cell
workable — GPS error moves the circle, and the avatar is placed deliberately by
touch, so jitter never teleports the avatar between cells.

Beyond the moving radius, the avatar may also travel to, from, and within:

- the player's own claimed **residence territory**,
- a **friend's** residence territory,
- shared **guild territory**.

**Travel.** The avatar occupies exactly **one allowed region at a time**. A
region is one of:

| Region | Anchored to |
| --- | --- |
| The moving radius | the player's live GPS position |
| Own residence territory | the claim |
| A friend's residence territory | the claim |
| Guild territory | the claim |
| An active battle the player is in | where that battle was seeded |

**Low-cost teleportation** moves the avatar between any of these. That includes
returning to your own radius, hopping to another player's radius, reaching a
claim, and rejoining a fight you are already part of.

The anchoring rule matters: while the avatar is in a territory or a battle,
**that region is the leash — not the player's GPS.** A player at home whose
avatar is in a friend's territory is playing normally, not cheating, and their
real movement does not drag the avatar. GPS only governs the radius region.

Teleporting into an active battle is also the reconnect story: a player whose
app died mid-fight teleports back in rather than forfeiting.

This does **not** conflict with pillar 1 — fights still resolve on real
geography, just not always geography the player is standing on. What it does
compete with is the walking loop, since cheap travel to every interesting place
removes the reason to go outside. Something must stay exclusive to physical
presence or the real map becomes decorative. The obvious candidates, none yet
chosen:

- encounters seed only near the player's real position;
- new claims can only be staked where the player physically is;
- some loot, discovery, or bestiary progress requires bodily presence.

Note also that a residence claim is somebody's actual home. Sharing it with
friends is the point, but it must not be visible to, or derivable by, anyone
else, and it should never be exposed as coordinates where a place name would do.

Two rules follow from GPS being noisy:

- **Recentre the radius with hysteresis.** Only move the circle when the player
  has genuinely moved further than the accuracy Android reports, so a stationary
  player's circle stays put.
- **Never yank the avatar.** If drift leaves the avatar outside the radius, it
  stays where it is and is walked back in, rather than being snapped.

**Elevation.** Use real terrain elevation where data is available, falling back
to flat when it isn't — elevation is a modifier on a working flat system, never
a prerequisite for one. Prefer a source that needs no API key and no payment
(AWS Terrain Tiles / terrarium-encoded PNGs are the obvious candidate, and are
tile-shaped so they cache like map tiles). Every elevation-aware rule needs a
defined behaviour for "no data here".

Be realistic about what this gives at a 1 m cell: free terrain data is roughly
30 m resolution, so a 30 m board samples it once or twice. That is a board
*tilt*, not FFT height tactics. Rules that need real height variation will have
to come from building data (OSM `building:levels` / `height`) or from authored
features, not from terrain.

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
| Self-hosted tiles | **required before launch** | OSM's public servers block heavy use; Protomaps/PMTiles recommended |
| Grid overlay on real map | not started | Pillar 1. H3 res 15 (~1 m hex) |
| Elevation | not started | Real terrain data, keyless source, graceful flat fallback. ~30 m data gives tilt, not height tactics |
| ATB turn order | not started | Speed stat charges the gauge |
| Movement leash | not started | Radius travels with the player; GPS centres the circle, not the avatar |
| Territories | not started | Residence + guild claims as coarse H3 cells; travel to/from/within |
| Teleportation | not started | Low cost, between allowed regions and into active battles; doubles as reconnect |
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
- Keep OpenStreetMap attribution visible wherever tiles are drawn.
- No prefetch, bulk download, or offline-area feature while tiles still come
  from the OSM Foundation's servers — their policy forbids it.
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

Settled: battlefield is the real map, 1 m hex cells on H3 res 15, a movement
radius that travels with the player plus residence and guild territories,
low-cost teleportation between allowed regions, party
of player + optional pet + optional solo-only NPC companion, elevation from real
terrain where available, ATB turn order, jobs as a data pipeline. Still open:

- **Does the leash follow the player mid-fight?** If someone walks away during
  combat, is the avatar dragged along, or is the radius anchored where the
  encounter started? Biggest remaining combat question.
- **How big is the radius?** It, not the cell size, decides whether a fight is a
  readable tactical board or an open field. 10–15 m gives an FFT-sized map.
- **What exactly is "low cost" teleportation?** Settled that it is cheap; not
  settled whether the cost is currency, a cooldown, a per-session budget, or
  some combination. This is the dial that decides whether walking still matters.
- **What stays exclusive to physical presence?** Cheap travel to every
  interesting place removes the reason to go outside. Pick at least one of:
  encounters seeding only near the real position, claims stakeable only where
  the player stands, or presence-gated loot and discovery.
- **Does teleporting to another player's radius need their consent?** Arriving
  uninvited at someone's live location is a social and a safety question, not
  just a mechanical one.
- **Territories make GPS spoofing profitable.** Claimable land plus travel to it
  is a standing incentive to fake a location. Cheap teleportation between known
  regions actually reduces the payoff — there is less to gain by faking a
  position you could have travelled to — but staking a *new* claim somewhere you
  are not is still worth spoofing for. Answer before open enrolment.
- **Is the world shared** (everyone sees the same encounter at the same place)
  or per-player? Difference between needing a backend and not.
- How much of a player's real location leaves the device, and where does it go?
- Does ATB charge continue while another unit is acting, and do heavier actions
  cost more gauge?
- **Does elevation come from terrain only, or do buildings count?** Terrain data
  won't know a player is on the fourth floor.
- Where does one speed stat end and several begin (move speed vs act speed)?
