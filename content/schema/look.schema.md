# Look schema

A unit's `look` is how it is drawn: optional, on a job
([`job.schema.md`](job.schema.md)) or a monster
([`monster.schema.md`](monster.schema.md)), inside that unit's own file. How a
look is drawn as a token or a plate, and how to draw a new one, is
[`docs/VISUALS.md`](../../docs/VISUALS.md) §4.5 and §8. This doc is the field
table, the path grammar and the resolving rules; `schemaVersion` stays 1.

## Example

```json
"look": {
  "glyph": "M12 6 C14.6 6 16.6 7.6 17.2 10 L23.5 5 L20 14.5 C19.2 18 15.6 21 12 21.5 C8.4 21 4.8 18 4 14.5 L0.5 5 L6.8 10 C7.4 7.6 9.4 6 12 6 Z M7 11.4 L10.8 13 L10.2 15.2 L7.6 14.4 Z M17 11.4 L16.4 14.4 L13.8 15.2 L13.2 13 Z",
  "hue": "moss",
  "details": [
    { "tone": "hue", "d": "M0 0 L24 0 L24 24 L0 24 Z" },
    { "tone": "shade", "d": "M0 0 L7 0 L7 14 L0 14 Z" }
  ]
}
```

## Fields

| Field | Type | Rule |
|---|---|---|
| `glyph` | path string | The stamp, drawn at every size. Even-odd. At most 40 segments and 6 subpaths. Every point outside the four cut corners (below). |
| `hue` | one of `moss`, `jade`, `slate`, `steel`, `violet`, `crimson`, `ember`, `bone` | The plate's colour. |
| `details` | list of `{ "tone", "d" }`, at most 6 | Plate only. `tone` is `hue`, `shade`, `paper`, `ink` or `accent`. `d` is a path of at most 16 segments and 6 subpaths, anywhere in the box, filled even-odd. |

All three are optional and `"look": {}` is valid. Unknown keys inside `look`
are an error in Rust and ignored by the app. The server
(`server/rules/src/content.rs`) enforces this table and the grammar and nothing
else. Every rule here is decided from the one file; the one check that needs a
second file, alike, runs on pull requests only
([`docs/VISUALS.md`](../../docs/VISUALS.md) §7).

## Path grammar

One grammar for `glyph` and `d`, small enough that the Rust and Kotlin parsers
cannot disagree:

```
path    = subpath { " " subpath }
subpath = "M" point { " " segment } " Z"
segment = "L" point | "C" point " " point " " point
point   = number " " number
number  = 1 or 2 digits, optionally "." and 1 or 2 digits, value 0 to 24
```

- Absolute `M`, `L`, `C` and `Z` only, the letter glued to its first number,
  single spaces, no commas, no signs, no exponents, no implicit repeats.
- The box is 24 x 24 units, x right, y down.
- A segment is one `L` or `C`. `M` and `Z` do not count.
- **Cut corners** (`glyph` only): for every number pair, control points
  included, `x + y >= 4`, `(24 - x) + y >= 4`, `x + (24 - y) >= 4` and
  `(24 - x) + (24 - y) >= 4`. A curve stays inside its control points, so this
  keeps the whole glyph inside an octagon that fits every frame with room for
  the outline.

## Resolving a look

A unit resolves from its own file alone. A look never refers to another unit's
file, and a job takes nothing from its prerequisite's look: on the board the
glyph is the only thing that says which unit it is, so a shared glyph would
make two units identical in a fight, and a reference would let an edit to one
file silently change another.

1. `glyph`: `look.glyph`. A unit with none draws its monogram.
2. `hue`: `look.hue`; else `HUES[fnv1a32(id) % 8]`, with `HUES` in the order of
   the Fields table above (`HUES` in `server/rules/src/content.rs`) and FNV-1a
   over the id's UTF-8 bytes (offset `0x811C9DC5`, prime `0x01000193`). The
   hash is an unsigned 32-bit number.
3. `details`: `look.details`. Tone `hue` is the unit's resolved hue.

## Shared fixtures

`content/fixtures/look/` holds the cases the Rust loader
(`server/rules/tests/looks.rs`) and the app must agree on. Each file is
tab-separated UTF-8 with one header row, and Rust and Kotlin read the same
bytes:

| File | Columns |
|---|---|
| `grammar.tsv` | `path`, `cutCorners` (`true` or `false`), `accept` (`true` or `false`), `segments`, `subpaths` (both empty on a rejected path) |
| `hue.tsv` | `id`, `hash` (the 32-bit FNV-1a value, decimal), `hue` |
| `mix.tsv` | `fn` (`tint` or `shade`), `hue`, `hex`: sixteen rows |

`hue.tsv` holds the hue an id hashes to, whatever look a unit of that id gives;
at least one hash is 2^31 or more, where a signed 32-bit `%` goes negative.
`mix.tsv` holds `tint` and `shade` of each hue
([`docs/VISUALS.md`](../../docs/VISUALS.md) §4.1). `grammar.tsv` holds accepted
paths with their counts, a point inside a cut corner both ways, and one
rejected row for each thing the grammar leaves out.