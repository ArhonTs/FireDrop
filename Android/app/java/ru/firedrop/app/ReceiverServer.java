package ru.firedrop.app;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal HTTP receiver that speaks the same protocol as the FireDrop PC app.
 * Runs inside ReceiverService so a phone can accept files from another phone or a PC.
 */
public final class ReceiverServer {
    private static final String TAG = "FireDrop";

    public static final int CHUNK_SIZE = 8 * 1024 * 1024;
    public static final int PARALLEL_CHUNKS = 6;
    private static final long MIN_FREE_SPACE = 64L * 1024 * 1024;
    private static final int MAX_CONNECTIONS = 12;

    public interface Listener {
        /** Called from a worker thread whenever progress or state changes. */
        void onServerChanged();

        /** Called from a worker thread when a file has been fully saved. */
        void onFileSaved(String name, String location);
    }

    public static final class Session {
        public final String id;
        public final String displayName;
        public final String relativePath;
        public final long size;
        public final long lastModified;
        public final File temp;
        public final int expectedChunks;
        public final long startedAt;
        public final BitSet received;
        public final AtomicLong receivedBytes = new AtomicLong();
        public volatile boolean aborted;
        public volatile boolean completed;
        /** True while the finished temp file is being published to MediaStore. */
        public volatile boolean publishing;

        FileChannel channel;

        Session(String id, String displayName, String relativePath, long size, long lastModified, File temp) {
            this.id = id;
            this.displayName = displayName;
            this.relativePath = relativePath;
            this.size = size;
            this.lastModified = lastModified;
            this.temp = temp;
            this.expectedChunks = size == 0 ? 0 : (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
            this.received = new BitSet(expectedChunks);
            this.startedAt = System.currentTimeMillis();
        }

        public String shortName() {
            int slash = displayName.lastIndexOf('/');
            return slash < 0 ? displayName : displayName.substring(slash + 1);
        }
    }

    private final Store store;
    private final String deviceName;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> finished = new java.util.LinkedHashMap<>();
    private final List<String> recent = new ArrayList<>();
    private final Map<String, long[]> joinFailures = new HashMap<>();

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService pool;
    private volatile boolean running;
    private volatile int port;
    private volatile DiscoveryResponder discovery;
    private final AtomicLong completedBytes = new AtomicLong();
    private final AtomicInteger completedCount = new AtomicInteger();
    private volatile long lastActivity;
    private volatile long lastNotify;

    public final String token;
    public final String pin;

    public ReceiverServer(Store store, String deviceName, Listener listener) {
        this.store = store;
        this.deviceName = deviceName == null || deviceName.isEmpty() ? "Телефон" : deviceName;
        this.listener = listener;
        this.token = randomHex(16);
        this.pin = String.format(Locale.US, "%06d", random.nextInt(1_000_000));
    }

    public int port() {
        return port;
    }

    public String pin() {
        return pin;
    }

