package app.ultraslim;

import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;

/** Runs in the test APK's own process, so it deliberately has no target-app/Kotlin dependencies. */
public class FolderTestProvider extends DocumentsProvider {
    private final String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
    private File sample() { return new File(getContext().getFilesDir(), "sample.jpg"); }
    @Override public boolean onCreate() {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(sample())) { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); }
        catch (Exception e) { throw new IllegalStateException(e); }
        finally { bitmap.recycle(); }
        return true;
    }
    @Override public MatrixCursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        cursor.addRow(new Object[]{"root", "root", "UltraSlim test", Root.FLAG_SUPPORTS_IS_CHILD});
        return cursor;
    }
    private void document(MatrixCursor cursor, String id, boolean directory, int flags) {
        cursor.addRow(new Object[]{id, directory ? id : id + ".jpg", directory ? Document.MIME_TYPE_DIR : "image/jpeg", flags, sample().length()});
    }
    @Override public MatrixCursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(columns);
        document(cursor, id, id.equals("root") || id.equals("sub") || id.equals("nested") || id.equals("denied"), 0);
        return cursor;
    }
    @Override public MatrixCursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if (parent.equals("denied")) throw new FileNotFoundException("Unreadable test directory");
        MatrixCursor cursor = new MatrixCursor(columns);
        switch (parent) {
            case "root":
                for (int i = 0; i < 1001; i++) document(cursor, "photo-" + i, false, 0);
                document(cursor, "sub", true, 0); document(cursor, "denied", true, 0);
                cursor.addRow(new Object[]{"video", "clip.mp4", "video/mp4", 0, 100});
                document(cursor, "virtual", false, Document.FLAG_VIRTUAL_DOCUMENT);
                break;
            case "sub":
                document(cursor, "child", false, 0); document(cursor, "nested", true, 0); document(cursor, "root", true, 0);
                break;
            case "nested": document(cursor, "grandchild", false, 0); document(cursor, "child", false, 0); break;
        }
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String id) {
        return parent.equals("root") || parent.equals("sub") || parent.equals("nested");
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(sample(), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
