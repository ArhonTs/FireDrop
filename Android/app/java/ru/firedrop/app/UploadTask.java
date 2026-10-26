package ru.firedrop.app;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uploads a queue of files to a FireDrop receiver (PC or another phone).
 * Large files use parallel 8 MB chunks, small files go through a small pool
 * so a folder of thousands of photos does not crawl on request latency.
 */
public final class UploadTask {
    private static final String TAG = "FireDrop";
    public static final int QUEUED = 0;
    public static final int RUNNING = 1;
    public static final int DONE = 2;
    public static final int ERROR = 3;
    public static final int CANCELLED = 4;

    public static final class Item {
        public final Uri uri;
        public final String relativePath;
        public final String name;
        public final long size;
        public final long lastModified;
        public final AtomicLong sent = new AtomicLong();
        public volatile int status = QUEUED;
        public volatile String error = "";
        public volatile String serverName = "";

        public Item(Uri uri, String relativePath, String name, long size, long lastModified) {
            this.uri = uri;
            this.relativePath = relativePath == null ? "" : relativePath;
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    public final List<Item> items;
    public final List<String> folders;
    public final String host;
    public final int port;
    public final String pin;

    public volatile boolean finished;
    public volatile boolean cancelled;
    public volatile String fatalError = "";
    public volatile long startedAt;
    public volatile long finishedAt;
    public volatile int chunkSize = 8 * 1024 * 1024;
    public volatile int parallel = 6;
    public volatile String targetName = "";
    /** Session token taken from a FireDrop QR link; skips the PIN join when set. */
    public volatile String token = "";

    private final ContentResolver resolver;
    private final AtomicLong sentTotal = new AtomicLong();
    private final AtomicLong doneBytes = new AtomicLong();
    private volatile Thread worker;
    private volatile ExecutorService smallPool;
    private volatile Http http;

    public UploadTask(ContentResolver resolver, String host, int port, String pin,
                      List<Item> items, List<String> folders) {
        this.resolver = resolver;
        this.host = host;
        this.port = port;
        this.pin = pin;
        this.items = new ArrayList<>(items);
        this.folders = new ArrayList<>(folders);
    }

    public long sentBytes() {
        return sentTotal.get();
    }

    public long totalBytes() {
        long total = 0;
        for (Item item : items) total += Math.max(0, item.size);
        return total;
    }

    public int completedFiles() {
        int count = 0;
        for (Item item : items) if (item.status == DONE) count++;
        return count;
    }

    public int failedFiles() {
        int count = 0;
        for (Item item : items) if (item.status == ERROR) count++;
        return count;
    }

    public void start() {
        startedAt = System.currentTimeMillis();
        Thread thread = new Thread(this::run, "firedrop-upload");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public void cancel() {
        cancelled = true;
        ExecutorService pool = smallPool;
        if (pool != null) pool.shutdownNow();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        for (Item item : items) {
            if (item.status == QUEUED) item.status = CANCELLED;
        }
    }

    private void run() {
        boolean ok = false;
        try {
            join();
            readInfo();
            createFolders();

            List<Item> large = new ArrayList<>();
            List<Item> small = new ArrayList<>();
            for (Item item : items) {
                if (item.size > chunkSize) large.add(item);
                else small.add(item);
            }

            for (Item item : large) {
                if (!alive()) break;
                uploadFile(item, parallel);
            }

            if (alive() && !small.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, small.size()));
                smallPool = pool;
                for (Item item : small) {
                    pool.submit(() -> {
                        if (!alive()) return;
                        uploadFile(item, 1);
                    });
                }
                pool.shutdown();
                while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    if (!alive()) {
                        pool.shutdownNow();
                        break;
                    }
                }
            }

            ok = alive() && failedFiles() == 0;
        } catch (Http.HttpError error) {
            fatalError = error.getMessage();
        } catch (Exception error) {
            Log.w(TAG, "upload failed", error);
            if (!cancelled) fatalError = String.format(L10n.s("upload.fail"), describe(error));
        } finally {
            if (cancelled) {
                fatalError = L10n.s("upload.cancelled");
            } else if (fatalError.isEmpty() && failedFiles() > 0) {
                fatalError = String.format(L10n.s("upload.failed.count"), failedFiles());
            }
            for (Item item : items) {
                if (item.status == QUEUED || item.status == RUNNING) {
                    item.status = cancelled ? CANCELLED : (ok ? DONE : ERROR);
                }
            }
            finishedAt = System.currentTimeMillis();
            finished = true;
        }
    }

    private boolean alive() {
        return !cancelled && !Thread.currentThread().isInterrupted();
    }

