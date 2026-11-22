package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Music page: built-in internet radio by genre (Phonk, Lofi, Gaming, PvP, Electronic) from the free
 * radio-browser.info directory, and the Spotify desktop app with album art, volume and saved playlists.
 */
final class MusicPage {
    record Station(String name, String url, String country, String tags, int bitrate) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final String[] MIRRORS = {"https://de1.api.radio-browser.info", "https://fi1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info"};
    /** Category label to the directory tag searched for it. */
    private static final Map<String, String> CATEGORIES = new java.util.LinkedHashMap<>(Map.of());

    static {
        CATEGORIES.put("Phonk", "phonk");
        CATEGORIES.put("Lofi", "lofi");
        CATEGORIES.put("Gaming", "gaming");
        CATEGORIES.put("PvP", "drum and bass");
        CATEGORIES.put("Electronic", "electronic");
    }

    private static MusicPage shared;
    private static MediaPlayer player;
    private static Station playing;
    private static double volume = 0.6;

    private final VBox root = new VBox(16);
    private final FlowPane stations = new FlowPane(10, 10);
    private final Label radioStatus = ModsUi.text("Pick a category.", 12, ModsUi.MUTED, false);
    private final Label nowPlaying = ModsUi.text("Radio is off", 15, ModsUi.TEXT, true);
    private final HBox categories = new HBox(8);
    private final Path playlistsFile = AppInfo.dataDir().resolve("spotify-playlists.json");
    private final VBox playlists = new VBox(6);
    private FeatureHost host;
    private String category = "Lofi";

    static VBox build(FeatureHost host) {
        if (shared == null) shared = new MusicPage(host);
        shared.host = host;
        return shared.root;
    }

    private MusicPage(FeatureHost host) {
        this.host = host;
        root.getChildren().addAll(radioCard(), spotifyCard());
        loadCategory(category);
    }

    // ---- radio ----------------------------------------------------------------------------------------------

    private VBox radioCard() {
        VBox card = UiKit.section("Radio", "Live internet radio by genre. Keeps playing while you are in game.");
        for (String label : CATEGORIES.keySet()) {
            Button button = UiKit.button(label, label.equals(category), () -> loadCategory(label));
            button.setUserData(label);
            categories.getChildren().add(button);
        }
        Slider slider = new Slider(0, 1, volume);
        slider.setPrefWidth(180);
        slider.valueProperty().addListener((observable, before, now) -> {
            volume = now.doubleValue();
            if (player != null) player.setVolume(volume);
        });
        Button stop = UiKit.button("■  STOP", false, MusicPage::stop);
        nowPlaying.setWrapText(true);
        HBox controls = UiKit.row(10, stop, ModsUi.text("Volume", 12, ModsUi.MUTED, false), slider);
        controls.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(categories, nowPlaying, controls, radioStatus, stations);
        if (playing != null) nowPlaying.setText("♫  " + playing.name());
        return card;
    }

    private void styleCategories() {
        for (var node : categories.getChildren()) {
            boolean selected = category.equals(node.getUserData());
            node.setStyle(selected
                    ? "-fx-background-color: " + Theme.accentRgba(0.85) + "; -fx-text-fill: #071321; -fx-font-weight: bold;"
                    + " -fx-background-radius: 10; -fx-padding: 6 14; -fx-cursor: hand;"
                    : "-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: #f5f7fd; -fx-background-radius: 10;"
                    + " -fx-padding: 6 14; -fx-cursor: hand;");
        }
    }

