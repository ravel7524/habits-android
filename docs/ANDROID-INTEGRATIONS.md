# Calendar, reminders and home-screen widgets

The 0.2 development work adds device integrations around the existing local workspace. Planner and habit records remain the primary user-owned sources. Calendar reads, reminders, report invitations and widgets are derived views/actions; they do not enable cloud sync or change the assistant contract.

## Calendar

Calendar access is optional and read-only. Settings requests Android's Calendar permission after an explicit user action, then lets the user choose calendars. Provider instances cover a bounded near-term window, including recurring events and civil all-day dates. Calendar-origin events are labelled separately from saved Habits appointments in Schedule, Today and the Morning view. A Calendar snapshot does not prove attendance or free time.

Device choices persist separately from the workspace; event content remains in memory. Calendar notes/events, calendar IDs and permissions are excluded from workspace backups and MCP academic context. Denial, revoked permission, removed calendars, query failure, truncation and unavailable provider coverage have distinct states; stale event displays are cleared rather than represented as complete coverage. Reading never writes to the Calendar provider.

## Reminders

Reminders are opt-in for local dated tasks/assignments, assessments, appointments and scheduled habits. Preparation reminders have their own opt-in; completing preparation does not complete its exam. Morning/Evening/Weekly alerts invite the user to open a report, without generating AI content in the background.

Android notification permission and channel availability are checked before posting. The bounded alarm queue is rebuilt from current records and device preferences. Archived/completed/deleted/changed sources invalidate old deliveries. Quiet hours, durable delivered-version identities and Snooze protect retries and prevent stale notifications. PendingIntent identities remain distinct and immutable. No exact-alarm permission is requested; Android may delay inexact reminders under battery/idle policies, which the settings UI discloses.

Preference and delivery state is device-local, outside the workspace and assistant context. Boot, clock/time-zone changes, app activation and source edits reconcile derived schedules. Report notifications route to the existing on-demand report views; they do not alter planner records or enrol a remote service.

## Widgets

Next up shows up to three local pending deadlines/assessments and future or ongoing saved appointments. Unknown deadline clocks remain unknown. Today's habits shows the scheduled/logged count and up to three explicit habit actions. Neither widget uploads data or reads personal Calendar content. Titles and logged states are visible on the home screen, and Settings discloses that visibility before offering pin actions.

Widget habit actions verify today's civil date, current source activity/schedule, expected completion and workspace revision before writing through the shared repository. Old buttons and double taps cannot overwrite newer logs or record another day. Empty, recovery and stale displays direct the user to refresh/open the app; damaged workspace files remain preserved.

Activities, system receivers and widgets use one process-owned workspace repository. Source writes refresh reminders/widgets; foreground activation refreshes Calendar permission/coverage and local projections. Periodic widget updates and clock-change handling keep dates current without requiring reminder opt-ins.

## Scenario and regression acceptance

| Scenario | Required behaviour |
| --- | --- |
| Permissions refused/revoked | Core planning continues; Calendar content clears; notifications show blocked state and do not post. |
| Empty/unselected calendars | Explain missing coverage; do not infer free time or silently select every calendar. |
| Recurrence/all-day/time-zone/DST | Preserve instance identity and all-day civil dates; source deadlines retain clock presence and source zone. |
| Large sources | Bound provider reads, alarm queue and widget rows; disclose partial Calendar coverage; retain all primary records. |
| Stale alarm/source edit/completion | Revalidate the source and version; cancel/drop outdated alerts. |
| Quiet hours/Snooze/offline | Reconcile safely from durable local state; inexact delivery limits remain visible. |
| Widget midnight/double tap/changed revision | Fail closed, refresh and preserve newer habit logs. |
| Corrupt storage/process restart/boot | Preserve recovery files and reload device preferences; do not imply successful writes or clear history. |
| Assistant import/replay/Undo | Reminders remain opt-in, source identity stays stable, and local actions still produce accurate desktop receipts. |

Tests must cover pure Calendar/reminder/widget projections, Android permission/provider/alarm/RemoteViews paths, existing Compose planning/report workflows and an actual emulator walkthrough. Runtime debugger evidence and physical-device acceptance must be reported separately. This document defines behaviour; the dated verification record establishes which paths actually passed.
