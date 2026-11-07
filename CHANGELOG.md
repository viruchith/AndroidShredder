# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **Android 10 (API 29) Device Lock Detection**: Resolved an issue where Android 10 devices and emulators continued prompting to set up a screen lock even after setting a PIN, pattern, or password. Switched to `KeyguardManager.isDeviceSecure` for credential detection on API 29 and below, where `BiometricManager.canAuthenticate(BIOMETRIC_STRONG | DEVICE_CREDENTIAL)` is unsupported and returns `BIOMETRIC_ERROR_UNSUPPORTED`.
- **Android 10 Biometric & PIN Authentication**: Fixed `BiometricPrompt` authentication on Android 10 and devices without biometric hardware by avoiding unsupported `CryptoObject` bindings with device credentials (supported only on API 30+) and using supported authenticator configurations (`BIOMETRIC_WEAK | DEVICE_CREDENTIAL`).
- **Automatic Security Posture Re-check**: Added an `ON_RESUME` lifecycle observer in `MainActivity` to automatically re-evaluate screen lock status when returning from System Settings, removing the need to restart the app or manually tap "Retry Check".

## [1.4] - 2026-07-05

### Added
- GitHub Actions CI pipeline for automated lint, build validation, unit tests, and signed release builds.
- Thread-safe algorithm snapshots captured at session start.
- Bounded thread pool with capacity-limited `ArrayBlockingQueue` and `CallerRunsPolicy` to prevent OOM errors during bulk shredding.
- Expanded path protection in `DestructiveOrchestrator` for `/system`, `/vendor`, `/data`, `/proc`, `/dev`, `/sys`, and top-level media folders.
- O(1) file selection performance using `HashSet`-backed containment checks.
- Explicit `PendingIntent` targets with `FLAG_IMMUTABLE` across all foreground service notifications.

### Fixed
- Critical integer overflow in pattern-based overwrite passes for files larger than 2 GB.
- Progress calculation overflow using `Double` arithmetic on large datasets.
- Stale file reference validation in service-layer deserialization.

## [1.3] - 2026-07-05

### Added
- Free Space Wipe standalone session mode with cancellation and progress reporting.
- Configurable overwrite algorithms: Fast (1-pass), Standard (3-pass), DoD 5220.22-M (7-pass), Bruce Schneier (7-pass), and Peter Gutmann (35-pass).
- Dedicated Settings screen with algorithm selection and safety warnings.
- State-preserving navigation with `AppScreen` enum.
- Operational keep-awake with `FLAG_KEEP_SCREEN_ON` and foreground service `PARTIAL_WAKE_LOCK`.
- Contextual back-button interception.

## [1.2] - 2026-07-04

### Added
- App lock security gating with device lock and biometric authentication barriers.

## [1.1] - 2026-07-04

### Added
- Adaptive launcher icons, settings management, and UI polish.

## [1.0] - 2026-07-04

### Added
- Initial release of Shredder featuring secure file deletion, recursive directory shredding, real-time log console, and Nuclear Option device wipe.
