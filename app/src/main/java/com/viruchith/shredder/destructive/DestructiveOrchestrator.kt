package com.viruchith.shredder.destructive

import android.os.Environment
import java.io.File

/**
 * DestructiveOrchestrator enforces safety constraints on shredding/deletion.
 * It prevents the standard shredder from wiping critical directories like external storage root,
 * /sdcard, or system folders.
 */
object DestructiveOrchestrator {

    // A list of canonical paths representing critical or root storage levels.
    private val criticalPaths: Set<String> by lazy {
        val paths = mutableSetOf<String>()
        try {
            paths.add(Environment.getExternalStorageDirectory().canonicalPath.lowercase())
            paths.add(Environment.getExternalStorageDirectory().absolutePath.lowercase())
        } catch (e: Exception) {}
        paths.add("/")
        paths
    }

    /**
     * Checks if a specific file path is considered "high risk" to shred (like the root of storage).
     */
    fun isHighRiskPath(file: File): Boolean {
        return try {
            val canonical = file.canonicalPath.lowercase()
            criticalPaths.any { canonical == it } || 
                canonical.endsWith("/android") || 
                canonical.endsWith("/android/data") ||
                canonical.endsWith("/android/obb")
        } catch (e: Exception) {
            true // Treat errors as high risk defensively
        }
    }

    /**
     * Checks if a list of files contains any high-risk paths.
     */
    fun hasHighRiskPaths(files: List<File>): Boolean {
        return files.any { isHighRiskPath(it) }
    }

    /**
     * Filters a list of selected files to extract only the safely shreddable files,
     * skipping any high-risk system or root directories.
     */
    fun filterSafelyShreddable(files: List<File>): List<File> {
        return files.filter { !isHighRiskPath(it) && it.exists() }
    }
}
