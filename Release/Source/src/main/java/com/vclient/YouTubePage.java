package com.vclient;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The YouTube page: search videos, music and playlists, watch inside the launcher, pop a video out into an always-on-top
 * mini player, and keep favorites. Playback is YouTube's own player in Edge/Chrome, so ads, sign-in and quality work as
 * on youtube.com.
 */
final class YouTubePage {
    private static final double CARD_WIDTH = 236;
    private static YouTubePage shared;

    private final YouTubeService service = new YouTubeService();
    private final VBox root = new VBox(16);
    private final TextField query = new TextField();
    private final HBox kinds = new HBox(8);
    private final FlowPane results = new FlowPane(14, 14);
    private final FlowPane favorites = new FlowPane(14, 14);
    private final Label status = ModsUi.text("", 12, ModsUi.MUTED, false);
    private final VBox player = ModsUi.glassCard(10, new Insets(14));
    private final StackPane screen = new StackPane();
    private final Label nowPlaying = ModsUi.text("", 14, ModsUi.TEXT, true);
    private YouTubeService.Kind kind = YouTubeService.Kind.VIDEOS;
    private YouTubeService.Item playing;
    private TabContent content;
    private CompletableFuture<ChromiumHost> engine;
    private FeatureHost host;

    private YouTubePage() {
        query.setPromptText("Search YouTube: songs, videos, playlists...");
        query.setPrefWidth(420);
        query.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: #f5f7fd; -fx-prompt-text-fill: #9fc6dc;"
                + " -fx-background-radius: 12; -fx-padding: 10 14; -fx-font-size: 14px;");
        query.setOnAction(event -> search());
        Button go = UiKit.button("SEARCH", true, this::search);
        for (YouTubeService.Kind each : YouTubeService.Kind.values()) {
            Button button = UiKit.button(each.label, each == kind, () -> {
                kind = each;
                styleKinds();
                if (!query.getText().isBlank()) search();
            });
            button.setUserData(each);
            kinds.getChildren().add(button);
        }
        HBox bar = UiKit.row(10, query, go);
        bar.setAlignment(Pos.CENTER_LEFT);

        screen.setStyle("-fx-background-color: #000000; -fx-background-radius: 12;");
        screen.setPrefSize(800, 450);
        screen.setMaxWidth(Double.MAX_VALUE);
        Rectangle clip = new Rectangle();
        clip.setArcWidth(18);
        clip.setArcHeight(18);
        clip.widthProperty().bind(screen.widthProperty());
        clip.heightProperty().bind(screen.heightProperty());
        screen.setClip(clip);
        nowPlaying.setWrapText(true);
        Button mini = UiKit.button("⧉  MINI PLAYER", false, () -> {
            if (playing != null) openMiniPlayer(playing);
        });
        Button outside = UiKit.button("OPEN ON YOUTUBE", false, () -> {
            if (playing != null) ExternalLinks.open(playing.watchUrl(), message -> status.setText(message));
        });
        Button close = UiKit.button("✕  CLOSE", false, this::closePlayer);
        player.getChildren().addAll(nowPlaying, screen, UiKit.row(10, mini, outside, close));
        player.setVisible(false);
        player.setManaged(false);

