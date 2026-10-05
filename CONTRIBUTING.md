# Contributing

This repository is the development home for Habits for Android. Useful reports include a concrete user problem, reproduction steps, expected behavior and the Android version or device involved. Please use synthetic examples and redact private notes, pairing invitations, credentials and exported planning data.

No project license has been selected yet. Discuss substantial contributions in an issue first while contribution and licensing terms are being settled.

## Set up and check a change

Use JDK 21 and the repository's Gradle wrapper. The [README](README.md#develop) lists the Android SDK requirements and pinned toolchain. Never commit `local.properties`, signing keys, credentials, generated APKs or local toolchain caches.

```sh
./gradlew -PcoreOnly=true :core:test
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

Run the checks relevant to the change. Core tests cover the portable proposal contract, workspace and desktop connection behavior. Compose tests cover user workflows on an emulator or device. A successful build alone does not establish runtime behavior or private desktop delivery.

Keep the original private signing key for updates to your own installation, and keep `ANDROID_USER_HOME` consistent if you set it. Never publish that key. A locally built debug APK may have a different signing identity from the downloadable preview. Export data before changing installations; do not recommend uninstalling as a harmless update fix.

Signing continuity for the published 0.1.0 preview is not established until a retained private copy of its original key is recovered. Routine signing-compatible upgrades are not promised; stable release signing is a priority for further development.

## Keep changes focused

- Use `WorkspaceRepository` for writes and `WorkspaceFacts` for reports. Keep orchestration out of individual controls.
- Preserve records and archived history. Make store/schema changes explicit, with migration and recovery coverage.
- Keep deadlines, appointments, preparation completion and assessment completion distinct.
- Never infer attendance, missed habits, free time or time studied from absent records or task estimates.
- Preserve phone review before assistant imports. Keep additions bounded, IDs stable on retries and receipts tied to actual phone state.
- Preserve private desktop binding, certificate pinning, per-device authentication, revocation and replay protection. Keep keys and invitation contents out of logs and screenshots.
- Check empty, large, malformed, stale, offline and interrupted cases as well as the normal workflow.

Explain what changed, why, what was tested and what remains unverified in a pull request. Include screenshots for visible changes and update the user or architecture docs when behavior changes. Do not describe an emulator check as physical-phone acceptance.
