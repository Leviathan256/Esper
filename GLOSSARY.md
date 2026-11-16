# Esper

A location-based RPG on the real map: players fight tactical encounters where they stand, claim territory, and co-operate through rituals.

## Language

### Combat

**Encounter**:
A fight on a hex board pinned to the real-world spot where it was seeded; it stays there for its whole life, wherever the players' bodies go.
_Avoid_: battle, fight, combat instance

**Flee**:
A character leaving an Encounter before it ends, at any moment and on any turn; it never fails. Short of the Encounter ending, it is the only way out: a character who does not Flee stays in the Encounter.
_Avoid_: run away, retreat, escape, leash

**Absent**:
A player whose character is in an Encounter while the player is not (app closed, no signal). The character stays in the Encounter; the other players vote on what it does.
_Avoid_: disconnected, AFK, offline

**AI Driver**:
The game playing an Absent player's character until the Encounter ends or the player returns.
_Avoid_: autopilot, auto-battle, bot

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
