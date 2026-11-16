package com.vclient;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Animation;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual building blocks for the Mods page: glass cards, a styled warning box, loading skeletons, empty states
 * and mod cards. Everything uses one spacing scale (8 / 12 / 16 / 20) and 12-16 px rounded corners so the page
 * reads as one design. JavaFX cannot blur what is behind a node, so the "glass" look is a translucent
 * gradient fill, a light top edge and a soft shadow over the animated night sky.
 */
final class ModsUi {
    static String ACCENT = "#00CFFF";
    static final String MUTED = "#9fc6dc";
    static final String TEXT = "#f5f7fd";
    static final String WARNING = "#ffb454";
    static final String GOOD = "#39d98a";

    private static final String GLASS = "-fx-background-color: linear-gradient(to bottom right, rgba(255,255,255,0.085),"
            + " rgba(255,255,255,0.025)), rgba(7, 19, 33,0.78);"
            + " -fx-background-radius: 16, 16; -fx-background-insets: 0, 0;"
            + " -fx-border-color: linear-gradient(to bottom, rgba(255,255,255,0.20), rgba(255,255,255,0.05));"
            + " -fx-border-radius: 16; -fx-border-width: 1;";

    private ModsUi() {}

    // ---- containers ------------------------------------------------------------------------------

    /** A translucent rounded card with a light edge and soft shadow. */
    static VBox glassCard(double spacing, Insets padding) {
        VBox card = new VBox(spacing);
        card.setPadding(padding);
        card.setStyle(GLASS);
        card.setEffect(new DropShadow(22, 0, 8, Color.rgb(0, 0, 0, 0.38)));
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    static VBox glassCard() {
        return glassCard(14, new Insets(20));
    }

    /** Slightly raises a node on hover and gives it an accent outline. */
    static void hoverLift(Region node, String baseStyle) {
        ScaleTransition up = new ScaleTransition(Duration.millis(140), node);
        up.setToX(1.012);
        up.setToY(1.012);
        ScaleTransition down = new ScaleTransition(Duration.millis(140), node);
        down.setToX(1);
        down.setToY(1);
        node.setOnMouseEntered(event -> {
            down.stop();
            up.playFromStart();
            node.setStyle(baseStyle + " -fx-border-color: " + Theme.accentRgba(0.55) + ";");
        });
        node.setOnMouseExited(event -> {
            up.stop();
            down.playFromStart();
            node.setStyle(baseStyle);
        });
    }

    static Label text(String value, int size, String color, boolean bold) {
        Label label = new Label(value);
        label.setStyle("-fx-font-size: " + size + "px; -fx-text-fill: " + color + ";" + (bold ? " -fx-font-weight: bold;" : ""));
        return label;
    }

    static Label sectionTitle(String value) {
        Label label = text(value.toUpperCase(java.util.Locale.ROOT), 11, MUTED, true);
        label.setStyle(label.getStyle() + " -fx-letter-spacing: 1px;");
        return label;
    }

    /** A small rounded tag such as "12.4K downloads". */
    static Label chip(String value, String color) {
        Label chip = text(value, 10, color, false);
        chip.setPadding(new Insets(3, 9, 3, 9));
        chip.setMinWidth(Region.USE_PREF_SIZE);
        chip.setStyle(chip.getStyle() + " -fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 10;");
        return chip;
    }

    // ---- warning / empty / loading ---------------------------------------------------------------

    /**
     * A warning box with an orange icon, a short title and the actual error underneath, instead of raw text
     * under the controls. {@code retry} may be null.
     */
    static HBox warningBox(String title, String detail, Runnable retry) {
        Label icon = new Label("⚠");
        icon.setMinWidth(34);
        icon.setMinHeight(34);
        icon.setAlignment(Pos.CENTER);
        icon.setStyle("-fx-font-size: 18px; -fx-text-fill: #1a1206; -fx-background-color: " + WARNING
                + "; -fx-background-radius: 17;");
        Label heading = text(title, 14, WARNING, true);
        heading.setWrapText(true);
        Label body = text(detail, 12, "#cfeeff", false);
        body.setWrapText(true);
        body.setMinHeight(Region.USE_PREF_SIZE);
        VBox message = new VBox(4, heading, body);
        message.setMinWidth(0);
        HBox.setHgrow(message, Priority.ALWAYS);
        HBox box = new HBox(14, icon, message);
        box.setAlignment(Pos.TOP_LEFT);
        if (retry != null) {
            Button again = new Button("TRY AGAIN");
            again.setStyle("-fx-background-color: rgba(255,180,84,0.18); -fx-text-fill: " + WARNING
                    + "; -fx-font-size: 11px; -fx-font-weight: bold; -fx-background-radius: 10; -fx-padding: 9 14 9 14; -fx-cursor: hand;");
            again.setMinWidth(Region.USE_PREF_SIZE);
            again.setOnAction(event -> retry.run());
            box.getChildren().add(again);
        }
        box.setPadding(new Insets(14, 16, 14, 16));
        box.setStyle("-fx-background-color: rgba(255,180,84,0.10); -fx-background-radius: 14;"
                + " -fx-border-color: rgba(255,180,84,0.45); -fx-border-radius: 14;");
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /** Splits a raw failure into a plain title and the real detail. */
    static String[] describeFailure(String what, String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("dns filter") || lower.contains("handshake")) {
            return new String[]{"Can't reach " + what, raw};
        }
        if (lower.contains("timed out") || lower.contains("did not respond")) {
            return new String[]{what + " is taking too long", raw};
        }
        return new String[]{what + " failed", raw == null || raw.isBlank() ? "No further details were reported." : raw};
    }

    static VBox emptyCard(String icon, String title, String hint) {
        Label glyph = text(icon, 30, MUTED, false);
        Label heading = text(title, 15, TEXT, true);
        Label body = text(hint, 12, MUTED, false);
        body.setWrapText(true);
        body.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox card = new VBox(8, glyph, heading, body);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(34, 20, 34, 20));
        card.setStyle("-fx-background-color: rgba(255,255,255,0.035); -fx-background-radius: 14;"
                + " -fx-border-color: rgba(255,255,255,0.10); -fx-border-radius: 14; -fx-border-style: dashed;");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /** Pulsing placeholder cards shown while a search runs. Call {@link #stop(List)} with the list when done. */
    static VBox skeleton(int count, List<Animation> animations) {
        VBox box = new VBox(12);
        for (int i = 0; i < count; i++) {
            Region icon = new Region();
            icon.setMinSize(52, 52);
            icon.setMaxSize(52, 52);
            icon.setStyle("-fx-background-color: rgba(255,255,255,0.10); -fx-background-radius: 12;");
            Region line1 = bar(180, 12);
            Region line2 = bar(320, 9);
            Region line3 = bar(120, 9);
            VBox lines = new VBox(8, line1, line2, line3);
            HBox.setHgrow(lines, Priority.ALWAYS);
            Region button = new Region();
            button.setMinSize(86, 34);
            button.setMaxSize(86, 34);
            button.setStyle("-fx-background-color: rgba(255,255,255,0.10); -fx-background-radius: 10;");
            HBox card = new HBox(16, icon, lines, button);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(16));
            card.setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 14;"
                    + " -fx-border-color: rgba(255,255,255,0.08); -fx-border-radius: 14;");
            FadeTransition pulse = new FadeTransition(Duration.millis(900), card);
            pulse.setFromValue(0.45);
            pulse.setToValue(1);
            pulse.setAutoReverse(true);
            pulse.setCycleCount(Animation.INDEFINITE);
            pulse.setDelay(Duration.millis(i * 160L));
            pulse.play();
            animations.add(pulse);
            box.getChildren().add(card);
        }
        return box;
    }

    static void stop(List<Animation> animations) {
        animations.forEach(Animation::stop);
        animations.clear();
    }

    private static Region bar(double width, double height) {
        Region bar = new Region();
        bar.setMinSize(0, height);
        bar.setPrefSize(width, height);
        bar.setMaxSize(width, height);
        bar.setStyle("-fx-background-color: rgba(255,255,255,0.10); -fx-background-radius: 6;");
        return bar;
    }

    /** A "Searching..." row with a spinner, for the results header. */
    static HBox busyLabel(String message) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);
        spinner.setMaxSize(16, 16);
        spinner.setStyle("-fx-progress-color: " + ACCENT + ";");
        HBox row = new HBox(10, spinner, text(message, 12, MUTED, false));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ---- mod card --------------------------------------------------------------------------------

    /** One search result: icon, name, short description, download count / version, and the Install button. */
    static HBox modCard(ModrinthModService.ModProject project, Image icon, boolean installed, boolean downloading,
                        String downloads, Runnable onInstall) {
        StackPane iconBox = new StackPane();
        iconBox.setMinSize(56, 56);
        iconBox.setMaxSize(56, 56);
        String initial = project.title().isBlank() ? "?" : project.title().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        int hue = Math.floorMod(project.title().hashCode(), 360);
        iconBox.setStyle("-fx-background-color: linear-gradient(to bottom right, hsb(" + hue + ", 45%, 55%), hsb(" + hue
                + ", 60%, 32%)); -fx-background-radius: 14;");
        iconBox.getChildren().add(text(initial, 22, "#ffffff", true));
        if (icon != null) {
            ImageView view = new ImageView(icon);
            view.setFitWidth(56);
            view.setFitHeight(56);
            view.setPreserveRatio(true);
            view.setVisible(false);
            // Shown only when the picture really decoded; otherwise the coloured letter stays.
            Runnable check = () -> view.setVisible(icon.getProgress() >= 1 && !icon.isError());
            icon.progressProperty().addListener((observable, before, after) -> check.run());
            icon.errorProperty().addListener((observable, before, after) -> check.run());
            check.run();
            Rectangle clip = new Rectangle(56, 56);
            clip.setArcWidth(28);
            clip.setArcHeight(28);
            iconBox.setClip(clip);
            iconBox.getChildren().add(view);
        }

        Label name = text(project.title(), 15, TEXT, true);
        name.setMinWidth(0);
        Label description = text(project.description(), 12, MUTED, false);
        description.setWrapText(true);
        description.setMinWidth(0);
        description.setMaxHeight(34);
        String version = project.versionNumber();
        if (version.length() > 22) version = version.substring(0, 21) + "\u2026";
        javafx.scene.layout.FlowPane meta = new javafx.scene.layout.FlowPane(6, 6,
                chip(downloads + " downloads", MUTED), chip(version, MUTED));
        meta.setMinWidth(0);
        VBox body = new VBox(5, name, description, meta);
        body.setMinWidth(0);
        HBox.setHgrow(body, Priority.ALWAYS);

        Button install = new Button(installed ? "INSTALLED" : downloading ? "INSTALLING…" : "INSTALL");
        String color = installed ? "#244a38" : ACCENT;
        String foreground = installed ? "#8ff0b8" : "#071321";
        install.setStyle("-fx-background-color: " + color + "; -fx-text-fill: " + foreground
                + "; -fx-font-size: 11px; -fx-font-weight: bold; -fx-background-radius: 11; -fx-padding: 0 18 0 18; -fx-cursor: hand;");
        install.setMinSize(Region.USE_PREF_SIZE, 38);
        install.setPrefHeight(38);
        install.setDisable(installed || downloading);
        install.setOnAction(event -> onInstall.run());

        HBox card = new HBox(16, iconBox, body, install);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(14, 16, 14, 14));
        card.setMinWidth(0);
        String base = "-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 14;"
                + " -fx-border-color: rgba(255,255,255,0.10); -fx-border-radius: 14;";
        card.setStyle(base);
        hoverLift(card, base);
        return card;
    }

    /** A rounded on/off switch. The callback runs with the new state when it is clicked. */
    static StackPane switchPill(boolean on, java.util.function.Consumer<Boolean> onChange) {
        javafx.scene.shape.Circle knob = new javafx.scene.shape.Circle(11, Color.WHITE);
        StackPane pill = new StackPane(knob);
        pill.setMinSize(54, 30);
        pill.setPrefSize(54, 30);
        pill.setMaxSize(54, 30);
        pill.setCursor(javafx.scene.Cursor.HAND);
        pill.setUserData(new boolean[]{on});
        setSwitch(pill, on);
        pill.setOnMouseClicked(event -> {
            boolean[] state = (boolean[]) pill.getUserData();
            onChange.accept(!state[0]);
        });
        return pill;
    }

    /** Moves a switch to a state without calling its callback. */
    static void setSwitch(StackPane pill, boolean on) {
        ((boolean[]) pill.getUserData())[0] = on;
        pill.setAlignment(on ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        pill.setPadding(new Insets(0, 4, 0, 4));
        pill.setStyle("-fx-background-radius: 15; -fx-background-color: " + (on ? ACCENT : "rgba(255,255,255,0.16)") + ";");
    }

    /** Sets every child of {@code cards} to the same width so a two-column grid stays aligned. */
    static void applyColumns(javafx.scene.layout.FlowPane flow, double available) {
        double gap = flow.getHgap();
        boolean twoColumns = available >= 820;
        double width = twoColumns ? Math.floor((available - gap) / 2) - 1 : available;
        for (Node child : new ArrayList<>(flow.getChildren())) {
            if (child instanceof Region region) {
                region.setPrefWidth(width);
                region.setMinWidth(Math.min(width, 280));
                region.setMaxWidth(width);
            }
        }
    }
}