    public boolean isRunning() {
        return running;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public long completedBytes() {
        return completedBytes.get();
    }

    public int completedCount() {
        return completedCount.get();
    }

    public long lastActivity() {
        return lastActivity;
    }

    public List<Session> activeSessions() {
        return new ArrayList<>(sessions.values());
    }

    public void start(int desiredPort) throws IOException {
        if (running) return;

        ServerSocket socket = null;
        IOException last = null;
        int[] candidates = {desiredPort, 0};
        for (int candidate : candidates) {
            try {
                ServerSocket created = new ServerSocket();
                created.setReuseAddress(true);
                created.bind(new InetSocketAddress(candidate), MAX_CONNECTIONS);
                socket = created;
                break;
            } catch (IOException error) {
                last = error;
            }
        }
        if (socket == null) throw last == null ? new IOException("Не удалось открыть порт") : last;

        final ServerSocket bound = socket;
        port = bound.getLocalPort();
        serverSocket = bound;
        running = true;
        ExecutorService created = Executors.newFixedThreadPool(MAX_CONNECTIONS);
        pool = created;

        // Remove half-written files from a previous session that was killed.
        try {
            store.cleanupTemp();
        } catch (Exception ignored) {
        }

        Thread accept = new Thread(() -> acceptLoop(bound, created), "firedrop-accept");
        accept.setDaemon(true);
        accept.start();

        DiscoveryResponder responder = new DiscoveryResponder(deviceName, port);
        responder.start();
        discovery = responder;

        Log.i(TAG, "receiver started on " + port);
    }

    public void stop() {
        running = false;
        DiscoveryResponder responder = discovery;
        discovery = null;
        if (responder != null) responder.stop();

        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        ExecutorService current = pool;
        pool = null;
        if (current != null) current.shutdownNow();

        for (Session session : new ArrayList<>(sessions.values())) {
            abortSession(session);
        }
        sessions.clear();
    }

    private void acceptLoop(ServerSocket socket, ExecutorService executor) {
        while (running && !socket.isClosed()) {
            try {
                Socket client = socket.accept();
                executor.execute(() -> handle(client));
            } catch (SocketException closed) {
                return;
            } catch (Exception error) {
                if (running) Log.w(TAG, "accept failed", error);
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(120_000);
            client.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(client.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 32 * 1024);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                respond(out, 400, error("Некорректный запрос."));
                return;
            }

            String method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }

            long contentLength = 0;
            String lengthHeader = headers.get("content-length");
            if (lengthHeader != null) {
                try {
                    contentLength = Long.parseLong(lengthHeader);
                } catch (NumberFormatException ignored) {
                }
            }

            // The sender never sends X-Forwarded-For; remember the real peer so the
            // PIN limiter counts failures per device instead of sharing one bucket.
            try {
                if (client.getInetAddress() != null) {
                    headers.put("x-firedrop-peer", client.getInetAddress().getHostAddress());
                }
            } catch (Exception ignored) {
            }

            if (headers.containsKey("expect") && headers.get("expect").toLowerCase(Locale.US).contains("100-continue")) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("UTF-8"));
                out.flush();
            }

            String path = target;
            String query = "";
            int mark = target.indexOf('?');
            if (mark >= 0) {
                path = target.substring(0, mark);
                query = target.substring(mark + 1);
            }

            Map<String, String> params = parseQuery(query);
            lastActivity = System.currentTimeMillis();

