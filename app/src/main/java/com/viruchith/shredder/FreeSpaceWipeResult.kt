package com.viruchith.shredder

sealed class FreeSpaceWipeResult {
    data class Completed(
        val sessionId: String,
        val writtenBytes: Long,
        val estimatedFreeBytes: Long
    ) : FreeSpaceWipeResult()

    data class Cancelled(
        val sessionId: String,
        val writtenBytes: Long,
        val estimatedFreeBytes: Long
    ) : FreeSpaceWipeResult()

    data class Failed(
        val sessionId: String,
        val writtenBytes: Long,
        val estimatedFreeBytes: Long,
        val errorMessage: String
    ) : FreeSpaceWipeResult()
}

