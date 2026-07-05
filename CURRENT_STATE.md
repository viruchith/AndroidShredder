# Current State of Shredder

This document provides a technical overview of the current architecture, security guarantees, recent modifications, and follow-up development goals for the Secure Shredder application.

---

## 🏗 Architecture Overview

The app is built on a modern Android stack with a decoupled, state-driven Jetpack Compose UI. It separates business rules, permission orchestration, security posture gating, and filesystem operations into modular, easily testable classes.

```
                    ┌────────────────────────┐
                    │      MainActivity      │ (Compose UI & State Binding)
                    └───────────┬────────────┘
                                │
         ┌──────────────────────┼──────────────────────┬──────────────────────┐
         ▼                      ▼                      ▼                      ▼
┌─────────────────┐   ┌─────────────────┐    ┌─────────────────┐    ┌──────────────────┐
│ SecurityGating  │   │  Permission-    │    │ FileBrowserModel│    │  Destructive-    │
│  (Auth, Biometrics) │  Orchestrator   │    │ (Sort/Filter/List) │   │  Orchestrator    │
└─────────────────┘   └─────────────────┘    └─────────────────┘    └──────────────────┘
         │                                             │                     │
         ▼                                             ▼                     ▼
┌─────────────────┐                          ┌─────────────────────────────────┐
│ SettingsScreen  │                          │         ShredderEngine          │ (Core Disk I/O)
│  (Algo Select)  │                          └─────────────────────────────────┘
└─────────────────┘                                           ▲
         │                                                    │
         ▼                                                    │
┌──────────────────┐                                          │
│ShredderPreferences│ ────────────────────────────────────────┘
└──────────────────┘
```

---

## 🗂 Major Components & Responsibilities

1.  **`MainActivity`**: Orchestrates UI rendering, binds lifecycle states, and forwards user actions. Uses no inline business, sorting, or security logic.
2.  **`SettingsScreen` (New)**:
    *   Exposes a RadioGroup-style selection panel for available shredding algorithms.
    *   Features detailed cards detailing the name, description, and pass count of each algorithm.
    *   Presents a prominent warnings banner (⚠️ *Extremely slow — 35 passes*) next to Peter Gutmann's 35-pass algorithm.
3.  **`ShredderPreferences` (New)**:
    *   A persistent configuration helper built with standard Android private `SharedPreferences` and core Kotlin KTX edit blocks.
    *   Handles reading and writing selected algorithm keys and buffer settings across app lifecycle states.
4.  **`ShredAlgorithm` & `ShredPass` (New)**:
    *   A type-safe `sealed class` mapping available shredding options (Fast 1-pass, Standard 3-pass, DoD 5220.22-M 7-pass, Bruce Schneier 7-pass, and Peter Gutmann 35-pass).
    *   Exposes individual passes and type-specific configurations (`RANDOM`, `ZEROS`, `ONES`, and repeating binary `PATTERN`s).
5.  **`ShredderEngine` (Core logic)**:
    *   Executes standard multi-pass overwriting tailored dynamically to the selected algorithm.
    *   Exposes real-time progress (`progressFlow`) and step-by-step pass descriptors (`currentPassFlow`).
    *   Includes circular symlink recursion protection.
    *   Provides partitioned free-space filling until exhaustion, matching the selected algorithm's first-pass scheme.
6.  **`ShredderService`**:
    *   Runs as a Foreground Service to prevent OS termination during deep disk I/O.
    *   Triggers standard list shredding, dedicated **Free Space Wipe Only** sessions, or (separately) Nuclear Option reset flow.
    *   Free Space Wipe notifications now include live progress and a direct Cancel action.
7.  **`SecurityGating`**:
    *   Decoupled, testable security utility that detects active lock-screen states.
    *   Orchestrates native BiometricPrompt challenges.
8.  **`PermissionOrchestrator`**:
    *   Centrally handles scoped storage checks and post-notifications checks depending on API levels.
9.  **`FileBrowserModel`**:
    *   Pure-Kotlin business rules for directory querying, case-insensitive searches, and multi-criteria sorting (Name, Size, Date).
    *   Ensures directories always appear first.
10. **`FileSelectionLogic`**:
    *   Pure-Kotlin operations for state lists (Toggling items, selecting all, deselecting all, and checking status).
11. **`DestructiveOrchestrator`**:
    *   Defensive filtering rules to isolate system-critical directories and protect them from bulk-shredding actions.
