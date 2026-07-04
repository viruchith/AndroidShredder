package com.viruchith.shredder

sealed class PassType {
    object RANDOM : PassType()
    object ZEROS : PassType()
    object ONES : PassType()
    data class PATTERN(val bytes: ByteArray) : PassType() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PATTERN
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

data class ShredPass(val type: PassType, val label: String)

sealed class ShredAlgorithm(
    val name: String,
    val description: String,
    val passes: List<ShredPass>,
    val colorHex: Long,
    val meaning: String
) {

    object Fast : ShredAlgorithm(
        name = "Fast (1-pass)",
        description = "Quick, single pass of random entropy. Best suited for SSDs and modern flash storage.",
        passes = listOf(ShredPass(PassType.RANDOM, "Random entropy")),
        colorHex = 0xFF26A69A, // Teal/Mint
        meaning = "Quick, low-overhead"
    )

    object Standard : ShredAlgorithm(
        name = "Standard (3-pass)",
        description = "Overwrites with random data, then alternating patterns (0xAA/0x55), then random data again.",
        passes = listOf(
            ShredPass(PassType.RANDOM, "Random entropy"),
            ShredPass(PassType.PATTERN(byteArrayOf(0xAA.toByte(), 0x55.toByte())), "0xAA/0x55 alternating"),
            ShredPass(PassType.RANDOM, "Random entropy")
        ),
        colorHex = 0xFF42A5F5, // Blue
        meaning = "Default, balanced"
    )

    object DoD5220_22M : ShredAlgorithm(
        name = "DoD 5220.22-M (7-pass)",
        description = "United States Department of Defense compliant overwrite sequence.",
        passes = listOf(
            ShredPass(PassType.ZEROS, "0x00 zeros"),
            ShredPass(PassType.ONES, "0xFF ones"),
            ShredPass(PassType.RANDOM, "Random entropy"),
            ShredPass(PassType.PATTERN(byteArrayOf(0x96.toByte())), "0x96 pattern"),
            ShredPass(PassType.ZEROS, "0x00 zeros"),
            ShredPass(PassType.ONES, "0xFF ones"),
            ShredPass(PassType.RANDOM, "Random entropy")
        ),
        colorHex = 0xFFFFCA28, // Amber
        meaning = "Serious, official standard"
    )

    object Schneier : ShredAlgorithm(
        name = "Schneier (7-pass)",
        description = "Bruce Schneier's scheme: 0x00 zeros, 0xFF ones, then 5 passes of random data.",
        passes = listOf(
            ShredPass(PassType.ZEROS, "0x00 zeros"),
            ShredPass(PassType.ONES, "0xFF ones"),
            ShredPass(PassType.RANDOM, "Random entropy"),
            ShredPass(PassType.RANDOM, "Random entropy"),
            ShredPass(PassType.RANDOM, "Random entropy"),
            ShredPass(PassType.RANDOM, "Random entropy"),
            ShredPass(PassType.RANDOM, "Random entropy")
        ),
        colorHex = 0xFFFF7043, // Orange
        meaning = "High security, longer"
    )

    object Gutmann : ShredAlgorithm(
        name = "Gutmann (35-pass)",
        description = "Peter Gutmann's exhaustive overwrite algorithm. Extremely secure but very slow.",
        passes = run {
            val list = mutableListOf<ShredPass>()
            // 4 random passes
            repeat(4) { list.add(ShredPass(PassType.RANDOM, "Random entropy")) }
            
            // 27 specific pattern passes
            val patterns = listOf(
                Pair(byteArrayOf(0x55.toByte()), "0x55 pattern"),
                Pair(byteArrayOf(0xAA.toByte()), "0xAA pattern"),
                Pair(byteArrayOf(0x92.toByte(), 0x49.toByte(), 0x24.toByte()), "0x924924 pattern"),
                Pair(byteArrayOf(0x49.toByte(), 0x24.toByte(), 0x92.toByte()), "0x492492 pattern"),
                Pair(byteArrayOf(0x24.toByte(), 0x92.toByte(), 0x49.toByte()), "0x249249 pattern"),
                Pair(byteArrayOf(0x00.toByte()), "0x00 pattern"),
                Pair(byteArrayOf(0x11.toByte()), "0x11 pattern"),
                Pair(byteArrayOf(0x22.toByte()), "0x22 pattern"),
                Pair(byteArrayOf(0x33.toByte()), "0x33 pattern"),
                Pair(byteArrayOf(0x44.toByte()), "0x44 pattern"),
                Pair(byteArrayOf(0x55.toByte()), "0x55 pattern"),
                Pair(byteArrayOf(0x66.toByte()), "0x66 pattern"),
                Pair(byteArrayOf(0x77.toByte()), "0x77 pattern"),
                Pair(byteArrayOf(0x88.toByte()), "0x88 pattern"),
                Pair(byteArrayOf(0x99.toByte()), "0x99 pattern"),
                Pair(byteArrayOf(0xAA.toByte()), "0xAA pattern"),
                Pair(byteArrayOf(0xBB.toByte()), "0xBB pattern"),
                Pair(byteArrayOf(0xCC.toByte()), "0xCC pattern"),
                Pair(byteArrayOf(0xDD.toByte()), "0xDD pattern"),
                Pair(byteArrayOf(0xEE.toByte()), "0xEE pattern"),
                Pair(byteArrayOf(0xFF.toByte()), "0xFF pattern"),
                Pair(byteArrayOf(0x92.toByte(), 0x49.toByte(), 0x24.toByte()), "0x924924 pattern"),
                Pair(byteArrayOf(0x49.toByte(), 0x24.toByte(), 0x92.toByte()), "0x492492 pattern"),
                Pair(byteArrayOf(0x24.toByte(), 0x92.toByte(), 0x49.toByte()), "0x249249 pattern"),
                Pair(byteArrayOf(0x6D.toByte(), 0xB6.toByte(), 0xDB.toByte()), "0x6DB6DB pattern"),
                Pair(byteArrayOf(0xB6.toByte(), 0xDB.toByte(), 0x6D.toByte()), "0xB6DB6D pattern"),
                Pair(byteArrayOf(0xDB.toByte(), 0x6D.toByte(), 0xB6.toByte()), "0xDB6DB6 pattern")
            )
            patterns.forEach { (p, desc) ->
                list.add(ShredPass(PassType.PATTERN(p), desc))
            }

            // 4 random passes
            repeat(4) { list.add(ShredPass(PassType.RANDOM, "Random entropy")) }
            list
        },
        colorHex = 0xFFE53935, // Deep Red/Crimson
        meaning = "Extreme — use with caution"
    )

    companion object {
        fun values(): List<ShredAlgorithm> = listOf(Fast, Standard, DoD5220_22M, Schneier, Gutmann)
        
        fun fromName(name: String): ShredAlgorithm {
            return values().find { it.name == name } ?: Standard
        }
    }
}
