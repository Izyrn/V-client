package com.vclient;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendClientTest {
    private HttpServer server;
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/api/news", 200, "{\"items\":[{\"id\":4,\"title\":\"Hi\",\"body\":\"Body\",\"category\":\"server\",\"pinned\":true,\"createdAt\":\"2026-01-02T03:04:05Z\"}]}");
        respond("/api/announcements", 200, "{\"items\":[{\"id\":1,\"message\":\"Maintenance\",\"level\":\"warning\"}]}");
        respond("/api/server/status", 200, "{\"java\":{\"online\":true,\"players\":{\"online\":3,\"max\":20},\"version\":\"1.21.11\",\"latencyMs\":42},\"bedrock\":{\"online\":false}}");
        respond("/api/launcher/update", 200, "{\"updateAvailable\":true,\"mandatory\":false,\"latest\":{\"version\":\"1.2.0\",\"notes\":\"New\",\"mandatory\":false,\"size\":10,\"sha256\":\"ab\",\"url\":\"/downloads/V-Client-windows-1.2.0.exe\"}}");
        respond("/api/auth/login", 401, "{\"error\":\"Incorrect username or password\"}");
        respond("/api/auth/me", 200, "{\"user\":{\"username\":\"Steve\",\"minecraftName\":\"Steve\",\"elybyName\":\"\"}}");
        respond("/api/stats/event", 202, "{\"ok\":true}");
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, int status, String json) {
        server.createContext(path, exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private BackendClient client() {
        return new BackendClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Test
    void parsesNewsAnnouncementsAndStatus() throws Exception {
        BackendClient client = client();
        List<BackendClient.NewsPost> news = client.news(5);
        assertEquals(1, news.size());
        assertEquals("Hi", news.getFirst().title());
        assertTrue(news.getFirst().pinned());
        assertEquals("warning", client.announcements().getFirst().level());
        BackendClient.ServerStatus status = client.serverStatus();
        assertTrue(status.online());
        assertEquals(3, status.players());
        assertEquals(20, status.maxPlayers());
    }

    @Test
    void updateInfoUsesAbsoluteInstallerUrl() throws Exception {
        BackendClient client = client();
        BackendClient.UpdateInfo info = client.checkBackendForUpdate("1.0.0");
        assertTrue(info.updateAvailable());
        assertNotNull(info.latest());
        assertEquals("1.2.0", info.latest().version());
        assertEquals(client.baseUrl() + "/downloads/V-Client-windows-1.2.0.exe", info.latest().url());
    }

    @Test
    void errorMessagesFromTheBackendReachThePlayer() {
        BackendClient.BackendException error = assertThrows(BackendClient.BackendException.class,
                () -> client().login("steve", "wrong"));
        assertEquals("Incorrect username or password", error.getMessage());
    }

    @Test
    void profileSendsBearerToken() throws Exception {
        BackendClient.Session session = client().profile("tok123");
        assertEquals("Bearer tok123", lastAuthorization.get());
        assertEquals("Steve", session.username());
    }

    @Test
    void eventReportCarriesPlatformAndVersion() {
        client().reportEvent("a1b2c3d4-e5f6", "launch");
        assertTrue(lastBody.get().contains("\"platform\":\"windows\""));
        assertTrue(lastBody.get().contains("\"event\":\"launch\""));
        assertTrue(lastBody.get().contains("\"version\":\"" + AppInfo.VERSION + "\""));
    }

    @Test
    void unconfiguredClientFailsClearlyAndStatisticsStaySilent() {
        BackendClient none = new BackendClient("");
        assertFalse(none.configured());
        assertThrows(BackendClient.BackendException.class, () -> none.news(5));
        none.reportEvent("a1b2c3d4-e5f6", "launch");
    }

    @Test
    void urlValidationAcceptsOnlyHttpAddresses() {
        assertTrue(BackendClient.isValidUrl("https://vclient.example.com/"));
        assertTrue(BackendClient.isValidUrl("http://localhost:8080"));
        assertFalse(BackendClient.isValidUrl("file:///C:/secret"));
        assertFalse(BackendClient.isValidUrl("javascript:alert(1)"));
        assertFalse(BackendClient.isValidUrl("not a url"));
        assertEquals("https://x.test", BackendClient.normalize(" https://x.test// "));
    }

    @Test
    void versionComparisonIsNumeric() {
        assertTrue(UpdateService.isNewer("1.10.0", "1.9.9"));
        assertTrue(UpdateService.isNewer("1.1", "1.0.9"));
        assertFalse(UpdateService.isNewer("1.0.0", "1.0"));
        assertFalse(UpdateService.isNewer("0.9", "1.0.0"));
    }
}