        VBox searchCard = UiKit.section("YouTube", "Search, then press ▶ to watch here or ⧉ for a small player that stays on "
                + "top of Minecraft. ☆ saves a favorite.");
        searchCard.getChildren().addAll(bar, kinds, status);
        VBox resultsCard = ModsUi.glassCard(12, new Insets(16));
        resultsCard.getChildren().addAll(ModsUi.sectionTitle("Results"), results);
        VBox favoritesCard = ModsUi.glassCard(12, new Insets(16));
        favoritesCard.getChildren().addAll(ModsUi.sectionTitle("☆  Favorites"), favorites);
        root.getChildren().addAll(searchCard, player, resultsCard, favoritesCard);
        styleKinds();
        results.getChildren().add(ModsUi.emptyCard("▶", "Search for something", "Try a song, a YouTuber or \"minecraft pvp\"."));
    }

    static VBox build(FeatureHost host) {
        if (shared == null) shared = new YouTubePage();
        shared.host = host;
        shared.refreshFavorites();
        return shared.root;
    }

    private void styleKinds() {
        for (var node : kinds.getChildren()) {
            boolean selected = node.getUserData() == kind;
            node.setStyle(selected
                    ? "-fx-background-color: " + Theme.accentRgba(0.85) + "; -fx-text-fill: #071321; -fx-font-weight: bold;"
                    + " -fx-background-radius: 10; -fx-padding: 6 14; -fx-cursor: hand;"
                    : "-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: #f5f7fd; -fx-background-radius: 10;"
                    + " -fx-padding: 6 14; -fx-cursor: hand;");
        }
    }

    private void search() {
        String text = query.getText();
        if (text == null || text.isBlank()) return;
        YouTubeService.Kind searching = kind;
        status.setText("Searching YouTube for \"" + text.strip() + "\"...");
        results.getChildren().setAll(ModsUi.busyLabel("Searching..."));
        host.executor().execute(() -> {
            try {
                List<YouTubeService.Item> found = service.search(text, searching);
                Platform.runLater(() -> {
                    status.setText(found.size() + " " + searching.label.toLowerCase(java.util.Locale.ROOT) + " found");
                    showCards(results, found, "Nothing found", "Try other words.");
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    status.setText("");
                    results.getChildren().setAll(ModsUi.warningBox("YouTube search failed", e.getMessage(), this::search));
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void refreshFavorites() {
        host.executor().execute(() -> {
            List<YouTubeService.Item> saved = service.favorites();
            Platform.runLater(() -> showCards(favorites, saved, "No favorites yet", "Press ☆ on a video or playlist."));
        });
    }

    private void showCards(FlowPane pane, List<YouTubeService.Item> items, String emptyTitle, String emptyHint) {
        pane.getChildren().clear();
        if (items.isEmpty()) {
            pane.getChildren().add(ModsUi.emptyCard("▶", emptyTitle, emptyHint));
            return;
        }
        for (YouTubeService.Item item : items) pane.getChildren().add(card(item));
    }

    private VBox card(YouTubeService.Item item) {
        VBox card = ModsUi.glassCard(8, new Insets(10));
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        ImageView thumbnail = new ImageView();
        thumbnail.setFitWidth(CARD_WIDTH - 20);
        thumbnail.setFitHeight((CARD_WIDTH - 20) * 9 / 16);
        thumbnail.setPreserveRatio(false);
        Rectangle clip = new Rectangle(CARD_WIDTH - 20, (CARD_WIDTH - 20) * 9 / 16);
        clip.setArcWidth(14);
        clip.setArcHeight(14);
        thumbnail.setClip(clip);
        StackPane picture = new StackPane(thumbnail);
        picture.setStyle("-fx-background-color: rgba(0,0,0,0.35); -fx-background-radius: 10; -fx-cursor: hand;");
        picture.setOnMouseClicked(event -> play(item));
        if (!item.thumbnail().isBlank()) {
            host.executor().execute(() -> {
                Image image = host.downloadImage(item.thumbnail());
                if (image != null) Platform.runLater(() -> thumbnail.setImage(image));
            });
        }
        Label title = ModsUi.text(item.title(), 13, ModsUi.TEXT, true);
        title.setWrapText(true);
        title.setMaxHeight(36);
        Label meta = ModsUi.text(item.channel() + (item.detail().isBlank() ? "" : "  ·  " + item.detail()), 11,
                ModsUi.MUTED, false);
        meta.setWrapText(true);
        Button watch = UiKit.button("▶", true, () -> play(item));
        Button mini = UiKit.button("⧉", false, () -> openMiniPlayer(item));
        mini.setTooltip(new javafx.scene.control.Tooltip("Mini player (stays on top)"));
        Button star = UiKit.button(service.isFavorite(item) ? "★" : "☆", false, () -> {});
        star.setTooltip(new javafx.scene.control.Tooltip("Favorite"));
        star.setOnAction(event -> host.executor().execute(() -> {
            try {
                boolean now = service.toggleFavorite(item);
                Platform.runLater(() -> {
                    star.setText(now ? "★" : "☆");
                    refreshFavorites();
                });
            } catch (IOException e) {
                Platform.runLater(() -> status.setText("Could not save the favorite: " + e.getMessage()));
            }
        }));
        card.getChildren().addAll(picture, title, meta, UiKit.row(8, watch, mini, star));
        return card;
    }

    // ---- watching -----------------------------------------------------------------------------------

    /** Plays inside the launcher with the same Edge/Chrome engine the Browser page uses. */
    private void play(YouTubeService.Item item) {
        playing = item;
        nowPlaying.setText("▶  " + item.title() + (item.channel().isBlank() ? "" : "  ·  " + item.channel()));
        player.setVisible(true);
        player.setManaged(true);
        screen.setPrefHeight(Math.max(300, Math.min(520, (host.stage().getWidth() - 340) * 9 / 16)));
        if (ChromiumHost.findChromium() == null) {
            // No Edge/Chrome engine: the mini player cannot work either, so use the default browser.
            closePlayer();
            ExternalLinks.open(item.watchUrl(), message -> status.setText(message));
            return;
        }
        try {
            if (engine == null) engine = ChromiumHost.shared(DohProxy.shared().port());
        } catch (IOException e) {
            status.setText("The video player could not start: " + e.getMessage());
            return;
        }
        // The watch page plays every video (some refuse to play when embedded) and shows its playlist.
        String url = item.playlist() ? item.watchUrl() : item.watchUrl() + "&autoplay=1";
        if (content == null) {
            content = new ChromiumContent(engine, url, null, failure -> Platform.runLater(() ->
                    status.setText("The video player stopped: " + failure.getMessage() + ". Try ⧉ Mini player.")));
            screen.getChildren().setAll(content.node());
        } else {
            content.load(url);
        }
        content.setActive(true);
        content.focus();
    }

    private void closePlayer() {
        if (content != null) {
            content.close();
            content = null;
        }
        screen.getChildren().clear();
        playing = null;
        player.setVisible(false);
        player.setManaged(false);
    }

    /**
     * A small Edge/Chrome app window (no tabs or address bar) with YouTube's player, kept above other windows so it
     * can float over Minecraft. It uses its own profile, so it never touches the player's own browser.
     */
    private void openMiniPlayer(YouTubeService.Item item) {
        if (content != null && playing != null && playing.id().equals(item.id())) closePlayer();
        Path exe = ChromiumHost.findChromium();
        if (exe == null) {
            ExternalLinks.open(item.watchUrl(), message -> status.setText(message));
            return;
        }
        host.executor().execute(() -> {
            try {
                Path profile = AppInfo.dataDir().resolve("youtube-mini");
                Files.createDirectories(profile);
                var screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
                int width = 480;
                int height = 300;
                int x = (int) (screenBounds.getMaxX() - width - 24);
                int y = (int) (screenBounds.getMaxY() - height - 24);
                Process process = new ProcessBuilder(exe.toString(), "--user-data-dir=" + profile,
                        "--proxy-server=http://127.0.0.1:" + DohProxy.shared().port(),
                        "--app=" + item.embedUrl(), "--window-size=" + width + "," + height,
                        "--window-position=" + x + "," + y, "--no-first-run", "--no-default-browser-check",
                        "--disable-extensions", "--disable-sync", "--autoplay-policy=no-user-gesture-required")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                Platform.runLater(() -> status.setText("Mini player opened (bottom right). If a video says it cannot be "
                        + "played here, use OPEN ON YOUTUBE."));
                keepOnTop(process);
            } catch (IOException e) {
                Platform.runLater(() -> status.setText("The mini player could not open: " + e.getMessage()));
            }
        });
    }

    /** Waits for the mini player's window and marks it always-on-top. Best effort: it simply stays a normal window if not. */
    private static void keepOnTop(Process process) {
        WinDef.HWND topmost = new WinDef.HWND(Pointer.createConstant(-1));
        int flags = 0x0001 | 0x0002 | 0x0040; // SWP_NOSIZE | SWP_NOMOVE | SWP_SHOWWINDOW
        for (int attempt = 0; attempt < 40 && process.isAlive(); attempt++) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long pid = process.pid();
            WinDef.HWND[] found = new WinDef.HWND[1];
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                IntByReference owner = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
                if (owner.getValue() == pid && User32.INSTANCE.IsWindowVisible(hwnd)
                        && User32.INSTANCE.GetWindowTextLength(hwnd) > 0) {
                    found[0] = hwnd;
                    return false;
                }
                return true;
            }, null);
            if (found[0] != null) {
                User32.INSTANCE.SetWindowPos(found[0], topmost, 0, 0, 0, 0, flags);
                return;
            }
        }
    }
}
