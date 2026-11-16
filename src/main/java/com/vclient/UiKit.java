package com.vclient;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small shared widgets and formatters for the feature pages, in the same look as the Mods page. */
final class UiKit {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private UiKit() {}

    static Button button(String text, boolean primary, Runnable action) {
        Button button = new Button(text);
        String background = primary ? ModsUi.ACCENT : "rgba(255,255,255,0.09)";
        String foreground = primary ? "#071321" : "#eaf9ff";
        button.setStyle("-fx-background-color: " + background + "; -fx-text-fill: " + foreground
                + "; -fx-font-size: 11px; -fx-font-weight: bold; -fx-background-radius: 10;"
                + " -fx-padding: 9 15 9 15; -fx-cursor: hand;");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(event -> action.run());
        return button;
    }

    static Button dangerButton(String text, Runnable action) {
        Button button = button(text, false, action);
        button.setStyle(button.getStyle() + " -fx-text-fill: #ff8a8a; -fx-background-color: rgba(255,90,90,0.14);");
        return button;
    }

    static Label muted(String text) {
        Label label = ModsUi.text(text, 12, ModsUi.MUTED, false);
        label.setWrapText(true);
        return label;
    }

    /** A big number with a caption underneath. */
    static VBox tile(String caption, String value) {
        VBox tile = new VBox(4, ModsUi.text(value, 22, ModsUi.TEXT, true), ModsUi.sectionTitle(caption));
        tile.setPadding(new Insets(14, 18, 14, 18));
        tile.setMinWidth(150);
        tile.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 12;");
        HBox.setHgrow(tile, Priority.ALWAYS);
        return tile;
    }

    static HBox row(double spacing, javafx.scene.Node... nodes) {
        HBox row = new HBox(spacing, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Runs {@code tick} every {@code seconds} while {@code page} is the page on screen and stops for good the first
     * time it is not, so a page the player has left leaves no timer behind. The first tick runs immediately.
     */
    static void poll(FeatureHost host, javafx.scene.Node page, double seconds, Runnable tick) {
        javafx.animation.Timeline timeline = new javafx.animation.Timeline();
        timeline.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(seconds), event -> {
            if (!host.shown(page)) {
                timeline.stop();
                return;
            }
            tick.run();
        }));
        timeline.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        timeline.play();
        javafx.application.Platform.runLater(tick);
    }

    static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    static VBox section(String title, String subtitle) {
        VBox card = ModsUi.glassCard(14, new Insets(20));
        card.getChildren().add(ModsUi.text(title, 17, ModsUi.TEXT, true));
        if (subtitle != null && !subtitle.isBlank()) card.getChildren().add(muted(subtitle));
        return card;
    }

    static String duration(long millis) {
        if (millis < 0) return "--:--";
        long seconds = Math.round(millis / 1000.0);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60);
    }

    static String date(long epochMillis) {
        return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    static String playtime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours > 0 ? hours + " h " + minutes + " min" : minutes + " min";
    }
}
