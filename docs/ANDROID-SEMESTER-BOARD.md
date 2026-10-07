# Mobile Semester Board and reviewed MCP handoff

The Android 0.5 development preview targets the iOS mobile Planner → Semester functionality. It adds no CloudKit, account connection, shared database, periodic source upload or automatic cross-device sync. The optional desktop bridge delivers typed proposals and receipts; the phone owns confirmation and source writes.

## Mobile scope

Courses keep source order, module codes and a fixed name column while the week grid scrolls horizontally. Week numbers are offsets from the supplied semester start, not ISO week numbers. Source-defined active weeks differ from an unknown course schedule; unrecorded status does not mean failure. Named colors supplement accessible labels. A week can show a status, read-only notes and assessments; choosing a status preserves notes. Current-week highlighting uses the semester zone. Missing dates keep the board usable without guessed calendar placement.

Exam rings and other assessment diamonds include saved records of every state. All assessments keeps completed, dismissed and dates outside the visible grid reachable. `showExamWeeks` is imported metadata, extending the display (maximum 52) to late pending/completed assessments while retaining the original teaching count/range; dismissed records do not extend it. State changes preserve pending/completed/dismissed semantics independently of preparation. Explicitly moving one materialized recurring occurrence retains its series/scheduled-date metadata and records a reviewed exception; siblings and future generation stay untouched. Recurrence metadata describes saved occurrences, not permission to create future reminders or records.

Plan review creates or reuses an ordinary source-linked preparation draft. It starts undated with zero estimate and saves only after the usual editor review/confirmation. No status entry, assessed completion or Calendar reservation is inferred. Preparation preferences and completion remain independent; source navigation opens the related week/assessment.

## Source formats and privacy

Raw iOS semester files use schemas 1–3. The decoder handles legacy exam fields, source-scoped UUIDs, custom statuses, active weeks, week notes and assessments. Unknown compatible fields stay local; imported reminder/snooze settings do not enroll Android delivery. Source names/notes remain untrimmed and retain historical fields. The file boundary is 2 MiB, 10 courses, 20 statuses, 52 indexed weeks and 200 assessments; invalid/oversized files preserve existing data.

Foundation JSON timestamps are numeric seconds since 2001, not Unix/ISO timestamps. Conversion retains recorded values; no missing completion time is invented. Valid civil clocks in a DST gap/overlap remain source data, with an unresolved warning. Timed priorities/reminders/widgets omit unresolved entries while retaining valid neighboring work and habits. They do not silently move the time or label a readable workspace corrupt.

The new `habits.semester-board-proposal` version 1 is a closed assistant DTO with proposal ID, source, optional expected-board digest and schema-3 semester content. It preserves the distinction between omitted fields and explicit clears. Unknown/helper/device reminder/recurrence data is excluded. Missing course schedules and notes stay omitted. A new board must not claim an existing digest; updating an existing board requires a digest obtained through explicit phone sharing. Existing-digest structural validation retains recorded ambiguous clocks; the writer resolves the effective merged zone and rejects new or changed ambiguous timings. Read-only context also preserves the original clock instead of normalizing it. This format supplements the unchanged, strict, add-only `habits.academic-plan` version 1.

Context sharing freezes one selected semester and the workspace revision for phone review. Notes and weekly progress have disclosed inclusion choices. Unknown compatibility fields, source URLs, recurrence/device delivery configuration, unrelated semesters, planner/preparation history, habits, profile, Calendar, credentials and model weights are excluded. A context digest is an opaque hash of the complete local academic scope, including retained data, rather than a claim that the snapshot remains current. Sharing may send selected context to the assistant provider; pairing alone shares nothing.

## Import and persistence

File import reviews selected courses and an explicit source zone. Assistant proposals are reviewed in full with their supplied zone, binding the receipt to the received content. A preview writes nothing. Confirmation rechecks revision, proposal identity and expected board digest, then atomically writes source changes and a durable receipt. Included values update only when provided; empty incoming collections do not delete omitted courses/weeks/assessments/definitions. Compatible unknown fields merge locally. Consumed replay preserves subsequent edits.

iOS child UUIDs are scoped by semester/course; a legacy exam can reuse its course UUID. Android retains external IDs and maps new children to deterministic namespaced local UUIDs, resolving older v1 identities already in the workspace. This preserves source coordinates and task associations without applying the old assistant's global-ID rule to raw boards.

Schema 1 loads into schema 2 in memory. Merely opening a readable old file does not rewrite it. The first deliberate source mutation preserves original bytes in the rolling backup and a dedicated `.schema-v1-backup`; subsequent edits keep the dedicated original. Old import receipts/history remain. Unreadable/future files enter recovery with preserved bytes and editing disabled. Explicit backup restoration keeps a damaged-file copy. Downgrading an edited workspace to 0.4 is unsupported; keep private exports and version-specific recovery files.

Undo compares the reviewed before/after records and checks dependent tasks/status references. Later edits, notes/progress or linked preparation block unsafe removal. Newly created records are archived; changed existing records are restored only when guards pass. A raw iOS file has a distinct local receipt origin and cannot acquire a desktop acknowledgement. A typed assistant proposal reopened from a file can match an existing authenticated delivery only when its proposal ID/content fingerprint matches the phone's durable remote mapping; opening a file alone does not establish that mapping. Remote receipt failures preserve local saves; retry stays explicit and durable across empty queues/restart.

## Desktop compatibility

The paired phone advertises Board support through authenticated `/v1/assistant/capabilities`. Older/unadvertised phones remain academic-plan v1 only. An authenticated 404 from an old desktop preserves legacy pairing/import; manual refresh can retry after an upgrade. Board staging needs an explicit capable device. Board queues and context use separate routes, so old iOS/Android parsers never receive an unfamiliar proposal. Existing private TLS pinning, per-device HMAC, nonce/revocation and receipt semantics remain.

The MCP adds `get_semester_board_format`, `propose_semester_board`, `stage_semester_board` and `get_shared_semester_board_context`. Review/apply/Undo remain phone actions. Partial context network delivery is disclosed; it is not reported as complete sharing. Android 0.5 and Habits MCP 0.2 or newer are needed for the connected Board workflow; the MCP cannot add missing UI to an older APK.

## Scenarios and verification

| Scenario | Required behavior |
| --- | --- |
| Normal semester | Source grid/markers/status labels agree with saved records; no inferred mastery/attendance/time studied. |
| Unknown date/schedule/clock | Keep absence distinct from inactive/unmarked, retain all-assessments access and disclose skipped timed coverage. |
| Long/large board | Lazy rows, stable identities, synchronized scrolling and bounded inputs; no silent truncation of source records. |
| Stale context/editor | Require refreshed source review; preserve the current records and draft instead of overwriting. |
| Re-import/Undo | Replay is consumed; omitted data survives; later edits/dependents block unsafe Undo. |
| Offline/old desktop | File/paste/manual status work locally; legacy v1 remains usable; delayed receipt/context outcomes stay explicit. |
| Corrupt/interrupted storage | Preserve primary/backup/original-version bytes and expose deliberate recovery. |
| Derived layers | Dismissed leaves upcoming work; historical completions retain timestamps; preparation/reminder/widgets/reports remain independent. |

Fresh core tests, app builds, Python/package checks and Swift v1 compatibility are distinct from native-LLDB app/TLS/restart acceptance and screenshot review. New Board visual acceptance requires an independent score of at least 8.5. The older 0.4 score is not a score for the new board. Physical phones, full TalkBack traversal, tablets/landscape/RTL, real LAN routing, idle delivery and thermal/battery behavior remain separate acceptance.
