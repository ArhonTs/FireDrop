package ru.firedrop.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Turns Storage Access Framework selections into a flat upload queue with relative paths. */
public final class Picker {
    private static final String TAG = "FireDrop";

    public static final class Result {
        public final List<UploadTask.Item> items = new ArrayList<>();
        public final List<String> folders = new ArrayList<>();
        public int skipped;

        public long totalBytes() {
            long total = 0;
            for (UploadTask.Item item : items) total += Math.max(0, item.size);
            return total;
        }
    }

    private Picker() {
    }

    private static final String[] PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    public static Result fromTree(Context context, Uri treeUri) {
        Result result = new Result();
        ContentResolver resolver = context.getContentResolver();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            String rootName = queryName(resolver, rootDocument);
            if (rootName == null || rootName.trim().isEmpty()) rootName = "Папка";
            rootName = rootName.replace("/", "_").replace("\\", "_").trim();
            result.folders.add(rootName);
            walk(resolver, treeUri, rootId, rootName, result, 0);
        } catch (Exception error) {
            Log.w(TAG, "tree walk failed", error);
        }
        return result;
    }

    private static void walk(ContentResolver resolver, Uri treeUri, String documentId,
                             String relative, Result out, int depth) {
        if (depth > 24) return;

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        Cursor cursor = null;
        boolean any = false;
        try {
            cursor = resolver.query(children, PROJECTION, null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                any = true;
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);

                if (name == null || name.trim().isEmpty()) {
                    out.skipped++;
                    continue;
                }

                Uri document = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                String childRelative = relative + "/" + name;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(resolver, treeUri, id, childRelative, out, depth + 1);
                } else if (size < 0) {
                    out.skipped++;
                } else {
                    out.items.add(new UploadTask.Item(document, relative, name, size, modified));
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "folder listing failed at " + relative, error);
        } finally {
            if (cursor != null) cursor.close();
        }

        if (!any && depth > 0) out.folders.add(relative);
    }

    public static Result fromDocuments(Context context, List<Uri> uris) {
        Result result = new Result();
        ContentResolver resolver = context.getContentResolver();
        for (Uri uri : uris) {
            try {
                String name = queryName(resolver, uri);
                long[] meta = querySizeAndDate(resolver, uri);
                if (name == null || name.trim().isEmpty()) name = "file";
                if (meta[0] < 0) {
                    result.skipped++;
                    continue;
                }
                result.items.add(new UploadTask.Item(uri, "", name, meta[0], meta[1]));
            } catch (Exception error) {
                Log.w(TAG, "document query failed", error);
                result.skipped++;
            }
        }
        return result;
    }

    private static String queryName(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private static long[] querySizeAndDate(ContentResolver resolver, Uri uri) {
        long[] result = {-1, 0};
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri,
                    new String[]{DocumentsContract.Document.COLUMN_SIZE,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                if (!cursor.isNull(0)) result[0] = cursor.getLong(0);
                if (!cursor.isNull(1)) result[1] = cursor.getLong(1);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        if (result[0] < 0) {
            try (android.os.ParcelFileDescriptor descriptor =
                         resolver.openFileDescriptor(uri, "r")) {
                if (descriptor != null) result[0] = descriptor.getStatSize();
            } catch (Exception ignored) {
            }
        }
        return result;
    }
}
