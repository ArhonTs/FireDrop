package ru.firedrop.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Thin HttpURLConnection wrapper with manual cookie handling. */
public final class Http {
    public final String host;
    public final int port;
    private volatile String cookie;

    public Http(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String base() {
        return "http://" + host + ":" + port;
    }

    public String cookie() {
        return cookie;
    }

    public void setCookie(String name, String value) {
        cookie = name + "=" + value;
    }

    public static final class HttpError extends IOException {
        public final int status;

        public HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private HttpURLConnection open(String method, String path, int connectMs, int readMs) throws IOException {
        URL url = new URL(base() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectMs);
        connection.setReadTimeout(readMs);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "FireDrop-Android/" + App.VERSION);
        String current = cookie;
        if (current != null) connection.setRequestProperty("Cookie", current);
        return connection;
    }

    private void captureCookie(HttpURLConnection connection) {
        String setCookie = connection.getHeaderField("Set-Cookie");
        if (setCookie == null) return;
        int semicolon = setCookie.indexOf(';');
        String pair = semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie;
        if (pair.contains("=")) cookie = pair.trim();
    }

    public JSONObject json(String method, String path, JSONObject body, int connectMs, int readMs)
            throws IOException {
        HttpURLConnection connection = open(method, path, connectMs, readMs);
        try {
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int status = connection.getResponseCode();
            captureCookie(connection);
            String text = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());

            if (status >= 400) {
                String message = String.format(L10n.s("http.server.error"), status);
                try {
                    JSONObject parsed = new JSONObject(text);
                    String error = parsed.optString("error", "");
                    if (!error.isEmpty()) message = error;
                } catch (Exception ignored) {
                }
                throw new HttpError(status, message);
            }

            if (text.isEmpty()) return new JSONObject();
            try {
                return new JSONObject(text);
            } catch (Exception broken) {
                return new JSONObject();
            }
        } finally {
            connection.disconnect();
        }
    }

    public interface BodyWriter {
        void write(OutputStream out) throws IOException;
    }

    /** PUT with a fixed content length, so no request body is buffered in memory. */
    public int put(String path, int length, BodyWriter writer, int connectMs, int readMs) throws IOException {
        HttpURLConnection connection = open("PUT", path, connectMs, readMs);
        try {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(length);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("Connection", "keep-alive");
            try (OutputStream out = connection.getOutputStream()) {
                writer.write(out);
                out.flush();
            }

            int status = connection.getResponseCode();
            captureCookie(connection);
            String text = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 400) {
                String message = String.format(L10n.s("http.transfer.error"), status);
                try {
                    JSONObject parsed = new JSONObject(text);
                    String error = parsed.optString("error", "");
                    if (!error.isEmpty()) message = error;
                } catch (Exception ignored) {
                }
                throw new HttpError(status, message);
            }
            return status;
        } finally {
            connection.disconnect();
        }
    }

    public void delete(String path) {
        HttpURLConnection connection = null;
        try {
            connection = open("DELETE", path, 6000, 8000);
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return buffer.toString("UTF-8");
        }
    }
}