    private void join() throws IOException {
        http = new Http(host, port);
        if (token != null && !token.isEmpty()) {
            http.setCookie(App.COOKIE_NAME, token);
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("pin", pin.replace(" ", "").replace("-", ""));
        } catch (Exception ignored) {
        }
        http.json("POST", "/api/join", body, 8000, 15000);
    }

    private void readInfo() {
        try {
            JSONObject info = http.json("GET", "/api/info", null, 8000, 15000);
            int size = info.optInt("chunkSize", chunkSize);
            if (size >= 256 * 1024 && size <= 64 * 1024 * 1024) chunkSize = size;
            int streams = info.optInt("parallelChunks", parallel);
            if (streams >= 1 && streams <= 16) parallel = streams;
            targetName = info.optString("deviceName", "");
        } catch (Exception ignored) {
        }
    }

    private void createFolders() {
        Set<String> ordered = new LinkedHashSet<>();
        List<String> sorted = new ArrayList<>(folders);
        sorted.sort((a, b) -> Integer.compare(a.length(), b.length()));
        ordered.addAll(sorted);

        for (String folder : ordered) {
            if (!alive()) return;
            try {
                JSONObject body = new JSONObject();
                body.put("path", folder);
                http.json("POST", "/api/folders", body, 8000, 15000);
            } catch (Http.HttpError error) {
                if (error.status == 404 || error.status == 405) {
                    // Older PC build without folder pre-creation: files still create their folders.
                    return;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void uploadFile(Item item, int streams) {
        if (!alive()) return;
        item.status = RUNNING;
        String id = null;
        try {
            JSONObject init = new JSONObject();
            init.put("name", item.name);
            init.put("size", item.size);
            init.put("lastModified", item.lastModified > 0 ? item.lastModified : 0);
            init.put("relativePath", item.relativePath);

            JSONObject created = http.json("POST", "/api/uploads", init, 15000, 30000);
            id = created.optString("id", "");
            if (id.isEmpty()) throw new IOException(L10n.s("upload.no.id"));

            int serverChunk = created.optInt("chunkSize", chunkSize);
            if (serverChunk >= 256 * 1024 && serverChunk <= 64 * 1024 * 1024) chunkSize = serverChunk;

            if (item.size > 0) {
                uploadChunks(id, item, Math.max(1, streams));
            }

            JSONObject done = completeFile(id);
            JSONObject file = done.optJSONObject("file");
            if (file != null) item.serverName = file.optString("name", "");

            item.sent.set(item.size);
            item.status = DONE;
            doneBytes.addAndGet(item.size);
        } catch (Exception error) {
            if (cancelled) {
                item.status = CANCELLED;
            } else {
                item.status = ERROR;
                item.error = describe(error);
            }
            if (id != null && !id.isEmpty()) http.delete("/api/uploads/" + id);
            Log.w(TAG, "file failed: " + item.name, error);
        }
    }

    /** The receiver may spend minutes copying a large file; retry if the reply is lost. */
    private JSONObject completeFile(String id) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return http.json("POST", "/api/uploads/" + id + "/complete",
                        null, 15000, 600000);
            } catch (Http.HttpError error) {
                last = error;
                if (error.status >= 400 && error.status < 500) throw error;
            } catch (IOException error) {
                last = error;
            }

            if (attempt < 3) {
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(L10n.s("upload.cancelled"));
                }
            }
        }
        throw last == null ? new IOException(L10n.s("upload.no.confirm")) : last;
    }

    private void uploadChunks(String id, Item item, int streams) throws Exception {
        final long size = item.size;
        final int chunk = chunkSize;
        final long chunks = (size + chunk - 1) / chunk;
        final int workers = (int) Math.min(streams, chunks);
        final Object failureLock = new Object();
        final IOException[] failure = new IOException[1];
        final int[] next = {0};

        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            threads[i] = new Thread(() -> {
                while (true) {
                    if (!alive()) return;
                    synchronized (failureLock) {
                        if (failure[0] != null) return;
                    }
                    int index;
                    synchronized (next) {
                        if (next[0] >= chunks) return;
                        index = next[0]++;
                    }

                    long offset = (long) index * chunk;
                    long length = Math.min(chunk, size - offset);
                    try {
                        putChunkWithRetry(id, item, offset, length);
                    } catch (IOException error) {
                        synchronized (failureLock) {
                            if (failure[0] == null) failure[0] = error;
                        }
                        return;
                    }
                }
            }, "firedrop-chunk-" + i);
            threads[i].start();
        }

