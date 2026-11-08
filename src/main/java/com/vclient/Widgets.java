package com.vclient;

import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Small reusable controls for the home and settings pages, in the launcher's dark style. */
final class Widgets {
    private Widgets() {}

    /** A real toggle switch (track + sliding knob) instead of a button that says ON / OFF. */
    static final class Switch extends StackPane {
        private final BooleanProperty on = new SimpleBooleanProperty();
        private final Region track = new Region();
        private final Circle knob = new Circle(8);
        private final TranslateTransition slide = new TranslateTransition(Duration.millis(120), knob);

        Switch(boolean initial, String accent) {
            setMinSize(44, 24);
            setPrefSize(44, 24);
            setMaxSize(44, 24);
            track.setMinSize(44, 24);
            track.setMaxSize(44, 24);
            knob.setFill(javafx.scene.paint.Color.web("#ffffff"));
            StackPane.setAlignment(knob, Pos.CENTER_LEFT);
            getChildren().addAll(track, knob);
            setStyle("-fx-cursor: hand;");
            setFocusTraversable(true);
            on.addListener((observable, was, now) -> render(now, accent, true));
            setOnMouseClicked(event -> on.set(!on.get()));
            setOnKeyPressed(event -> {
                if (event.getCode() == javafx.scene.input.KeyCode.SPACE || event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                    on.set(!on.get());
                }
            });
            on.set(initial);
            render(initial, accent, false);
        }

        BooleanProperty onProperty() {
            return on;
        }

        private void render(boolean state, String accent, boolean animate) {
            track.setStyle("-fx-background-radius: 12; -fx-background-color: " + (state ? accent : "#3a3860")
                    + "; -fx-border-radius: 12; -fx-border-color: " + (state ? accent : "#5b5885") + ";");
            double x = state ? 22 : 4;
            if (animate) {
                slide.setToX(x);
                slide.playFromStart();
            } else {
                knob.setTranslateX(x);
            }
        }
    }

    /** A labelled switch row: caption on the left (with an optional hint under it), the switch on the right. */
    static HBox switchRow(String caption, String hint, boolean initial, String accent, Consumer<Boolean> onChange) {
        Label title = new Label(caption);
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #f3f0ff;");
        VBox text = new VBox(2, title);
        if (hint != null && !hint.isBlank()) {
            Label sub = new Label(hint);
            sub.setWrapText(true);
            sub.setStyle("-fx-font-size: 12px; -fx-text-fill: #b9b9dc;");
            text.getChildren().add(sub);
        }
        Switch toggle = new Switch(initial, accent);
        toggle.onProperty().addListener((observable, was, now) -> onChange.accept(now));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(14, text, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A pulsing grey bar shown while real content loads. It stops pulsing once it leaves the scene. */
    static Region skeleton(double width, double height) {
        Region bar = new Region();
        bar.setMinSize(width, height);
        bar.setPrefSize(width, height);
        bar.setMaxHeight(height);
        if (width <= 0) bar.setMaxWidth(Double.MAX_VALUE);
        bar.setStyle("-fx-background-color: rgba(185, 185, 220, 0.16); -fx-background-radius: 8;");
        FadeTransition pulse = new FadeTransition(Duration.millis(850), bar);
        pulse.setFromValue(0.35);
        pulse.setToValue(1.0);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(FadeTransition.INDEFINITE);
        bar.sceneProperty().addListener((observable, was, scene) -> {
            if (scene == null) pulse.stop();
            else pulse.play();
        });
        return bar;
    }

    /** A card-sized stack of skeleton lines for a section that is still loading. */
    static VBox skeletonBlock(int lines) {
        VBox box = new VBox(10);
        for (int i = 0; i < lines; i++) {
            Region bar = skeleton(0, 14);
            if (i == lines - 1 && lines > 1) bar.setMaxWidth(220);
            box.getChildren().add(bar);
        }
        return box;
    }

    /** A collapsed "Details" section for technical text that most players never need. */
    static TitledPane details(String title, javafx.scene.Node content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setExpanded(false);
        pane.setAnimated(true);
        pane.setStyle("-fx-text-fill: #d6d3ee; -fx-font-size: 12px; -fx-background-color: transparent;");
        return pane;
    }

    /** A path shortened to its last two folders, e.g. {@code ...\V-Client\minecraft}. */
    static String shortPath(Path path) {
        if (path == null) return "";
        Path name = path.getFileName();
        Path parent = path.getParent();
        if (name == null) return path.toString();
        if (parent == null || parent.getFileName() == null) return path.toString();
        return "…" + java.io.File.separator + parent.getFileName() + java.io.File.separator + name;
    }

    static boolean openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            java.awt.Desktop.getDesktop().open(folder.toFile());
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /** Traffic-light colours used by the "Launcher ready" indicator. */
    static final String GREEN = "#39d98a";
    static final String YELLOW = "#f0c24b";
    static final String RED = "#e5626f";
}
