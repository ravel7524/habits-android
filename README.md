# Habits for Android

**0.4.0 development preview** · Android 8.0 or newer

A native Android app for daily work, appointments, habits and semester deadlines. Review an assistant's exam proposal before saving it, or use the planner independently. Your records stay on the phone unless you explicitly export or share them.

## Download

Get the [0.4.0 preview](https://github.com/ravel7524/habits-android/releases/tag/v0.4.0) or [download the APK](https://github.com/ravel7524/habits-android/releases/download/v0.4.0/habits-android-0.4.0-debug.apk). The release includes checksums and a manifest. This is a debug-signed development build, not a Play Store release.

Open the APK with Android's browser or Files app. If asked, permit **Install unknown apps** for that app, then turn that permission off after installation. [Google's installation guidance](https://support.google.com/pixelphone/answer/7391672?hl=en) explains the setting; labels vary by device. Tasks and habits need no account or AI connection.

**Existing installations:** 0.4 retains the separately held 0.2/0.3 development certificate and can update those builds in place. It uses a different certificate from public 0.1 and cannot update 0.1 in place. Export and retain your private backup before changing installations; uninstalling removes local data. Stable release signing and a supported 0.1 migration remain open.

## Daily use

- **Today:** open deadlines, saved appointments and scheduled habits before report entry.
- **Planner:** tasks/assignments, appointments and semester assessments with separate preparation. Native date/time pickers preserve date-only work. Review, then Confirm before saving.
- **Habits:** scheduled days, daily completion and retained logs/archive history. Missing logs are unknown, not inferred misses.
- **Reports:** offline Morning, Evening and Weekly summaries from saved facts. Estimated minutes describe planned work, not time studied.
- **Calendar:** optional read-only access to calendars you select; events stay out of assistant context.
- **Notifications:** explicit permission and per-type opt-ins, quiet hours and Snooze; Android can delay inexact delivery. Report invitations do not run AI in the background.
- **Widgets:** Next up and Today's habits use local records; titles can be seen by anyone viewing the home screen.
- **Settings:** English/German/Spanish, appearance, local backup/recovery, archived history and optional device tools.

<p>
  <img src="docs/images/v0.4/today.png" alt="Synthetic populated Today" width="260">
  <img src="docs/images/v0.4/work-preparation.png" alt="Synthetic Work view" width="260">
</p>

## Plan exams with Codex or Claude

Open **Planner → Semester → Plan with an assistant**. Share the instructions and your original timetable with your chosen assistant, then open or paste its JSON proposal. Check course, exam date/time/zone and preparation. Nothing is added until **Confirm and save**. Completing preparation does not complete the exam. Reopening a proposal does not duplicate it; guarded Undo preserves unrelated work and a durable receipt. [Format and synthetic example](docs/contracts/README.md).

For connected delivery, install the separate [Habits MCP](https://github.com/ravel7524/habits-mcp) on your computer and use **Connect Codex or Claude** on the phone. It needs a reachable private desktop address. Pairing shares no records. You explicitly review one selected semester's context, including disclosed assessment notes/states, before sharing; an assistant may send that context to its provider. Offline file/paste import stays available.

Only phone review/confirmation applies a staged proposal. The desktop receives applied/undone receipts, with manual retry when delivery is unavailable. Version 1 adds academic content; it has no arbitrary app-code, UI-editing or destructive command.

## Optional Liquid models on the phone

Settings → **On-device AI** provides separate selection, download, verification, load, unload and removal actions. Choosing a model never downloads or loads it automatically. The two pinned artifacts match the iPhone catalog:

| Model | Download | Minimum total RAM |
| --- | --- | --- |
| LFM2.5 230M QAD Q4_0 · fast | about 149 MB | 2 GiB |
| LFM2.5 2.6B QAD Q4_0 · reasoning | about 1.59 GB | 5 GiB |

Each official Liquid GGUF is pinned to an immutable revision and verified by full size/SHA-256. Supported ARM64/x86_64 devices run CPU inference; 32-bit ARM retains basic planning without local AI. Downloads contact Hugging Face and approved HTTPS delivery hosts; inference sends no records/prompts to a cloud AI endpoint. Weights stay outside the APK, backups and desktop context. [Pinned licenses, delivery, contracts and scenarios](docs/ANDROID-LOCAL-AI.md).

In a report, **Choose highlights** selects at most eight supplied fact IDs. The app displays their full original source records with readable dates/status, never model-authored claims. It rejects unknown IDs, duplicates, extra keys and prose. Changed source facts invalidate late results. Cancellation, backgrounding, timeout or missing models leave the recorded report available and release native memory; explicit reload is required. This is not the iPhone's full prose-generation feature set.

## Validation and limits

The 0.4 rework received an independent **8.6/10** review: all **69 reviewed states across 14 families** scored at least **8.5** (lowest raw 8.525). Review includes populated/empty/staged/error states, actual MainActivity OS font scale 1.3, native pickers/software keyboard and real model recovery. [Dated public verification](docs/verification/0.4.0/README.md) records build and screenshot boundaries.

**139 core tests, 3 formatting tests and 65 affected Android tests passed**; accepted emulator launches had native LLDB attached. Actual fast selected-result UI completed in 1.6 s; balanced UI completed in 59.5 s and its bounded recorded-report retry in 54.845 s. Balanced attempts also hit the fixed 90-second deadline, including the final publication APK recheck. That APK passed all seven focused fast-model tests; its balanced recheck timed out and safely retained the recorded report. Successful earlier runs do not resolve this repeated timing limitation. Exact publication checks are recorded in the release manifest.

Physical phones, heat/battery pressure, full TalkBack, tablets/landscape, real LAN routing and prolonged idle notification delivery remain separate acceptance. There is no automatic iPhone/Android sync or CloudKit integration. Source/test builds and visual scores do not establish production or physical-device parity.

## Develop

Use **JDK 21** and the checked-in wrapper. App builds require SDK platform 37, build tools 36.0.0, **NDK 29.0.14206865** and **CMake 3.31.6**, with licenses accepted in your own environment. Set `ANDROID_HOME` or an untracked `local.properties`. CPU native code is vendored at a pinned upstream revision; weights are separate explicit downloads.

```sh
./gradlew -PcoreOnly=true :core:test
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

The first needs no Android SDK/NDK. Device/inference acceptance needs an adb target, actual verified weights and explicit fixture arguments; skipped or synthetic delivery tests do not prove inference. For operator runtime checks, follow [native debugger verification](docs/development/patterns/native-android-debugging.md). CI compiles app/test APKs and runs portable checks; it does not replace emulator/private-TLS/physical acceptance.

Gradle 9.3.1, AGP 9.1.1 and Kotlin 2.4.10 are pinned; compile SDK 37, target SDK 36, minimum SDK 26. See [Contributing](CONTRIBUTING.md), [Architecture](docs/ARCHITECTURE.md) and [Roadmap](ROADMAP.md). No project license is selected; retained [third-party notices](docs/licenses/README.md) and Liquid model terms still apply.
