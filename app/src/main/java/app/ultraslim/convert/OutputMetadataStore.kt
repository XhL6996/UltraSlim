package app.ultraslim.convert

import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Capture source dates before encoding; deferred saves must not substitute the conversion clock. */
internal object OutputMetadataStore {
    private fun atomic(file: File) = AtomicFile(File(file.path + ".timestamps"))

    fun write(file: File, timestamps: SourceTimestamps) {
        val atomic = atomic(file)
        var stream: java.io.FileOutputStream? = null
        try {
            val json = JSONObject().put("dateTaken", timestamps.dateTaken).put("modifiedAt", timestamps.modifiedAt)
            stream = atomic.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun read(file: File): SourceTimestamps? {
        val atomic = atomic(file)
        if (!atomic.baseFile.exists() && !File(file.path + ".timestamps.bak").exists()) return null
        // A damaged receipt must not silently turn into a conversion-time mtime.
        val json = JSONObject(String(atomic.readFully(), Charsets.UTF_8))
        return SourceTimestamps(json.optLong("dateTaken").takeIf { it > 0 },
            json.optLong("modifiedAt").takeIf { it > 0 })
    }

    fun delete(file: File) = atomic(file).delete()
}
