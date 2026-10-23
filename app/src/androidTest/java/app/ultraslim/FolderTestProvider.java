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
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
    private File sample() { return new File(getContext().getFilesDir(), "sample.jpg"); }
    private File outputDirectory() {
        File directory = new File(getContext().getFilesDir(), "folder-output");
        directory.mkdirs();
        return directory;
    }
    private File output(String id) throws FileNotFoundException {
        if (!id.startsWith("output/") || id.substring(7).contains("/") || id.substring(7).equals(".."))
            throw new FileNotFoundException(id);
        return new File(outputDirectory(), id.substring(7));
    }
    private void outputDocument(MatrixCursor cursor, File file) {
        cursor.addRow(new Object[]{"output/" + file.getName(), file.getName(), "image/jpeg",
            Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE, file.length(),
            file.getName().startsWith("mtime_") ? 1L : file.lastModified()});
    }
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
        cursor.addRow(new Object[]{id, directory ? id : id + ".jpg", directory ? Document.MIME_TYPE_DIR : "image/jpeg", flags, sample().length(), sample().lastModified()});
    }
    @Override public MatrixCursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(columns);
        if (id.equals("output")) {
            cursor.addRow(new Object[]{id, "Converted photos", Document.MIME_TYPE_DIR, Document.FLAG_DIR_SUPPORTS_CREATE, 0, 0});
            return cursor;
        }
        if (id.startsWith("output/")) {
            outputDocument(cursor, output(id));
            return cursor;
        }
        document(cursor, id, id.equals("root") || id.equals("sub") || id.equals("nested") || id.equals("denied"), 0);
        return cursor;
    }
    @Override public MatrixCursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if (parent.equals("denied")) throw new FileNotFoundException("Unreadable test directory");
        MatrixCursor cursor = new MatrixCursor(columns);
        switch (parent) {
            case "output":
                File[] files = outputDirectory().listFiles();
                if (files != null) for (File file : files) outputDocument(cursor, file);
                break;
            case "root":
                for (int i = 0; i < 1001; i++) document(cursor, "photo-" + i, false, 0);
                document(cursor, "sub", true, 0); document(cursor, "denied", true, 0);
                cursor.addRow(new Object[]{"video", "clip.mp4", "video/mp4", 0, 100, 0});
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
        return parent.equals("root") || parent.equals("sub") || parent.equals("nested") ||
            (parent.equals("output") && id.startsWith("output/"));
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (id.startsWith("output/")) {
            if (mode.contains("w") && id.substring(7).startsWith("failwrite_")) throw new FileNotFoundException("Test write failure");
            if (mode.equals("rw") && id.substring(7).startsWith("nomtime_")) {
                try {
                    // Model a cloud provider whose metadata is not a writable local file.
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    pipe[0].close();
                    return pipe[1];
                } catch (java.io.IOException e) { throw new FileNotFoundException(e.toString()); }
            }
            return ParcelFileDescriptor.open(output(id), ParcelFileDescriptor.parseMode(mode));
        }
        return ParcelFileDescriptor.open(sample(), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("output")) throw new FileNotFoundException(parent);
        File file = output("output/" + name);
        try {
            int suffix = 1;
            while (!file.createNewFile()) file = output("output/" + name + " (" + suffix++ + ")");
            if (name.startsWith("mtime_") && !file.setLastModified(1600000000123L))
                throw new java.io.IOException("Cannot set test mtime");
        } catch (java.io.IOException e) { throw new FileNotFoundException(e.toString()); }
        return "output/" + file.getName();
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!output(id).delete()) throw new FileNotFoundException(id);
    }
}
