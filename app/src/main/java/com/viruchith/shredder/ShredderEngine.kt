package com.viruchith.shredder

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ShredderEngine is the core logic provider for secure file deletion.
 * It implements a multi-pass overwrite algorithm designed to thwart data recovery
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
    val consoleLogFlow = MutableStateFlow<List<String>>(emptyList())
    val refreshTrigger = MutableStateFlow(0)

    // AtomicLongs ensure thread-safe progress tracking across parallel shredding tasks.
    private var totalBytesToShred = AtomicLong(0L)
    private var shreddedBytes = AtomicLong(0L)

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
        addConsoleLog("[Started Session] Total size: ${formatSize(totalSize)}")

        if (files.isEmpty()) {
            onComplete()
            return
        }

        files.forEach { file ->
            executor.execute {
                try {
                    recursiveShred(file)
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
     * including contents of sub-directories.
     */
    fun calculateTotalSize(files: List<File>): Long {
        var size = 0L
        files.forEach { file ->
            if (file.isDirectory) {
                size += calculateTotalSize(file.listFiles()?.toList() ?: emptyList())
            } else {
                size += file.length()
            }
        }
        return size
    }

    /**
     * Recursively counts the total number of files.
     */
    fun countFiles(files: List<File>): Int {
        var count = 0
        files.forEach { file ->
            if (file.isDirectory) {
                count += countFiles(file.listFiles()?.toList() ?: emptyList())
            } else {
                count++
            }
        }
        return count
    }

    /**
     * Handles directory structures by shredding files first, then deleting folders.
     */
    private fun recursiveShred(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { recursiveShred(it) }
            if (!file.delete()) {
                // Log.e(TAG, "Failed to delete directory: ${file.absolutePath}")
            }
        } else {
            secureShred(file)
        }
    }

    /**
     * Implements a 3-pass secure shredding algorithm:
     * Pass 1: Cryptographically strong random data.
     * Pass 2: Alternating bit patterns (0xAA, 0x55) to stress test magnetic storage cells.
     * Pass 3: Final random data pass.
     * 
     * After overwriting, the file is renamed to a random string and truncated to 
     * zero length before final deletion to wipe metadata.
     */
    private fun secureShred(file: File) {
        val fileName = file.name
        val length = file.length()
        // Log.v(TAG, "Starting shred: $fileName (Size: $length, Thread: ${Thread.currentThread().id})")
        addConsoleLog("[Started] $fileName")

        try {
            if (length > 0) {
                /**
                 * Using "rw" mode for performance. "rws" or "rwd" forces synchronous 
                 * writes on every block, which is extremely slow. Instead, we use 
                 * manual raf.fd.sync() at the end of each pass to ensure data is 
                 * actually flushed to physical storage.
                 */
                RandomAccessFile(file, "rw").use { raf ->
                    // Pass 1: Random
                    addConsoleLog("[Pass 1/3] $fileName")
                    performPass(raf, length, null)
                    raf.fd.sync()

                    // Pass 2: AA/55 patterns
                    addConsoleLog("[Pass 2/3] $fileName")
                    performPass(raf, length, byteArrayOf(0xAA.toByte(), 0x55.toByte()))
                    raf.fd.sync()

                    // Pass 3: Final Random
                    addConsoleLog("[Pass 3/3] $fileName")
                    performPass(raf, length, null)
                    raf.fd.sync()
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
                    // Log.e(TAG, "Failed to delete renamed file: ${renamedFile.absolutePath}")
                    renamedFile.deleteOnExit()
                }
            } else {
                if (!file.delete()) {
                    // Log.e(TAG, "Failed to delete file: ${file.absolutePath}")
                    file.deleteOnExit()
                }
            }

            addConsoleLog("[Deleted] $fileName")
            // Log.v(TAG, "Completed shred: $fileName")
        } catch (e: Exception) {
            // Log.e(TAG, "Error shredding $fileName", e)
            addConsoleLog("[Failed] $fileName: ${e.message}")
            // Compensate progress on failure so the progress bar reaches 100%.
            val remaining = (length * 3) - (length * getCurrentPass(e))
            updateProgress(maxOf(0, remaining))
            if (!file.delete()) file.deleteOnExit()
        }
    }

    private fun getCurrentPass(e: Exception): Int {
        val msg = e.message ?: ""
        return when {
            msg.contains("Pass 1") -> 0
            msg.contains("Pass 2") -> 1
            msg.contains("Pass 3") -> 2
            else -> 0
        }
    }

    /**
     * Overwrites the file content with the specified pattern or random data.
     * Uses a 1MB buffer to optimize throughput for large files.
     */
    private fun performPass(raf: RandomAccessFile, length: Long, pattern: ByteArray?) {
        raf.seek(0)
        val buffer = ByteArray(1024 * 1024) 
        var written = 0L
        while (written < length) {
            val toWrite = minOf(buffer.size.toLong(), length - written).toInt()
            if (pattern != null) {
                // Fill buffer with alternating pattern.
                for (i in 0 until toWrite) {
                    buffer[i] = pattern[(written + i).toInt() % pattern.size]
                }
            } else {
                // Fill buffer with random entropy.
                random.nextBytes(buffer)
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
            // total * 3 because we do 3 passes.
            progressFlow.value = minOf(1.0f, currentShredded.toFloat() / (total * 3))
        }
    }

    /**
     * Wipes all available free space on the partition by creating a temporary file 
     * and filling it until the storage is exhausted (ENOSPC).
     */
    fun wipeFreeSpace(storageDir: File) {
        addConsoleLog("[Wiping Free Space]")
        val wipeFile = File(storageDir, "wipe_free_space_${System.currentTimeMillis()}.tmp")
        try {
            RandomAccessFile(wipeFile, "rw").use { raf ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    random.nextBytes(buffer)
                    raf.write(buffer)
                }
            }
        } catch (e: java.io.IOException) {
            // Expected when storage is full.
            // Log.d(TAG, "Free space wipe completed (Disk Full)")
        } finally {
            wipeFile.delete()
            addConsoleLog("[Free Space Wiped]")
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
