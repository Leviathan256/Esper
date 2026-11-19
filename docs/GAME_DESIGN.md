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
5. **Some things take a group, and some take a group in one place.** Rituals
   cannot be performed alone (see Rituals). Some Rituals and actions also
   require **Co-presence**: bodies at the same real spot. Teleported Visitors
   cannot meet that requirement, so it is what gives the real map a social role.

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
tactical.** Rendering is set in dp per metre, not zoom levels
(`docs/VISUALS.md` §4.3): a fight opens at 34 dp per metre, with the whole
board one tap away at about 16. On raster tiles scaled to the screen's density
that is zoom 20 to 22.5, up to three levels past the 19 the standard OSM layer
serves — confirm the cap against whichever tile source is chosen, and expect an
enlarged, coarse base map under a fight either way.

**MVP deviation: a local hex grid, not H3.** The recommendation above stands for
territory work, and the reasons for it — globally stable cell IDs and a cheap
parent lookup for "is this inside guild territory?" — are unaffected. But
`h3-java` is JNI, and this project's development environment has no Android SDK
and no emulator, so **there is no way to verify that a native library actually
loads on a device before shipping it.** The MVP therefore uses a pure-Kotlin
axial hex grid (`engine/geometry`), 1 m across flats, projected onto real
coordinates by a local east/north tangent plane anchored at the encounter. It is
fully unit-tested, adds no per-ABI native library to the APK, and matches H3 res
15's cell size closely enough that combat plays the same. **H3 is deferred to
territory work, where the hierarchy is the actual reason to adopt it** — and
adopting it there does not require changing combat, because the grid lives
behind `HexBoard`.

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
- 1 m cells want zoom 20–22.5, past what the standard layer serves;
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

