package com.viruchith.shredder.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * PermissionOrchestrator centralizes checks for storage permissions and notification permissions,
 * separating OS-specific API checks from UI code.
 */
class PermissionOrchestrator(private val context: Context) {

    /**
     * Checks whether the app is granted permissions to read and write/shred files on external storage.
     */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks whether the app is granted notification permissions (required for Foreground Services in Android 13+).
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Checks if all required permissions are fully granted.
     */
    fun hasAllPermissions(): Boolean {
        return hasStoragePermission() && hasNotificationPermission()
    }
}
