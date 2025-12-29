# Esper in-app prompts (Codex / Copilot / ChatGPT)

## Ground rules (paste this first)
- You are modifying an Android app repo.
- Keep changes minimal, safe, and buildable.
- Prefer small commits and explain what changed and why.
- Do not delete functionality unless asked.
- If you add dependencies, pin versions and justify them.
- Always provide a test/build command to validate the change.

## Change request template
Goal:
- <Describe the user-visible behavior change.>

Constraints:
- First screen on launch must be a map view.
- App must compile and launch.
- Avoid API keys and paid services.

Repo notes:
- Android app uses Gradle (`./gradlew`).
- Entry point is `app/src/main/java/.../MainActivity.kt`.

Tasks:
- Identify the right files to change.
- Make the code changes.
- Update docs if needed.
- Provide a short verification plan.

## “Implement this feature” prompt
Implement the following in this repo:
<feature description>

Acceptance criteria:
- App builds with `./gradlew :app:assembleDebug`.
- App launches to the map screen.
- If UI is added, it is reachable and does not crash.

Please:
- Show the exact files changed.
- Explain any trade-offs.

## PR summary template
Title: <short, action-oriented>

Summary:
- <what + why>
- <notable behavior changes>

Test plan:
- `./gradlew :app:assembleDebug`
- (Optional) `./gradlew :app:testDebugUnitTest`

Risks:
- <what could break>

## Merge / conflict resolution prompt
We have a merge conflict between branch A and branch B.

Please:
- Explain which side to keep for each hunk and why.
- Produce the final merged file content.
- Ensure the app still builds after the merge.

## Code review checklist
- Does the app still launch?
- Does the first view show a map?
- Any crashes on rotation / background-foreground?
- Any new permissions? Are they justified?
- Any new dependencies? Are versions pinned?

