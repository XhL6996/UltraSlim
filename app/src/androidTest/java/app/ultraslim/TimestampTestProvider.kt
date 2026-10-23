package app.ultraslim

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.media.ExifInterface
import java.io.File

/** Test-only ephemeral provider; dates can exist exclusively in provider columns. */
class TimestampTestProvider : ContentProvider() {
    override fun onCreate() = true
    @Synchronized private fun fixture(uri: Uri): File {
        val exif = uri.lastPathSegment == "exif.jpg"
        val file = File(requireNotNull(context).cacheDir, if (exif) "timestamp-exif.jpg" else "timestamp-plain.jpg")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
            finally { bitmap.recycle() }
            if (exif) ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2021:07:08 09:10:11")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+05:30")
                setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "345")
                setAttribute(ExifInterface.TAG_MODEL, "Timestamp Regression")
                saveAttributes()
            }
        }
        return file
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fixture(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { column -> when (column) {
            OpenableColumns.DISPLAY_NAME -> file.name
            OpenableColumns.SIZE -> file.length()
            MediaStore.Images.Media.DATE_TAKEN -> uri.getQueryParameter("taken")?.toLongOrNull()
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> uri.getQueryParameter("modified")?.toLongOrNull()
            else -> null
        } }) }
    }
    override fun getType(uri: Uri) = "image/jpeg"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(fixture(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
