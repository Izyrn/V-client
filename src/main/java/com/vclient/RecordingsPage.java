package com.vclient;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The Recordings tab: replay-buffer controls, the imported recordings and an in-app player. */
final class RecordingsPage {
    private static final double THUMB_WIDTH = 176;
    private static final double THUMB_HEIGHT = 99;

    private final FeatureHost host;
    private final VBox root = new VBox(18);
    private final VBox listBody = new VBox(10);
    private final Label bufferState = ModsUi.text("", 12, ModsUi.MUTED, false);
    private final Label notice = ModsUi.text("", 12, ModsUi.GOOD, false);
    private final Pane thumbHolder = new Pane();
    private final ArrayDeque<Runnable> thumbQueue = new ArrayDeque<>();
    private boolean thumbBusy;
    private boolean favoritesOnly;
    private String shownSignature = "";
    private String lastStatus = "";

    private RecordingsPage(FeatureHost host) {
        this.host = host;
    }

    static VBox build(FeatureHost host) {
        RecordingsPage page = new RecordingsPage(host);
        page.assemble();
        return page.root;
    }

    private void assemble() {
        thumbHolder.setMouseTransparent(true);
        thumbHolder.setPrefSize(1, 1);
        thumbHolder.setMaxSize(1, 1);
        root.getChildren().addAll(bufferCard(), listCard(), thumbHolder);
        refreshList(true);
        // New recordings are imported in the background; poll the folder while this page is on screen.
        UiKit.poll(host, root, 3, () -> {
            updateBufferState();
            String status = host.replayStatus();
            if (!status.isBlank() && !status.equals(lastStatus)) {
                lastStatus = status;
                notice.setText(status);
            }
            refreshList(false);
        });
    }

    // ---- replay buffer card ------------------------------------------------------------------------

    private VBox bufferCard() {
        LauncherSettings settings = host.settings();
        VBox card = UiKit.section("Save the last 30 seconds",
                "While Minecraft is the window in front, V-Client keeps the last 30 seconds in memory. Press the hotkey "
                        + "and they are saved as a clip - nothing is written to disk until you do.");
        ComboBox<String> hotkey = new ComboBox<>();
        hotkey.getItems().addAll(WinInput.HOTKEYS.keySet());
        hotkey.setValue(settings.clipHotkey());
        hotkey.setOnAction(event -> {
            settings.setClipHotkey(hotkey.getValue());
            host.saveSettings();
        });
        CheckBox enabled = new CheckBox("Replay buffer on");
        enabled.setSelected(settings.replayBufferEnabled());
        enabled.setStyle("-fx-text-fill: " + ModsUi.TEXT + ";");
        enabled.setOnAction(event -> {
            settings.setReplayBufferEnabled(enabled.isSelected());
            host.saveSettings();
            updateBufferState();
        });
        CheckBox microphone = new CheckBox("Record my microphone too");
        microphone.setSelected(settings.clipMicrophone());
        microphone.setStyle("-fx-text-fill: " + ModsUi.TEXT + ";");
        microphone.setOnAction(event -> {
            settings.setClipMicrophone(microphone.isSelected());
            host.saveSettings();
        });
        Button saveNow = UiKit.button("SAVE LAST 30 SECONDS NOW", true, () -> {
            host.replayBuffer().saveClip();
            notice.setText("Saving...");
        });
        Button open = UiKit.button("OPEN RECORDINGS FOLDER", false, this::openFolder);
        Button scan = UiKit.button("IMPORT RECORDINGS NOW", false, this::scanNow);
        HBox controls = UiKit.row(10, ModsUi.text("Hotkey", 12, ModsUi.MUTED, false), hotkey, enabled);
        HBox actions = UiKit.row(10, saveNow, scan, open);
        card.getChildren().addAll(controls, microphone, actions, bufferState, notice,
                UiKit.muted("Clips are Motion-JPEG .avi files at up to 1280 px wide. Captured pixels are whatever is on "
                        + "screen in the Minecraft window; the capture pauses whenever another window is in front. "
                        + "Game and system sound is recorded with the clip; your microphone only if the box above is ticked "
                        + "(it stays in memory until you save)."));
        updateBufferState();
        return card;
    }

