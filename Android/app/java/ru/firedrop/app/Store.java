package ru.firedrop.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Writes finished transfers into the public Downloads/FireDrop tree. */
public final class Store {
    private static final String TAG = "FireDrop";
    public static final String ROOT = "FireDrop";
    private static final java.util.concurrent.atomic.AtomicBoolean PENDING_CLEANED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Context context;
    private final File tempDir;

    public Store(Context context) {
        this.context = context.getApplicationContext();
        File external = this.context.getExternalFilesDir(null);
        File base = external != null ? external : this.context.getCacheDir();
        this.tempDir = new File(base, "incoming");
        if (!tempDir.exists()) tempDir.mkdirs();

        // Pending MediaStore rows from a killed process are invisible and waste space.
        if (PENDING_CLEANED.compareAndSet(false, true)) cleanupPending();
    }

    private void cleanupPending() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            int removed = context.getContentResolver().delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.IS_PENDING + "=1 AND "
                            + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?",
                    new String[]{context.getPackageName()});
            if (removed > 0) Log.i(TAG, "cleaned " + removed + " pending media rows");
        } catch (Exception error) {
            Log.w(TAG, "pending media cleanup failed", error);
        }
    }

    public File tempDir() {
        return tempDir;
    }

    public long freeBytes() {
        try {
            StatFs stat = new StatFs(tempDir.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception error) {
            return Long.MAX_VALUE;
        }
    }

    public String displayFolder() {
        return "Download/" + ROOT;
    }

    /** Creates an empty directory when the platform allows it. Returns false when not supported. */
    public boolean createFolder(String relativePath) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false;
        try {
            File target = new File(legacyRoot(), safeRelative(relativePath));
            return target.isDirectory() || target.mkdirs();
        } catch (Exception error) {
            return false;
        }
    }

    /**
     * Moves a completed temp file into the public Downloads folder.
     * Returns the human readable location, for example {@code Download/FireDrop/Pictures/1.jpg}.
     */
    public String publish(File temp, String relativePath, long lastModified) throws IOException {
        String relative = safeRelative(relativePath);
        String leaf = leafOf(relative);
        String dir = directoryOf(relative);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishModern(temp, dir, leaf, lastModified);
        }
        return publishLegacy(temp, dir, leaf, lastModified);
    }

    private String publishModern(File temp, String dir, String leaf, long lastModified) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, leaf);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(leaf));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + ROOT + (dir.isEmpty() ? "" : "/" + dir));
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        if (lastModified > 0) {
            values.put(MediaStore.MediaColumns.DATE_MODIFIED, lastModified / 1000L);
        }

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException(L10n.s("store.save.failed"));

        try {
            try (InputStream input = new FileInputStream(temp);
                 OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) throw new IOException(L10n.s("store.downloads.unavailable"));
                copy(input, output);
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } catch (IOException error) {
            try {
                resolver.delete(uri, null, null);
            } catch (Exception ignored) {
            }
            throw error;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }

        return "Download/" + ROOT + (dir.isEmpty() ? "" : "/" + dir) + "/" + leaf;
    }

    private String publishLegacy(File temp, String dir, String leaf, long lastModified) throws IOException {
        File folder = new File(legacyRoot(), dir);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException(String.format(L10n.s("store.folder.failed"), folder.getAbsolutePath()));
        }

        File target = new File(folder, leaf);
        int index = 1;
        while (target.exists() && index < 10000) {
            target = new File(folder, insertSuffix(leaf, index));
            index++;
        }

        if (!temp.renameTo(target)) {
            try (InputStream input = new FileInputStream(temp);
                 OutputStream output = new FileOutputStream(target)) {
                copy(input, output);
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }

        if (lastModified > 0) {
            //noinspection ResultOfMethodCallIgnored
            target.setLastModified(lastModified);
        }

        return "Download/" + ROOT + (dir.isEmpty() ? "" : "/" + dir) + "/" + target.getName();
    }

    private File legacyRoot() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[256 * 1024];
        int read;
        while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        output.flush();
    }

    static String safeRelative(String relativePath) {
        if (relativePath == null) return "";
        StringBuilder builder = new StringBuilder();
        int depth = 0;
        for (String part : relativePath.replace('\\', '/').split("/")) {
            String clean = part.trim();
            if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) continue;
            clean = clean.replaceAll("[\\\\:*?\"<>|\\p{Cntrl}]", "_");
            clean = clean.trim();
            if (clean.isEmpty()) continue;
            if (depth >= 12 || builder.length() + clean.length() + 1 > 120) break;
            if (builder.length() > 0) builder.append('/');
            builder.append(clean);
            depth++;
        }
        return builder.toString();
    }

    static String leafOf(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    static String directoryOf(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private static String insertSuffix(String name, int index) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + " (" + index + ")";
        return name.substring(0, dot) + " (" + index + ")" + name.substring(dot);
    }

    public void cleanupTemp() {
        try {
            File[] files = tempDir.listFiles();
            if (files == null) return;
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception error) {
            Log.w(TAG, "temp cleanup failed", error);
        }
    }
}
