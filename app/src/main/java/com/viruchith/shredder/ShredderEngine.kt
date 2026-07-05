package com.viruchith.shredder

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.pow

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
    val freeSpaceWipeStateFlow = MutableStateFlow(FreeSpaceWipeState())
    val freeSpaceWipeProgressFlow = MutableStateFlow(0f)
    val freeSpaceWipeStatusFlow = MutableStateFlow("")
    val freeSpaceWipeLogFlow = MutableStateFlow<List<String>>(emptyList())
    val destructiveOperationFlow = MutableStateFlow<String?>(null)

    // AtomicLongs ensure thread-safe progress tracking across parallel shredding tasks.
    private var totalBytesToShred = AtomicLong(0L)
    private var shreddedBytes = AtomicLong(0L)
    private val cancelTokens = ConcurrentHashMap<String, Boolean>()
    private val operationLock = Any()

    // The currently selected secure deletion algorithm
    var currentAlgorithm: ShredAlgorithm = ShredAlgorithm.Standard
        private set

    private var preferences: ShredderPreferences? = null

    private const val WIPE_BUFFER_BYTES = 1024 * 1024
    private const val WIPE_CHUNK_BYTES = 512L * 1024L * 1024L
    private const val WIPE_SYNC_INTERVAL_BYTES = 16L * 1024L * 1024L

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
        if (!tryAcquireOperation("FILE_SHRED")) {
            addConsoleLog("[Blocked] Another destructive operation is already running.")
            onComplete()
            return
        }

        val totalSize = calculateTotalSize(files)
        totalBytesToShred.set(totalSize)
        shreddedBytes.set(0L)
        progressFlow.value = 0f
        currentPassFlow.value = "Preparing..."
        addConsoleLog(
            "[Started Session] Algorithm: ${currentAlgorithm.name} | Total size: ${
                formatSize(
                    totalSize
                )
            }"
        )

        if (files.isEmpty()) {
            releaseOperation("FILE_SHRED")
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
            releaseOperation("FILE_SHRED")
            onComplete()
            addConsoleLog("[Session Completed]")
        }
    }

    fun isAnyDestructiveOperationRunning(): Boolean = destructiveOperationFlow.value != null

    fun requestCancelFreeSpaceWipe(sessionId: String): Boolean {
        if (!cancelTokens.containsKey(sessionId)) return false
        cancelTokens[sessionId] = true
        addFreeSpaceWipeLog("[session=$sessionId] Cancellation requested by user.")
        return true
    }

    fun cancelFreeSpaceWipe(sessionId: String) {
        cancelTokens[sessionId] = true
    }

    fun newSessionId(): String = UUID.randomUUID().toString()

    fun computeFreeSpaceWipeProgress(writtenBytes: Long, estimatedFreeBytes: Long): Float {
        if (estimatedFreeBytes <= 0L) return 0f
        val ratio = writtenBytes.toDouble() / estimatedFreeBytes.toDouble()
        return minOf(0.99f, ratio.toFloat())
    }

    fun wipeFreeSpaceSafely(
        wipeDir: File,
        sessionId: String,
        onProgress: (bytesWritten: Long, totalEstimated: Long) -> Unit,
        onComplete: (FreeSpaceWipeResult) -> Unit
    ) {
        val operationId = "FREE_SPACE_WIPE_ONLY:$sessionId"
        if (!tryAcquireOperation(operationId)) {
            val reason = "Another destructive operation is already running."
            updateFreeSpaceState(
                sessionId = sessionId,
                status = FreeSpaceWipeStatus.FAILED,
                writtenBytes = 0L,
                estimatedFreeBytes = 0L,
                progress = 0f,
                message = reason
            )
            onComplete(FreeSpaceWipeResult.Failed(sessionId, 0L, 0L, reason))
            return
        }

        cancelTokens[sessionId] = false
        if (!wipeDir.exists() && !wipeDir.mkdirs()) {
            val reason = "Unable to create wipe directory."
            updateFreeSpaceState(
                sessionId = sessionId,
                status = FreeSpaceWipeStatus.FAILED,
                writtenBytes = 0L,
                estimatedFreeBytes = 0L,
                progress = 0f,
                message = "Failed: $reason"
            )
            onComplete(FreeSpaceWipeResult.Failed(sessionId, 0L, 0L, reason))
            releaseOperation(operationId)
            cancelTokens.remove(sessionId)
            return
        }

        val estimatedFreeBytes = try {
            StatFs(wipeDir.absolutePath).availableBytes.coerceAtLeast(1L)
        } catch (_: Exception) {
            wipeDir.usableSpace.coerceAtLeast(1L)
        }

        updateFreeSpaceState(
            sessionId = sessionId,
            status = FreeSpaceWipeStatus.WRITING,
            writtenBytes = 0L,
            estimatedFreeBytes = estimatedFreeBytes,
            progress = 0f,
            message = "Writing..."
        )
        freeSpaceWipeStatusFlow.value = "Writing... 0 B"
        addFreeSpaceWipeLog("[session=$sessionId] Started. Estimated free bytes: $estimatedFreeBytes")

        executor.execute {
            val writtenBytes = AtomicLong(0L)
            var completedByEnospc = false
            var failure: Throwable? = null
            val sessionDir = File(wipeDir, sessionId)

            try {
                ensureWipeSessionDir(wipeDir, sessionDir)
                val pass = currentAlgorithm.passes.firstOrNull() ?: ShredPass(
                    PassType.RANDOM,
                    "Random entropy"
                )
                var chunkIndex = 0
                var shouldStop = false

                while (!shouldStop) {
                    if (isCancelRequested(sessionId)) break

                    val chunkFile = File(sessionDir, "wipe_${chunkIndex++}.bin")
                    ensurePathWithin(sessionDir, chunkFile)

                    try {
                        RandomAccessFile(chunkFile, "rw").use { raf ->
                            val buffer = ByteArray(WIPE_BUFFER_BYTES)
                            var chunkWritten = 0L

                            while (chunkWritten < WIPE_CHUNK_BYTES) {
                                if (isCancelRequested(sessionId)) {
                                    shouldStop = true
                                    break
                                }

                                val toWrite = minOf(
                                    buffer.size.toLong(),
                                    WIPE_CHUNK_BYTES - chunkWritten
                                ).toInt()
                                fillBufferForPass(
                                    buffer,
                                    toWrite,
                                    pass,
                                    writtenBytes.get() + chunkWritten
                                )
                                raf.write(buffer, 0, toWrite)

                                chunkWritten += toWrite
                                val totalWritten = writtenBytes.addAndGet(toWrite.toLong())

                                if (totalWritten % WIPE_SYNC_INTERVAL_BYTES < toWrite) {
                                    raf.fd.sync()
                                }

                                val progress =
                                    computeFreeSpaceWipeProgress(totalWritten, estimatedFreeBytes)
                                freeSpaceWipeProgressFlow.value = progress
                                freeSpaceWipeStatusFlow.value =
                                    "Writing... ${formatSize(totalWritten)}"
                                onProgress(totalWritten, estimatedFreeBytes)
                                updateFreeSpaceState(
                                    sessionId = sessionId,
                                    status = FreeSpaceWipeStatus.WRITING,
                                    writtenBytes = totalWritten,
                                    estimatedFreeBytes = estimatedFreeBytes,
                                    progress = progress,
                                    message = "Writing... ${formatSize(totalWritten)}"
                                )
                            }

                            raf.fd.sync()
                        }
                    } catch (io: IOException) {
                        if (isNoSpaceLeft(io)) {
                            completedByEnospc = true
                            shouldStop = true
                        } else {
                            throw io
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t
            }

            updateFreeSpaceState(
                sessionId = sessionId,
                status = FreeSpaceWipeStatus.CLEANING_UP,
                writtenBytes = writtenBytes.get(),
                estimatedFreeBytes = estimatedFreeBytes,
                progress = freeSpaceWipeProgressFlow.value,
                message = "Cleaning up..."
            )
            freeSpaceWipeStatusFlow.value = "Cleaning up..."

            val cleanupErrors = cleanupWipeArtifacts(sessionDir)

            val cancelled = isCancelRequested(sessionId)
            val finalFailure = failure ?: cleanupErrors.firstOrNull()
            val result = when {
                finalFailure != null -> {
                    val msg = finalFailure.message ?: "Unknown error"
                    updateFreeSpaceState(
                        sessionId = sessionId,
                        status = FreeSpaceWipeStatus.FAILED,
                        writtenBytes = writtenBytes.get(),
                        estimatedFreeBytes = estimatedFreeBytes,
                        progress = freeSpaceWipeProgressFlow.value,
                        message = "Failed: $msg"
                    )
                    freeSpaceWipeStatusFlow.value = "Failed: $msg"
                    addFreeSpaceWipeLog("[session=$sessionId] Failed: $msg")
                    FreeSpaceWipeResult.Failed(
                        sessionId,
                        writtenBytes.get(),
                        estimatedFreeBytes,
                        msg
                    )
                }

                cancelled -> {
                    updateFreeSpaceState(
                        sessionId = sessionId,
                        status = FreeSpaceWipeStatus.CANCELLED,
                        writtenBytes = writtenBytes.get(),
                        estimatedFreeBytes = estimatedFreeBytes,
                        progress = freeSpaceWipeProgressFlow.value,
                        message = "Cancelled"
                    )
                    freeSpaceWipeStatusFlow.value = "Cancelled"
                    addFreeSpaceWipeLog("[session=$sessionId] Cancelled after writing ${writtenBytes.get()} bytes.")
                    FreeSpaceWipeResult.Cancelled(sessionId, writtenBytes.get(), estimatedFreeBytes)
                }

                completedByEnospc || writtenBytes.get() > 0L -> {
                    updateFreeSpaceState(
                        sessionId = sessionId,
                        status = FreeSpaceWipeStatus.COMPLETED,
                        writtenBytes = writtenBytes.get(),
                        estimatedFreeBytes = estimatedFreeBytes,
                        progress = 1.0f,
                        message = "Completed"
                    )
                    freeSpaceWipeProgressFlow.value = 1.0f
                    freeSpaceWipeStatusFlow.value = "Completed"
                    addFreeSpaceWipeLog("[session=$sessionId] Completed. Written bytes: ${writtenBytes.get()}")
                    FreeSpaceWipeResult.Completed(sessionId, writtenBytes.get(), estimatedFreeBytes)
                }

                else -> {
                    updateFreeSpaceState(
                        sessionId = sessionId,
                        status = FreeSpaceWipeStatus.FAILED,
                        writtenBytes = writtenBytes.get(),
                        estimatedFreeBytes = estimatedFreeBytes,
                        progress = freeSpaceWipeProgressFlow.value,
                        message = "Failed: No bytes were written"
                    )
                    freeSpaceWipeStatusFlow.value = "Failed: No bytes were written"
                    val msg = "No bytes were written before termination."
                    addFreeSpaceWipeLog("[session=$sessionId] Failed: $msg")
                    FreeSpaceWipeResult.Failed(
                        sessionId,
                        writtenBytes.get(),
                        estimatedFreeBytes,
                        msg
                    )
                }
            }

            cancelTokens.remove(sessionId)
            releaseOperation(operationId)
            onComplete(result)
        }
    }

    fun wipeFreeSpaceSafely(
        storageDir: File,
        sessionId: String,
        onComplete: (FreeSpaceWipeResult) -> Unit
    ) {
        wipeFreeSpaceSafely(
            wipeDir = storageDir,
            sessionId = sessionId,
            onProgress = { _, _ -> },
            onComplete = onComplete
        )
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
            val randomName =
                (1..16).map { (('a'..'z') + ('A'..'Z') + ('0'..'9')).random() }.joinToString("")
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

    private fun fillBufferForPass(buffer: ByteArray, toWrite: Int, pass: ShredPass, offset: Long) {
        when (val type = pass.type) {
            is PassType.RANDOM -> random.nextBytes(buffer)
            is PassType.ZEROS -> buffer.fill(0)
            is PassType.ONES -> buffer.fill(0xFF.toByte())
            is PassType.PATTERN -> {
                val patternBytes = type.bytes
                for (i in 0 until toWrite) {
                    buffer[i] = patternBytes[((offset + i) % patternBytes.size).toInt()]
                }
            }
        }
    }

    private fun secureDeleteTempFile(file: File) {
        if (!file.exists() || file.isDirectory) return

        val randomName = (1..16)
            .map { (('a'..'z') + ('A'..'Z') + ('0'..'9')).random() }
            .joinToString("")

        val parentDir = file.parentFile
        val renamedFile = if (parentDir != null) File(parentDir, randomName) else file

        val targetFile = if (file.renameTo(renamedFile)) renamedFile else file
        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.setLength(0)
            raf.fd.sync()
        }
        if (!targetFile.delete()) {
            targetFile.deleteOnExit()
        }
    }

    private fun cleanupWipeArtifacts(wipeDir: File): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        if (!wipeDir.exists()) return failures

        val children = wipeDir.listFiles() ?: emptyArray()
        children.forEach { child ->
            try {
                ensurePathWithin(wipeDir, child)
                if (child.isDirectory) {
                    failures.addAll(cleanupWipeArtifacts(child))
                    if (child.exists() && !child.delete()) {
                        addFreeSpaceWipeLog("[Warning] Temporary directory cleanup failed.")
                    }
                } else {
                    secureDeleteTempFile(child)
                }
            } catch (t: Throwable) {
                failures.add(t)
                addFreeSpaceWipeLog("[Warning] Temporary wipe artifact cleanup failed.")
            }
        }

        if (wipeDir.exists() && !wipeDir.delete()) {
            addFreeSpaceWipeLog("[Warning] Wipe session directory cleanup incomplete.")
        }

        return failures
    }

    private fun ensureWipeSessionDir(expectedBase: File, sessionDir: File) {
        if (!expectedBase.exists() && !expectedBase.mkdirs()) {
            throw IOException("Unable to create wipe base directory.")
        }
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            throw IOException("Unable to create wipe session directory.")
        }
        ensurePathWithin(expectedBase, sessionDir)
    }

    private fun ensurePathWithin(baseDir: File, target: File) {
        val baseCanonical = baseDir.canonicalFile
        val targetCanonical = target.canonicalFile
        val within = targetCanonical.path == baseCanonical.path ||
                targetCanonical.path.startsWith(baseCanonical.path + File.separator)
        if (!within) {
            val sessionId = freeSpaceWipeStateFlow.value.sessionId ?: "unknown"
            addFreeSpaceWipeLog("[session=$sessionId] Security error: path escaped wipe directory.")
            throw SecurityException("Path escaped wipe directory.")
        }
    }

    private fun deleteRecursivelySafely(baseDir: File, target: File) {
        if (!target.exists()) return
        ensurePathWithin(baseDir, target)

        if (target.isDirectory) {
            val children = target.listFiles() ?: emptyArray()
            children.forEach { child -> deleteRecursivelySafely(baseDir, child) }
        }

        if (!target.delete()) {
            throw IOException("Unable to delete temporary wipe artifact.")
        }
    }

    private fun isNoSpaceLeft(exception: IOException): Boolean {
        val message = exception.message?.lowercase(Locale.getDefault()).orEmpty()
        return message.contains("enospc") || message.contains("no space left")
    }

    private fun isCancelRequested(sessionId: String): Boolean = cancelTokens[sessionId] == true

    private fun addFreeSpaceWipeLog(message: String) {
        val currentLogs = freeSpaceWipeLogFlow.value.toMutableList()
        currentLogs.add(message)
        if (currentLogs.size > 200) currentLogs.removeAt(0)
        freeSpaceWipeLogFlow.value = currentLogs
    }

    private fun updateFreeSpaceState(
        sessionId: String,
        status: FreeSpaceWipeStatus,
        writtenBytes: Long,
        estimatedFreeBytes: Long,
        progress: Float,
        message: String
    ) {
        freeSpaceWipeProgressFlow.value = progress
        freeSpaceWipeStatusFlow.value = message
        freeSpaceWipeStateFlow.value = FreeSpaceWipeState(
            sessionId = sessionId,
            status = status,
            writtenBytes = writtenBytes,
            estimatedFreeBytes = estimatedFreeBytes,
            progress = progress,
            message = message
        )
    }

    private fun tryAcquireOperation(operationId: String): Boolean {
        synchronized(operationLock) {
            if (destructiveOperationFlow.value != null) return false
            destructiveOperationFlow.value = operationId
            return true
        }
    }

    private fun releaseOperation(operationId: String) {
        synchronized(operationLock) {
            if (destructiveOperationFlow.value == operationId ||
                (operationId == "FILE_SHRED" && destructiveOperationFlow.value == "FILE_SHRED")
            ) {
                destructiveOperationFlow.value = null
            }
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
            val firstPass = currentAlgorithm.passes.firstOrNull() ?: ShredPass(
                PassType.RANDOM,
                "Random entropy"
            )
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
            secureDeleteTempFile(wipeFile)
            addConsoleLog("[Free Space Wiped]")
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return String.format(
            Locale.getDefault(),
            "%.1f %s",
            size / 1024.0.pow(digitGroups.toDouble()),
            units[digitGroups]
        )
    }
}
