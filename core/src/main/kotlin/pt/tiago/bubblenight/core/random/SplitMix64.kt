package pt.tiago.bubblenight.core.random

/** Version 1 SplitMix64. Long overflow is intentional and defined by JVM arithmetic. */
class SplitMix64(var state: Long) {
    fun nextLong(): Long {
        var z = (state + 0x9E3779B97F4A7C15UL.toLong()).also { state = it }
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return z xor (z ushr 31)
    }

    /** Uniform in [0, bound), using rejection to avoid modulo bias. */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        val m = bound - 1
        var bits = nextLong().ushr(1)
        if ((bound and m) == 0) return (bits and m.toLong()).toInt()
        var value = (bits % bound).toInt()
        while (bits - value.toLong() + m.toLong() < 0L) {
            bits = nextLong().ushr(1)
            value = (bits % bound).toInt()
        }
        return value
    }

    fun stateHex(): String = java.lang.Long.toUnsignedString(state, 16).padStart(16, '0')

    companion object {
        fun fromHex(hex: String): SplitMix64 {
            require(hex.matches(Regex("[0-9a-fA-F]{16}"))) { "RNG state must be 16 hex digits" }
            return SplitMix64(java.lang.Long.parseUnsignedLong(hex, 16))
        }
        fun seedFromHex(hex: String): Long {
            require(hex.matches(Regex("[0-9a-fA-F]{1,16}"))) { "Seed must be 1..16 hex digits" }
            return java.lang.Long.parseUnsignedLong(hex, 16)
        }
    }
}
