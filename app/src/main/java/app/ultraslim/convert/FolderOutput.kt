package app.ultraslim.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException
import app.ultraslim.util.keptOutputName
import app.ultraslim.util.confirmKeptOutputName

/** SAF output only: folder browsing and selection belong to Android's DocumentsUI. */
object FolderOutput {
    /** Keep the grant for future batches, process restarts and retries of older batches. */
    fun retain(context: Context, tree: Uri): String {
        require(DocumentsContract.isTreeUri(tree)) { "请选择文件夹" }
        context.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return context.contentResolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_FLAGS),
            null, null, null)?.use {
            check(it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(Document.COLUMN_FLAGS)) and Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "此文件夹不支持写入，请重新选择" }
            it.getString(it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)).orEmpty().ifBlank { "所选文件夹" }
        } ?: throw IOException("无法读取文件夹，请重新选择")
    }

    /** Never overwrite an existing document. Recover a previous publication only by matching bytes. */
    fun save(context: Context, file: File, tree: Uri, name: String, mime: String,
        expected: MediaStoreSaver.Fingerprint, keepFilename: Boolean = false, modifiedTime: Long? = null): Uri {
        val resolver = context.contentResolver
        val receipt = if (keepFilename) PublicationReceipt(file, "folder:$tree:$name") else null
        receipt?.recover(context, expected)?.let {
            MediaStoreSaver.requireFileTime(context, it, modifiedTime)
            return it
        }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val numbered = Regex(Regex.escape(base) + " ?\\(\\d+\\)" +
            if (extension.isEmpty()) "" else "\\." + Regex.escape(extension))
        val existing = mutableSetOf<String>()
        var recovered: Uri? = null
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = cursor.getString(1) ?: continue
                if (!keepFilename && candidate != name && !numbered.matches(candidate)) continue
                existing += candidate
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                if (!keepFilename && recovered == null && MediaStoreSaver.matchesAlbumCopy(context, uri, expected)) recovered = uri
            }
        } ?: throw IOException("无法读取输出文件夹，请在设置中重新选择")
        recovered?.let {
            MediaStoreSaver.requireFileTime(context, it, modifiedTime)
            return it
        }
        val chosen = if (keepFilename) keptOutputName(name, existing, expected.sha256) else disambiguateName(name, existing)
        var created = DocumentsContract.createDocument(resolver, root, mime, chosen)
            ?: throw IOException("无法在输出文件夹创建文件")
        try {
            if (keepFilename) confirmKeptOutputName(name, chosen, existing, expected.sha256,
                readName = { MediaStoreSaver.displayName(context, created) },
                rename = { renamed ->
                    created = DocumentsContract.renameDocument(resolver, created, renamed)
                        ?: throw IOException("无法设置输出文件名")
                })
            receipt?.record(created)
            resolver.openOutputStream(created, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法写入输出文件夹")
            check(MediaStoreSaver.matchesAlbumCopy(context, created, expected)) { "输出文件写入校验失败" }
            MediaStoreSaver.requireFileTime(context, created, modifiedTime)
            return created
        } catch (e: Exception) {
            // Only remove the document just created by this attempt, never an existing user file.
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            receipt?.delete()
            throw e
        }
    }
}