    private void loadCategory(String label) {
        category = label;
        styleCategories();
        radioStatus.setText("Finding " + label + " stations...");
        stations.getChildren().setAll(ModsUi.busyLabel("Loading..."));
        String tag = CATEGORIES.get(label);
        host.executor().execute(() -> {
            try {
                List<Station> found = stations(tag);
                Platform.runLater(() -> {
                    radioStatus.setText(found.size() + " " + label + " stations");
                    stations.getChildren().clear();
                    for (Station station : found) stations.getChildren().add(stationCard(station));
                    if (found.isEmpty()) stations.getChildren().add(ModsUi.emptyCard("♫", "No stations", "Try another category."));
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    radioStatus.setText("");
                    stations.getChildren().setAll(ModsUi.warningBox("Radio stations could not be loaded", e.getMessage(),
                            () -> loadCategory(label)));
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Stations the launcher can play (MP3 or HLS), most popular first. Tries each directory mirror in turn. */
    static List<Station> stations(String tag) throws IOException, InterruptedException {
        IOException last = null;
        for (String mirror : MIRRORS) {
            try {
                URI uri = URI.create(mirror + "/json/stations/bytag/" + URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20")
                        + "?limit=60&order=clickcount&reverse=true&hidebroken=true");
                HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                        .header("User-Agent", AppInfo.NAME + "/" + AppInfo.VERSION).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) throw new IOException("the radio directory answered HTTP " + response.statusCode());
                return parse(JSON.readTree(response.body()));
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("no radio directory reachable") : last;
    }

    static List<Station> parse(JsonNode array) {
        List<Station> list = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonNode node : array) {
            String url = node.path("url_resolved").asText(node.path("url").asText(""));
            boolean playable = "MP3".equalsIgnoreCase(node.path("codec").asText("")) || node.path("hls").asInt(0) == 1
                    || url.endsWith(".m3u8");
            if (!playable || !url.matches("(?i)https?://.+")) continue;
            String name = node.path("name").asText("").strip();
            if (name.isEmpty() || !names.add(name.toLowerCase(java.util.Locale.ROOT))) continue;
            list.add(new Station(name, url, node.path("countrycode").asText(""), node.path("tags").asText(""),
                    node.path("bitrate").asInt(0)));
            if (list.size() >= 24) break;
        }
        return list;
    }

    private VBox stationCard(Station station) {
        VBox card = ModsUi.glassCard(6, new Insets(10));
        card.setPrefWidth(220);
        card.setMaxWidth(220);
        Label name = ModsUi.text(station.name(), 13, ModsUi.TEXT, true);
        name.setWrapText(true);
        name.setMaxHeight(36);
        String detail = (station.country().isBlank() ? "" : station.country() + "  ·  ")
                + (station.bitrate() > 0 ? station.bitrate() + " kbps" : "live");
        Button play = UiKit.button("▶  PLAY", true, () -> play(station));
        card.getChildren().addAll(name, ModsUi.text(detail, 11, ModsUi.MUTED, false), play);
        return card;
    }

    private void play(Station station) {
        stop();
        playing = station;
        nowPlaying.setText("♫  Connecting to " + station.name() + "...");
        try {
            MediaPlayer next = new MediaPlayer(new Media(station.url()));
            next.setVolume(volume);
            next.setOnPlaying(() -> nowPlaying.setText("♫  " + station.name()));
            next.setOnError(() -> {
                nowPlaying.setText("This station could not be played (" + (next.getError() == null ? "unknown error"
                        : next.getError().getMessage()) + "). Try another.");
                stop();
            });
            next.setOnStalled(() -> nowPlaying.setText("♫  " + station.name() + " (buffering...)"));
            next.play();
            player = next;
        } catch (RuntimeException e) {
            nowPlaying.setText("This station could not be played: " + e.getMessage());
        }
    }

    private static void stop() {
        if (player != null) {
            player.stop();
            player.dispose();
            player = null;
        }
        playing = null;
        if (shared != null) shared.nowPlaying.setText("Radio is off");
    }

    // ---- spotify ----------------------------------------------------------------------------------------------

    private VBox spotifyCard() {
        VBox card = UiKit.section("Spotify", "Controls the Spotify desktop app with your media keys. No login needed.");
        ImageView art = new ImageView();
        art.setFitWidth(96);
        art.setFitHeight(96);
        art.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 12, 0.2, 0, 3);");
        Label song = ModsUi.text("Looking for Spotify...", 16, ModsUi.TEXT, true);
        song.setWrapText(true);
        Button previous = UiKit.button("⏮", false, SpotifyBridge::previous);
        Button toggle = UiKit.button("⏯  PLAY / PAUSE", true, SpotifyBridge::playPause);
        Button next = UiKit.button("⏭", false, SpotifyBridge::next);
        Button down = UiKit.button("🔉", false, WinInput::volumeDown);
        down.setTooltip(new javafx.scene.control.Tooltip("Volume down (Windows volume)"));
        Button up = UiKit.button("🔊", false, WinInput::volumeUp);
        up.setTooltip(new javafx.scene.control.Tooltip("Volume up (Windows volume)"));
        VBox info = new VBox(10, song, UiKit.row(8, previous, toggle, next, down, up));
        HBox top = new HBox(16, art, info);
        top.setAlignment(Pos.CENTER_LEFT);

        TextField link = new TextField();
        link.setPromptText("Paste a Spotify playlist link (open.spotify.com/playlist/...)");
        link.setPrefWidth(380);
        link.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: #f5f7fd; -fx-prompt-text-fill: #9fc6dc;"
                + " -fx-background-radius: 10; -fx-padding: 8 12;");
        Button add = UiKit.button("SAVE PLAYLIST", false, () -> {
            String uri = spotifyUri(link.getText());
            if (uri == null) {
                song.setText("That does not look like a Spotify playlist or album link.");
                return;
            }
            List<String> saved = loadPlaylists();
            if (!saved.contains(link.getText().strip())) saved.add(link.getText().strip());
            savePlaylists(saved);
            link.clear();
            showPlaylists();
        });
        card.getChildren().addAll(top, ModsUi.sectionTitle("Playlists"), UiKit.row(10, link, add), playlists);
        showPlaylists();

        String[] lastSong = {""};
        UiKit.poll(host, card, 2, () -> host.executor().execute(() -> {
            SpotifyBridge.Now now = SpotifyBridge.current();
            String display = now.display();
            Image cover = null;
            if (now.state() == SpotifyBridge.State.PLAYING && !display.equals(lastSong[0])) cover = artwork(now.artist(), now.title());
            Image found = cover;
            boolean changed = !display.equals(lastSong[0]);
            lastSong[0] = display;
            Platform.runLater(() -> {
                song.setText("♫  " + display);
                if (changed) art.setImage(found);
            });
        }));
        return card;
    }

    /** Album art from Apple's free iTunes search (no key), or null. */
    private Image artwork(String artist, String title) {
        try {
            String term = URLEncoder.encode((artist + " " + title).strip(), StandardCharsets.UTF_8);
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                            URI.create("https://itunes.apple.com/search?entity=song&limit=1&term=" + term))
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            String url = JSON.readTree(response.body()).path("results").path(0).path("artworkUrl100").asText("");
            return url.isEmpty() ? null : host.downloadImage(url.replace("100x100", "300x300"));
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** "https://open.spotify.com/playlist/ID?si=..." to "spotify:playlist:ID" (playlists and albums). */
    static String spotifyUri(String link) {
        if (link == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:open\\.spotify\\.com/(?:intl-[a-z]+/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{10,40})")
                .matcher(link.strip());
        return m.find() ? "spotify:" + m.group(1) + ":" + m.group(2) : null;
    }

    private List<String> loadPlaylists() {
        List<String> list = new ArrayList<>();
        try {
            if (Files.isRegularFile(playlistsFile)) JSON.readTree(playlistsFile.toFile()).forEach(node -> list.add(node.asText()));
        } catch (IOException e) {
            System.err.println("Could not read saved playlists: " + e);
        }
        return list;
    }

    private void savePlaylists(List<String> list) {
        try {
            ArrayNode array = JSON.createArrayNode();
            list.forEach(array::add);
            Files.createDirectories(playlistsFile.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(playlistsFile.toFile(), array);
        } catch (IOException e) {
            System.err.println("Could not save playlists: " + e);
        }
    }

    private void showPlaylists() {
        playlists.getChildren().clear();
        List<String> saved = loadPlaylists();
        if (saved.isEmpty()) {
            playlists.getChildren().add(UiKit.muted("No saved playlists yet."));
            return;
        }
        for (String link : saved) {
            String uri = spotifyUri(link);
            Button open = UiKit.button("▶  OPEN IN SPOTIFY", true, () -> {
                try {
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri).start();
                } catch (IOException e) {
                    ExternalLinks.open(link, message -> {});
                }
            });
            Button remove = UiKit.button("✕", false, () -> {
                List<String> list = loadPlaylists();
                list.remove(link);
                savePlaylists(list);
                showPlaylists();
            });
            Label label = ModsUi.text(uri == null ? link : uri.replace("spotify:", "").replace(":", " · "), 12, ModsUi.TEXT, false);
            HBox row = UiKit.row(10, open, remove, label);
            row.setAlignment(Pos.CENTER_LEFT);
            playlists.getChildren().add(row);
        }
    }
}
