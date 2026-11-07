package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The browser engine behind the Browser tab: the PC's own Edge (or Chrome) running hidden with a remote-debugging
 * port, driven over the DevTools protocol. V-Client shows its pages inside its own window, so sites that need a
 * modern engine (Discord, for example) work. Its traffic goes through the secure-DNS proxy.
 */
final class ChromiumHost {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static CompletableFuture<ChromiumHost> starting;

    private final Process process;
    private final WebSocket socket;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, JsonNode>> sessions = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> targets = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chromium-send");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Consumer<String> popupHandler = targetId -> {};
    private volatile boolean closed;
    private volatile String userAgent = "";
    private volatile ObjectNode userAgentMetadata;

    private ChromiumHost(int proxyPort) throws IOException, InterruptedException {
        Path exe = findChromium();
        if (exe == null) throw new IOException("Microsoft Edge or Google Chrome was not found on this PC.");
        Path profile = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")),
                "V-Client", "browser-engine");
        Files.createDirectories(profile);
        Files.deleteIfExists(profile.resolve("DevToolsActivePort"));
        process = new ProcessBuilder(exe.toString(), "--headless=new", "--remote-debugging-port=0",
                "--remote-allow-origins=*", "--user-data-dir=" + profile,
                "--proxy-server=http://127.0.0.1:" + proxyPort, "--no-first-run", "--no-default-browser-check",
                "--disable-features=Translate", "--disable-blink-features=AutomationControlled",
                "--window-size=1280,800", "about:blank")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        try {
            socket = connect(profile.resolve("DevToolsActivePort"));
        } catch (IOException | InterruptedException | RuntimeException e) {
            shutdown();
            throw e;
        }
        send("Target.setDiscoverTargets", JSON.createObjectNode().put("discover", true), null).join();
        JsonNode version = send("Browser.getVersion", null, null).join();
        String agent = version.path("userAgent").asText("");
        userAgent = agent.replace("HeadlessChrome", "Chrome");
        userAgentMetadata = clientHints(userAgent, version.path("product").asText(""));
    }

    /**
     * The Sec-CH-UA headers and navigator.userAgentData that go with {@link #userAgent}: without them a headless engine
     * still reports "HeadlessChrome" there, which bot checks (Cloudflare's "verifying you are human" loop) read as automation.
     */
    private static ObjectNode clientHints(String agent, String product) {
        String full = product.substring(product.indexOf('/') + 1);
        String major = full.contains(".") ? full.substring(0, full.indexOf('.')) : full;
        String brand = agent.contains("Edg/") ? "Microsoft Edge" : "Google Chrome";
        ObjectNode metadata = JSON.createObjectNode();
        var brands = metadata.putArray("brands");
        brands.addObject().put("brand", "Not.A/Brand").put("version", "99");
        brands.addObject().put("brand", "Chromium").put("version", major);
        brands.addObject().put("brand", brand).put("version", major);
        var fullList = metadata.putArray("fullVersionList");
        fullList.addObject().put("brand", "Not.A/Brand").put("version", "99.0.0.0");
        fullList.addObject().put("brand", "Chromium").put("version", full);
        fullList.addObject().put("brand", brand).put("version", full);
        metadata.put("fullVersion", full).put("platform", "Windows").put("platformVersion", "10.0.0")
                .put("architecture", "x86").put("model", "").put("mobile", false).put("bitness", "64").put("wow64", false);
        return metadata;
    }

    /** Starts the engine on first use (in the background) and reuses it after that. */
    static synchronized CompletableFuture<ChromiumHost> shared(int proxyPort) {
        if (starting == null || starting.isCompletedExceptionally()
                || (starting.isDone() && !starting.join().alive())) {
            CompletableFuture<ChromiumHost> future = new CompletableFuture<>();
            starting = future;
            Thread thread = new Thread(() -> {
                try {
                    future.complete(new ChromiumHost(proxyPort));
                } catch (IOException | RuntimeException e) {
                    future.completeExceptionally(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(e);
                }
            }, "chromium-start");
            thread.setDaemon(true);
            thread.start();
        }
        return starting;
    }

    static Path findChromium() {
        String[][] candidates = {
                {"ProgramFiles(x86)", "Microsoft/Edge/Application/msedge.exe"},
                {"ProgramFiles", "Microsoft/Edge/Application/msedge.exe"},
                {"ProgramFiles", "Google/Chrome/Application/chrome.exe"},
                {"ProgramFiles(x86)", "Google/Chrome/Application/chrome.exe"},
                {"LOCALAPPDATA", "Google/Chrome/Application/chrome.exe"}};
        for (String[] candidate : candidates) {
            String base = System.getenv(candidate[0]);
            if (base == null) continue;
            Path exe = Path.of(base, candidate[1]);
            if (Files.isRegularFile(exe)) return exe;
        }
        return null;
    }

    boolean alive() {
        return !closed && process.isAlive();
    }

    String userAgent() {
        return userAgent;
    }

    ObjectNode userAgentMetadata() {
        return userAgentMetadata;
    }

    /** Called with the id of a page the engine opened by itself (a link that opens a new window). */
    void onPopup(Consumer<String> handler) {
        popupHandler = handler;
    }

    void onSession(String sessionId, BiConsumer<String, JsonNode> handler) {
        sessions.put(sessionId, handler);
    }

    void onTarget(String targetId, Consumer<JsonNode> handler) {
        targets.put(targetId, handler);
    }

    void forget(String sessionId, String targetId) {
        if (sessionId != null) sessions.remove(sessionId);
        if (targetId != null) targets.remove(targetId);
    }

    CompletableFuture<JsonNode> send(String method, ObjectNode params, String sessionId) {
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new IOException("The browser engine is not running."));
            return result;
        }
        int id = ids.incrementAndGet();
        pending.put(id, result);
        ObjectNode message = JSON.createObjectNode().put("id", id).put("method", method);
        if (params != null) message.set("params", params);
        if (sessionId != null) message.put("sessionId", sessionId);
        String text = message.toString();
        sender.execute(() -> {
            try {
                socket.sendText(text, true).get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                pending.remove(id);
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    static ObjectNode params() {
        return JSON.createObjectNode();
    }

    // ---- connection ----------------------------------------------------------------------------------

    private WebSocket connect(Path portFile) throws IOException, InterruptedException {
        String port = null;
        String path = null;
        for (int i = 0; i < 100 && path == null; i++) {
            if (!process.isAlive()) throw new IOException("The browser engine closed right after starting.");
            if (Files.isRegularFile(portFile)) {
                List<String> lines = Files.readAllLines(portFile);
                if (lines.size() >= 2) {
                    port = lines.get(0).trim();
                    path = lines.get(1).trim();
                }
            }
            if (path == null) Thread.sleep(150);
        }
        if (path == null) throw new IOException("The browser engine did not start in time.");
        try {
            return HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + path), new Listener())
                    .get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("Could not connect to the browser engine: " + e.getMessage(), e);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(JSON.readTree(message));
                } catch (IOException | RuntimeException e) {
                    // one malformed message must not stop the stream
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ChromiumHost.this.closeAll();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ChromiumHost.this.closeAll();
        }
    }

    private void handle(JsonNode message) {
        if (message.has("id")) {
            CompletableFuture<JsonNode> waiting = pending.remove(message.path("id").asInt());
            if (waiting == null) return;
            if (message.has("error")) {
                waiting.completeExceptionally(new IOException(message.path("error").path("message").asText("error")));
            } else {
                waiting.complete(message.path("result"));
            }
            return;
        }
        String method = message.path("method").asText("");
        JsonNode params = message.path("params");
        String sessionId = message.path("sessionId").asText(null);
        if (sessionId != null) {
            BiConsumer<String, JsonNode> handler = sessions.get(sessionId);
            if (handler != null) handler.accept(method, params);
            return;
        }
        JsonNode info = params.path("targetInfo");
        switch (method) {
            case "Target.targetInfoChanged" -> {
                Consumer<JsonNode> handler = targets.get(info.path("targetId").asText());
                if (handler != null) handler.accept(info);
            }
            case "Target.targetCreated" -> {
                if (info.path("type").asText().equals("page") && info.hasNonNull("openerId")) {
                    popupHandler.accept(info.path("targetId").asText());
                }
            }
            default -> { }
        }
    }

    private void closeAll() {
        closed = true;
        IOException gone = new IOException("The browser engine stopped.");
        pending.values().forEach(future -> future.completeExceptionally(gone));
        pending.clear();
        sessions.values().forEach(handler -> handler.accept("ChromiumHost.closed", JSON.createObjectNode()));
    }

    private void shutdown() {
        closed = true;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
