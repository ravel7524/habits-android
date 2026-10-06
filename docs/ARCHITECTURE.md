# Architecture

Habits for Android is a native Kotlin/Compose application with one local source of planning data. Today and reports derive their content from that source; optional assistant delivery feeds a review flow rather than writing directly into the workspace.

## Modules and ownership

| Area | Responsibility |
| --- | --- |
| `app` | Android entry point, Compose routes and forms, document/share intents, encrypted desktop credentials, device integrations, private model delivery and CPU JNI orchestration |
| `core` | Portable academic proposal contract, validation, local workspace, recorded-facts projections, bounded report-selection contract and desktop connection logic |
| Separate [Habits MCP project](https://github.com/ravel7524/habits-mcp) | Desktop MCP tools and the optional private phone bridge |

`MainActivity` supplies the real repository and optional desktop connection. `HabitsApp` presents Today, Planner, Habits and Settings. Planner contains Work, Schedule and Semester. Forms hold draft state; saving goes through `WorkspaceRepository`. The UI observes its `StateFlow` and never substitutes a separate planning database.

## Local persistence and derived reports

The workspace is a versioned JSON snapshot in private app storage. It contains tasks and assignments, appointments, academic semesters/courses/assessments, habits and logs, preferences and durable import receipts. Writes are serialized within the app process and committed atomically, with a previous local backup. Damaged files are preserved; recovery pauses writes instead of silently starting over. Initial loading is synchronous, so larger stores need continued startup testing.

Archiving retains history. Civil dates, optional clock times and source time zones remain explicit. An absent time is not an invented midnight appointment. Preparation estimates describe planned work; they do not prove study time or mastery.

`WorkspaceFacts` derives today's view and report periods from recorded data. Morning, Evening and Weekly reports are deterministic offline summaries. Missing logs do not become missed habits; completions without a usable timestamp cannot be assigned to a report period. Preparation completion and assessment completion remain separate records.

The 0.3 optional local AI layer can select highlights from those recorded sources. `ReportFacts` creates a bounded set of labelled fact lines. Its short system prompt and structured facts are supplied to the model; coverage warnings remain visible in the UI and participate in context invalidation, without enlarging the model prompt. Grammar-constrained native inference returns only supplied fact IDs; `ReportSelectionCodec` rejects invalid output, and the UI resolves the selected typed references to full original source rows. A source fingerprint rejects stale late results. The selection is derived in-memory state, not a workspace write, a free-prose report generator or an MCP action. The deterministic reports remain available in every model fallback state.

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

The portable suite and Compose workflows cover contract validation, local writes, replay, guarded Undo, stale previews and nearby task/habit/report behavior. The 3 October 2026 emulator evidence also covers real private TLS/HMAC pairing and receipt delivery across refresh, restart and file-based Undo. Physical phones, real LAN routing and power-loss behavior need separate acceptance. See the [README](../README.md#validation-and-limits) for the release's exact status.

## Device integrations

The 0.2 working tree adds read-only selected Calendar access, opt-in local reminders/report invitations and native home-screen widgets. `HabitsAndroidRuntime` owns the process-shared repository so widget actions and receivers use the same writer as activities. Device permission/preferences remain separate from the workspace and MCP context. See [integration contracts and scenarios](ANDROID-INTEGRATIONS.md).

## Optional local model in 0.3

`ModelCatalog` pins the two official Liquid GGUF presets used by iOS: fast LFM2.5 230M QAD Q4_0 and balanced LFM2.5 2.6B QAD Q4_0 (reasoning). Each has its own author revision, exact byte size, SHA-256, LFM Open License link and minimum RAM. `ModelStore` accepts only catalog descriptors through its public API, verifies startup/install state on IO, downloads only after an explicit action, retains compatible partial downloads, and installs a fully verified file by atomic rename. Files and bounded delivery metadata stay in `noBackupFilesDir/models`, outside workspace backups and desktop context. Network delivery accepts only HTTPS at the official author host and a narrow CDN allowlist; inference has no network client and does not upload prompts.

`LocalAIController` separates selected preset, stored/verified bytes and loaded native memory. It gates unsupported or low-memory devices using the selected model's 2 GiB/5 GiB minimum, serializes loading/generation/release, cancels work when the app leaves the foreground and invalidates changed source facts. Changing presets does not download or load one automatically. Cancellation is sticky for a native handle: release and a fresh explicit load are required before reuse. Background report invitations and widgets do not load or invoke the model.

`NativeLlama` bridges pinned llama.cpp CPU code for ARM64 and x86_64. The packaged 32-bit ARM library is an unsupported stub so core planning still runs there. Fast has a 1,536-token context and the report action requests 128 output tokens; balanced has a 6,144-token context and at most 2,048 generated tokens including internal reasoning and final JSON. Sampling matches the iOS Liquid profiles: top-k 50, top-p 1, temperature 0.10, seed 1337 and a 64-token repetition window, with penalties 1.05 for fast and 1.10 for balanced. Grammar is applied before sampling so final choices remain constrained.

The bridge reserves output within context, rejects oversized complete input rather than truncating fact JSON, and limits workers to four CPU threads. For balanced, grammar activates after the loaded model's closing reasoning token; thoughts are discarded, and only validated final fact-ID JSON reaches the UI. Missing closure, timeout or an exhausted budget falls back to the recorded report. The report screen uses static generation status text rather than a continuously animated progress indicator, keeping rendering work modest during CPU inference. The APK includes native code and its notices, but no weights. App builds require NDK 29.0.14206865 and CMake 3.31.6 in addition to JDK 21 and the documented SDK.

The 0.3 local app/test build passed in 24 seconds, with 136 portable passes and no failures or skips. Earlier revisions passed 50 instrumentation tests and six additional actual balanced-model native/controller checks. The replacement APK passed all 51 fast-profile tests in 67.15s and all seven balanced-profile tests in 244.916s, including recorded-report acceptance, with no failures/errors/skips. Real fast download/cancel/resume and report selection completed; the latest balanced report UI displayed an original recorded completion in 36.989 seconds without showing thoughts.

Native LLDB was attached before managed execution. A separate source-tracing run resolved JNI generation through the matching unstripped library and APK mapping, confirming balanced arguments; the successful current UI run used attachment without inference breakpoints. Earlier verbose prompts reached the 90-second deadline and safely fell back. Those diagnostics do not establish a single cause for the earlier slow run. Physical-phone latency, battery/thermal behaviour and sustained memory pressure remain unverified. These 0.3 checks preceded the separate 0.4 publication; current release details are in the README. See [local AI contracts and scenarios](ANDROID-LOCAL-AI.md).

## Screen composition in 0.4

`HabitsTheme` owns semantic light/dark colors, typography and system-bar icon contrast. `UiCopy` formats civil dates, optional clock times and localized singular/plural counts without changing stored values. `HabitsApp` owns detail and settings routing; Calendar and report AI recovery select a focused Settings section. Settings first scrolls to compose the focused item, then `IntegrationPanel` expands and reveals that section in the existing parent scroll. These routes do not enable a capability or start model work.

Editors keep drafts apart from source records. Native date pickers interpret their UTC-midnight values as civil dates, not device-zone instants; time pickers use the system 24-hour preference. The action footer stays outside the scrolling draft area with IME padding. Review validates existing date/time, recognized-zone and DST rules; Confirm still uses the repository writer and stale-record guards. Removing a task date also removes its time. The deadline-dependent lazy interval shape is captured before the deferred list builder runs, preventing it from reading changing draft state during measurement.

Today shows a disclosed bounded work preview and a route to the full Planner. Reports show recorded facts before optional AI and expandable source details; all three reports preserve missing-log and preparation/assessment distinctions. Long lists disclose their preview limits and expose more records. Settings separates optional device integration controls from ordinary preferences and backup/archive actions. Desktop sharing freezes the selected semester at review and rejects confirmation after a workspace revision changes.

The final 0.4 work passed 139 core tests, three app formatting tests and a 65-test native affected suite under LLDB. Independent review accepted 69 rendered states at a raw minimum 8.5 across 14 families. Exact build/state provenance, superseded failures and physical-device limits remain part of the dated verification; visual acceptance does not replace functional checks.

`UiReviewCaptureTest` is an opt-in render harness (`uiReviewCapture=true`) with real `HabitsApp`, Calendar OFF and unloaded-AI composables. It drives review/error transitions in an isolated synthetic cache workspace and checks visible markers before `UiAutomation` screenshots. Output is PNG plus safe JSON under target-private `files/ui-review-captures/<run-id>/`. Metadata distinguishes its test-host Activity, source fixtures and Compose font scale from actual MainActivity/provider/model/TLS acceptance. Without the explicit argument it is skipped rather than counted as a no-op pass. Run it under native LLDB like other runtime tests; do not package screenshots or model weights into the app.

Old success/Undo snackbars are dismissed when an editor opens, including queued messages, so they cannot intercept its fixed footer. A write failure in the active editor has a separate visible dialog and leaves the draft open; it does not make a blanket no-save claim when outcome is uncertain. Source stale-write checks still own record preservation.

`ReportFact.sourceRef` is typed derived-local metadata with captured record/dependency values. It never enters `userPrompt`, the grammar, exports or persistence. `ReportHighlightRow` resolves the validated selection against the same snapshot/Calendar state used to build its facts, verifies unique identity and captured values, and formats full source title/date/time/status/estimate for display. It never parses a compact prompt summary to reconstruct a source. Missing, edited, duplicate, wrong-kind or revoked references produce an unavailable notice. Golden prompt tests and source comparisons preserve the existing model contract and native budgets.

The actual 4 GiB device state preserves verified model files but marks the selected 2.6B tier unavailable and not loaded; the 230M recovery remains explicit. A background return unloads native memory and keeps the recorded report available. An earlier seven-fact balanced acceptance attempt hit the 90-second deadline; an actual UI run and single bounded retry later succeeded without changing data, prompts or budget. Timing variability remains a limitation.

Publication builds inherit native file-prefix maps for the checkout, build and NDK directories. Native source/assert/debug paths become portable; no model selection, sampling, budget, grammar or source-writing behavior changes. The public source tree and release assets exclude private runtime/signing data and downloaded weights.
