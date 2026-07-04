package com.viruchith.shredder.browser

import java.io.File

/**
 * FileSelectionLogic isolates file-selection and state mutation rules
 * from Jetpack Compose UI state management.
 */
object FileSelectionLogic {

    /**
     * Toggles a file's selection state within a list of selected files.
     */
    fun toggleSelection(selectedFiles: List<File>, file: File): List<File> {
        return if (selectedFiles.contains(file)) {
            selectedFiles.filter { it != file }
        } else {
            selectedFiles + file
        }
    }

    /**
     * Selects all current files, adding them to the selected list if not already present.
     */
    fun selectAll(selectedFiles: List<File>, currentFiles: List<File>): List<File> {
        val mutable = selectedFiles.toMutableList()
        currentFiles.forEach { file ->
            if (!mutable.contains(file)) {
                mutable.add(file)
            }
        }
        return mutable
    }

    /**
     * Deselects all files present in the current view from the selected list.
     */
    fun deselectAll(selectedFiles: List<File>, currentFiles: List<File>): List<File> {
        return selectedFiles.filter { !currentFiles.contains(it) }
    }

    /**
     * Checks if all files in the current directory listing are selected.
     */
    fun isAllSelected(selectedFiles: List<File>, currentFiles: List<File>): Boolean {
        if (currentFiles.isEmpty()) return false
        return currentFiles.all { selectedFiles.contains(it) }
    }
}
