package com.viruchith.shredder

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

object Shredder {
    private val random = SecureRandom()

    fun shred(file: File, passes: Int = 3, onProgress: (String) -> Unit = {}) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                shred(child, passes, onProgress)
            }
            file.delete()
        } else {
            onProgress("Shredding ${file.name}...")
            try {
                val length = file.length()
                if (length > 0) {
                    RandomAccessFile(file, "rws").use { raf ->
                        repeat(passes) {
                            raf.seek(0)
                            val buffer = ByteArray(65536)
                            var written = 0L
                            while (written < length) {
                                random.nextBytes(buffer)
                                val toWrite = minOf(buffer.size.toLong(), length - written).toInt()
                                raf.write(buffer, 0, toWrite)
                                written += toWrite
                            }
                        }
                    }
                }
                file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to simple delete if shredding fails (e.g. read-only)
                file.delete()
            }
        }
    }

    fun shredAll(files: List<File>, onProgress: (String) -> Unit = {}) {
        files.forEach { shred(it, onProgress = onProgress) }
    }
}
