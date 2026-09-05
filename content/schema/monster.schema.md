# Monster schema (`schemaVersion` 1)

One monster is one file: `content/monsters/<id>.json`. Adding a monster means
adding one file and nothing else — no index to edit, no merge conflicts between
authors adding different monsters.

Parsed by `com.esper.engine.content.ContentLoader.parseMonster` into a
`MonsterDefinition`, validated by `com.esper.engine.content.ContentValidator`.
The loader ignores unknown JSON keys and every optional field has a schema
default, so an older APK never crashes when it is shipped a newer monster file.

## Example

```json
{
  "schemaVersion": 1,
  "id": "goblin",
  "displayName": "Goblin",
  "maxHp": 7,
  "armorClass": 13,
  "attackBonus": 4,
  "damage": "1d6+2",
  "speed": 7,
  "moveRangeCells": 3,
  "attackRangeCells": 1,
  "xpReward": 50,
  "jobPointsReward": 2,
  "bestiaryText": "A vicious but cowardly raider. Fights in packs, flees alone."
}
```

## Fields

| field | type | default | validator rule |
| --- | --- | --- | --- |
| `schemaVersion` | int | — (required) | must equal `1` |
| `id` | string | — (required) | non-blank, lowercase, matches `[a-z0-9_]+`, unique among all monsters |
| `displayName` | string | — (required) | non-blank |
| `maxHp` | int | — (required) | `>= 1` |
| `armorClass` | int | — (required) | `1..30` |
| `attackBonus` | int | — (required) | added directly to a `d20` roll; no range check |
| `damage` | string | — (required) | dice notation (see below) |
| `speed` | int | — (required) | `1..50`; ATB gauge gain per tick — a wolf with a high `speed` acts far more often than a slime |
| `moveRangeCells` | int | — (required) | `0..24` |
| `attackRangeCells` | int | `1` | `1..24` |
| `xpReward` | int | — (required) | `>= 0`; added to the character's `xp` on victory |
| `jobPointsReward` | int | — (required) | `>= 0`; added to the character's *current* job on victory |
| `bestiaryText` | string | `""` | none; shown on the character sheet's bestiary entry |

## Flat stat block, not ability scores

Monsters carry a flat stat block — `armorClass` and `attackBonus` directly,
not six D&D ability scores an engine would have to derive them from. Nothing
in the MVP combat rules reads a monster's ability scores, so carrying them
would be dead data the content author has to fill in for no effect.

One attack per monster is all the MVP schema models. The natural extension is
a `List<AttackDefinition>` (each with its own `damage`, `attackBonus`,
`attackRangeCells`) once a monster needs more than one move; that is
out of scope for this schema version.

## Dice notation

`damage` accepts the pattern `^\s*(\d+)d(\d+)\s*([+-]\s*\d+)?\s*$` (e.g.
`"1d6+2"`), or a bare integer for a fixed, non-random value. It is one string,
not a split `damageDice`/`damageModifier` pair, for the same reason as the job
schema's `damage` field: `DiceExpr` keeps count and modifier apart internally,
so a critical hit can double the dice without doubling the modifier.

## Forward compatibility

`ContentLoader` parses with `Json { ignoreUnknownKeys = true; encodeDefaults =
true }`. A field this doc does not yet document, added by a future
`schemaVersion` bump, is silently ignored by an older build. Monster files are
read fresh from the APK every launch — there is no save-style migration chain
for them (that exists only for `CharacterState`).

## Shipped monsters

| id | notes |
| --- | --- |
| `slime` | slow, low AC — the winnable first fight |
| `goblin` | mid-tier raider |
| `wolf` | fast (high `speed`) — proves ATB ordering matters, since it acts far more often than a slow party member |
