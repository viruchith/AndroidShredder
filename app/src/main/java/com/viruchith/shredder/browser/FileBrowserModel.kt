package com.viruchith.shredder.browser

import com.viruchith.shredder.MainActivity.SortOrder
import com.viruchith.shredder.MainActivity.SortType
import java.io.File

/**
 * FileBrowserModel encapsulates pure, testable logic for listing, searching, and sorting files.
 */
object FileBrowserModel {

    /**
     * Filters, searches, and sorts a list of files.
     * Ensures folders/directories always appear at the top, followed by files,
     * each sorted according to the requested sort type and order.
     */
    fun processFiles(
        files: List<File>,
        searchQuery: String,
        sortType: SortType,
        sortOrder: SortOrder
    ): List<File> {
        // Filter out non-existent files
        var list = files.filter { it.exists() }

        // Filter based on search query
        if (searchQuery.isNotEmpty()) {
            list = list.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        // Sort based on type
        list = when (sortType) {
            SortType.NAME -> list.sortedBy { it.name.lowercase() }
            SortType.SIZE -> list.sortedBy { it.length() }
            SortType.DATE -> list.sortedBy { it.lastModified() }
        }

        // Reverse if descending
        if (sortOrder == SortOrder.DESC) {
            list = list.reversed()
        }

        // Directories must always appear first
        return list.sortedWith(compareBy { !it.isDirectory })
    }

    /**
     * Safely lists files within a given directory.
     * Returns an empty list if directory is missing, unreadable, or not a directory.
     */
    fun listDirContent(dir: File): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.toList() ?: emptyList()
    }
}
