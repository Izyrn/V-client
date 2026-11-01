package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A loopback HTTP proxy for the built-in browser. Every host name is looked up through Cloudflare's DNS-over-HTTPS
 * endpoint (reached by IP, so it needs no DNS itself) instead of the PC's resolver, which means a network's DNS
 * filter or hijack cannot redirect the browser. HTTPS pages are tunnelled with CONNECT, so TLS still runs end to
 * end between the page and the site. If the DoH lookup fails the system resolver is used as a fallback.
 */
final class DohProxy {
    private record Answer(InetAddress address, long expiresAt) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Answer> CACHE = new ConcurrentHashMap<>();
    private static DohProxy instance;

    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "doh-proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient client = PlatformTls.httpClientBuilder(Duration.ofSeconds(5)).build();

    private DohProxy() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "doh-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Starts the proxy on first use and returns it; later calls reuse the same one. */
    static synchronized DohProxy shared() throws IOException {
        if (instance == null) instance = new DohProxy();
        return instance;
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(30_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 3) return;
            StringBuilder headers = new StringBuilder();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) headers.append(line).append("\r\n");

            if (parts[0].equalsIgnoreCase("CONNECT")) {
                String target = parts[1];
                int colon = target.lastIndexOf(':');
                String host = colon < 0 ? target : target.substring(0, colon);
                int port = colon < 0 ? 443 : Integer.parseInt(target.substring(colon + 1));
                try (Socket remote = connect(host, port)) {
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    pipe(socket, remote);
                }
            } else {
                URI uri = URI.create(parts[1]);
                if (uri.getHost() == null) return;
                int port = uri.getPort() < 0 ? 80 : uri.getPort();
                String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                try (Socket remote = connect(uri.getHost(), port)) {
                    String head = parts[0] + " " + path + " " + parts[2] + "\r\n"
                            + headers.toString().replaceAll("(?im)^proxy-connection:.*\r\n", "")
                            .replaceAll("(?im)^connection:.*\r\n", "") + "Connection: close\r\n\r\n";
                    remote.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
                    remote.getOutputStream().flush();
                    pipe(socket, remote);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // a dropped connection just ends that request
        }
    }

    private Socket connect(String host, int port) throws IOException {
        Integer vpn = VpnManager.socksPort();
        if (vpn != null) return connectThroughVpn(vpn, host, port);
        Socket remote = new Socket();
        try {
            remote.connect(new InetSocketAddress(resolve(host), port), 10_000);
        } catch (IOException e) {
            remote.close();
            throw e;
        }
        remote.setSoTimeout(60_000);
        return remote;
    }

    /**
     * With the VPN on, the site is reached through the tunnel's SOCKS5 port by name, so neither the connection nor
     * the DNS lookup leaves this PC outside the tunnel. A failure is reported, never retried outside the VPN.
     */
    private static Socket connectThroughVpn(int socksPort, String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), socksPort), 5_000);
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{5, 1, 0});
            out.flush();
            if (in.readNBytes(2).length != 2) throw new IOException("The VPN did not answer.");
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            byte[] request = new byte[7 + name.length];
            request[0] = 5;
            request[1] = 1;
            request[3] = 3;
            request[4] = (byte) name.length;
            System.arraycopy(name, 0, request, 5, name.length);
            request[5 + name.length] = (byte) (port >> 8);
            request[6 + name.length] = (byte) port;
            out.write(request);
            out.flush();
            byte[] head = in.readNBytes(4);
            if (head.length != 4 || head[1] != 0) throw new IOException("The VPN could not reach " + host + ".");
            int skip = switch (head[3]) {
                case 1 -> 6;
                case 4 -> 18;
                default -> 1 + in.read() + 2;
            };
            in.readNBytes(head[3] == 3 ? skip - 1 : skip);
            socket.setSoTimeout(60_000);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** Looks {@code host} up over DNS-over-HTTPS; falls back to the system resolver when that fails. */
    InetAddress resolve(String host) throws IOException {
        if (host.matches("[0-9.]+") || host.contains(":")) return InetAddress.getByName(host);
        Answer cached = CACHE.get(host);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.address();
        InetAddress address = lookup(host);
        if (address == null) return InetAddress.getByName(host);
        return address;
    }

    private InetAddress lookup(String host) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://1.1.1.1/dns-query?type=A&name="
                            + URLEncoder.encode(host, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/dns-json").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            for (JsonNode answer : JSON.readTree(response.body()).path("Answer")) {
                if (answer.path("type").asInt() != 1) continue;
                InetAddress address = InetAddress.getByName(answer.path("data").asText());
                long ttl = Math.max(30, Math.min(answer.path("TTL").asLong(60), 600));
                CACHE.put(host, new Answer(address, System.currentTimeMillis() + ttl * 1000));
                return address;
            }
        } catch (IOException | RuntimeException e) {
            // fall through to the system resolver
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Copies bytes both ways until either side closes. */
    private void pipe(Socket a, Socket b) throws IOException {
        Thread upstream = new Thread(() -> copy(a, b), "doh-proxy-up");
        upstream.setDaemon(true);
        upstream.start();
        copy(b, a);
        try {
            upstream.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(Socket from, Socket to) {
        byte[] buffer = new byte[16 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // the other direction ending closes this one
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.append((char) c);
        }
        return c < 0 && line.length() == 0 ? null : line.toString();
    }
}
