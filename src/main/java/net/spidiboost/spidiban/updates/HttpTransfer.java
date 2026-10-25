package net.spidiboost.spidiban.updates;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.HttpsURLConnection;

public final class HttpTransfer implements AutoCloseable {
    @FunctionalInterface
    public interface Connections { HttpURLConnection open(URI address) throws IOException; }
    public record Response(int status, String location, byte[] body) {}
    private final Connections connections;
    private final int connectTimeout, readTimeout, totalTimeout;
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public HttpTransfer() {
        this(address -> (HttpsURLConnection) address.toURL().openConnection(), 10000, 10000, 45000);
    }

    public HttpTransfer(Connections connections, int connectTimeout, int readTimeout, int totalTimeout) {
        this.connections = connections;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.totalTimeout = totalTimeout;
    }

    public Response request(URI address, String body, int limit) throws IOException {
        return request(address, body, limit, System.nanoTime() + totalTimeout * 1_000_000L);
    }

    public Response request(URI address, String body, int limit, long deadline) throws IOException {
        if (!"https".equalsIgnoreCase(address.getScheme()) || address.getHost() == null
                || address.getUserInfo() != null || address.getFragment() != null
                || address.getPort() != -1 && address.getPort() != 443)
            throw new IOException("HTTPS address required");
        if (limit < 1) throw new IllegalArgumentException("Invalid response limit");
        checkOpen();
        var connection = connections.open(address);
        active.add(connection);
        try {
            checkOpen();
            connection.setConnectTimeout(remaining(deadline, connectTimeout));
            connection.setReadTimeout(remaining(deadline, readTimeout));
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "SpidiBoost");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (body == null) connection.setRequestMethod("GET");
            else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            remaining(deadline, readTimeout);
            String location = connection.getHeaderField("Location");
            if (status != 200) return new Response(status, location, new byte[0]);
            long length = connection.getContentLengthLong();
            if (length > limit) throw new IOException("Response exceeds size limit");
            try (var input = connection.getInputStream();
                    var output = new ByteArrayOutputStream((int)Math.max(0, Math.min(length, 65536)))) {
                byte[] buffer = new byte[Math.min(limit, 16384)];
                while (true) {
                    checkOpen();
                    connection.setReadTimeout(remaining(deadline, readTimeout));
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (count > limit - output.size()) throw new IOException("Response exceeds size limit");
                    output.write(buffer, 0, count);
                }
                remaining(deadline, readTimeout);
                if (length >= 0 && output.size() != length) throw new EOFException("Incomplete response");
                return new Response(status, location, output.toByteArray());
            }
        } finally {
            active.remove(connection);
            connection.disconnect();
        }
    }

    private void checkOpen() throws InterruptedIOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Request stopped");
    }

    private static int remaining(long deadline, int maximum) throws SocketTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException("Request timed out");
        return (int)Math.min(maximum, Math.max(1, nanos / 1_000_000));
    }

    @Override public void close() {
        closed = true;
        active.forEach(HttpURLConnection::disconnect);
    }
}