        for (Thread thread : threads) thread.join();
        if (failure[0] != null) throw failure[0];
        if (!alive()) throw new IOException(L10n.s("upload.cancelled"));
    }

    private void putChunkWithRetry(String id, Item item, long offset, long length) throws IOException {
        int attempts = 3;
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (!alive()) throw new IOException(L10n.s("upload.cancelled"));
            try {
                putChunk(id, item, offset, length);
                return;
            } catch (Http.HttpError error) {
                last = error;
                if (error.status == 400 || error.status == 404 || error.status == 410 || error.status == 507) {
                    throw error;
                }
            } catch (IOException error) {
                last = error;
            }

            if (attempt < attempts) {
                try {
                    Thread.sleep(350L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(L10n.s("upload.cancelled"));
                }
            }
        }
        throw last == null ? new IOException(L10n.s("upload.chunk.failed")) : last;
    }

    private void putChunk(String id, Item item, long offset, long length) throws IOException {
        final String path = "/api/uploads/" + id + "/chunk?offset=" + offset;
        final ParcelFileDescriptor[] holder = new ParcelFileDescriptor[1];
        final AtomicLong written = new AtomicLong();

        try {
            http.put(path, (int) length, out -> {
                ParcelFileDescriptor descriptor = resolver.openFileDescriptor(item.uri, "r");
                if (descriptor == null) throw new IOException(String.format(L10n.s("upload.no.access"), item.name));
                holder[0] = descriptor;

                FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
                FileChannel channel = stream.getChannel();
                boolean seekable = false;
                try {
                    seekable = channel.size() >= offset + length;
                } catch (IOException ignored) {
                    // Pipes and cloud-backed documents report no usable size.
                }

                try {
                    if (seekable) {
                        try {
                            transferChannel(channel, offset, length, out, item, written);
                        } catch (IOException channelError) {
                            // Some providers lie about size: fall back only when nothing was sent yet.
                            if (!alive() || written.get() > 0) throw channelError;
                            transferStream(item.uri, offset, length, out, item, written);
                        }
                    } else {
                        transferStream(item.uri, offset, length, out, item, written);
                    }
                } finally {
                    try {
                        channel.close();
                    } catch (Exception ignored) {
                    }
                }
            }, 15000, 120000);
        } catch (IOException error) {
            long rollback = written.get();
            if (rollback > 0) {
                item.sent.addAndGet(-rollback);
                sentTotal.addAndGet(-rollback);
            }
            throw error;
        } finally {
            if (holder[0] != null) {
                try {
                    holder[0].close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void transferChannel(FileChannel channel, long offset, long length,
                                 OutputStream out, Item item, AtomicLong written) throws IOException {
        channel.position(offset);
        ByteBuffer buffer = ByteBuffer.allocate(128 * 1024);
        long left = length;
        while (left > 0) {
            if (!alive()) throw new IOException(L10n.s("upload.cancelled"));
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), left));
            int read = channel.read(buffer);
            if (read <= 0) throw new IOException(L10n.s("upload.file.ended"));
            out.write(buffer.array(), 0, read);
            left -= read;
            written.addAndGet(read);
            item.sent.addAndGet(read);
            sentTotal.addAndGet(read);
        }
    }

    /**
     * Fallback for providers that expose a one-shot stream instead of a seekable fd.
     * Chunks are re-read from the beginning and skipped, which is slower but correct.
     */
    private void transferStream(android.net.Uri uri, long offset, long length,
                                OutputStream out, Item item, AtomicLong written) throws IOException {
        try (java.io.InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException(String.format(L10n.s("upload.no.access"), item.name));

            long skip = offset;
            while (skip > 0) {
                if (!alive()) throw new IOException(L10n.s("upload.cancelled"));
                long skipped = input.skip(skip);
                if (skipped <= 0) {
                    int single = input.read();
                    if (single < 0) throw new IOException(L10n.s("upload.file.changed"));
                    skip--;
                } else {
                    skip -= skipped;
                }
            }

            byte[] buffer = new byte[128 * 1024];
            long left = length;
            while (left > 0) {
                if (!alive()) throw new IOException(L10n.s("upload.cancelled"));
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read <= 0) throw new IOException(L10n.s("upload.file.ended"));
                out.write(buffer, 0, read);
                left -= read;
                written.addAndGet(read);
                item.sent.addAndGet(read);
                sentTotal.addAndGet(read);
            }
        }
    }

    private static String describe(Exception error) {
        if (error instanceof Http.HttpError) return error.getMessage();
        String message = error.getMessage();
        if (message == null || message.isEmpty()) return L10n.s("upload.err.unknown");
        if (message.contains("ECONNREFUSED") || message.contains("Connection refused")) {
            return L10n.s("upload.err.refused");
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return L10n.s("upload.err.timeout");
        }
        if (message.contains("Network is unreachable") || message.contains("EHOSTUNREACH")) {
            return L10n.s("upload.err.unreachable");
        }
        return message;
    }
}
