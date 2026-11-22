package com.vclient.mod.cosmetics;

import com.vclient.mod.Module;
import com.vclient.mod.VClientMod;
import com.vclient.mod.VConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Network and disk plumbing shared by the catalog, the textures and the skin loader. Never touches the render
 * thread.
 *
 * Two rules keep loading from ever hanging:
 * <ul>
 *   <li>Code running on the pool never waits ({@code join()}/{@code get()}) for another pool task. Callers
 *       chain with {@code thenAccept}/{@code whenComplete} instead. (Waiting from inside a bounded pool starves
 *       it: that was the cause of skins and cosmetics loading forever.)</li>
 *   <li>Every request and every download has a hard {@link #TIMEOUT_SECONDS}-second limit and always ends in
 *       either a result or an exception that is logged with its URL.</li>
 * </ul>
 */
final class CosmeticNet {
    /** Largest single asset or catalog document accepted. */
    static final int MAX_BYTES = 2 * 1024 * 1024;
    /** Longest any single request or download may take before it is reported as failed. */
    static final int TIMEOUT_SECONDS = 10;
    private static final long RETRY_AFTER_MILLIS = 30_000;

    /** One network request, as shown in the debug view. */
    record Request(long time, String method, String url, int status, long millis, String error) {
        String describe() {
            return method + " " + url + " -> " + (error != null ? "FAILED: " + error : status + " (" + millis + " ms)");
        }
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "vclient-cosmetics-net");
        thread.setDaemon(true);
        return thread;
    });
    private static final ConcurrentHashMap<String, CompletableFuture<byte[]>> DOWNLOADS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> FAILED_AT = new ConcurrentHashMap<>();
    private static final ArrayDeque<Request> RECENT = new ArrayDeque<>();
    private static HttpClient http;

    private CosmeticNet() {}

    // ---- debug mode ------------------------------------------------------------------------------

    /** True with {@code -Dvclient.debug=true} or the "Debug mode" switch of the Cosmetics module. */
    static boolean debug() {
        if (Boolean.getBoolean("vclient.debug")) return true;
        try {
            return VConfig.bool(Module.COSMETICS, "debug");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The last requests made, newest last, for the in-game debug view. */
    static List<Request> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    private static void record(Request request) {
        synchronized (RECENT) {
            RECENT.addLast(request);
            while (RECENT.size() > 40) RECENT.removeFirst();
        }
        if (request.error() != null) VClientMod.LOGGER.warn("[Net] {}", request.describe());
        else if (debug() || request.status() >= 400) VClientMod.LOGGER.info("[Net] {}", request.describe());
    }

    /** Logs a failure with its real message; the full stack trace is added in debug mode. Never swallows. */
    static void logError(String what, Throwable error) {
        Throwable cause = unwrap(error);
        if (debug()) VClientMod.LOGGER.error("[Cosmetics] {}: {}\n{}", what, describe(cause), stackTrace(cause));
        else VClientMod.LOGGER.error("[Cosmetics] {}: {}", what, describe(cause));
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** A message a player can act on. */
    static String describe(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
            return "timed out after " + TIMEOUT_SECONDS + " seconds";
        }
        if (cause instanceof java.net.UnknownHostException) return "unknown host " + cause.getMessage()
                + " (DNS lookup failed or is blocked on this network)";
        if (cause instanceof javax.net.ssl.SSLException) return "TLS/HTTPS failure: " + cause.getMessage()
                + " (a DNS filter, proxy or antivirus may be blocking this host)";
        if (cause instanceof com.google.gson.JsonParseException) {
            return "the server answered with something that is not valid JSON (wrong backend address, or the "
                    + "service is still starting)";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }

    static String stackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    // ---- locations -------------------------------------------------------------------------------

    static Path directory() {
        return FabricLoader.getInstance().getGameDir().resolve("vclient").resolve("cosmetics");
    }

    /** The backend base URL the launcher passed with -Dvclient.backend, or empty when none is configured. */
    static String backendUrl() {
        String value = System.getProperty("vclient.backend", "");
        if (value.isBlank()) value = System.getenv().getOrDefault("VCLIENT_BACKEND_URL", "");
        value = value.strip();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return "";
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    ? value : "";
        } catch (IllegalArgumentException e) {
            VClientMod.LOGGER.warn("[Cosmetics] Ignoring invalid backend address: {}", value);
            return "";
        }
    }

    static void run(Runnable task) {
        POOL.execute(() -> {
            try {
                task.run();
            } catch (Throwable error) {
                logError("Background task failed", error);
            }
        });
    }

    /** Runs a blocking job on the pool; the future always completes, with a timeout error at the latest. */
    static <T> CompletableFuture<T> supply(java.util.function.Supplier<T> job) {
        return CompletableFuture.supplyAsync(job, POOL).orTimeout(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);
    }

    /** Resolves a possibly relative asset address and enforces https (plain http only for local testing). */
    static URI resolve(String base, String address) throws IOException {
        URI uri;
        try {
            uri = address.isBlank() ? null : URI.create(base + "/").resolve(address);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid cosmetic asset address: " + address, e);
        }
        if (uri == null || uri.getHost() == null) throw new IOException("Cosmetic asset has no usable download address ("
                + (address.isBlank() ? "empty" : address) + ").");
        boolean loopback = uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1")
                || uri.getHost().equals("[::1]");
        if (!"https".equals(uri.getScheme()) && !(loopback && "http".equals(uri.getScheme()))) {
            throw new IOException("Cosmetic downloads must use https: " + uri);
        }
        return uri;
    }

    // ---- requests --------------------------------------------------------------------------------

    private static synchronized HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
        }
        return http;
    }

    /** Blocking GET with a hard timeout. Only call from pool tasks. Every call is recorded for the debug view. */
    static HttpResponse<byte[]> get(URI uri, String etag) throws IOException, InterruptedException {
        return send("GET", uri, etag, null);
    }

    static HttpResponse<byte[]> head(URI uri) throws IOException, InterruptedException {
        return send("HEAD", uri, null, null);
    }

    static HttpResponse<byte[]> postJson(URI uri, String json) throws IOException, InterruptedException {
        return send("POST", uri, null, json);
    }

    private static HttpResponse<byte[]> send(String method, URI uri, String etag, String json)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, image/png, */*").header("User-Agent", "VYRN-Mod");
        if (etag != null && !etag.isBlank()) request.header("If-None-Match", etag);
        switch (method) {
            case "HEAD" -> request.method("HEAD", HttpRequest.BodyPublishers.noBody());
            case "POST" -> request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            default -> request.GET();
        }
        try {
            HttpResponse<InputStream> response = client().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Response is larger than " + MAX_BYTES + " bytes");
                long millis = (System.nanoTime() - start) / 1_000_000;
                record(new Request(System.currentTimeMillis(), method, safe(uri), response.statusCode(), millis, null));
                return new FixedResponse(response, bytes);
            }
        } catch (IOException | RuntimeException e) {
            long millis = (System.nanoTime() - start) / 1_000_000;
            record(new Request(System.currentTimeMillis(), method, safe(uri), 0, millis, describe(e)));
            if (debug()) VClientMod.LOGGER.error("[Net] {} {} failed:\n{}", method, safe(uri), stackTrace(e));
            throw e;
        }
    }

    /** The address without a query string or credentials, so tokens and keys never reach a log. */
    private static String safe(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getRawPath();
    }

    /**
     * Downloads {@code uri} once (concurrent callers share the request), verifies the SHA-256 when one is
     * given, and caches the file in {@code cacheFile}. A cached file that still matches is returned without
     * touching the network. The future always completes: with the bytes, or with the logged reason it failed.
     */
    static CompletableFuture<byte[]> download(URI uri, String sha256, Path cacheFile) {
        String key = uri + "|" + sha256;
        Long failedAt = FAILED_AT.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MILLIS) {
            return CompletableFuture.failedFuture(new IOException(
                    "Download of " + uri + " failed less than 30 seconds ago; not retrying yet."));
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = DOWNLOADS.putIfAbsent(key, result);
        if (existing != null) return existing;
        CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.isRegularFile(cacheFile)) {
                    byte[] cached = Files.readAllBytes(cacheFile);
                    if (sha256.isEmpty() || sha256(cached).equals(sha256)) {
                        if (debug()) VClientMod.LOGGER.info("[Cosmetics] Cache hit for {}", uri);
                        return cached;
                    }
                }
                VClientMod.LOGGER.info("[Cosmetics] Downloading {}", uri);
                HttpResponse<byte[]> response = get(uri, null);
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + uri
                            + (response.statusCode() == 404 ? " (the file does not exist on the server)" : ""));
                }
                byte[] bytes = response.body();
                if (!sha256.isEmpty() && !sha256(bytes).equals(sha256)) {
                    throw new IOException("Checksum mismatch for " + uri + " (the file on the server changed or is corrupt)");
                }
                write(cacheFile, bytes);
                VClientMod.LOGGER.info("[Cosmetics] Downloaded {} ({} bytes)", uri, bytes.length);
                return bytes;
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, POOL).orTimeout(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS).whenComplete((bytes, error) -> {
            DOWNLOADS.remove(key);
            if (error != null) {
                FAILED_AT.put(key, System.currentTimeMillis());
                logError("Download failed for " + uri, error);
                result.completeExceptionally(unwrap(error));
            } else {
                result.complete(bytes);
            }
        });
        return result;
    }

    static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".vclient-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** A response whose body has already been read into memory. */
    private record FixedResponse(HttpResponse<InputStream> source, byte[] body) implements HttpResponse<byte[]> {
        @Override public int statusCode() { return source.statusCode(); }
        @Override public HttpRequest request() { return source.request(); }
        @Override public java.util.Optional<HttpResponse<byte[]>> previousResponse() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() { return source.headers(); }
        @Override public URI uri() { return source.uri(); }
        @Override public HttpClient.Version version() { return source.version(); }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return source.sslSession(); }
    }
}
