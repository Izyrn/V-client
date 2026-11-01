package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Client for the shared V-Client backend API (news, announcements, server status, updates, accounts). */
final class BackendClient {
    record NewsPost(int id, String title, String body, String category, boolean pinned, String createdAt) {
    }

    record Announcement(int id, String message, String level) {
    }

    record ServerStatus(boolean online, int players, int maxPlayers, String version, long latencyMs) {
        static final ServerStatus OFFLINE = new ServerStatus(false, 0, 0, "", 0);
    }

    record Release(String version, String notes, boolean mandatory, long size, String sha256, String url) {
    }

    /** {@code latest} is null when no release has been published for the platform. */
    record UpdateInfo(boolean updateAvailable, boolean mandatory, Release latest) {
    }

    /** A friend with presence: {@code online} is true while their launcher is sending heartbeats. */
    record Friend(int userId, String username, String minecraftName, boolean online, String activity, String server) {}

    /** A pending friend request; {@code userId} / {@code username} are the other person. */
    record FriendRequest(int requestId, int userId, String username) {}

    record FriendsOverview(List<Friend> friends, List<FriendRequest> incoming, List<FriendRequest> outgoing) {}

    record Session(String token, String username, String minecraftName, String elybyName) {
    }

    /** The backend answered with an error message intended for the player. */
    static final class BackendException extends IOException {
        BackendException(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private final String baseUrl;

    BackendClient(String configuredUrl) {
        this.baseUrl = normalize(configuredUrl);
    }

    /** Resolves the effective URL: environment override, then the player's setting, then the built-in default. */
    static String resolveUrl(String settingValue) {
        String fromEnvironment = System.getenv("VCLIENT_BACKEND_URL");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment;
        if (settingValue != null && !settingValue.isBlank()) return settingValue;
        return AppInfo.DEFAULT_BACKEND_URL;
    }

    static String normalize(String url) {
        if (url == null) return "";
        String trimmed = url.strip();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    /** Only http(s) URLs with a host are accepted so a typo cannot point the launcher at a file or script. */
    static boolean isValidUrl(String url) {
        try {
            URI uri = URI.create(normalize(url));
            return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    boolean configured() {
        return isValidUrl(baseUrl);
    }

    String baseUrl() {
        return baseUrl;
    }

    List<NewsPost> news(int limit) throws IOException, InterruptedException {
        JsonNode items = get("/api/news?limit=" + limit).path("items");
        List<NewsPost> posts = new ArrayList<>();
        for (JsonNode item : items) {
            posts.add(new NewsPost(item.path("id").asInt(), item.path("title").asText(), item.path("body").asText(),
                    item.path("category").asText("launcher"), item.path("pinned").asBoolean(), item.path("createdAt").asText()));
        }
        return posts;
    }

    List<Announcement> announcements() throws IOException, InterruptedException {
        List<Announcement> result = new ArrayList<>();
        for (JsonNode item : get("/api/announcements").path("items")) {
            result.add(new Announcement(item.path("id").asInt(), item.path("message").asText(), item.path("level").asText("info")));
        }
        return result;
    }

    ServerStatus serverStatus() throws IOException, InterruptedException {
        JsonNode root = get("/api/server/status");
        JsonNode java = root.path("java");
        if (!java.path("online").asBoolean()) return ServerStatus.OFFLINE;
        return new ServerStatus(true, java.path("players").path("online").asInt(), java.path("players").path("max").asInt(),
                java.path("version").asText(""), java.path("latencyMs").asLong());
    }

    /** The website on GitHub Pages: its update.json and installer are published by every push to main. */
    static final String SITE_URL = "https://eliteprofast.github.io/V-client";

    /**
     * Reads the release from the website's update.json (GitHub Pages) first, and only asks the backend when the site
     * cannot be reached, so updates do not depend on a separately hosted server.
     */
    UpdateInfo checkForUpdate(String currentVersion) throws IOException, InterruptedException {
        try {
            return checkSiteForUpdate(currentVersion);
        } catch (IOException | RuntimeException e) {
            requestLog.accept("Update check via website failed (" + e.getMessage() + "); asking the backend");
        }
        return checkBackendForUpdate(currentVersion);
    }

    private UpdateInfo checkSiteForUpdate(String currentVersion) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SITE_URL + "/update.json?t=" + System.currentTimeMillis()))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).header("Accept", "application/json")
                .header("User-Agent", "V-Client/" + AppInfo.VERSION + " (" + AppInfo.PLATFORM + ")").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        JsonNode root = JSON.readTree(response.body());
        String version = root.path("version").asText("");
        if (version.isBlank()) throw new IOException("update.json has no version");
        String url = root.path("url").asText("");
        if (!url.isEmpty() && !url.startsWith("http")) url = SITE_URL + (url.startsWith("/") ? "" : "/") + url;
        boolean mandatory = root.path("mandatory").asBoolean();
        Release release = new Release(version, root.path("notes").asText(""), mandatory, root.path("size").asLong(),
                root.path("sha256").asText(""), url);
        boolean newer = UpdateService.isNewer(version, currentVersion);
        boolean unsupported = UpdateService.isNewer(root.path("minSupported").asText("0.0.0"), currentVersion);
        return new UpdateInfo(newer, mandatory || unsupported, release);
    }

