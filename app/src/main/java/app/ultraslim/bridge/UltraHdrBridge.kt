package app.ultraslim.bridge

/**
 * Result of probing an image for Ultra HDR / gain map information.
 * Constructed from native code (field order must match the JNI signature).
 */
data class ProbeResult(
    val errorCode: Int,
    val error: String,
    val hasGainMap: Boolean,
    /** 0 = JPEG, 1 = HEIF, 2 = AVIF, -1 = unknown */
    val container: Int,
    val width: Int,
    val height: Int,
    val gainmapWidth: Int,
    val gainmapHeight: Int,
    val hasExif: Boolean,
    val maxContentBoost: Float,
    val hdrCapacityMax: Float,
) {
    val isSuccess: Boolean get() = errorCode == 0

    val containerLabel: String
        get() = when (container) {
            0 -> "JPEG"
            1 -> "HEIC"
            2 -> "AVIF"
            else -> "未知"
        }
}

/**
 * Result of transcoding one image to HEIC/AVIF (with gain map preserved when present).
 * Constructed from native code (field order must match the JNI signature).
 */
data class TranscodeResult(
    val errorCode: Int,
    val error: String,
    val inputHadGainMap: Boolean,
    val width: Int,
    val height: Int,
    val gainmapWidth: Int,
    val gainmapHeight: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val durationMs: Double,
) {
    val isSuccess: Boolean get() = errorCode == 0

    /** 100 means same size; lower is better. */
    val sizeRatio: Double
        get() = if (inputBytes > 0) outputBytes.toDouble() / inputBytes else 1.0
}

/** Thin Kotlin facade over the native libultrahdr transcode pipeline. */
object UltraHdrBridge {

    init {
        System.loadLibrary("ultraslim")
    }

    @JvmStatic
    external fun nativeProbe(fd: Int): ProbeResult

    @JvmStatic
    external fun nativeTranscode(
        inFd: Int,
        outFd: Int,
        outCodec: Int,
        quality: Int,
        gainmapQuality: Int,
        gainmapScale: Int,
        multiChannelGainmap: Boolean,
        keepExif: Boolean,
        allowSdrTranscode: Boolean,
        speedLevel: Int,
        resizePercent: Int,
    ): TranscodeResult

    @JvmStatic
    external fun nativeVersion(): String

    /**
     * Decodes the primary SDR rendition of an image to a small JPEG, for previews
     * the platform decoder cannot handle (e.g. ISO 21496-1 gain map HEIC).
     * Returns null on failure.
     */
    @JvmStatic
    external fun nativeDecodePreview(fd: Int, maxDim: Int): ByteArray?
}
