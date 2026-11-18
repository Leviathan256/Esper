# A unit's look is path text in its own content file, and colour on the board says whose side

Esper has no artist and no image model: a look has to be something a Claude Code run can write as text and check by rendering it. So a unit's look is a `look` object inside its own job or monster file: a glyph in a small path grammar (absolute `M L Q C Z` in a 24-unit box, filled even-odd), a named hue, and optional detail paths. The app draws it as a token whose frame and colour say whose side the unit is on, and as a plate at bestiary size. A look never refers to another unit's file, except that a job takes what it does not give from its first prerequisite. The full design is `docs/VISUALS.md`.

## Considered Options

- **Sprite grids in the JSON** (the Final Fantasy Tactics look): rejected. 144 or more hand-placed cells per unit, the side carried only by a base colour, and mush at the 16 dp a token can shrink to.
- **Coats of arms composed from a few words over a shared library of charges**: rejected for units. Nothing is drawn per monster, but colour then says which monster instead of whose side, many monsters share one silhouette, and "no two coats alike" is a check across files that two green pull requests can break together. Kept in reserve for guilds and titles.
- **A drawing function or a drawable per creature in the app**: rejected. A new monster would be code, which the content rule forbids.
- **A shared glyph library that looks point into**: rejected. On the board the glyph is the only thing that says which monster a unit is, and an edit to one file would silently change another.
- **A third-party icon set**: rejected. Mixed authors, per-asset attribution, and detail that collapses at token size.

## Consequences

- The strict Rust loader must know `look` before any content file carries it. The server checks a look's structure and refuses to start on a malformed one; it does not judge a drawing.
- Every monster gets a drawn glyph, so player-added ones will be uneven. The baseline, an ink stamp on a tint, has to look intended with no detail at all, and pixel checks and a focus render are what a run uses in place of an eye.
- No committed picture may show shipped content: reference pictures are drawn from fixtures and a unit's own pictures are CI artifacts. Otherwise every new monster would rewrite a shared binary file.
- Likeness between two looks is checked only for the files a pull request changes, never as a test on `main`, so two pull requests that each pass cannot turn `main` red together.
- A unit with no look still draws, as a monogram with a hue from a hash of its id, so content never waits on art.
- Colour cannot tell monsters apart in a fight. That is the glyph's job on the board, and the plate's in the bestiary.
