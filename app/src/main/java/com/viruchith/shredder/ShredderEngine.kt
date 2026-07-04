package com.viruchith.shredder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ShredderEngine is the core logic provider for secure file deletion.
 * It implements configurable multi-pass overwrite algorithms designed to thwart data recovery
 * from magnetic and solid-state storage media.
 */
object ShredderEngine {
    private const val TAG = "ShredderEngine"
    
    // SecureRandom is used for cryptographic-strength entropy in random passes.
    private val random = SecureRandom()
    
    /**
     * ThreadPoolExecutor manages parallel shredding of multiple files.
     * Core pool size is based on available processors to maximize I/O throughput 
     * without overwhelming the system's disk scheduler.
     */
    private val executor = ThreadPoolExecutor(
        Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().availableProcessors(),
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue()
    )

    // State flows for real-time UI updates.
    val progressFlow = MutableStateFlow(0f)
    val currentPassFlow = MutableStateFlow("")
    val consoleLogFlow = MutableStateFlow<List<String>>(emptyList())
    val refreshTrigger = MutableStateFlow(0)

    // AtomicLongs ensure thread-safe progress tracking across parallel shredding tasks.
    private var totalBytesToShred = AtomicLong(0L)
    private var shreddedBytes = AtomicLong(0L)

    // The currently selected secure deletion algorithm
    var currentAlgorithm: ShredAlgorithm = ShredAlgorithm.Standard
        private set

    private var preferences: ShredderPreferences? = null

    /**
     * Initializes the ShredderEngine, loading the previously persisted algorithm selection.
     */
    fun init(context: Context) {
        val prefs = ShredderPreferences.getInstance(context)
        preferences = prefs
        currentAlgorithm = prefs.getSelectedAlgorithm()
    }

    /**
     * Updates the active shredding algorithm and persists the configuration.
     */
    fun setAlgorithm(algo: ShredAlgorithm) {
        currentAlgorithm = algo
        preferences?.setSelectedAlgorithm(algo)
    }

    /**
     * Entry point for a shredding session.
     * @param files List of files/directories to be securely deleted.
     * @param onComplete Callback invoked when all tasks in the session are finished.
     */
    fun shredFiles(files: List<File>, onComplete: () -> Unit) {
        val totalSize = calculateTotalSize(files)
        totalBytesToShred.set(totalSize)
        shreddedBytes.set(0L)
        progressFlow.value = 0f
        currentPassFlow.value = "Preparing..."
        addConsoleLog("[Started Session] Algorithm: ${currentAlgorithm.name} | Total size: ${formatSize(totalSize)}")

        if (files.isEmpty()) {
            onComplete()
            return
        }

        files.forEach { file ->
            executor.execute {
                try {
                    recursiveShred(file, mutableSetOf())
                } finally {
                    checkCompletion(onComplete)
                }
            }
        }
    }

    /**
     * Checks if all queued and active tasks in the executor have completed.
     * Increments refreshTrigger to signal the UI to refresh the file list.
     */
    private fun checkCompletion(onComplete: () -> Unit) {
        if (executor.activeCount <= 1 && executor.queue.isEmpty()) {
            progressFlow.value = 1.0f
            currentPassFlow.value = ""
            refreshTrigger.value += 1
            onComplete()
            addConsoleLog("[Session Completed]")
        }
    }

    private fun addConsoleLog(message: String) {
        val currentLogs = consoleLogFlow.value.toMutableList()
        currentLogs.add(message)
        // Keep only the last 200 entries to prevent memory bloat.
        if (currentLogs.size > 200) currentLogs.removeAt(0)
        consoleLogFlow.value = currentLogs
    }

    /**
     * Recursively calculates the total byte size of all files in a list, 
     * including contents of sub-directories. Safely handles circular symlinks and unreadable files.
     */
    fun calculateTotalSize(files: List<File>, visited: MutableSet<String> = mutableSetOf()): Long {
        var size = 0L
        files.forEach { file ->
            try {
                if (!file.exists()) return@forEach
                val canonical = file.canonicalPath
                if (visited.add(canonical)) {
                    if (file.isDirectory) {
                        val children = file.listFiles()
                        if (children != null) {
                            size += calculateTotalSize(children.toList(), visited)
                        }
                    } else {
                        size += file.length()
                    }
                }
            } catch (e: Exception) {
                // Safely handle file system edge cases
            }
        }
        return size
    }

    /**
     * Recursively counts the total number of files safely, guarding against recursion loops.
     */
    fun countFiles(files: List<File>, visited: MutableSet<String> = mutableSetOf()): Int {
        var count = 0
        files.forEach { file ->
            try {
                if (!file.exists()) return@forEach
                val canonical = file.canonicalPath
                if (visited.add(canonical)) {
                    if (file.isDirectory) {
                        val children = file.listFiles()
                        if (children != null) {
                            count += countFiles(children.toList(), visited)
                        }
                    } else {
                        count++
                    }
                }
            } catch (e: Exception) {
                // Safely handle file system edge cases
            }
        }
        return count
    }