12. **`Free Space Wipe` (New Session Mode)**:
    *   Exposed from Settings as a standalone operation independent of Nuclear Option.
    *   Writes only to app-scoped temp artifacts under `.../Android/data/<package>/cache/free_space_wipe/<sessionId>/`.
    *   Uses canonical-path validation before every cleanup delete to prevent out-of-scope file removal.

---

## 🔒 Security Decisions & Assumptions

*   **Forced Strong Security Posture**: Prior versions allowed users with zero screen-lock security to access the app and run destructive actions. This fallback has been **fully blocked**. The app now detects unsecured devices and displays a full-screen lock blocker directing them to system settings.
*   **No Path Leaks in Logcat**: Standard `logcat` is sanitized. Absolute file paths are never printed to system logs. Only local file names appear inside the collapsible, memory-limited UI console.
*   **Dual-Layered confirmation**: Before launching biometrics for the high-risk Factory Reset, the user must explicitly type **"NUCLEAR WIPE"** into a text field. This mitigates accidental trigger risk.
*   **Safe-Failover on Overwrites**: If a file block write fails (due to write permissions, locked blocks, or hardware failures), Shredder compensates the progress bar, reports a localized warning, and attempts file truncation and standard delete as a fallback.
*   **Strict Mode Separation**: Free Space Wipe mode is guarded from factory reset paths by design; it does not call `DevicePolicyManager.wipeData()`.
*   **Cooperative Cancellation**: Free Space Wipe uses session-scoped cancel tokens and cleanup-on-cancel to avoid leaving partial wipe artifacts.

---

## ⚠️ Known Limitations & Risks

*   **Out-of-Place Writes on NAND Flash (eMMC/UFS)**: Overwriting single files cannot guarantee physical block erasure due to wear-leveling and FTL mapping (see README.md). The Free Space Wiper must be run alongside regular shreds to clean mapped block remnants.
*   **File-Based Encryption (FBE) Remnants**: Deleting data does not purge old keys from memory immediately unless the Nuclear Option (Factory Reset) is executed, which discards keys at the hardware level.

---

## 🛠 What Was Changed in This Task

1.  **Refactoring & Cleanup**:
    *   Removed the unused legacy, dead-code `/com/viruchith/shredder/Shredder.kt` file.
    *   Extracted dynamic algorithm configurations and preferences out of core disk I/O logic.
2.  **Configurable Overwriting**:
    *   Introduced `ShredAlgorithm` and `ShredPass` defining Fast (1-pass), Standard (3-pass), DoD 5220.22-M (7-pass), Schneier (7-pass), and Gutmann (35-pass) overwrite configurations.
    *   Configured `ShredderEngine.kt` to run dynamic, pass-specific overwriting loop sequences.
    *   Updated mathematical progress computations to use `totalBytes * currentAlgorithm.passes.size` for perfect visual reliability.
3.  **Preferences Storage**:
    *   Created `ShredderPreferences.kt` to load, check, and persist selected algorithm configurations across app lifecycles.
4.  **Jetpack Compose Settings**:
    *   Created `SettingsScreen.kt` featuring RadioGroup algorithm configurations and a performance warning chip.
    *   Added Settings navigation controls and active algorithm visual indicators below the main progress bar.
5.  **Test Coverage**:
    *   Appended 2 high-value, JVM-compatible algorithm testing methods to `ShredderUnitTest.kt`, validating that all algorithms configure the exact required pass lengths and types. All 12 unit tests pass beautifully.
6.  **Free Space Wipe Only (New)**:
    *   Added a Settings section with explicit warnings, acknowledgement checkbox, and Start/Cancel controls.
    *   Added `FREE_SPACE_WIPE_ONLY` service mode and `CANCEL_FREE_SPACE_WIPE` action using `sessionId` routing.
    *   Added typed free-space result/state model (`Completed`, `Cancelled`, `Failed`) with progress semantics based on initial free-space estimate.
    *   Added safety-bound cleanup that deletes only app-created wipe artifacts under the session directory.

---

## 🔮 Future Work & TODOs

*   **[ ] Custom Overwrite Passes**: Allow advanced users to select custom overwrite schemes (e.g. DoD 5220.22-M 3-pass or custom pattern entries) via the Settings panel.
*   **[ ] SD Card Partition Support**: Fully test and adapt `DestructiveOrchestrator` to scan secondary external SD cards when plugged in.
*   **[ ] Auto-Wipe Panic Trigger**: Add a configurable panic trigger (e.g., after X failed biometric attempts, trigger the Nuclear Option automatically).
