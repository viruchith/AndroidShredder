package com.viruchith.shredder


data class FreeSpaceWipeState(
    val sessionId: String? = null,
    val status: FreeSpaceWipeStatus = FreeSpaceWipeStatus.IDLE,
    val writtenBytes: Long = 0L,
    val estimatedFreeBytes: Long = 0L,
    val progress: Float = 0f,
    val message: String = "Idle"
)

enum class FreeSpaceWipeStatus {
    IDLE,
    WRITING,
    CLEANING_UP,
    COMPLETED,
    CANCELLED,
    FAILED
}

