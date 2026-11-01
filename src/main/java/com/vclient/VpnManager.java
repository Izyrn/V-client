package com.vclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * V-Client's built-in VPN. It runs wireproxy (a userspace WireGuard client shipped inside V-Client) as a hidden
 * background process, connected to a free Cloudflare WARP tunnel (or a WireGuard config the player imported). For each
 * game server it opens a local port that is forwarded through the tunnel, and V-Client points Minecraft at that local
 * port. Nothing is installed and no administrator rights are needed; only the game's server connections are tunneled.
 */
final class VpnManager {
    private static final Path DIRECTORY = Path.of(
            System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")), "V-Client", "vpn");
    /** The WireGuard identity the tunnel uses: a generated Cloudflare WARP one or the player's imported config. */
    private static final Path CONFIG = DIRECTORY.resolve("vclient-vpn.conf");
    private static final Path RUN_CONFIG = DIRECTORY.resolve("run.conf");
    private static final Path ENGINE = DIRECTORY.resolve("wireproxy.exe");
    private static final Path ENGINE_LOG = DIRECTORY.resolve("wireproxy.log");
    private static final String ENGINE_RESOURCE = "/wireproxy.exe";

    private static final String WARP_REGISTER = "https://api.cloudflareclient.com/v0a2158/reg";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Set<String> INTERFACE_KEYS = Set.of("privatekey", "address", "dns", "mtu");
    private static final Set<String> PEER_KEYS = Set.of("publickey", "presharedkey", "endpoint", "allowedips",
            "persistentkeepalive");

    /** "host:port" of a game server -> the local port that reaches it through the tunnel. */
    private static final Map<String, Integer> ROUTES = new ConcurrentHashMap<>();
    private static Process engine;
    private static volatile int socksPort = -1;

    private VpnManager() {}

    /** The local SOCKS5 port that sends traffic (and DNS lookups) through the tunnel, or null while the VPN is off. */
    static Integer socksPort() {
        return isConnected() && socksPort > 0 ? socksPort : null;
    }

    static boolean hasConfig() {
        return Files.isRegularFile(CONFIG);
    }

    /** Checks the file looks like a WireGuard client config, then stores it (replacing any previous one). */
    static void importConfig(Path source) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("[interface]") || !lower.contains("[peer]") || !lower.contains("privatekey")
                || !lower.contains("endpoint")) {
            throw new IOException("That is not a WireGuard client config (it needs [Interface], PrivateKey, "
                    + "[Peer] and Endpoint).");
        }
        Files.createDirectories(DIRECTORY);
        Files.writeString(CONFIG, text, StandardCharsets.UTF_8);
    }

    static void removeConfig() throws IOException {
        Files.deleteIfExists(CONFIG);
    }

    static synchronized boolean isConnected() {
        return engine != null && engine.isAlive();
    }

    /** The local port that reaches host:port through the VPN, or null while the VPN is off. */
    static Integer route(String host, int port) {
        return isConnected() ? ROUTES.get(routeKey(host, port)) : null;
    }

    private static String routeKey(String host, int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** Starts the tunnel with one local forwarding port per server. */
    static synchronized void connect(List<InetSocketAddress> servers, Consumer<String> log) throws IOException {
        if (isConnected()) return;
        Files.createDirectories(DIRECTORY);
        if (!hasConfig()) provisionWarp(log);
        extractEngine();

        ROUTES.clear();
        StringBuilder config = new StringBuilder(baseConfig(Files.readString(CONFIG, StandardCharsets.UTF_8)));
        for (InetSocketAddress server : servers) {
            int local = freeLocalPort();
            ROUTES.put(routeKey(server.getHostString(), server.getPort()), local);
            config.append("\n[TCPClientTunnel]\nBindAddress = 127.0.0.1:").append(local)
                    .append("\nTarget = ").append(server.getHostString()).append(':').append(server.getPort())
                    .append('\n');
        }
        // A SOCKS5 port on the same tunnel for the built-in browser; the tunnel resolves host names itself.
        int socks = freeLocalPort();
        config.append("\n[Socks5]\nBindAddress = 127.0.0.1:").append(socks).append('\n');
        socksPort = socks;
        Files.writeString(RUN_CONFIG, config.toString(), StandardCharsets.UTF_8);

        log.accept("Starting the V-Client VPN tunnel...");
        Files.deleteIfExists(ENGINE_LOG);
        engine = new ProcessBuilder(ENGINE.toString(), "-c", RUN_CONFIG.toString())
                .redirectErrorStream(true).redirectOutput(ENGINE_LOG.toFile()).start();
        try {
            if (!waitUntilTunnelWorks(servers.isEmpty() ? -1 : ROUTES.get(
                    routeKey(servers.get(0).getHostString(), servers.get(0).getPort())))) {
                String detail = engineLogTail();
                stopEngine();
                throw new IOException("The VPN tunnel did not come up." + (detail.isEmpty() ? "" : " " + detail));
            }
        } catch (InterruptedException e) {
            stopEngine();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    static synchronized void disconnect() {
        stopEngine();
    }

    private static void stopEngine() {
        if (engine != null) {
            engine.destroyForcibly();
            engine = null;
        }
        socksPort = -1;
        ROUTES.clear();
    }

    /**
     * Opens the first forwarded port and waits: wireproxy dials the real server through the tunnel as soon as
     * something connects, and drops the connection straight away if the tunnel is not working. A connection that
     * stays open (the server waits for the game to talk first) means the tunnel carries traffic.
     */
    private static boolean waitUntilTunnelWorks(int localPort) throws InterruptedException {
        if (localPort < 0) return engine.isAlive();
        for (int attempt = 0; attempt < 8; attempt++) {
            Thread.sleep(1500);
            if (!engine.isAlive()) return false;
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 2000);
                probe.setSoTimeout(2500);
                try {
                    if (probe.getInputStream().read() == -1) continue; // dropped: tunnel not up yet
                } catch (SocketTimeoutException stillOpen) {
                    return true;
                }
                return true; // the server sent data, so the tunnel works
            } catch (IOException notReady) {
                // The local port may not be listening yet; try again.
            }
        }
        return false;
    }

    private static String engineLogTail() {
        try {
            List<String> lines = Files.readAllLines(ENGINE_LOG, StandardCharsets.UTF_8);
            return String.join(" ", lines.subList(Math.max(0, lines.size() - 3), lines.size())).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void extractEngine() throws IOException {
        try (var in = VpnManager.class.getResourceAsStream(ENGINE_RESOURCE)) {
            if (in == null) throw new IOException("This build of V-Client does not include the VPN engine.");
            byte[] bytes = in.readAllBytes();
            if (!Files.isRegularFile(ENGINE) || Files.size(ENGINE) != bytes.length) {
                stopEngine();
                Files.write(ENGINE, bytes);
            }
        }
    }

    private static int freeLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** Keeps only the [Interface] and [Peer] settings wireproxy understands, dropping wg-quick extras. */
    static String baseConfig(String text) {
        StringBuilder result = new StringBuilder();
        String section = "";
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[")) {
                section = line.toLowerCase(Locale.ROOT);
                if (section.equals("[interface]") || section.equals("[peer]")) result.append('\n').append(line).append('\n');
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            String key = line.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            boolean keep = section.equals("[interface]") ? INTERFACE_KEYS.contains(key)
                    : section.equals("[peer]") && PEER_KEYS.contains(key);
            if (keep) result.append(line).append('\n');
        }
        return result.toString();
    }

    /**
     * Registers a free Cloudflare WARP device (the same thing the wgcf tool does) and stores its WireGuard config,
     * so the VPN works without the player importing anything.
     */
    private static void provisionWarp(Consumer<String> log) throws IOException {
        log.accept("No VPN identity yet; creating a free Cloudflare WARP one...");
        try {
            KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            byte[] privateEncoded = pair.getPrivate().getEncoded();
            byte[] publicEncoded = pair.getPublic().getEncoded();
            Base64.Encoder base64 = Base64.getEncoder();
            String privateKey = base64.encodeToString(Arrays.copyOfRange(privateEncoded, privateEncoded.length - 32,
                    privateEncoded.length));
            String publicKey = base64.encodeToString(Arrays.copyOfRange(publicEncoded, publicEncoded.length - 32,
                    publicEncoded.length));
            ObjectNode body = JSON.createObjectNode();
            body.put("key", publicKey);
            body.put("install_id", "");
            body.put("fcm_token", "");
            body.put("tos", java.time.Instant.now().toString());
            body.put("model", "PC");
            body.put("serial_number", java.util.UUID.randomUUID().toString());
            body.put("locale", "en_US");
            var root = JSON.readTree(http("POST", WARP_REGISTER, JSON.writeValueAsString(body), new String[]{
                    "Content-Type: application/json", "User-Agent: okhttp/3.12.1",
                    "CF-Client-Version: a-6.3-1922"}, log));
            var config = root.path("config");
            String peerKey = config.path("peers").path(0).path("public_key").asText("");
            String endpoint = config.path("peers").path(0).path("endpoint").path("host").asText("");
            String v4 = config.path("interface").path("addresses").path("v4").asText("");
            String v6 = config.path("interface").path("addresses").path("v6").asText("");
            if (peerKey.isBlank() || endpoint.isBlank() || v4.isBlank()) {
                throw new IOException("Cloudflare WARP answered with an unexpected response.");
            }
            String text = "[Interface]\nPrivateKey = " + privateKey + "\nAddress = " + v4 + "/32"
                    + (v6.isBlank() ? "" : ", " + v6 + "/128") + "\nDNS = 1.1.1.1\nMTU = 1280\n\n[Peer]\nPublicKey = "
                    + peerKey + "\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = " + endpoint + "\n";
            Files.createDirectories(DIRECTORY);
            Files.writeString(CONFIG, text, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not generate a VPN key: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    /**
     * One HTTPS request returning the body text. Java's TLS stack goes first; if the server refuses that handshake,
     * Windows' own curl.exe (Schannel TLS) retries it.
     */
    private static String http(String method, String url, String body, String[] headers, Consumer<String> log)
            throws IOException, InterruptedException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
            for (String header : headers) {
                int colon = header.indexOf(':');
                builder.header(header.substring(0, colon), header.substring(colon + 1).trim());
            }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = PlatformTls.sendWithRetries(HTTP, builder.build(),
                    HttpResponse.BodyHandlers.ofString(), log);
            if (response.statusCode() / 100 != 2) {
                throw new IOException(URI.create(url).getHost() + " answered HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            log.accept("Java's secure connection to " + URI.create(url).getHost() + " failed ("
                    + PlatformTls.describeFailure(e) + "); retrying with Windows' own TLS (curl.exe).");
            return curl(method, url, body, headers);
        }
    }

    private static String curl(String method, String url, String body, String[] headers)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "System32", "curl.exe").toString(),
                "-sS", "-f", "-L", "--max-time", "60", "-X", method));
        for (String header : headers) {
            command.add("-H");
            command.add(header);
        }
        if (body != null) command.addAll(List.of("--data-binary", "@-"));
        command.add(url);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var stdin = process.getOutputStream()) {
            if (body != null) stdin.write(body.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(70, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out contacting " + URI.create(url).getHost() + ".");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Could not reach " + URI.create(url).getHost() + ": " + output.trim());
        }
        return output;
    }
}
