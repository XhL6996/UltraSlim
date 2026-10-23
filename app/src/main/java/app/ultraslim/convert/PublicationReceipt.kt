package app.ultraslim.convert

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** A plain original name cannot identify our own previous publication. Record its URI before writing. */
internal class PublicationReceipt(file: File, private val destination: String) {
    private val receipt = AtomicFile(File(file.path + ".publication"))

    fun recover(context: Context, expected: MediaStoreSaver.Fingerprint): Uri? = runCatching {
        val json = JSONObject(receipt.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        if (json.getString("destination") != destination) return@runCatching null
        Uri.parse(json.getString("uri")).takeIf { MediaStoreSaver.matchesAlbumCopy(context, it, expected) }
    }.getOrNull()

    fun record(uri: Uri) {
        val stream = receipt.startWrite()
        try {
            stream.write(JSONObject().put("destination", destination).put("uri", uri.toString())
                .toString().toByteArray(Charsets.UTF_8))
            receipt.finishWrite(stream)
        } catch (e: Exception) {
            receipt.failWrite(stream)
            throw e
        }
    }

    fun delete() = receipt.delete()

    companion object {
        fun delete(file: File) = AtomicFile(File(file.path + ".publication")).delete()
    }
}
