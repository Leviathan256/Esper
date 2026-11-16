# Feature workflow

The pipeline for any change bigger than a one-file fix. Stages marked _(user)_ are commands only the user can start: when a stage ends, name the next command and stop.

1. **`/grill-with-docs`** _(user)_: interview until the plan has no open questions, recording ADRs in `docs/adr/` and terms in `GLOSSARY.md` as they settle. Done when the user calls the plan settled.
2. **`/to-spec`** _(user)_: one spec issue labelled `ready-for-agent`, with test seams the user confirmed.
3. **`/to-tickets`** _(user)_: tracer-bullet tickets under the spec, each listing its `Blocked by` edges.
4. **`/implement-spec`** _(user)_: every ticket built on one integration branch. It runs `/tdd` inside each ticket and `/code-review` on the finished branch, so those two belong to this stage. Done when every ticket is resolved and the code-review fixes are committed.
5. **`/ponytail-review`** of the integration branch against `main`: safety, load, test gaps, lean. Fix every _Must fix_ and _Should fix_, then merge.

Single changes outside the pipeline: `/tdd` for a bug fix or small feature, `/code-review` for a branch built some other way.