    private void updateBufferState() {
        if (!WinInput.available()) {
            bufferState.setText("The replay buffer needs Windows.");
        } else if (!host.settings().replayBufferEnabled()) {
            bufferState.setText("Replay buffer is off.");
        } else if (!GameSession.running()) {
            bufferState.setText("Waiting for Minecraft - start the game from V-Client and the buffer starts with it.");
        } else if (host.replayBuffer().capturing()) {
            bufferState.setText("Recording the last 30 seconds - press " + host.settings().clipHotkey() + " in game to save.");
        } else {
            bufferState.setText("Minecraft is running; the buffer fills while its window is in front.");
        }
    }

    private void openFolder() {
        try {
            Files.createDirectories(host.recordings().folder());
            Desktop.getDesktop().open(host.recordings().folder().toFile());
        } catch (IOException | UnsupportedOperationException e) {
            notice.setText("Could not open the folder: " + e.getMessage());
        }
    }

    private void scanNow() {
        notice.setText("Looking for recordings from the last 24 hours...");
        host.executor().execute(() -> {
            RecordingLibrary library = host.recordings();
            List<Path> sources = RecordingLibrary.watchedFolders(host.settings().minecraftDirectory());
            long since = System.currentTimeMillis() - 24L * 3600 * 1000;
            java.util.Map<Path, Long> sizes = new java.util.HashMap<>();
            library.findImportable(sources, since, sizes);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            int imported = 0;
            for (Path file : library.findImportable(sources, since, sizes)) {
                try {
                    library.importFile(file);
                    imported++;
                } catch (IOException e) {
                    host.log("Could not import " + file.getFileName() + ": " + e.getMessage());
                }
            }
            int count = imported;
            Platform.runLater(() -> {
                notice.setText(count == 0 ? "No new recordings found in " + sources.size() + " watched folder(s)."
                        : "Imported " + count + " recording" + (count == 1 ? "" : "s") + ".");
                refreshList(true);
            });
        });
    }

    // ---- list --------------------------------------------------------------------------------------

    private VBox listCard() {
        VBox card = UiKit.section("Your recordings", null);
        CheckBox favorites = new CheckBox("Favorites only");
        favorites.setStyle("-fx-text-fill: " + ModsUi.TEXT + ";");
        favorites.setOnAction(event -> {
            favoritesOnly = favorites.isSelected();
            refreshList(true);
        });
        card.getChildren().addAll(favorites, listBody);
        return card;
    }

    private void refreshList(boolean force) {
        List<RecordingLibrary.Recording> all = host.recordings().list();
        String signature = all.stream().map(r -> r.name() + r.sizeBytes() + r.favorite()).reduce("", String::concat)
                + favoritesOnly;
        if (!force && signature.equals(shownSignature)) return;
        shownSignature = signature;
        listBody.getChildren().clear();
        thumbQueue.clear();
        List<RecordingLibrary.Recording> shown = all.stream().filter(r -> !favoritesOnly || r.favorite()).toList();
        if (shown.isEmpty()) {
            listBody.getChildren().add(ModsUi.emptyCard("▶", favoritesOnly ? "No favorite recordings" : "No recordings yet",
                    "Record with OBS, Xbox Game Bar or any recorder while Minecraft runs and the video appears here "
                            + "automatically. Or press the hotkey in game to save the last 30 seconds."));
            return;
        }
        for (RecordingLibrary.Recording recording : shown) listBody.getChildren().add(row(recording));
    }

