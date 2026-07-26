package com.viruchith.shredder.browser

import java.io.File

/**
 * FileSelectionLogic isolates file-selection and state mutation rules
 * from Jetpack Compose UI state management.
 * Uses Set-based lookups internally for O(1) containment checks.
 */
object FileSelectionLogic {

    /**
     * Toggles a file's selection state within a list of selected files.
     */
    fun toggleSelection(selectedFiles: List<File>, file: File): List<File> {
        val selectedSet = selectedFiles.toHashSet()
        return if (selectedSet.contains(file)) {
            selectedFiles.filter { it != file }
        } else {
            selectedFiles + file
        }
    }

    /**
     * Selects all current files, adding them to the selected list if not already present.
     */
    fun selectAll(selectedFiles: List<File>, currentFiles: List<File>): List<File> {
        val selectedSet = selectedFiles.toHashSet()
        val mutable = selectedFiles.toMutableList()
        currentFiles.forEach { file ->
            if (selectedSet.add(file)) {
                mutable.add(file)
            }
        }
        return mutable
    }

    /**
     * Deselects all files present in the current view from the selected list.
     */
    fun deselectAll(selectedFiles: List<File>, currentFiles: List<File>): List<File> {
        val currentSet = currentFiles.toHashSet()
        return selectedFiles.filter { !currentSet.contains(it) }
    }

    /**
     * Checks if all files in the current directory listing are selected.
     */
    fun isAllSelected(selectedFiles: List<File>, currentFiles: List<File>): Boolean {
        if (currentFiles.isEmpty()) return false
        val selectedSet = selectedFiles.toHashSet()
        return currentFiles.all { selectedSet.contains(it) }
    }
}
