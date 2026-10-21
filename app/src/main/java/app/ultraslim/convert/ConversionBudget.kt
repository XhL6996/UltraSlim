package app.ultraslim.convert

/** Conservative admission estimates, not a guarantee of a codec's peak allocation. */
internal object ConversionBudget {
    private const val MIB = 1024L * 1024

    fun threads(cores: Int): Int = (cores / 2).coerceIn(1, 4)

    fun previewBytes(available: Long, total: Long, threshold: Long, lowMemory: Boolean): Long {
        if (lowMemory || available <= 0 || total <= 0) return 0
        val reserve = maxOf(threshold, total / 5, 512 * MIB)
        return minOf(available / 3, available - reserve).coerceAtLeast(0)
    }

    fun canStart(width: Int, height: Int, gainWidth: Int, gainHeight: Int, hdr: Boolean,
                 inputBytes: Long, available: Long, total: Long, threshold: Long, lowMemory: Boolean): Boolean {
        val pixels = width.toLong() * height
        val gainPixels = gainWidth.toLong() * gainHeight
        if (lowMemory || width <= 0 || height <= 0 || pixels > 100_000_000L ||
            gainWidth < 0 || gainHeight < 0 || gainPixels > 100_000_000L) return false
        val reserve = maxOf(threshold, total / 5, 512 * MIB)
        val budget = minOf(available / 3, available - reserve).coerceAtLeast(0)
        val estimate = 128 * MIB + pixels * (if (hdr) 48 else 24) + gainPixels * 16 +
            inputBytes.coerceIn(0, 256 * MIB) * 2
        return estimate < budget
    }
}
