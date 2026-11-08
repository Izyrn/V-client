package com.vclient;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LauncherUpgradeTest {
    @Test
    void dohAnswersKeepTheHostNameSoTlsNeverChecksABareIp() throws Exception {
        InetAddress address = DohProxy.named("api.minecraftservices.com", "150.171.109.163");
        assertEquals("api.minecraftservices.com", address.getHostName());
        assertEquals("150.171.109.163", address.getHostAddress());
    }

    @Test
    void dohServersAreHostNamesAndIpUrlsAreRefused() {
        assertTrue(DohProxy.isDohHost("dns.google"));
        assertTrue(PlatformTls.isIpLiteral(URI.create("https://150.171.109.163/x")));
        assertThrows(IllegalArgumentException.class, () -> PlatformTls.requireHostName(URI.create("https://1.1.1.1/dns-query")));
        assertDoesNotThrow(() -> PlatformTls.requireHostName(URI.create("https://piston-meta.mojang.com/x")));
    }

    @Test
    void dohServerBootstrapAddressesKeepTheirHostName() {
        var addresses = DohProxy.bootstrap("cloudflare-dns.com");
        assertFalse(addresses.isEmpty());
        assertEquals("cloudflare-dns.com", addresses.getFirst().getHostName());
        assertTrue(DohProxy.bootstrap("discord.com").isEmpty());
    }

    @Test
    void ipSanErrorIsRecognisedAsHostNameMismatch() {
        var error = new javax.net.ssl.SSLHandshakeException("No subject alternative names matching IP address 150.171.109.163 found");
        assertTrue(PlatformTls.hasHostnameMismatch(error));
        assertFalse(PlatformTls.interceptionHint(error).isEmpty());
    }

    @Test
    void jvmArgsRejectCodeLoadingAndLauncherPermissionFlags() {
        assertNull(JvmArgs.validate("-XX:+UseG1GC -Dfile.encoding=UTF-8 -Xss2M"));
        assertNotNull(JvmArgs.validate("-javaagent:evil.jar"));
        assertNotNull(JvmArgs.validate("-Dvclient.cheats=true"));
        assertNotNull(JvmArgs.validate("-XX:OnError=calc.exe"));
        assertNotNull(JvmArgs.validate("--add-opens x"));
        assertEquals(2, JvmArgs.parse("-Xss2M -javaagent:x.jar -XX:+UseG1GC").size());
    }

    @Test
    void profileImportDropsUnsafeJvmArgumentsAndClampsMemory(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("p.json");
        java.nio.file.Files.writeString(file, "{\"kind\":\"v-client-profile\",\"memoryGb\":999,\"jvmArguments\":\"-Xss2M -javaagent:x.jar\"}");
        LauncherSettings settings = LauncherSettings.defaults(dir);
        ProfileTransfer.importInto(settings, file);
        assertEquals(LauncherSettings.maxMemory(), settings.memoryGb());
        assertEquals("-Xss2M", settings.jvmArguments());
        java.nio.file.Files.writeString(file, "{\"kind\":\"other\"}");
        assertThrows(java.io.IOException.class, () -> ProfileTransfer.importInto(settings, file));
    }

    @Test
    void friendlyErrorsAreShortAndKeepDetails() {
        var message = FriendlyError.of(new java.io.IOException("Minecraft exited with code 1."));
        assertEquals("Minecraft crashed", message.title());
        assertTrue(message.details().contains("exited with code 1"));
    }

    @Test
    void downloadStatsParseProgressLines() {
        DownloadStats stats = new DownloadStats();
        stats.start();
        assertTrue(stats.accept("Asset preparation progress: 250/1000"));
        assertEquals(0.25, stats.fraction(), 0.001);
        assertEquals("1m 5s", DownloadStats.formatDuration(65));
    }

    @Test
    void backendRetriesWhileWakingThenCachesTheLastAnswer(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/server/status", exchange -> {
            boolean waking = calls.incrementAndGet() < 3;
            byte[] body = (waking ? "<html>starting</html>"
                    : "{\"java\":{\"online\":true,\"players\":{\"online\":7,\"max\":20},\"version\":\"1.21.11\",\"latencyMs\":5}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", waking ? "text/html" : "application/json");
            exchange.sendResponseHeaders(waking ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        BackendClient.setCacheFile(dir.resolve("cache.json"));
        BackendClient.retryDelayScale = 1000;
        try {
            BackendClient client = new BackendClient("http://127.0.0.1:" + server.getAddress().getPort());
            assertNull(client.cachedServerStatus());
            assertEquals(7, client.serverStatus().players());
            assertEquals(3, calls.get());
            assertEquals(7, client.cachedServerStatus().value().players());
        } finally {
            BackendClient.retryDelayScale = 1;
            server.stop(0);
        }
    }
}
