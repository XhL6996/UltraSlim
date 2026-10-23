package app.ultraslim;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Standalone test APK process: no dependency on the target app's Kotlin runtime. */
public class TimestampTestProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private synchronized File fixture(Uri uri) throws IOException {
        boolean exif = "exif.jpg".equals(uri.getLastPathSegment());
        File file = new File(getContext().getCacheDir(), exif ? "timestamp-exif.jpg" : "timestamp-plain.jpg");
        if (!file.exists()) {
            Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            } finally { bitmap.recycle(); }
            if (exif) {
                ExifInterface metadata = new ExifInterface(file.getPath());
                metadata.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2021:07:08 09:10:11");
                metadata.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+05:30");
                metadata.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "345");
                metadata.setAttribute(ExifInterface.TAG_MODEL, "Timestamp Regression");
                metadata.saveAttributes();
            }
        }
        return file;
    }

    private Long parameter(Uri uri, String key) {
        try { return Long.valueOf(uri.getQueryParameter(key)); }
        catch (NumberFormatException ignored) { return null; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = fixture(uri);
            String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                String column = columns[i];
                if (OpenableColumns.DISPLAY_NAME.equals(column)) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(column)) row[i] = file.length();
                else if (MediaStore.Images.Media.DATE_TAKEN.equals(column)) row[i] = parameter(uri, "taken");
                else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) row[i] = parameter(uri, "modified");
            }
            cursor.addRow(row);
            return cursor;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try { return ParcelFileDescriptor.open(fixture(uri), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (IOException error) { throw new FileNotFoundException(error.toString()); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