    private HBox row(RecordingLibrary.Recording recording) {
        ImageView thumb = new ImageView();
        thumb.setFitWidth(THUMB_WIDTH);
        thumb.setFitHeight(THUMB_HEIGHT);
        thumb.setPreserveRatio(true);
        Label placeholder = ModsUi.text("▶", 30, "#7f7fa6", false);
        StackPane frame = new StackPane(placeholder, thumb);
        frame.setMinSize(THUMB_WIDTH, THUMB_HEIGHT);
        frame.setPrefSize(THUMB_WIDTH, THUMB_HEIGHT);
        frame.setMaxSize(THUMB_WIDTH, THUMB_HEIGHT);
        frame.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-background-radius: 8;");
        frame.setCursor(javafx.scene.Cursor.HAND);
        frame.setOnMouseClicked(event -> play(recording));
        requestThumbnail(recording, image -> {
            thumb.setImage(image);
            placeholder.setVisible(false);
        });

        Label name = ModsUi.text(recording.name(), 14, ModsUi.TEXT, true);
        name.setWrapText(true);
        HBox facts = UiKit.row(8, ModsUi.chip(UiKit.date(recording.modifiedMillis()), ModsUi.MUTED),
                ModsUi.chip("⏱ " + UiKit.duration(recording.durationMillis()), ModsUi.MUTED),
                ModsUi.chip(CleanupService.formatBytes(recording.sizeBytes()), ModsUi.MUTED));
        VBox info = new VBox(8, name, facts);
        info.setMinWidth(0);

        Button favorite = UiKit.button(recording.favorite() ? "★ FAVORITE" : "☆ FAVORITE", recording.favorite(), () -> {
            host.recordings().setFavorite(recording.file(), !recording.favorite());
            refreshList(true);
        });
        Button play = UiKit.button("▶ PLAY", true, () -> play(recording));
        Button folder = UiKit.button("SHOW IN FOLDER", false, () -> reveal(recording.file()));
        Button delete = UiKit.dangerButton("DELETE", () -> confirmDelete(recording));
        VBox buttons = new VBox(8, UiKit.row(8, play, favorite), UiKit.row(8, folder, delete));
        HBox row = new HBox(16, frame, info, UiKit.grow(), buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));
        row.setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 12;");
        return row;
    }

    private void reveal(Path file) {
        try {
            new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
        } catch (IOException e) {
            notice.setText("Could not open Explorer: " + e.getMessage());
        }
    }

    private void confirmDelete(RecordingLibrary.Recording recording) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Delete " + recording.name() + " from your PC? This cannot be undone.",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("Delete recording");
        alert.initOwner(host.stage());
        alert.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
            if (host.recordings().delete(recording.file())) {
                deleteThumbnail(recording);
                notice.setText("Deleted " + recording.name() + ".");
            } else {
                notice.setText("Could not delete " + recording.name() + " (is it open in another program?).");
            }
            refreshList(true);
        });
    }

    // ---- thumbnails --------------------------------------------------------------------------------

    private Path thumbFile(RecordingLibrary.Recording recording) {
        return host.recordings().folder().resolve(".thumbs").resolve(recording.name() + ".png");
    }

    private void deleteThumbnail(RecordingLibrary.Recording recording) {
        try {
            Files.deleteIfExists(thumbFile(recording));
        } catch (IOException e) {
            // A stale thumbnail is harmless.
        }
    }

    private void requestThumbnail(RecordingLibrary.Recording recording, Consumer<Image> target) {
        Path cached = thumbFile(recording);
        try {
            if (Files.isRegularFile(cached)
                    && Files.getLastModifiedTime(cached).toMillis() >= recording.modifiedMillis()) {
                target.accept(new Image(cached.toUri().toString(), THUMB_WIDTH * 2, 0, true, true, true));
                return;
            }
        } catch (IOException e) {
            // Regenerate below.
        }
        if (recording.name().toLowerCase().endsWith(".avi")) {
            host.executor().execute(() -> {
                MjpegAvi.Info info = MjpegAvi.read(recording.file());
                if (info == null) return;
                try {
                    byte[] jpeg = MjpegAvi.frame(recording.file(), info, info.frameCount() / 2);
                    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
                    saveThumbnail(cached, decoded);
                    Image image = new Image(new ByteArrayInputStream(jpeg), THUMB_WIDTH * 2, 0, true, true);
                    Platform.runLater(() -> target.accept(image));
                } catch (IOException e) {
                    // Keep the placeholder.
                }
            });
            return;
        }
        thumbQueue.add(() -> mediaThumbnail(recording, cached, target));
        drainThumbnails();
    }

    private void drainThumbnails() {
        if (thumbBusy || thumbQueue.isEmpty()) return;
        thumbBusy = true;
        thumbQueue.poll().run();
    }

    private void thumbnailDone() {
        thumbBusy = false;
        drainThumbnails();
    }

    /** Seeks a hidden player to one second in and snapshots its view; ends quietly if the format is unsupported. */
    private void mediaThumbnail(RecordingLibrary.Recording recording, Path cached, Consumer<Image> target) {
        MediaPlayer[] holder = new MediaPlayer[1];
        MediaView view = new MediaView();
        view.setFitWidth(THUMB_WIDTH * 2);
        view.setFitHeight(THUMB_HEIGHT * 2);
        view.setOpacity(0.01);
        boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            thumbHolder.getChildren().remove(view);
            if (holder[0] != null) holder[0].dispose();
            thumbnailDone();
        };
        try {
            Media media = new Media(recording.file().toUri().toString());
            MediaPlayer player = new MediaPlayer(media);
            holder[0] = player;
            player.setMute(true);
            view.setMediaPlayer(player);
            thumbHolder.getChildren().add(view);
            player.setOnError(finish);
            media.setOnError(finish);
            player.setOnReady(() -> {
                Duration total = media.getDuration();
                Duration at = total.isUnknown() || total.lessThan(Duration.seconds(2)) ? Duration.millis(300)
                        : Duration.seconds(Math.min(2, total.toSeconds() / 3));
                player.setOnPaused(() -> {
                });
                player.seek(at);
                player.play();
                PauseTransition wait = new PauseTransition(Duration.millis(700));
                wait.setOnFinished(event -> {
                    try {
                        player.pause();
                        WritableImage shot = view.snapshot(null, null);
                        if (shot != null && shot.getWidth() > 1) {
                            saveThumbnail(cached, toBuffered(shot));
                            target.accept(shot);
                        }
                    } catch (RuntimeException e) {
                        // No thumbnail for this file.
                    }
                    finish.run();
                });
                wait.play();
            });
            PauseTransition timeout = new PauseTransition(Duration.seconds(8));
            timeout.setOnFinished(event -> finish.run());
            timeout.play();
        } catch (RuntimeException e) {
            finish.run();
        }
    }

    private static BufferedImage toBuffered(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) out.setRGB(x, y, reader.getArgb(x, y));
        return out;
    }

    private static void saveThumbnail(Path file, BufferedImage image) {
        try {
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException | RuntimeException e) {
            // Thumbnails are a cache; regenerate next time.
        }
    }

    // ---- player ------------------------------------------------------------------------------------

    private void play(RecordingLibrary.Recording recording) {
        Stage stage = new Stage();
        stage.initOwner(host.stage());
        stage.setTitle(recording.name());
        BorderPane layout = new BorderPane();
        layout.setStyle("-fx-background-color: #05060f;");
        StackPane screen = new StackPane();
        screen.setMinSize(0, 0);
        layout.setCenter(screen);
        Label message = ModsUi.text("", 13, ModsUi.WARNING, false);
        message.setWrapText(true);
        message.setMaxWidth(520);

        Button toggle = UiKit.button("PAUSE", true, () -> {});
        Slider position = new Slider(0, 1, 0);
        HBox.setHgrow(position, javafx.scene.layout.Priority.ALWAYS);
        Label time = ModsUi.text("0:00 / 0:00", 12, ModsUi.MUTED, false);
        Button external = UiKit.button("OPEN IN DEFAULT PLAYER", false, () -> openExternally(recording.file()));
        HBox controls = UiKit.row(12, toggle, position, time, external);
        controls.setPadding(new Insets(12, 16, 12, 16));
        controls.setStyle("-fx-background-color: rgba(13,14,36,0.95);");
        layout.setBottom(controls);

        Runnable[] cleanup = {() -> {}};
        if (recording.name().toLowerCase().endsWith(".avi") && MjpegAvi.read(recording.file()) != null) {
            cleanup[0] = playAvi(recording, screen, toggle, position, time, message);
        } else {
            cleanup[0] = playMedia(recording, screen, toggle, position, time, message);
        }
        screen.getChildren().add(message);
        stage.setOnHidden(event -> cleanup[0].run());
        stage.setScene(new Scene(layout, 960, 600));
        stage.show();
    }

    private void openExternally(Path file) {
        try {
            Desktop.getDesktop().open(file.toFile());
        } catch (IOException | UnsupportedOperationException e) {
            notice.setText("Could not open the default player: " + e.getMessage());
        }
    }

    private Runnable playAvi(RecordingLibrary.Recording recording, StackPane screen, Button toggle, Slider position,
                             Label time, Label message) {
        MjpegAvi.Info info = MjpegAvi.read(recording.file());
        List<byte[]> frames = new ArrayList<>();
        try {
            for (int i = 0; i < info.frameCount(); i++) frames.add(MjpegAvi.frame(recording.file(), info, i));
        } catch (IOException e) {
            message.setText("Could not read the clip: " + e.getMessage());
            return () -> {};
        }
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.fitWidthProperty().bind(screen.widthProperty());
        view.fitHeightProperty().bind(screen.heightProperty());
        screen.getChildren().addFirst(view);
        double[] clock = {0}; // seconds of video shown
        boolean[] playing = {true};
        long[] last = {0};
        int[] shownFrame = {-1};
        boolean[] seeking = {false};
        double total = info.frameCount() / info.framesPerSecond();
        javax.sound.sampled.Clip[] sound = {null};
        if (info.hasAudio()) {
            try {
                byte[] pcm = MjpegAvi.audio(recording.file(), info);
                javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(info.audioRate(), 16,
                        info.audioChannels(), true, false);
                javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
                clip.open(format, pcm, 0, pcm.length);
                sound[0] = clip;
            } catch (Exception | Error e) {
                sound[0] = null; // no sound device: the picture still plays
            }
        }
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (last[0] != 0 && playing[0]) clock[0] += (now - last[0]) / 1e9;
                last[0] = now;
                if (clock[0] >= total) {
                    clock[0] = total;
                    playing[0] = false;
                    toggle.setText("REPLAY");
                }
                javax.sound.sampled.Clip clip = sound[0];
                if (clip != null) {
                    long want = (long) (clock[0] * 1_000_000);
                    if (playing[0] && want < clip.getMicrosecondLength() - 20_000) {
                        // start, resume after a seek, or correct drift
                        if (!clip.isRunning() || Math.abs(clip.getMicrosecondPosition() - want) > 150_000) {
                            clip.stop();
                            clip.setMicrosecondPosition(want);
                            clip.start();
                        }
                    } else if (clip.isRunning()) {
                        clip.stop();
                    }
                }
                int index = Math.min(info.frameCount() - 1, (int) (clock[0] * info.framesPerSecond()));
                if (index != shownFrame[0]) {
                    shownFrame[0] = index;
                    view.setImage(new Image(new ByteArrayInputStream(frames.get(index))));
                }
                seeking[0] = true;
                position.setValue(total <= 0 ? 0 : clock[0] / total);
                seeking[0] = false;
                time.setText(UiKit.duration((long) (clock[0] * 1000)) + " / " + UiKit.duration((long) (total * 1000)));
            }
        };
        toggle.setOnAction(event -> {
            if (clock[0] >= total) clock[0] = 0;
            playing[0] = !playing[0];
            toggle.setText(playing[0] ? "PAUSE" : "PLAY");
        });
        position.valueProperty().addListener((observable, before, after) -> {
            if (!seeking[0]) clock[0] = after.doubleValue() * total;
        });
        timer.start();
        return () -> {
            timer.stop();
            if (sound[0] != null) {
                sound[0].stop();
                sound[0].close();
            }
        };
    }

    private Runnable playMedia(RecordingLibrary.Recording recording, StackPane screen, Button toggle, Slider position,
                               Label time, Label message) {
        MediaPlayer[] holder = new MediaPlayer[1];
        Runnable fail = () -> message.setText("V-Client's player can't open this file (the video format is not supported "
                + "here - MP4 with H.264 works best). Use the button below to open it in your default player.");
        try {
            Media media = new Media(recording.file().toUri().toString());
            MediaPlayer player = new MediaPlayer(media);
            holder[0] = player;
            MediaView view = new MediaView(player);
            view.setPreserveRatio(true);
            view.fitWidthProperty().bind(screen.widthProperty());
            view.fitHeightProperty().bind(screen.heightProperty());
            screen.getChildren().addFirst(view);
            media.setOnError(fail);
            player.setOnError(fail);
            player.setOnReady(() -> player.play());
            boolean[] seeking = {false};
            player.currentTimeProperty().addListener((observable, before, after) -> {
                Duration total = media.getDuration();
                if (total.isUnknown() || total.toMillis() <= 0) return;
                seeking[0] = true;
                position.setValue(after.toMillis() / total.toMillis());
                seeking[0] = false;
                time.setText(UiKit.duration((long) after.toMillis()) + " / " + UiKit.duration((long) total.toMillis()));
            });
            position.valueProperty().addListener((observable, before, after) -> {
                Duration total = media.getDuration();
                if (!seeking[0] && !total.isUnknown()) player.seek(total.multiply(after.doubleValue()));
            });
            toggle.setOnAction(event -> {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    player.pause();
                    toggle.setText("PLAY");
                } else {
                    player.play();
                    toggle.setText("PAUSE");
                }
            });
            player.setOnEndOfMedia(() -> {
                player.seek(Duration.ZERO);
                player.pause();
                toggle.setText("PLAY");
            });
        } catch (RuntimeException e) {
            fail.run();
        }
        return () -> {
            if (holder[0] != null) holder[0].dispose();
        };
    }
}