    UpdateInfo checkBackendForUpdate(String currentVersion) throws IOException, InterruptedException {
        JsonNode root = get("/api/launcher/update?platform=" + AppInfo.PLATFORM + "&current=" + encode(currentVersion));
        JsonNode latest = root.path("latest");
        Release release = latest.isObject()
                ? new Release(latest.path("version").asText(), latest.path("notes").asText(""), latest.path("mandatory").asBoolean(),
                latest.path("size").asLong(), latest.path("sha256").asText(""), absolute(latest.path("url").asText("")))
                : null;
        return new UpdateInfo(root.path("updateAvailable").asBoolean(), root.path("mandatory").asBoolean(), release);
    }

    Session login(String login, String password) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("login", login).put("password", password);
        return session(send("POST", "/api/auth/login", body, null));
    }

    Session register(String username, String email, String password) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("username", username).put("email", email).put("password", password);
        return session(send("POST", "/api/auth/register", body, null));
    }

    /** Refreshes the profile for a saved token; throws {@link BackendException} when the token expired. */
    Session profile(String token) throws IOException, InterruptedException {
        JsonNode user = send("GET", "/api/auth/me", null, token).path("user");
        return new Session(token, user.path("username").asText(), user.path("minecraftName").asText(), user.path("elybyName").asText());
    }

    Session linkNames(String token, String minecraftName, String elybyName) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("minecraftName", minecraftName).put("elybyName", elybyName);
        JsonNode user = send("PUT", "/api/auth/profile", body, token).path("user");
        return new Session(token, user.path("username").asText(), user.path("minecraftName").asText(), user.path("elybyName").asText());
    }

    /** Anonymous usage counter; failures are ignored because statistics must never disturb the player. */
    void reportEvent(String installId, String event) {
        if (!configured() || installId == null || installId.isBlank()) return;
        try {
            ObjectNode body = JSON.createObjectNode().put("installId", installId).put("platform", AppInfo.PLATFORM)
                    .put("version", AppInfo.VERSION).put("event", event);
            send("POST", "/api/stats/event", body, null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** The raw cosmetics catalog document (schema documented in docs/COSMETICS_API.md). */
    JsonNode cosmeticsCatalog() throws IOException, InterruptedException {
        return get("/api/cosmetics/catalog");
    }

    /** Ids of the premium/event cosmetics granted to the Minecraft account with this UUID. */
    java.util.Set<String> ownedCosmetics(String uuid) throws IOException, InterruptedException {
        java.util.Set<String> owned = new java.util.HashSet<>();
        for (JsonNode id : get("/api/cosmetics/owned/" + encode(uuid.replace("-", ""))).path("owned")) {
            owned.add(id.asText());
        }
        return owned;
    }

    FriendsOverview friends(String token) throws IOException, InterruptedException {
        JsonNode root = send("GET", "/api/friends", null, token);
        List<Friend> friends = new ArrayList<>();
        for (JsonNode node : root.path("friends")) {
            friends.add(new Friend(node.path("userId").asInt(), node.path("username").asText(),
                    node.path("minecraftName").asText(""), node.path("online").asBoolean(),
                    node.path("activity").asText(""), node.path("server").asText("")));
        }
        return new FriendsOverview(List.copyOf(friends), requests(root.path("incoming")), requests(root.path("outgoing")));
    }

    private static List<FriendRequest> requests(JsonNode nodes) {
        List<FriendRequest> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            result.add(new FriendRequest(node.path("requestId").asInt(), node.path("userId").asInt(), node.path("username").asText()));
        }
        return List.copyOf(result);
    }

    /** Sends a friend request; returns "pending", or "accepted" when they had already asked you. */
    String requestFriend(String token, String username) throws IOException, InterruptedException {
        return send("POST", "/api/friends/request", JSON.createObjectNode().put("username", username), token)
                .path("status").asText("pending");
    }

    void acceptFriend(String token, int requestId) throws IOException, InterruptedException {
        send("POST", "/api/friends/requests/" + requestId + "/accept", JSON.createObjectNode(), token);
    }

    /** Declines an incoming request or cancels an outgoing one. */
    void dropFriendRequest(String token, int requestId) throws IOException, InterruptedException {
        send("DELETE", "/api/friends/requests/" + requestId, null, token);
    }

    void removeFriend(String token, int userId) throws IOException, InterruptedException {
        send("DELETE", "/api/friends/" + userId, null, token);
    }

    /** The launcher's heartbeat: tells friends you are online and what you are doing. */
    void sendPresence(String token, String activity, String server) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("activity", activity).put("server", server).put("platform", AppInfo.PLATFORM);
        send("PUT", "/api/presence", body, token);
    }

    // ---- Admin panel: players who have played, and cosmetic grants ----------------------------------

    record AdminPlayer(String uuid, String name, String accountType, String lastSeen, int launches, int grants) {}

    record AdminOverview(int total, int activeToday, int accounts, List<AdminPlayer> players) {}

    record AdminReward(int invites, List<String> cosmetics) {}

    record GrantResult(List<String> uuids, List<String> granted) {}

    /** Tells the backend which account started the game; failures are ignored because it only feeds the admin list. */
    void recordPlayer(String uuid, String name, String accountType) {
        if (!configured() || uuid == null || uuid.isBlank() || name == null || name.isBlank()) return;
        try {
            ObjectNode body = JSON.createObjectNode().put("uuid", uuid.replace("-", "")).put("name", name)
                    .put("accountType", accountType).put("version", AppInfo.VERSION);
            send("POST", "/api/players/seen", body, null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    boolean isAdmin(String token) throws IOException, InterruptedException {
        return "admin".equals(send("GET", "/api/auth/me", null, token).path("user").path("role").asText(""));
    }

    AdminOverview adminPlayers(String token, String query) throws IOException, InterruptedException {
        JsonNode root = send("GET", "/api/admin/players?limit=200&q=" + encode(query == null ? "" : query), null, token);
        List<AdminPlayer> players = new ArrayList<>();
        for (JsonNode node : root.path("items")) {
            players.add(new AdminPlayer(node.path("uuid").asText(), node.path("name").asText(), node.path("accountType").asText(),
                    node.path("lastSeen").asText(""), node.path("launches").asInt(), node.path("grants").asInt()));
        }
        return new AdminOverview(root.path("total").asInt(), root.path("activeToday").asInt(), root.path("accounts").asInt(),
                List.copyOf(players));
    }

    /** Ids of the cosmetics granted to one UUID. */
    List<String> adminGrants(String token, String uuid) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : send("GET", "/api/admin/cosmetics/grants?uuid=" + encode(uuid), null, token).path("items")) {
            ids.add(node.path("cosmeticId").asText());
        }
        return ids;
    }

    /** Grants one cosmetic, or an invite-reward tier when {@code invites} is not null, to a player name or UUID. */
    GrantResult adminGrant(String token, String player, String cosmeticId, Integer invites) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("player", player);
        if (invites != null) body.put("invites", invites);
        else body.put("cosmeticId", cosmeticId);
        JsonNode root = send("POST", "/api/admin/cosmetics/grants", body, token);
        List<String> uuids = new ArrayList<>();
        root.path("uuids").forEach(node -> uuids.add(node.asText()));
        List<String> granted = new ArrayList<>();
        root.path("granted").forEach(node -> granted.add(node.asText()));
        return new GrantResult(List.copyOf(uuids), List.copyOf(granted));
    }

    void adminRevoke(String token, String uuid, String cosmeticId) throws IOException, InterruptedException {
        try {
            send("DELETE", "/api/admin/cosmetics/grants", JSON.createObjectNode().put("uuid", uuid).put("cosmeticId", cosmeticId), token);
        } catch (BackendException e) {
            if (!e.getMessage().toLowerCase().contains("not found")) throw e;
        }
    }

    List<AdminReward> adminRewards(String token) throws IOException, InterruptedException {
        List<AdminReward> rewards = new ArrayList<>();
        for (JsonNode node : send("GET", "/api/admin/cosmetics/rewards", null, token).path("items")) {
            List<String> ids = new ArrayList<>();
            node.path("cosmetics").forEach(id -> ids.add(id.asText()));
            rewards.add(new AdminReward(node.path("invites").asInt(), List.copyOf(ids)));
        }
        return rewards;
    }

    private Session session(JsonNode root) {
        JsonNode user = root.path("user");
        return new Session(root.path("token").asText(), user.path("username").asText(),
                user.path("minecraftName").asText(), user.path("elybyName").asText());
    }

    private String absolute(String path) {
        if (path.isEmpty()) return "";
        return path.startsWith("http") ? path : baseUrl + path;
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, null);
    }

    /** Where every request is reported (URL, status, time, error); the launcher points it at its log. */
    private static volatile java.util.function.Consumer<String> requestLog = line -> {};
    private static volatile boolean debug;

    /** Makes every backend request log its URL and status; with {@code debug} failures also log their stack trace. */
    static void setRequestLog(java.util.function.Consumer<String> log, boolean debugMode) {
        requestLog = log == null ? line -> {} : log;
        debug = debugMode;
    }

    static String stackTrace(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    /** Backend requests give up after this long, so a sleeping or unreachable server never means "loading forever". */
    static final int TIMEOUT_SECONDS = 10;

    private JsonNode send(String method, String path, ObjectNode body, String token) throws IOException, InterruptedException {
        if (!configured()) throw new BackendException("The V-Client backend address is not set. Add it on the News page.");
        String url = baseUrl + path;
        long start = System.nanoTime();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("Accept", "application/json")
                    .header("User-Agent", "V-Client/" + AppInfo.VERSION + " (" + AppInfo.PLATFORM + ")");
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requestLog.accept("Backend " + method + " " + url + " -> HTTP " + response.statusCode()
                    + " (" + (System.nanoTime() - start) / 1_000_000 + " ms)");
            JsonNode root;
            try {
                root = response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
            } catch (IOException e) {
                String type = response.headers().firstValue("Content-Type").orElse("no content type");
                boolean plainNotFound = response.statusCode() == 404 && type.startsWith("text/plain");
                throw new IOException(plainNotFound
                        ? "This address answers /api requests with a plain \"Not Found\" (HTTP 404), so it is not running the "
                        + "V-Client backend. It is probably the website's static hosting. Point the launcher at the Node backend "
                        + "service instead (see backend/README.md and render.yaml)."
                        : "The backend returned something that is not JSON (HTTP " + response.statusCode() + ", " + type + "). "
                        + "Check the backend address; a free host may still be waking up.", e);
            }
            if (response.statusCode() >= 400) {
                String message = root.path("error").asText("");
                throw new BackendException(message.isBlank() ? "The backend returned HTTP " + response.statusCode() + "." : message);
            }
            return root;
        } catch (java.net.http.HttpTimeoutException e) {
            IOException timeout = new IOException("The backend did not answer within " + TIMEOUT_SECONDS + " seconds ("
                    + url + "). If it is hosted on a free plan it may be waking up; try again in a minute.", e);
            requestLog.accept("Backend " + method + " " + url + " -> FAILED: " + timeout.getMessage()
                    + (debug ? "\n" + stackTrace(e) : ""));
            throw timeout;
        } catch (IOException | RuntimeException e) {
            requestLog.accept("Backend " + method + " " + url + " -> FAILED: " + e.getMessage()
                    + (debug ? "\n" + stackTrace(e) : ""));
            if (e instanceof RuntimeException) throw new IOException("Invalid backend request " + url + ": " + e.getMessage(), e);
            throw e;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
