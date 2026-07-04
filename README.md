# Shredder - Secure File Deletion & Device Wiper for Android

[![Version](https://img.shields.io/badge/Version-1.2-blue.svg)](https://github.com/viruchith/shredder/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0)-blue.svg)](https://developer.android.com/studio/releases/platforms)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-blue.svg)](https://developer.android.com/studio/releases/platforms)
[![Language](https://img.shields.io/badge/Language-Kotlin-blueviolet.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-orange.svg)](https://developer.android.com/jetpack/compose)

Shredder is a high-security Android application designed to safely delete files, folders, and wipe unused space. It provides a highly defensive security posture by gating access behind device locks and biometric credentials, shielding critical system files from accidental shredding, and offering a dual-layered "Nuclear Option" (Full Factory Reset).

---

## 🎯 Threat Model

Shredder is engineered to mitigate specific security threats while operating under the sandboxed constraints of the Android platform:

*   **Threat 1: Device Loss / Physical Theft**
    *   *Mitigation*: Shredder gates access app-wide. If an attacker gains physical possession of an unlocked phone, they cannot open Shredder to inspect sensitive hidden files or run destructive actions without a secondary biometric/PIN challenge.
*   **Threat 2: Post-Deletion Forensic Recovery**
    *   *Mitigation*: Shredder overwrites target blocks with pseudo-random and structured bit-patterns prior to unlinking. This prevents basic undelete tools and forensic software from recovering file content from free blocks.
*   **Threat 3: Metadata / Identity Leakage**
    *   *Mitigation*: Before deletion, files are renamed to random string identifiers and truncated to `0` bytes, erasing trace elements of the original filenames and lengths.
*   **Threat 4: Coerced Device Wipe (Emergency)**
    *   *Mitigation*: The "Nuclear Option" is available to immediately initiate a cryptographic wipe and factory reset if the device's physical security is compromised.

---

## ⚠️ Limitations of Modern Storage (eMMC / UFS / SSD)

Unlike old magnetic platters where bits were directly overwritten on physical tracks, modern smartphones use **Flash-based Solid-State Storage (eMMC/UFS)** which operates under fundamentally different mechanics:

1.  **Flash Translation Layer (FTL)**: Modern storage chips utilize an FTL to map logical blocks to physical NAND flash cells. When a file is overwritten, the storage controller writes the new data to a *different* physical block (out-of-place writes) and marks the old block as "dirty" to be garbage-collected later.
2.  **Wear Leveling**: To prolong storage life, the controller distributes write operations evenly across cells. An in-place overwrite is not physically guaranteed.
3.  **Over-Provisioning**: Storage chips maintain hidden sectors for bad block replacement. Sensitive data might reside in these hidden sectors, unreachable by standard file-system APIs.

### How Shredder Mitigates This
*   **Free Space Wiper**: Because individual file overwriting can leave orphaned "dirty" blocks in free space, Shredder provides a **Free Space Wiper**. This creates a continuous stream of random files filling the storage partition to its limits (triggering `ENOSPC`). This forces the FTL to garbage-collect and overwrite all unallocated blocks, neutralizing leaked data fragments.
*   **Hardware Encryption**: Modern Android devices use File-Based Encryption (FBE). For absolute secure deletion of the entire chip, the **Nuclear Option (Factory Reset)** discards the device's master encryption keys, rendering all physical flash sectors permanently unreadable instant-by-instant.

---

## 🔒 Hardened Security Gateways

1.  **Strict Security Posture Enforcement**
    *   If no lock screen credentials (PIN, Pattern, Password, or Biometrics) are configured, Shredder **blocks application access**. Instead of bypassing or warning via a toast, the app displays a full-screen block and guides the user to System Settings to secure their device first.
2.  **Accidental Destruction Safeguards**
    *   The standard file shredder incorporates path analysis via `DestructiveOrchestrator` to automatically block the shredding of critical system folders (like `/`, `/storage/emulated/0`, `/sdcard`, `/Android/data`, and `/Android/obb`).
3.  **Dual-Layered Nuclear Option**
    *   To prevent accidental triggers, clicking the Nuclear Option does *not* immediately launch authentication. Instead, it prompts a high-visibility warning dialog requiring the user to explicitly type the phrase **"NUCLEAR WIPE"** in a text field. Once typed, the user must then pass a biometric/device credential authentication check before the reset service starts.

---

## 🚀 Key Features

*   **Configurable Multi-Pass Shredding**: Implements multiple secure, industry-standard overwrite algorithms with explicit manual `fsync` flushing.
*   **Recursion Guards**: The counting and shredding engines track canonical paths to prevent infinite loops from circular symlinks.
*   **Real-time Log Console**: Fully collapsible and memory-sanitized console in the UI. Absolute file paths are never printed to system `logcat` to avoid metadata leakage.
*   **Foreground Service Execution**: Runs shredding operations in a Foreground Service to prevent the OS from killing the process during intensive disk operations.

---

## 🎛 Supported Shredding Algorithms

Users can customize the secure overwrite sequence to balance performance and security needs:

1.  **Fast (1-pass)**
    *   *Sequence*: 1 × Cryptographically strong Random entropy.
    *   *Best use*: Recommended for Solid-State Drives (SSDs) and modern flash storage where block-level wear-leveling makes multi-pass schemes less useful.
2.  **Standard (3-pass)** *(Default)*
    *   *Sequence*: Random -> Alternating Bit Patterns (`0xAA`/`0x55`) -> Random.
    *   *Best use*: Excellent, general-purpose multi-pass balance.
3.  **DoD 5220.22-M (7-pass)**
    *   *Sequence*: Zeros (`0x00`) -> Ones (`0xFF`) -> Random -> Pattern `0x96` -> Zeros (`0x00`) -> Ones (`0xFF`) -> Random.
    *   *Best use*: Fully compliant with the United States Department of Defense specifications for secure overwrite media sanitization.
4.  **Schneier (7-pass)**
    *   *Sequence*: Zeros (`0x00`) -> Ones (`0xFF`) -> 5 × Random passes.
    *   *Best use*: Bruce Schneier's highly acclaimed algorithm combining block boundary limits with high-entropy cryptographic randomization.
5.  **Gutmann (35-pass)**
    *   *Sequence*: 4 × Random -> 27 structured alternating pattern passes targeting raw magnetic sector encoding -> 4 × Random.
    *   *Best use*: Legacy sanitization standard originally formulated for magnetic disk media. *Warning: Extremely slow on large files due to extensive I/O passes.*

---

## ⚙️ Settings & Customization

A dedicated **Settings Screen** is accessible from the top toolbar's gear button, allowing users to:
*   **Select active algorithms**: Instantly switch between Fast, Standard, DoD, Schneier, and Gutmann algorithms.
*   **Gutmann Warning**: A prominent warning banner is displayed next to the Gutmann option to alert users of the performance penalty (⚠️ *Extremely slow — 35 passes*).
*   **Preferences Persistency**: Selected algorithms are persisted across application restarts using the device's standard private `SharedPreferences` via the `ShredderPreferences` helper.
*   **Dynamic Progress Feedback**: The progress label dynamically transitions from percentage to real-time `Pass X of Y (Pass Label)` info tracking based on the selected algorithm's length.

---

## 🛡 Permission Details

*   **`MANAGE_EXTERNAL_STORAGE` (All Files Access)**: Required on Android 11+ to browse and securely shred user-selected files on shared storage.
*   **`WRITE_EXTERNAL_STORAGE`**: Legacy permission required on Android 10 and lower.
*   **`POST_NOTIFICATIONS`**: Required on Android 13+ to display the ongoing shredding progress notification.
*   **Device Administrator (`DevicePolicyManager`)**: Required to obtain privileges for the `wipeData(0)` API to execute the Nuclear Option.

---

## 🛠 Setup & Usage Instructions

### 1. Enable Device Lock
Ensure your phone has a secure PIN, pattern, password, or biometrics configured.

### 2. Grant Storage Permissions
On start, the app will request the necessary storage permissions.
*   *Android 11+*: Grant the "All Files Access" setting when prompted.
*   *Android 10-*: Accept the standard runtime permission.

### 3. Customize Shredding Algorithm
1.  Tap the **Settings (Gear)** icon in the top toolbar.
2.  Review and select your preferred secure erasure algorithm.

### 4. (Optional) Enable Nuclear Option (Factory Reset)
1.  Tap the **Device Admin (Shield)** icon in the top toolbar.
2.  Follow the system prompt to activate Secure Shredder as a **Device Administrator**.

### 5. Shredding Files
1.  Navigate the directory tree using the built-in file explorer.
2.  Select files and folders using the checkboxes.
3.  Tap the **Trash Icon** to review total size/count, then confirm to shred.

---

## 🏗 Build Requirements

*   **Android SDK**: targetSdk 36, minSdk 24
*   **Language**: Kotlin 2.2.10
*   **UI Framework**: Jetpack Compose
*   **Build System**: Gradle Kotlin DSL (`.kts`)

---

## 🆕 What's New (v1.2)

*   **Configurable Overwrite Algorithms**: Choose from Fast (1-pass), Standard (3-pass), DoD 5220.22-M (7-pass), Bruce Schneier (7-pass), and Peter Gutmann (35-pass) algorithms.
*   **Hardened Danger Zone in Settings**: Removed the highly destructive "Nuclear Option" and "Device Admin" buttons from the main screen's top app bar to prevent accidental triggers. They are now cleanly isolated within a dedicated, red-bordered "Danger Zone" card at the bottom of the Settings screen.
*   **State-Preserving Navigation**: Replaced inline screen-switching logic with a unified, type-safe `AppScreen` enum-driven state. This guarantees that your current directory level, file selections, and search/sort queries survive transitions to the Settings or About screens and back.
*   **Fully Clickable About Screen Links**: Wrapped both the labels (e.g. "Website:") and URLs in unified clickable rows with underlined styling and primary color highlights, increasing click target accessibility.
*   **Contextual Back-Button Interception**: Registered a standard Compose `BackHandler` that contextually intercepts the system back button—navigating up parent directories in the file browser or returning to the browser from secondary screens—without abruptly exiting the app unless you are at the storage root.
*   **Dead Code Cleanup**: Eliminated unused legacy `Shredder.kt` file for better project maintainability.

*Disclaimer: Data deleted with Shredder is IRREVERSIBLE. Use with caution.*
