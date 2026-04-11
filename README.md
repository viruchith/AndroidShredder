# Shredder - Secure File Deletion for Android

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0)-blue.svg)](https://developer.android.com/studio/releases/platforms)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-blue.svg)](https://developer.android.com/studio/releases/platforms)
[![Language](https://img.shields.io/badge/Language-Kotlin-blueviolet.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-orange.svg)](https://developer.android.com/jetpack/compose)

Shredder is a high-security Android application designed to permanently and irrecoverably delete files and directories. It implements industry-standard multi-pass shredding algorithms to thwart data recovery even from specialized hardware forensic tools.

## 🚀 Key Features

-   **Multi-Pass Shredding**: Implements a robust 3-pass overwrite algorithm.
-   **Metadata Wiping**: Renames files to random strings and truncates them before final deletion to hide original file identities.
-   **Free Space Wiper**: Securely wipes available free space on the device storage to prevent recovery of previously deleted files.
-   **Nuclear Option**: Integrated device administrator capability to trigger a secure factory reset (wiping all data).
-   **Foreground Execution**: Uses Android Foreground Services to ensure long-running shredding tasks complete successfully even if the app is in the background.
-   **Biometric Security**: Protects high-risk operations (like the Nuclear Option) with Biometric authentication.
-   **Modern UI**: Built entirely with Jetpack Compose for a responsive, fluid user experience.

## 🛠 Technical Implementation

### Shredding Algorithm (`ShredderEngine.kt`)

The core engine implements a 3-pass overwrite strategy:

1.  **Pass 1 (Random)**: Overwrites the entire file with cryptographically strong random data using `java.security.SecureRandom`.
2.  **Pass 2 (Pattern)**: Overwrites with alternating bit patterns (`0xAA`, `0x55`) to stress test and flip bits on magnetic and flash storage cells.
3.  **Pass 3 (Random)**: A final pass of random entropy.

**I/O Optimization**:
-   Uses `RandomAccessFile` in `"rw"` mode for direct disk access.
-   Employs a **1MB buffer** to maximize throughput on modern high-speed storage.
-   Explicitly calls `FileDescriptor.sync()` after every pass to ensure the OS flushes the write buffer to physical hardware.

### Service Architecture (`ShredderService.kt`)

Shredding is an I/O intensive and potentially long-running operation. To prevent the system from killing the process:
-   The app promotes itself to a **Foreground Service**.
-   Uses `START_NOT_STICKY` to manage lifecycle efficiently.
-   Provides real-time progress updates via a persistent notification.

### Security & Permissions

-   **`MANAGE_EXTERNAL_STORAGE`**: Utilizes the Scoped Storage "All Files Access" permission (on Android 11+) to allow deep shredding across the entire user-accessible storage.
-   **Device Administration**: Registered as a Device Admin to enable the `wipeData()` system call for the "Nuclear Option".
-   **No Backups**: `android:allowBackup="false"` is set in the Manifest to prevent sensitive app states from being cached in cloud backups.

## 🏗 Build Requirements

-   **Android SDK**: 36 (target), 24 (min)
-   **Language**: Kotlin
-   **UI Framework**: Jetpack Compose
-   **Build System**: Gradle Kotlin DSL (`.kts`)

## 🛡 Production Readiness

The application is configured for production with:
-   **R8/ProGuard**: Enabled for code shrinking and obfuscation.
-   **Resource Shrinking**: Enabled to minimize APK footprint.
-   **Log Sanitization**: Verbose and debug logging is disabled in the core engine to prevent metadata leakage in `logcat`.

---
*Disclaimer: Data deleted with Shredder is IRREVERSIBLE. Use with caution.*