    /**
     * Handles directory structures by shredding files first, then deleting folders.
     * Uses a canonical path visited tracking set to prevent infinite loops from symlinks.
     */
    private fun recursiveShred(file: File, visited: MutableSet<String>) {
        try {
            if (!file.exists()) {
                addConsoleLog("[Warning] Skipping non-existent file/folder: ${file.name}")
                return
            }

            val canonical = file.canonicalPath
            if (!visited.add(canonical)) {
                addConsoleLog("[Warning] Circular path or duplicate file ignored: ${file.name}")
                return
            }

            if (file.isDirectory) {
                val children = file.listFiles()
                if (children == null) {
                    addConsoleLog("[Error] Directory unreadable or access denied: ${file.name}")
                    return
                }
                children.forEach { recursiveShred(it, visited) }
                if (!file.delete()) {
                    addConsoleLog("[Error] Failed to delete directory: ${file.name}")
                }
            } else {
                secureShred(file)
            }
        } catch (e: Exception) {
            addConsoleLog("[Failed] Error shredding ${file.name}: ${e.message}")
        }
    }

    /**
     * Implements the secure shredding algorithm sequence of the active algorithm:
     * Overwrites, renames to a random string, and truncates to zero length.
     */
    private fun secureShred(file: File) {
        val fileName = file.name
        val length = file.length()
        addConsoleLog("[Started] $fileName")

        val algo = currentAlgorithm
        val totalPasses = algo.passes.size
        var currentPassIndex = 0

        try {
            if (length > 0) {
                /**
                 * Using "rw" mode for performance. "rws" or "rwd" forces synchronous 
                 * writes on every block, which is extremely slow. Instead, we use 
                 * manual raf.fd.sync() at the end of each pass to ensure data is 
                 * actually flushed to physical storage.
                 */
                RandomAccessFile(file, "rw").use { raf ->
                    algo.passes.forEachIndexed { index, pass ->
                        currentPassIndex = index
                        val passIndex = index + 1
                        currentPassFlow.value = "Pass $passIndex of $totalPasses (${pass.label})"
                        addConsoleLog("[Pass $passIndex/$totalPasses | ${pass.label}] $fileName")
                        performPass(raf, length, pass)
                        raf.fd.sync()
                    }
                }
            } else {
                updateProgress(0) 
            }

            // Metadata Wiping: Rename to random string to hide original file name.
            val randomName = (1..16).map { (('a'..'z') + ('A'..'Z') + ('0'..'9')).random() }.joinToString("")
            val renamedFile = File(file.parent, randomName)
            if (file.renameTo(renamedFile)) {
                // Truncate file to release allocated disk blocks.
                RandomAccessFile(renamedFile, "rw").use { it.setLength(0) }
                if (!renamedFile.delete()) {
                    renamedFile.deleteOnExit()
                }
            } else {
                if (!file.delete()) {
                    file.deleteOnExit()
                }
            }

            addConsoleLog("[Deleted] $fileName")
        } catch (e: Exception) {
            addConsoleLog("[Failed] $fileName: ${e.message}")
            // Compensate progress on failure so the progress bar reaches 100%.
            val remaining = (length * totalPasses) - (length * currentPassIndex)
            updateProgress(maxOf(0, remaining))
            if (!file.delete()) file.deleteOnExit()
        }
    }

    /**
     * Overwrites the file content with the specified pattern or random data.
     * Uses a 1MB buffer to optimize throughput for large files.
     */
    private fun performPass(raf: RandomAccessFile, length: Long, pass: ShredPass) {
        raf.seek(0)
        val buffer = ByteArray(1024 * 1024) 
        var written = 0L
        while (written < length) {
            val toWrite = minOf(buffer.size.toLong(), length - written).toInt()
            when (val type = pass.type) {
                is PassType.RANDOM -> {
                    random.nextBytes(buffer)
                }
                is PassType.ZEROS -> {
                    buffer.fill(0)
                }
                is PassType.ONES -> {
                    buffer.fill(0xFF.toByte())
                }
                is PassType.PATTERN -> {
                    val patternBytes = type.bytes
                    for (i in 0 until toWrite) {
                        buffer[i] = patternBytes[(written + i).toInt() % patternBytes.size]
                    }
                }
            }
            raf.write(buffer, 0, toWrite)
            written += toWrite
            updateProgress(toWrite.toLong())
        }
    }

    private fun updateProgress(bytes: Long) {
        val currentShredded = shreddedBytes.addAndGet(bytes)
        val total = totalBytesToShred.get()
        if (total > 0) {
            val totalPasses = currentAlgorithm.passes.size
            progressFlow.value = minOf(1.0f, currentShredded.toFloat() / (total * totalPasses))
        }
    }

    /**
     * Wipes all available free space on the partition by creating a temporary file 
     * and filling it until the storage is exhausted (ENOSPC).
     * Employs a single pass of the currently selected algorithm's first pass type.
     */
    fun wipeFreeSpace(storageDir: File) {
        addConsoleLog("[Wiping Free Space]")
        val wipeFile = File(storageDir, "wipe_free_space_${System.currentTimeMillis()}.tmp")
        try {
            val firstPass = currentAlgorithm.passes.firstOrNull() ?: ShredPass(PassType.RANDOM, "Random entropy")
            RandomAccessFile(wipeFile, "rw").use { raf ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    when (val type = firstPass.type) {
                        is PassType.RANDOM -> {
                            random.nextBytes(buffer)
                        }
                        is PassType.ZEROS -> {
                            buffer.fill(0)
                        }
                        is PassType.ONES -> {
                            buffer.fill(0xFF.toByte())
                        }
                        is PassType.PATTERN -> {
                            val patternBytes = type.bytes
                            for (i in buffer.indices) {
                                buffer[i] = patternBytes[i % patternBytes.size]
                            }
                        }
                    }
                    raf.write(buffer)
                }
            }
        } catch (e: java.io.IOException) {
            // Expected when storage is full (ENOSPC).
        } finally {
            wipeFile.delete()
            addConsoleLog("[Free Space Wiped]")
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }
}
