# Esper

A location-based RPG on the real map: players fight tactical encounters where they stand, claim territory, and co-operate through rituals.

## Language

### Combat

**Encounter**:
A fight on a hex board pinned to the real-world spot where it was seeded; it stays there for its whole life, wherever the players' bodies go.
_Avoid_: battle, fight, combat instance

**Coverage region**:
The one area of the real map the server holds map data for, so the only place an Encounter can be seeded, and only at least 100 m inside its edge. Elsewhere no Encounter spawns.
_Avoid_: region alone (an allowed region is where the avatar may travel), service area, supported area

**Flee**:
A character leaving an Encounter before it ends, at any moment and on any turn; it never fails. Short of the Encounter ending, it is the only way out: a character who does not Flee stays in the Encounter.
_Avoid_: run away, retreat, escape, leash

**Absent**:
A player whose character is in an Encounter while the player is not (app closed, no signal). The character stays in the Encounter; the other players vote on what it does.
_Avoid_: disconnected, AFK, offline

**AI Driver**:
The game playing an Absent player's character until the Encounter ends or the player returns.
_Avoid_: autopilot, auto-battle, bot

### Visuals

**Look**:
A unit's appearance as data: the `look` object in its own job or monster file. A unit with no look still draws.
_Avoid_: sprite, skin, icon, art asset

**Glyph**:
The one shape in a look that says what a unit is. It is stamped in paper on a token and in ink on a plate.
_Avoid_: silhouette, icon, charge, emblem alone (an emblem is a job's glyph)

**Token**:
A unit as drawn on the board and in the HUD: a frame, a side colour and a glyph.
_Avoid_: sprite, marker, piece, avatar (the avatar is the player's character in the world)

**Frame**:
The outline of a token, which says whose side the unit is on: a circle for the player's party, a diamond for hostiles, a rounded square for neutrals.
_Avoid_: border, badge, ring (the gold ring marks the unit acting now)

**Plate**:
The large hexagonal picture of a unit in the bestiary, on the character sheet and in the character creator.
_Avoid_: portrait, card, avatar

**Mat**:
The paper wash and grid drawn over the real map inside an Encounter's leash ring: the board as the player sees it.
_Avoid_: arena, battle scene, battlefield screen

**Fight view**:
The camera an Encounter opens in: close enough to choose a cell, with part of the board off screen. It names a camera. In anything a player reads, what is on screen is still an Encounter, never "a fight".
_Avoid_: close-up, zoomed-in view, action view

**Whole board**:
The camera that fits an Encounter's whole leash ring on screen, one tap from the fight view.
_Avoid_: overview, zoomed-out view, map view

### Co-operation

**Ritual**:
An act performed by a group of players that no solo player can do.
_Avoid_: ceremony, group action

**Co-presence**:
Players being bodily at the same real-world location: within one board radius of each other (for a Ritual, of its starting point). It begins the moment a player is in range and ends only after a grace period that shrinks the farther out of range they are, so GPS drift does not break it. Arriving by teleport never counts. Some Rituals and actions require it; the rest accept participants who arrived by teleport.
_Avoid_: same place, together, nearby

**Teleportation Ritual**:
The Ritual that moves one player to another player's location. The receiving player must take part in it, so no one can arrive at another player uninvited.
_Avoid_: summon, invite, teleport request

**Host**:
The player a Teleportation Ritual brings a Visitor to, and who must accept it.
_Avoid_: receiver, target

**Visitor**:
A player whose character is anchored to a Host's character after a Teleportation Ritual while their body is elsewhere. Visitors take part in Rituals that do not require Co-presence. The visit ends when the Host or the Visitor ends it, or when the Visitor's body comes into Co-presence with the Host. If the Host or the Visitor ends it during an Encounter, it ends at once and the Visitor Flees.
_Avoid_: remote player, guest, teleporter
