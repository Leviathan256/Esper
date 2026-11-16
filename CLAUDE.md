# CLAUDE.md

Guidance for AI coding agents in this repo. See `README.md` for the build and release loop, and `docs/GAME_DESIGN.md` for the game.

## Agent skills

### Issue tracker

GitHub Issues on `Leviathan256/esper` via `gh` or the GitHub MCP tools. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context (root `GLOSSARY.md` + `docs/adr/`, created lazily). See `docs/agents/domain.md`.

### Feature workflow

Grill → spec → tickets → implement → review, in that order, for any change bigger than a one-file fix. See `docs/agents/workflow.md`.
