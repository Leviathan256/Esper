# Job schema (`schemaVersion` 1)

One job is one file: `content/jobs/<id>.json`. Adding a job means adding one file
and nothing else — there is no index to edit, so two authors adding two jobs at
once never conflict.

Parsed by `com.esper.engine.content.ContentLoader.parseJob` into a
`JobDefinition`, validated by `com.esper.engine.content.ContentValidator`. The
loader ignores unknown JSON keys and every optional field has a schema default,
so an older APK never crashes when it is shipped a newer job file.

## Example

```json
{
  "schemaVersion": 1,
  "id": "squire",
  "displayName": "Squire",
  "description": "A front-line trainee. Where every knight begins.",
  "hitDie": "1d10",
  "baseArmorClass": 12,
  "attackAbility": "str",
  "damage": "1d8",
  "speed": 8,
  "moveRangeCells": 4,
  "attackRangeCells": 1,
  "statGrowth": { "str": 1, "con": 1 },
  "abilitiesGranted": ["shield_bash"],
  "prerequisites": [],
  "unlocks": ["knight"],
  "jobPointsToMaster": 100
}
```

## Fields

| field | type | default | validator rule |
| --- | --- | --- | --- |
| `schemaVersion` | int | — (required) | must equal `1` |
| `id` | string | — (required) | non-blank, lowercase, matches `[a-z0-9_]+`, unique among all jobs |
| `displayName` | string | — (required) | non-blank |
| `description` | string | `""` | none |
| `hitDie` | string | — (required) | dice notation `NdS` (see below); `N` must be exactly `1` |
| `baseArmorClass` | int | — (required) | `1..30` |
| `attackAbility` | string | — (required) | one of `str, dex, con, int, wis, cha` |
| `damage` | string | — (required) | dice notation (see below); count is unrestricted |
| `speed` | int | — (required) | `1..50`; ATB gauge gain per tick — higher acts more often |
| `moveRangeCells` | int | — (required) | `0..24`; hex cells reachable per move action |
| `attackRangeCells` | int | `1` | `1..24`; hex distance an attack can reach |
| `statGrowth` | map<string, int> | `{}` | every key one of `str, dex, con, int, wis, cha`; applied once per level above 1 |
| `abilitiesGranted` | list<string> | `[]` | free-form ability ids; not validated against a registry at MVP |
| `prerequisites` | list<string> | `[]` | every id must resolve to another job in the same load |
| `unlocks` | list<string> | `[]` | every id must resolve to another job in the same load |
| `jobPointsToMaster` | int | `100` | `>= 1`; the current job's job points must reach this before a job it unlocks becomes available |

## Dice notation

Both `hitDie` and `damage` accept the pattern
`^\s*(\d+)d(\d+)\s*([+-]\s*\d+)?\s*$` (e.g. `"1d8+2"`, `"2d6-1"`, `"1d10"`), or a
bare integer (e.g. `"0"`) for a fixed, non-random value. Damage is authored as
one string, not split dice/modifier fields, because `DiceExpr` keeps the dice
count and the modifier apart internally — a critical hit doubles the dice
without doubling the modifier — so the split would buy authors nothing.

## The job graph

`prerequisites` and `unlocks` together form a directed graph across all shipped
jobs (edges `prerequisite -> job` and `job -> unlock`). `ContentValidator`
rejects:

- any `prerequisites`/`unlocks` entry that does not resolve to a real job id in
  the same load, and
- any cycle in that graph, detected by depth-first search with a recursion
  stack. The rejection message names the full path, first id repeated last —
  e.g. `job unlock cycle: squire -> knight -> squire` — never a bare "cycle
  found".

A job unlocks for a character once every one of its `prerequisites` has
accumulated `jobPoints >= that prerequisite's jobPointsToMaster` (see
`Progression.recomputeUnlocks`).

## Forward compatibility

`ContentLoader` parses with `Json { ignoreUnknownKeys = true; encodeDefaults =
true }`. A field this doc does not yet document, added by a future
`schemaVersion` bump, is silently ignored by an older build rather than
crashing it. `schemaVersion` itself is the only field checked for content
migration — job and monster files are read fresh from the APK every launch and
carry no save-style migration chain (that exists only for `CharacterState`).

## Shipped jobs

| id | notes |
| --- | --- |
| `squire` | starting job; `unlocks: ["knight"]` |
| `knight` | `prerequisites: ["squire"]` |
| `apprentice` | independent second starter, `attackAbility: "int"` |
