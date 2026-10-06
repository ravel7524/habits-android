# Roadmap

Habits for Android 0.4 is a development preview with selected read-only Calendar, opt-in reminders/widgets, two optional Liquid models and a reworked native UI. Emulator checks and independent visual review passed; physical-device, stable signing/migration and real-LAN acceptance remain open. This is a tentative order of work, not a release schedule or a promise that every idea will ship.

## First: make the preview dependable

- Test installation, updates, backup recovery and the core flows on physical phones.
- Establish stable release signing and a sustainable update path that preserves local data.
- Verify private desktop pairing, interrupted delivery and receipt recovery on real LANs.
- Improve accessibility, large text, small screens, language coverage and everyday editing.

## Then: improve daily use

- Refine planner navigation and semester presentation as real usage exposes gaps.
- Validate the implemented read-only Calendar, opt-in reminders and native widgets on physical devices and real OEM power-management/launcher combinations.
- Improve Morning, Evening and Weekly reports while keeping recorded facts, planned estimates and missing data clearly distinguished.
- Extend emulator-verified model delivery and CPU JNI report selection to physical devices: download interruption/restart, checksum failures, foreground cancellation, memory release/reload, bounded context, stale source facts and recorded-report fallback. Keep weights out of the APK, backups and assistant exports.
- Check report-highlight usefulness, latency, heat and memory on physical ARM64 phones before treating emulator inference as everyday-device acceptance. Unsupported devices must retain the basic reports.

## Explore separately

Broader assistant actions and cross-platform sync may be considered later. Each needs a defined scope, privacy boundaries, conflict handling and independent validation. They are not enabled by desktop pairing, and they are not commitments for the next release.

The current app and its limitations are described in the [README](README.md). Larger changes should start with a focused issue describing the user problem and how success would be checked.
