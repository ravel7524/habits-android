# Architecture

Habits for Android is a native Kotlin/Compose application with one local source of planning data. Today and reports derive their content from that source; optional assistant delivery feeds a review flow rather than writing directly into the workspace.

## Modules and ownership

| Area | Responsibility |
| --- | --- |
| `app` | Android entry point, Compose routes and forms, document/share intents, encrypted desktop credentials |
| `core` | Portable academic proposal contract, validation, local workspace, recorded-facts projections and desktop connection logic |
| Separate [Habits MCP project](https://github.com/ravel7524/habits-mcp) | Desktop MCP tools and the optional private phone bridge |

`MainActivity` supplies the real repository and optional desktop connection. `HabitsApp` presents Today, Planner, Habits and Settings. Planner contains Work, Schedule and Semester. Forms hold draft state; saving goes through `WorkspaceRepository`. The UI observes its `StateFlow` and never substitutes a separate planning database.

## Local persistence and derived reports

The workspace is a versioned JSON snapshot in private app storage. It contains tasks and assignments, appointments, academic semesters/courses/assessments, habits and logs, preferences and durable import receipts. Writes are serialized within the app process and committed atomically, with a previous local backup. Damaged files are preserved; recovery pauses writes instead of silently starting over. Initial loading is synchronous, so larger stores need continued startup testing.

Archiving retains history. Civil dates, optional clock times and source time zones remain explicit. An absent time is not an invented midnight appointment. Preparation estimates describe planned work; they do not prove study time or mastery.

`WorkspaceFacts` derives today's view and report periods from recorded data. Morning, Evening and Weekly reports are deterministic offline summaries. Missing logs do not become missed habits; completions without a usable timestamp cannot be assigned to a report period. Preparation completion and assessment completion remain separate records.

Android's workspace is independent of the iPhone app's persistence. There is no automatic cross-platform sync, and the Android app does not inherit the iPhone's LLM, CloudKit, widget, external Calendar or reminder implementations.

## Academic proposal flow

1. A JSON file, pasted text or paired desktop supplies a bounded academic proposal.
2. The strict codec validates the structure, IDs, limits, dates, time zones and linked preparation. Invalid input cannot be partially accepted.
3. The repository creates a preview against the current workspace revision. The user checks the source, dates and additions on the phone.
4. Confirmation commits additions and a durable receipt together. A changed workspace requires a fresh review.
5. Stable proposal IDs and content identity prevent duplicate replay. Guarded Undo checks imported records and dependent data before archiving only the created items. The consumed receipt prevents the proposal from silently returning.

Version 1 adds academic content and preparation tasks. It does not authorize assistant edits to arbitrary app code, UI, existing records or deletion commands.

## Optional desktop connection

Pairing uses a private HTTPS endpoint with certificate pinning. Requests use per-device HMAC authentication and nonce protection. Android encrypts credentials and durable proposal/receipt mappings with Android Keystore, separately from the planning snapshot.

Connected context sharing requires one explicitly selected active semester and discloses included assessment notes and recorded states. It excludes unrelated planner/preparation history, habits, logs, profile, Calendar and credentials. Sharing with an assistant may send that selected context to its AI provider; pairing alone does not share context or enable sync.

Staging means pending, not applied. Only a phone receipt confirms a local save or Undo. Delivery mappings survive restart so file-based Undo can still update the paired desktop. Offline receipt failures preserve local records and retry on manual refresh. A disconnect removes the phone's local credential; if remote revocation cannot be reached, revoke the device from the desktop too.

## Verification boundary

The portable suite and Compose workflows cover contract validation, local writes, replay, guarded Undo, stale previews and nearby task/habit/report behavior. The 3 October 2026 emulator evidence also covers real private TLS/HMAC pairing and receipt delivery across refresh, restart and file-based Undo. Physical phones, real LAN routing and power-loss behavior need separate acceptance. See the [README](../README.md#current-limits-and-verification) for the release's exact status.
