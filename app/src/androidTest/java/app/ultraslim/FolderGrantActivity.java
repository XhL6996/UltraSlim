package app.ultraslim;

/** Test picker stand-in: grants a tree from the provider's owning UID. */
public class FolderGrantActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle savedState) {
        super.onCreate(savedState);
        String target = getIntent().getStringExtra("target");
        if (target == null || !target.startsWith("app.ultraslim.")) throw new IllegalArgumentException();
        String treeId = getIntent().getStringExtra("treeId");
        android.net.Uri tree = android.provider.DocumentsContract.buildTreeDocumentUri("app.ultraslim.tests.documents", treeId == null ? "root" : treeId);
        int write = getIntent().getBooleanExtra("write", false) ? android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0;
        if (getIntent().getBooleanExtra("revoke", false)) {
            revokeUriPermission(target, tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            finish();
            return;
        }
        grantUriPermission(target, tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
            write | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        finish();
    }
}
