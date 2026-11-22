package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube search without an API key or account: reads the results page's embedded data, the same list a browser shows.
 * Favorites are kept on this PC only (%APPDATA%\VYRN\youtube-favorites.json).
 */
final class YouTubeService {
    enum Kind {
        VIDEOS("Videos", "EgIQAQ%3D%3D", ""), MUSIC("Music", "EgIQAQ%3D%3D", " music"),
        PLAYLISTS("Playlists", "EgIQAw%3D%3D", "");

        final String label;
        final String filter;
        final String suffix;

        Kind(String label, String filter, String suffix) {
            this.label = label;
            this.filter = filter;
            this.suffix = suffix;
        }
    }

    /** One search result or favorite. {@code playlist} results play as a playlist. */
    record Item(String id, String title, String channel, String detail, String thumbnail, boolean playlist) {
        String watchUrl() {
            return playlist ? "https://www.youtube.com/playlist?list=" + id : "https://www.youtube.com/watch?v=" + id;
        }

        String embedUrl() {
            return playlist ? "https://www.youtube.com/embed/videoseries?autoplay=1&list=" + id
                    : "https://www.youtube.com/embed/" + id + "?autoplay=1";
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INITIAL_DATA = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>", Pattern.DOTALL);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{6,64}");
    private static final String AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/131.0 Safari/537.36";
    private static final HttpClient DIRECT = PlatformTls.httpClientBuilder(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private final Path favoritesFile;

    YouTubeService() {
        this(AppInfo.dataDir().resolve("youtube-favorites.json"));
    }

    YouTubeService(Path favoritesFile) {
        this.favoritesFile = favoritesFile;
    }

    List<Item> search(String query, Kind kind) throws IOException, InterruptedException {
        String text = query == null ? "" : query.strip();
        if (text.isEmpty()) return List.of();
        URI uri = URI.create("https://www.youtube.com/results?search_query="
                + URLEncoder.encode(text + kind.suffix, StandardCharsets.UTF_8) + "&sp=" + kind.filter);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", AGENT).header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "SOCS=CAI; CONSENT=YES+1").GET().build();
        String page;
        try {
            page = fetch(DIRECT, request);
        } catch (IOException direct) {
            // Where YouTube is blocked by the network's DNS, go through VYRN's secure-DNS proxy instead.
            HttpClient proxied = PlatformTls.httpClientBuilder(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", DohProxy.shared().port()))).build();
            page = fetch(proxied, request);
        }
        return parse(page, kind == Kind.PLAYLISTS);
    }

    private static String fetch(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("YouTube answered HTTP " + response.statusCode());
        return response.body();
    }

    /** Pulls videos or playlists out of a results page. Unknown shapes are skipped, never fatal. */
    static List<Item> parse(String page, boolean playlists) throws IOException {
        Matcher matcher = INITIAL_DATA.matcher(page);
        if (!matcher.find()) throw new IOException("YouTube's results page had no result data (the layout may have changed).");
        JsonNode root = JSON.readTree(matcher.group(1));
        List<Item> items = new ArrayList<>();
        collect(root, items, playlists);
        return items;
    }

    private static void collect(JsonNode node, List<Item> items, boolean playlists) {
        if (node == null || items.size() >= 30) return;
        if (node.isObject()) {
            if (!playlists && node.has("videoRenderer")) {
                Item item = video(node.get("videoRenderer"));
                if (item != null) items.add(item);
                return;
            }
            if (playlists && node.has("lockupViewModel")) {
                Item item = playlist(node.get("lockupViewModel"));
                if (item != null) items.add(item);
                return;
            }
            node.elements().forEachRemaining(child -> collect(child, items, playlists));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collect(child, items, playlists));
        }
    }

    private static Item video(JsonNode video) {
        String id = video.path("videoId").asText("");
        if (!SAFE_ID.matcher(id).matches()) return null;
        String title = runs(video.path("title"));
        String channel = runs(video.path("ownerText"));
        String length = video.path("lengthText").path("simpleText").asText("");
        String views = video.path("viewCountText").path("simpleText").asText(runs(video.path("viewCountText")));
        boolean live = length.isEmpty() && !views.isEmpty() && views.toLowerCase(java.util.Locale.ROOT).contains("watching");
        String detail = live ? "LIVE · " + views : join(length, views);
        return new Item(id, title, channel, detail, "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", false);
    }

    private static Item playlist(JsonNode lockup) {
        if (!"LOCKUP_CONTENT_TYPE_PLAYLIST".equals(lockup.path("contentType").asText(""))) return null;
        String id = lockup.path("contentId").asText("");
        if (!SAFE_ID.matcher(id).matches()) return null;
        JsonNode metadata = lockup.path("metadata").path("lockupMetadataViewModel");
        String title = metadata.path("title").path("content").asText("Playlist");
        String channel = metadata.path("metadata").path("contentMetadataViewModel").path("metadataRows").path(0)
                .path("metadataParts").path(0).path("text").path("content").asText("");
        JsonNode thumbnail = lockup.path("contentImage").path("collectionThumbnailViewModel").path("primaryThumbnail")
                .path("thumbnailViewModel");
        String image = thumbnail.path("image").path("sources").path(0).path("url").asText("");
        String count = "";
        for (JsonNode overlay : thumbnail.path("overlays")) {
            String text = overlay.path("thumbnailOverlayBadgeViewModel").path("thumbnailBadges").path(0)
                    .path("thumbnailBadgeViewModel").path("text").asText("");
            if (!text.isEmpty()) count = text;
        }
        return new Item(id, title, channel, count.isEmpty() ? "Playlist" : "Playlist · " + count, image, true);
    }

    private static String runs(JsonNode text) {
        if (text.has("simpleText")) return text.path("simpleText").asText("");
        StringBuilder out = new StringBuilder();
        for (JsonNode run : text.path("runs")) out.append(run.path("text").asText(""));
        return out.toString();
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " · " + b;
    }

    // ---- favorites ---------------------------------------------------------------------------------

    synchronized List<Item> favorites() {
        List<Item> items = new ArrayList<>();
        if (!Files.isRegularFile(favoritesFile)) return items;
        try {
            for (JsonNode node : JSON.readTree(favoritesFile.toFile())) {
                String id = node.path("id").asText("");
                if (!SAFE_ID.matcher(id).matches()) continue;
                items.add(new Item(id, node.path("title").asText(""), node.path("channel").asText(""),
                        node.path("detail").asText(""), node.path("thumbnail").asText(""),
                        node.path("playlist").asBoolean(false)));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read YouTube favorites: " + e);
        }
        return items;
    }

    synchronized boolean isFavorite(Item item) {
        return favorites().stream().anyMatch(each -> each.id().equals(item.id()));
    }

    /** Adds or removes the item; returns whether it is a favorite afterwards. */
    synchronized boolean toggleFavorite(Item item) throws IOException {
        List<Item> items = favorites();
        boolean removed = items.removeIf(each -> each.id().equals(item.id()));
        if (!removed) items.addFirst(item);
        ArrayNode array = JSON.createArrayNode();
        for (Item each : items) {
            ObjectNode node = array.addObject();
            node.put("id", each.id()).put("title", each.title()).put("channel", each.channel())
                    .put("detail", each.detail()).put("thumbnail", each.thumbnail()).put("playlist", each.playlist());
        }
        Files.createDirectories(favoritesFile.getParent());
        Path temporary = favoritesFile.resolveSibling(favoritesFile.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), array);
        Files.move(temporary, favoritesFile, StandardCopyOption.REPLACE_EXISTING);
        return !removed;
    }
}