**Encounter placement safety check.** Before seeding, the app asks the Esper
server for OpenStreetMap roads, railways, water, coastline, buildings and
`access=private` land within 40 m of the leash centre (one request per seeding,
never prefetched). The server answers from its own copy of OpenStreetMap for
one **coverage region**, refreshed daily. `GeodataSafetyChecker` rejects any
candidate inside or too close to a hazard: roads and railways by carriageway
half-width (or the `width` tag) plus 3 m, water by 2 m, buildings and private
land by 1 m; footpaths, tunnels and `access=no` + `foot=yes` paths are not
hazards. The tag policy lives in `OverpassHazards`. `EncounterSeeder` tries,
in order: random spots 6 to 10 m from the leash centre; then outdoor rings 2 m
wider at a time, out to the leash radius; and only then spots **just inside a
building** (issue #61), so a player deep inside a large building still gets an
Encounter. An indoor anchor sits at most 3 m inside the outline, clear of every
non-building hazard, and the point 0.5 m outside the nearest wall must be
outside every building and pass the road, railway, water and private-land
checks, so a wall facing a road is refused. Homes never qualify (`building=`
house, detached, semidetached_house, terrace, apartments, residential,
bungalow, cabin, dormitory, farm, houseboat, static_caravan or hut), nor any
building tagged `access=private` or `access=no`; a plain `building=yes` does.
Indoor spots are searched out to 24 m, past the leash, because the map card
enters from anywhere and the 40 m hazard query still covers what lies beyond
that wall. When nothing passes, the notice names what blocked the outdoor
spots, e.g. "No safe spot nearby: blocked by buildings and roads." ADR-0002
freezes the Kotlin engine, but this ships there as a bug fix to an existing
rule, since the server does not seed; it moves to Rust with server combat.
It **fails closed**: no
geodata (offline, server or map error, timeout) means no new encounter, never a
blind one. **Encounters only spawn inside the coverage region**, at least 100 m
in from its edge: anywhere else the game says "Encounters aren't available in
this area yet." and seeds nothing, because missing map data would look exactly
like safe ground. Known limits: OSM coverage is only as good as local mapping,
and the copy can be a day behind; only the encounter anchor is checked, not
every board cell (acceptable because **no cell ever has to be physically
walked to** — combat movement is entirely on-screen); and players outside the
coverage region get no encounters at all.

**Movement.** A radius of allowed territory travels with the player. The avatar
moves freely within it; the player's GPS position sets where the circle is
centred, *not* which cell the avatar occupies. This is what makes a 1 m cell
workable — GPS error moves the circle, and the avatar is placed deliberately by
touch, so jitter never teleports the avatar between cells.

**Why the app asks for location.** `ACCESS_FINE_LOCATION` (with
`ACCESS_COARSE_LOCATION` alongside, for Android 12+'s approximate-only grant) is
requested because two core-loop mechanics need it: **encounters seed near the
player's real position**, and **the movement radius the avatar acts inside is
centred on the GPS fix**. Esper is online-only. While the map is open, the app
sends your precise position (latitude, longitude, accuracy) to the Esper server
over an encrypted connection, at most every 30 seconds or whenever you have
moved 50 m. The server keeps only your latest position, in memory: it is
overwritten by the next one, dropped 10 minutes after your last update, on
sign-out, and whenever the server restarts. It is never written to the database
or to logs, and never shared outside the game. Today it exists so the server can
place you in the shared world; later steps use it for showing you to players
within about 300 m, movement checks and territory. Before an encounter is
seeded, the app also sends the centre of your movement radius to the Esper
server once, so it can check the ground there is safe; the server looks it up
in its own copy of the map and keeps nothing. If the player declines, the
game stays playable — the radius anchors to the map view instead, and the server
receives no position at all, only the map's centre for the safety check — because no player may be required to grant a permission, or take a
walk, in order to keep playing.

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

**Low-cost teleportation** moves the avatar between any of these, solo. That
includes returning to your own radius, reaching a claim, and rejoining a fight
you are already part of. **Reaching another player is different**: it is the
Teleportation Ritual below, and it needs that player to take part.

The anchoring rule matters: while the avatar is in a territory or a battle,
**that region is the leash — not the player's GPS.** A player at home whose
avatar is in a friend's territory is playing normally, not cheating, and their
real movement does not drag the avatar. GPS only governs the radius region.

Teleporting into an active battle is also the reconnect story: a player whose
app died mid-fight is **Absent**, their character stays in the fight (see
Leaving an encounter), and they teleport back in to take it over again.

**Teleportation Ritual (travel to a player).** Arriving at someone's live
location is a safety question, so it is never one-sided:

- Only a **friend, guildmate, or party member** can ask. Strangers cannot even
  send the request, because an unanswered request still says "this person is
  online".
- The receiving player, the **Host**, must accept each request live. A request
  expires after **5 minutes**. There is no standing permission, not even for
  friends.
- The arriving player is a **Visitor**. A Visitor's avatar is anchored to the
  Host, not to the Visitor's GPS: the Visitor walking does not move it.
- The **Host or the Visitor** can end the visit. The Visitor's avatar then
  returns at once to the Visitor's real location. A visit ended during an
  Encounter ends immediately and the Visitor Flees.
- If the Visitor's body comes into Co-presence with the Host, the visit ends
  and they are simply co-present. A Visitor in an Encounter stays in it.
- If the Host goes Absent, the Visitor stays at the Host's last known position.
  The visit ends after the Host has been Absent for 5 minutes.
- A Visitor takes part in any Ritual that does not require Co-presence.

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

## Claiming territory

Settled rules:

1. An area can only be claimed after the player has been **active in it for at
   least a week**. **Active means fighting an encounter there — not merely being
   present.** A phone left on a windowsill accrues nothing.
2. The player must be **physically present** to claim it. Teleporting there is
   not enough.
3. A player holds **exactly one claim at a time**.
4. Claiming while already holding one offers three choices: **move** the
   existing claim, **remove** it and start a new one, or **cancel**.
5. Attunement does not survive leaving. Returning to a previously held area
   means **another full week** of activity there first.

### What a claim holds

A claim is not just a flag on the map. It accumulates:

- **Structures** granting benefits — faster recovery, storage, and similar;
- **Upgrades** to those structures;
- **History** of what happened there.

This is what separates the three options in rule 4. **Moving** a claim carries
the structures, upgrades, and history to the new location. **Removing** it and
starting another does not — you begin with bare ground. That is the whole cost
of the choice, and it is why the option exists.

### Decay and purgatory

**90 days of inactivity sends a claim into purgatory.** The land becomes
available to other players again.

Purgatory is a holding state, **not deletion**. Structures, upgrades, history
and stored contents are preserved and recoverable — a returning player gets
their things back, even if someone else now holds the ground. Nothing a player
built is ever destroyed by absence alone. Any implementation that deletes on
expiry is wrong.

**Titles are attuned too.** Guild leadership and similar roles accrue and fade
on the same principle: a title left inactive fades and becomes claimable by
another player. This is deliberate — it stops a guild being locked forever by
an absent leader. An *active* leader is never at risk, since only inactivity
causes fade.

### Guilds

- **Every member contributes attunement** to an area. A guild naturally becomes
  able to claim the places its members actually play.
- **The guild leader, or a designee, stakes the claim** on the guild's behalf.

## Rituals

**Settled: rituals exist, and they are performed by groups of players.** A
ritual is something no solo player can do.

### Co-presence

**Settled: some Rituals and actions require Co-presence; the rest also accept
Visitors.** Co-presence means bodies at the same real spot: every participant
within 12 m (one board radius) of the Ritual's starting point. Teleportation
already lets players reach each other, so remote co-operation is solved;
Co-presence is the reason to stand in the same street.

- Co-presence **begins at once** when a player comes within range.
- It **ends only after a grace period**, because GPS drifts. The grace is
  `31 − m` seconds, where `m` is how many metres out of range the player is:
  30 s at 1 m out, 16 s at 15 m out, 1 s at 30 m out. It is recomputed from the
  current distance on every fix, and coming back into range resets it.
- Arriving by teleport **never** counts. A Visitor is somewhere in the game,
  not in the street.

### Duration and drop-outs

Rituals take **real time**, about 1–5 minutes depending on the Ritual. If a
participant drops out partway, the Ritual still completes if enough remain;
otherwise it fails and its cost is refunded. For a Ritual that requires
Co-presence, leaving Co-presence (after the grace period) is dropping out.

### What rituals could be for

Named here because each one plugs a gap that already exists, not as a wishlist.
Each is marked with whether it requires Co-presence:

- _[Visitors allowed]_ **Summoning a large encounter** the party could not otherwise meet — the raid
  shape, and a natural fit for ATB combat with several real players.
- _[Co-presence]_ **Staking guild territory**, making a guild claim a ceremony rather than a
  button the leader presses alone.
- _[Co-presence]_ **Investing a title** that has faded, so succession is something a guild
  performs together rather than a race to tap first.
- _[Visitors allowed]_ **Recovering a claim from purgatory**, giving the returning-player flow a
  shape and a reason to involve others.
- _[Co-presence]_ **Unlocking advanced jobs**, gating the deep end of progression behind
  co-operation.

### Constraints any ritual design inherits

- Rituals happen **on the real map** like everything else (pillar 1). They are
  not a menu screen.
- The **safety rules still apply**: a ritual must never require standing
  somewhere dangerous or private, and never route players onto roads, railways,
  water, or private property.
- Rituals are **content, so they are data** — defined by a schema and validated
  in CI, exactly like jobs and monsters. A ritual should be addable without
  touching engine code.
- If a ritual can be performed at a claim, **access follows the claim's
  permissions** — a friend's residence admits friends, guild territory admits
  the guild.
- Anything that grants attunement, land, or a title through a ritual must
  respect the anti-spoofing rules above; a ritual must not become the cheap path
  around a week of encounters.

Why these rules are load-bearing, so no run "streamlines" them away:

- The week of activity plus physical presence is the **anti-spoofing design**,
  and tying activity to *encounters* rather than presence is what gives it
  teeth. Faking a position is trivial and leaving a device somewhere is free;
  fighting a week of encounters is neither. Do not weaken this to "time spent"
  for convenience.
- **Attunement must accrue where the player physically is, not where the avatar
  is.** Otherwise teleporting to a friend's territory and fighting there would
  farm attunement for ground the player has never visited, which defeats every
  rule above. This follows from the intent rather than having been stated
  outright — worth confirming, but no run should assume the opposite.
- Rule 2 is also the answer to what stays exclusive to being physically
  somewhere. Teleportation reaches claims, but only real presence creates them.
- Rule 5 stops claim-hopping: no cycling between two spots to hold the best of
  both.

Implementation constraints that follow:

- **Time must come from a trusted source, not the device clock.** A week gate
  enforced against a clock the player controls is not a gate at all.
- **Activity is stored on-device as aggregated H3 cell counters, never as a raw
  location trace.** Tracking a week of presence means holding a week of
  someone's real movements; keep the minimum that answers "has this cell met the
  threshold", and nothing that reconstructs a path. The server's anti-cheat uses
  only the previous position, per "Why the app asks for location".
- Activity should accumulate at a **coarser resolution than the claim itself**
  (a res-12 claim is only ~19 m across, smaller than the area someone
  meaningfully "spends a week in"), then the claim is staked within it.

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

**Leaving an encounter.** An Encounter stays where it was seeded for its whole
life. A character leaves it only by **Flee** or by the Encounter ending.
Walking away in real life does not take a character out of a fight.

- **Flee** is an action the player chooses. It can be used **at any moment, on
  any turn**, not only on the character's own turn, and it never fails: a
  failed Flee in a real-world game would trap someone who needs to leave. It
  takes effect when the action resolving now finishes (gauges are paused then,
  so turn order stays deterministic). The character forfeits that Encounter's
  XP and loot and cannot rejoin it.
- A player is **Absent** after 30 s with no heartbeat (app closed, no signal).
  Their character stays in the Encounter. The other players have 30 s to vote
  on what it does: **AI Driver** (the game plays it until the Encounter ends or
  the player returns), **Flee**, or **Defend and wait**.
- No votes: the AI Driver takes over. A tie goes to the safer option, in the
  order Flee, then Defend and wait, then AI Driver.
- With nobody to vote (a solo Encounter, or every player Absent), the character
  takes Defend and wait. An unwatched AI must not lose a solo fight for an
  absent player.
- A returning player takes control back.

## Jobs: a data pipeline, not a pile of classes

Jobs get a **full pipeline framework**, not hand-written code per job. This is
the single highest-leverage decision for a game vibe-coded by its players: if
adding a job means editing engine code, every new job is a merge conflict and a
regression risk. If adding a job means adding a data file, players can add jobs
safely and in parallel.

The framework needs, in this order:

1. **A schema.** A versioned definition of what a job is — stat growth, abilities
   granted, prerequisites, what unlocks it, what it unlocks. Committed as a
   schema file, not just implied by the loader.
2. **Content as data.** One file per job under a content directory, validated
   against the schema. Never code.
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

## Visuals

How the game looks is designed in [`docs/VISUALS.md`](VISUALS.md), with every
colour, size and path a run needs and the way to render and check its work. A
run reads the part it needs, by section title: "Adding a look" and "Tier 1"
for a new or changed unit; for the theme (a colour, a size) or a screen, that
part of "The look", then "Fixtures" and "Tier 2"; for a rollout step, its row
in "Rollout". It is built in the order of that section, and the Systems table
below says how far. These are its hard rules, here because every run reads
this file. A rule binds from the rollout step that builds what it is checked
with: looks from step 1 (until that is on `main`, no content file carries
`look`), the camera from step 2, render and look from steps 0 and 3, colours
from step 4. Before that, a run follows the rule where it can and says in the
pull request what it could not do.

1. **On the board, colour says whose side a unit is on, never which monster it
   is.** The frame says it too: a circle for your party, a diamond for
   hostiles, a rounded square for neutrals.
2. **Gold means "acting now" and the one primary action of a screen.** Nothing
   else is gold.
3. **A unit's look is data**: the `look` object in its own content file, drawn
   from path text. A unit with no `look` still draws and plays. A look never
   refers to another file.
4. No raster image under `app/src/main/res`, no bundled font, and no colour
   written outside `ui/theme/EsperColors.kt` (the launcher icon's colours in
   `res/values/colors.xml` excepted).
5. **No picture a test compares shows shipped content.** Reference pictures
   are drawn from fixtures only, and a unit's own pictures are CI artifacts,
   written and never compared, so adding a monster never changes a file
   another pull request touches. The mockups under `docs/visuals/` show the
   six starting units and are frozen: no content pull request edits them.
6. Every map in a test, an artifact or a committed picture is Fixture Town, an
   invented place. Never a fetched tile, a real coordinate or a real place
   name.
7. Sizes on the map are dp per metre, not zoom levels. An Encounter opens in
   the fight view with the whole board one tap away, and a token is never
   smaller than 16 dp.
8. **An Encounter never waits on a tile.** The paper ground, the board and
   the HUD draw with tiles slow, missing or blank.
9. Nothing is said by colour alone. Text in an Encounter is 14 sp or more
   (the map attribution is 12 sp, a die's number is drawn at 14 dp or more,
   and a monogram is sized with its token), every button and control is 48 dp
   or more (a cell is smaller, which is why a map tap only selects), and the
   contrast floors are a test.
10. Motion never blocks input and never loops (the loading spinner excepted):
    one flash per event, at most three a second, and with animations off every
    event is a still.
11. A change to anything on screen, or to a `look`, is rendered and looked at
    before the pull request opens, and the pull request says which pictures
    were looked at.

## Systems

| System | Status | Notes |
| --- | --- | --- |
| Map / world | implemented | osmdroid + OSM tiles, `ui/MapScreen.kt` |
| Self-hosted tiles | **required before launch** | OSM's public servers block heavy use; Protomaps/PMTiles recommended |
| Visual identity | in progress | Field Mat: `docs/VISUALS.md`, rollout in its §9. Built: the camera (step 2); each unit's `look` in its content file, checked by the server (step 1a, `content/schema/look.schema.md`), not yet drawn by the app; the looks sheet and focus render in `cargo` (step 1b); the theme, the launcher icon and edge to edge, on sign-in and settings (step 4, `ui/theme/`) |
| Grid overlay on real map | MVP | Pure-Kotlin axial hex, 1 m across flats, projected onto lat/lon by a local tangent plane. **Deviation from H3 — see Combat > Grid.** `engine/geometry`. The camera frames it in dp per metre (`ui/game/CameraMath.kt`): an Encounter opens in the fight view, the whole board one tap away |
| Elevation | not started | Real terrain data, keyless source, graceful flat fallback. ~30 m data gives tilt, not height tactics |
| ATB turn order | MVP | Discrete gauge ticks at `speed`; flat action cost. `engine/combat/CombatEngine.kt` |
| Movement leash | MVP | 12 m radius; GPS centres the circle with hysteresis, never the avatar |
| Territories | not started | Residence + guild claims as coarse H3 cells; travel to/from/within |
| Claiming | not started | Week of encounters + physical presence; one claim; re-attune to return |
| Structures | not started | Recovery, storage, upgrades, history; carried by a move, lost by a remove |
| Purgatory | not started | 90-day decay; holding state, never deletion |
| Titles | not started | Attuned like land; fade on inactivity, claimable by another |
| Activity tracking | not started | Encounter counters per H3 cell at the player's real position, trusted clock, no raw traces |
| Teleportation | not started | Low cost, between allowed regions and into active battles; doubles as reconnect |
| Encounter seeding | MVP | Per-player, on-device, 6–10 m from the radius centre, checked against OSM roads/rail/water/private land from the server's own map copy; coverage region only (fails closed). Shared-vs-per-player placement still open |
| Job pipeline | MVP | Schema doc → `/content` data → CI validation with cycle detection → loader ignoring unknown fields → migration hook |
| Character sheet / stats | MVP | D&D ability scores + job-derived HP/AC/attack/damage/move |
| Save / persistence | MVP | Character saved on the Esper server (Postgres), tied to your passkey account; `schemaVersion` + migration chain from day one; an on-device save from before the server existed is uploaded once on first sign-in and then deleted from the phone |
| Pet | not started | Persists in co-op |
| NPC companion | not started | Solo only; removed when partied with real players |
| Dice / resolution | MVP | d20 + bonus vs AC; nat 20 crit doubles damage dice, nat 1 fumbles |
| Bestiary | MVP | Entry with defeat count recorded on victory; monsters are data |
| Inventory / loot | not started | MVP awards job points, XP and a bestiary entry only — no items yet |
| Rituals | not started | Group-only. Co-presence requirement undecided; see Rituals |
| Social / co-op | not started | Pokémon Go's domain: friends, trading, shared encounters |

## Rules for changes

These apply to every Claude run, whoever dispatched it:

- The map stays the first screen on launch.
- No paid services, no API keys baked into the app.
- No new runtime permission without a stated in-game reason.
- Location handling: request only what a feature actually needs, and never
  transmit, store or retain a player's position beyond what "Why the app asks
  for location" states; any change to what is sent, why, or how long it is kept
  is written there first.
- Never require a player to walk somewhere to keep playing, and never route
  them onto roads, railways, water, or private property.
- Content (jobs, monsters, abilities) is data validated in CI, not code.
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

The same goes for what a dispatched run may execute. Once the dispatch
workflow sets up Gradle and Cargo so a run can render and look at its work
(`docs/VISUALS.md` §7, rollout step 0), the run executes code it wrote, in a
job that holds a repository write token and the Anthropic credential, while
acting on request text it cannot trust. Its turn limit is also the spend
limit. That is acceptable while every dispatcher is someone you gave repository
access to, and must be settled before open enrolment.

## Open questions

<!-- Park undecided things here so runs don't silently decide them for you. -->

Settled: battlefield is the real map, 1 m hex cells on H3 res 15, a movement
radius that travels with the player plus residence and guild territories,
low-cost teleportation between allowed regions, party
of player + optional pet + optional solo-only NPC companion, elevation from real
terrain where available, ATB turn order, jobs as a data pipeline. Still open:

- **Does the leash follow the player mid-fight?** *Settled: no. An Encounter
  stays where it was seeded, and a character leaves only by Flee or by the
  Encounter ending (see Leaving an encounter).*
- **How big is the radius?** It, not the cell size, decides whether a fight is a
  readable tactical board or an open field. 10–15 m gives an FFT-sized map.
  *MVP default, revisit: 12 m, giving a 469-cell board (`3n(n+1)+1` at n = 12).
  Chosen at the low end of the 10–15 m range so the board reads on a phone
  screen. Nothing in the engine hard-codes it beyond
  `Encounter.DEFAULT_BOARD_RADIUS_CELLS`.*
- **What exactly is "low cost" teleportation?** Settled that it is cheap; not
  settled whether the cost is currency, a cooldown, a per-session budget, or
  some combination. This is the dial that decides whether walking still matters.
- **What else stays exclusive to physical presence?** Claim-staking now is
  (see Claiming territory). Whether encounters also seed only near the real
  position, and whether any loot or discovery is presence-gated, is still open —
  and claiming alone is a weekly act, so it may not carry the walking loop.
- **How many encounters make a week of attunement?** Settled that activity means
  encounters; not settled whether the bar is a total count, encounters on N
  distinct days, or a rate. Must the week be contiguous, and does a missed day
  reset it? This decides how claiming actually feels to earn.
- **Does a guild member's encounter count toward both** their personal
  attunement and the guild's, or must they choose? Doubling everything by
  default may be right, but it should be a decision.
- **How is a faded title claimed, and by whom?** Any member, the one with most
  attunement, an election? An absent leader losing the title is intended; the
  succession rule is undefined.
- **Does a guild claim decay on the guild's inactivity or the leader's?** A
  guild of active members under an absent leader should probably keep its land
  and lose only the title, but that is not stated.
- **How does a player recover things from purgatory** when another player now
  holds the ground? The contents are preserved by rule; the retrieval flow is
  not designed.
- **Does one claim at a time count guild claims?** Whether holding a personal
  residence prevents staking for a guild, or the two are independent, is
  undefined.
- **Do rituals require physical co-presence?** *Settled: some do (see
  Rituals: Co-presence), and so do participant drop-outs and duration. Still
  open: how many participants each Ritual needs.*
- **Does teleporting to another player's radius need their consent?**
  *Settled: yes. It is the Teleportation Ritual, and the Host accepts each one
  live (see Travel).*
- **Territories make GPS spoofing profitable.** Claimable land plus travel to it
  is a standing incentive to fake a location. Cheap teleportation between known
  regions actually reduces the payoff — there is less to gain by faking a
  position you could have travelled to — but staking a *new* claim somewhere you
  are not is still worth spoofing for. Answer before open enrolment.
- **Is the world shared** (everyone sees the same encounter at the same place)
  or per-player? *Settled: shared world on a single Esper server; online is
  required, there is no offline play mode.*
- How much of a player's real location leaves the device, and where does it go?
  *Resolved: see "Why the app asks for location".*
- **Does ATB charge continue while another unit is acting, and do heavier actions
  cost more gauge?** *MVP default, revisit: no to both. Gauges pause while an
  action resolves, and every action costs a flat `GAUGE_MAX`. This is FFT's model
  and it makes the whole turn order deterministic and testable. Variable costs
  are the obvious first extension.*
- **Does elevation come from terrain only, or do buildings count?** Terrain data
  won't know a player is on the fourth floor.
- Where does one speed stat end and several begin (move speed vs act speed)?
