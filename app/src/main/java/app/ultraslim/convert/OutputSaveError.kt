package app.ultraslim.convert

import app.ultraslim.data.HistoryEntity
import java.io.File

/** Persist the cause alongside a private result without changing the Room history schema. */
internal object OutputSaveError {
    private fun file(output: File) = File(output.path + ".save-error")
    fun write(output: File, message: String?) {
        runCatching {
            if (message == null) file(output).delete()
            else file(output).writeText(message.take(500))
        }
    }
    fun read(record: HistoryEntity): String? {
        if (record.outputUri.startsWith("content://") || record.saveState != "FAILED") return null
        return runCatching { file(File(record.outputUri)).readText().takeIf { it.isNotBlank() } }.getOrNull()
    }
}