            try {
                route(method, path, params, headers, contentLength, in, out);
            } catch (UploadError error) {
                respond(out, error.status, error(error.getMessage()));
            } catch (Exception error) {
                Log.w(TAG, "request failed " + method + " " + path, error);
                respond(out, 500, error("Внутренняя ошибка приёма файла."));
            }
        } catch (SocketException | EOFException ignored) {
        } catch (Exception error) {
            if (running) Log.w(TAG, "connection failed", error);
        }
    }

    private void route(String method, String path, Map<String, String> params,
                       Map<String, String> headers, long contentLength,
                       InputStream in, OutputStream out) throws Exception {
        if (path.equals("/api/health") && method.equals("GET")) {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("app", "FireDrop");
            json.put("version", App.VERSION);
            respond(out, 200, json.toString());
            return;
        }

        if (path.equals("/api/info") && method.equals("GET")) {
            JSONObject json = new JSONObject();
            json.put("deviceName", deviceName);
            json.put("hostName", deviceName);
            json.put("chunkSize", CHUNK_SIZE);
            json.put("parallelChunks", PARALLEL_CHUNKS);
            json.put("folders", true);
            json.put("version", App.VERSION);
            respond(out, 200, json.toString());
            return;
        }

        if (path.equals("/api/join") && method.equals("POST")) {
            handleJoin(headers, contentLength, in, out);
            return;
        }

        if (!isAuthorized(params, headers)) {
            throw new UploadError(401, "Требуется подключение к FireDrop.");
        }

        if (path.equals("/api/folders") && method.equals("POST")) {
            String body = readBody(in, contentLength);
            JSONObject json = parseJson(body);
            store.createFolder(json.optString("path", ""));
            respond(out, 200, "{\"ok\":true}");
            return;
        }

        if (path.equals("/api/uploads") && method.equals("POST")) {
            String body = readBody(in, contentLength);
            handleCreate(parseJson(body), out);
            return;
        }

        if (path.startsWith("/api/uploads/") && method.equals("PUT") && path.endsWith("/chunk")) {
            String id = path.substring("/api/uploads/".length(), path.length() - "/chunk".length());
            long offset = parseLong(params.get("offset"), -1);
            handleChunk(id, offset, contentLength, in, out);
            return;
        }

        if (path.startsWith("/api/uploads/") && method.equals("POST") && path.endsWith("/complete")) {
            String id = path.substring("/api/uploads/".length(), path.length() - "/complete".length());
            handleComplete(id, out);
            return;
        }

        if (path.startsWith("/api/uploads/") && method.equals("DELETE")) {
            String id = path.substring("/api/uploads/".length());
            Session session = sessions.remove(id);
            if (session != null) abortSession(session);
            respond(out, 204, null);
            return;
        }

        throw new UploadError(404, "Не найдено.");
    }

    private void handleJoin(Map<String, String> headers, long contentLength,
                            InputStream in, OutputStream out) throws Exception {
        String ip = headers.get("x-firedrop-peer");
        String key = ip == null ? "lan" : ip;

        synchronized (joinFailures) {
            long[] state = joinFailures.get(key);
            if (state != null && state[0] >= 8 && System.currentTimeMillis() - state[1] < 120_000) {
                throw new UploadError(429, "Слишком много попыток. Подождите пару минут.");
            }
        }

        String body = readBody(in, contentLength);
        JSONObject json = parseJson(body);
        String candidate = json.optString("pin", "").replace(" ", "").replace("-", "");

        if (!constantEquals(candidate, pin)) {
            synchronized (joinFailures) {
                long[] state = joinFailures.get(key);
                if (state == null) {
                    joinFailures.put(key, new long[]{1, System.currentTimeMillis()});
                } else {
                    state[0]++;
                    state[1] = System.currentTimeMillis();
                }
            }
            throw new UploadError(401, "Неверный код. Проверьте цифры на экране телефона.");
        }

        synchronized (joinFailures) {
            joinFailures.remove(key);
        }

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("token", token);
        result.put("deviceName", deviceName);

        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Set-Cookie: " + App.COOKIE_NAME + "=" + token + "; Path=/; HttpOnly\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + result.toString().getBytes("UTF-8").length + "\r\n\r\n"
                + result).getBytes("UTF-8"));
        out.flush();
    }

    private void handleCreate(JSONObject json, OutputStream out) throws Exception {
        String name = json.optString("name", "file");
        if (name == null || name.isEmpty() || "null".equals(name)) name = "file";
        long size = json.optLong("size", -1);
        long lastModified = json.optLong("lastModified", 0);
        String relativePath = json.optString("relativePath", "");
        if (relativePath == null || "null".equals(relativePath)) relativePath = "";

        if (size < 0) throw new UploadError(400, "Некорректный размер файла.");
        if (store.freeBytes() < size + MIN_FREE_SPACE) {
            throw new UploadError(507, "На телефоне недостаточно места.");
        }

        // relativePath is the parent folder (the same contract as the PC server).
        // The filename must be appended, otherwise every file in a folder would be
        // saved under the folder name and lose its extension.
        String fullPath = relativePath.isEmpty() ? name : relativePath + "/" + name;
        String safe = Store.safeRelative(fullPath);
        if (safe.isEmpty()) safe = "file";

        String id = randomHex(16);
        File temp = new File(store.tempDir(), id + ".part");

        RandomAccessFile raf = new RandomAccessFile(temp, "rw");
        raf.setLength(size);
        FileChannel channel = raf.getChannel();

        Session session = new Session(id, safe, Store.directoryOf(safe), size, lastModified, temp);
        session.channel = channel;
        synchronized (session) {
            sessions.put(id, session);
        }

        Log.i(TAG, "incoming " + safe + " (" + size + ")");
        notifyChanged(true);

        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("chunkSize", CHUNK_SIZE);
        result.put("parallelChunks", PARALLEL_CHUNKS);
        respond(out, 200, result.toString());
    }

    private void handleChunk(String id, long offset, long contentLength,
                             InputStream in, OutputStream out) throws Exception {
        Session session = sessions.get(id);
        if (session == null) throw new UploadError(404, "Передача не найдена.");
        if (session.aborted) throw new UploadError(410, "Передача отменена.");
        if (session.completed) throw new UploadError(409, "Файл уже принят.");

        long expected = expectedChunkLength(session.size, offset);
        if (expected < 0) throw new UploadError(400, "Некорректный размер части файла.");
        if (contentLength != expected) throw new UploadError(400, "Некорректный размер части файла.");

        int index = (int) (offset / CHUNK_SIZE);
        synchronized (session) {
            if (session.received.get(index)) {
                drain(in, contentLength);
                respond(out, 200, "{\"ok\":true}");
                return;
            }
        }

        byte[] buffer = new byte[256 * 1024];
        try {
            long written = 0;
            while (written < expected) {
                int want = (int) Math.min(buffer.length, expected - written);
                int read = in.read(buffer, 0, want);
                if (read <= 0) throw new EOFException("part ended early");

                ByteBuffer wrapper = ByteBuffer.wrap(buffer, 0, read);
                long at = offset + written;
                while (wrapper.hasRemaining()) {
                    int stored = session.channel.write(wrapper, at);
                    if (stored <= 0) throw new IOException("write stalled");
                    at += stored;
                }

                written += read;
            }
        } catch (EOFException early) {
            throw new UploadError(400, "Часть файла пришла не полностью.");
        }

        // Count the whole chunk only after it is fully on disk. A connection that
        // dies mid-chunk is retried, and the failed attempt must not inflate the
        // received-byte total (otherwise /complete would reject the file).
        synchronized (session) {
            if (!session.received.get(index)) {
                session.received.set(index);
                session.receivedBytes.addAndGet(expected);
            }
        }
        notifyChanged(false);
        respond(out, 200, "{\"ok\":true}");
    }

    private void handleComplete(String id, OutputStream out) throws Exception {
        // A repeated "complete" (for example after a lost reply) is not an error.
        // Check the cache before looking up the session: the session is removed as
        // soon as publishing finishes, and a retry must still get the same answer.
        synchronized (finished) {
            String cached = finished.get(id);
            if (cached != null) {
                respond(out, 200, cached);
                return;
            }
        }

        Session session = sessions.get(id);
        if (session == null) throw new UploadError(404, "Передача не найдена.");

        synchronized (session) {
            if (session.completed) {
                synchronized (finished) {
                    String cached = finished.get(id);
                    if (cached != null) {
                        respond(out, 200, cached);
                        return;
                    }
                }
                throw new UploadError(409, "Файл уже принят.");
            }
            if (session.aborted) throw new UploadError(410, "Передача отменена.");

            int count = session.received.cardinality();
            long bytes = session.receivedBytes.get();
            if (count != session.expectedChunks || bytes != session.size) {
                throw new UploadError(409,
                        "Передача ещё не завершена (не хватает частей: "
                                + Math.max(0, session.expectedChunks - count) + ").");
            }

            try {
                session.channel.force(true);
                session.channel.close();
            } catch (IOException error) {
                Log.w(TAG, "flush failed", error);
            }

            String location;
            session.publishing = true;
            try {
                location = store.publish(session.temp, session.displayName, session.lastModified);
            } catch (Exception error) {
                session.publishing = false;
                Log.w(TAG, "publish failed", error);
                sessions.remove(id);
                //noinspection ResultOfMethodCallIgnored
                session.temp.delete();
                throw new UploadError(500, "Не удалось сохранить файл в папке «Загрузки».");
            }
            session.publishing = false;

            long seconds = Math.max(1, (System.currentTimeMillis() - session.startedAt) / 1000);
            JSONObject file = new JSONObject();
            file.put("name", session.displayName);
            file.put("size", session.size);
            file.put("path", location);
            file.put("speed", session.size / seconds);

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("file", file);
            String payload = result.toString();

            // Publish the cached answer before marking the session complete: a retry
            // that arrives during the hand-off must never see "not found" or 409.
            synchronized (finished) {
                finished.put(id, payload);
                while (finished.size() > 200) {
                    java.util.Iterator<String> oldest = finished.keySet().iterator();
                    if (!oldest.hasNext()) break;
                    oldest.next();
                    oldest.remove();
                }
            }

            session.completed = true;
            sessions.remove(id);
            completedCount.incrementAndGet();
            completedBytes.addAndGet(session.size);
            synchronized (recent) {
                recent.add(0, session.shortName());
                if (recent.size() > 100) recent.remove(recent.size() - 1);
            }

            Log.i(TAG, "saved " + location + " (" + session.size + " bytes)");
            listener.onFileSaved(session.shortName(), location);
            notifyChanged(true);
            respond(out, 200, payload);
        }
    }

    private void abortSession(Session session) {
        // A file that is already being published must not be deleted under the copier.
        if (session.aborted || session.completed || session.publishing) return;
        session.aborted = true;
        try {
            if (session.channel != null) session.channel.close();
        } catch (Exception ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        session.temp.delete();
        notifyChanged(true);
    }

    // ------------------------------------------------------------- helpers

    private boolean isAuthorized(Map<String, String> params, Map<String, String> headers) {
        String fromQuery = params.get("t");
        if (fromQuery != null && constantEquals(fromQuery, token)) return true;

        String header = headers.get("x-firedrop-token");
        if (header != null && constantEquals(header, token)) return true;

        String cookie = headers.get("cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String trimmed = part.trim();
                int equals = trimmed.indexOf('=');
                if (equals > 0 && trimmed.substring(0, equals).equals(App.COOKIE_NAME)
                        && constantEquals(trimmed.substring(equals + 1), token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void notifyChanged(boolean force) {
        long now = System.currentTimeMillis();
        if (!force) {
            long previous = lastNotify;
            if (now - previous < 150) return;
            lastNotify = now;
        } else {
            lastNotify = now;
        }
        try {
            listener.onServerChanged();
        } catch (Exception ignored) {
        }
    }

    private static long expectedChunkLength(long size, long offset) {
        if (offset < 0 || offset % CHUNK_SIZE != 0 || offset >= size) return -1;
        return Math.min(CHUNK_SIZE, size - offset);
    }

    private static void drain(InputStream in, long length) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long left = length;
        while (left > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read <= 0) return;
            left -= read;
        }
    }

    private String readBody(InputStream in, long contentLength) throws IOException {
        if (contentLength <= 0) return "";
        if (contentLength > 8L * 1024 * 1024) throw new IOException("body too large");
        byte[] buffer = new byte[(int) contentLength];
        int read = 0;
        while (read < buffer.length) {
            int chunk = in.read(buffer, read, buffer.length - read);
            if (chunk <= 0) throw new EOFException("body ended early");
            read += chunk;
        }
        return new String(buffer, "UTF-8");
    }

    private static JSONObject parseJson(String body) {
        try {
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        while (true) {
            int current = in.read();
            if (current < 0) return buffer.size() == 0 ? null : buffer.toString("UTF-8");
            if (current == '\n') {
                String line = buffer.toString("UTF-8");
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            buffer.write(current);
            if (buffer.size() > 16 * 1024) throw new IOException("header too long");
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) return result;
        for (String part : query.split("&")) {
            if (part.isEmpty()) continue;
            int equals = part.indexOf('=');
            try {
                if (equals > 0) {
                    result.put(java.net.URLDecoder.decode(part.substring(0, equals), "UTF-8"),
                            java.net.URLDecoder.decode(part.substring(equals + 1), "UTF-8"));
                } else {
                    result.put(java.net.URLDecoder.decode(part, "UTF-8"), "");
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static void respond(OutputStream out, int status, String body) throws IOException {
        String reason;
        switch (status) {
            case 200:
                reason = "OK";
                break;
            case 204:
                reason = "No Content";
                break;
            case 400:
                reason = "Bad Request";
                break;
            case 401:
                reason = "Unauthorized";
                break;
            case 404:
                reason = "Not Found";
                break;
            case 409:
                reason = "Conflict";
                break;
            case 410:
                reason = "Gone";
                break;
            case 411:
                reason = "Length Required";
                break;
            case 429:
                reason = "Too Many Requests";
                break;
            case 500:
                reason = "Internal Server Error";
                break;
            case 507:
                reason = "Insufficient Storage";
                break;
            default:
                reason = "Error";
        }

        byte[] payload = body == null ? new byte[0] : body.getBytes("UTF-8");
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (body != null) {
            head.append("Content-Type: application/json; charset=utf-8\r\n");
        }
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n");
        head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        if (payload.length > 0) out.write(payload);
        out.flush();
    }

    private static String error(String message) {
        JSONObject json = new JSONObject();
        try {
            json.put("error", message);
        } catch (Exception ignored) {
        }
        return json.toString();
    }

    private String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (byte value : data) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    public String address() {
        return Net.primaryIp();
    }

    public String timestamp() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    static final class UploadError extends Exception {
        final int status;

        UploadError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
