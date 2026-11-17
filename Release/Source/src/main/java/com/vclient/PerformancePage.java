package com.vclient;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;

/** The Performance tab: FPS history graphs from the in-game mod and the "Analyze PC" recommendations. */
final class PerformancePage {
    private final FeatureHost host;
    private final VBox root = new VBox(18);

    private PerformancePage(FeatureHost host) {
        this.host = host;
    }

    static VBox build(FeatureHost host) {
        PerformancePage page = new PerformancePage(host);
        page.root.getChildren().addAll(page.fpsCard(), page.analysisCard());
        return page.root;
    }

    // ---- FPS history -------------------------------------------------------------------------------

    private VBox fpsCard() {
        VBox card = UiKit.section("FPS history",
                "Recorded by the V-Client mod while you play (one sample per second). Average, lowest and highest FPS "
                        + "for every play session.");
        List<FpsHistory.Sample> samples = FpsHistory.read(FpsHistory.file(host.settings().minecraftDirectory()));
        List<FpsHistory.Session> sessions = FpsHistory.sessions(samples);
        if (sessions.isEmpty()) {
            card.getChildren().add(ModsUi.emptyCard("📈", "No FPS data yet",
                    "Play with the V-Client profile (it includes the FPS recorder) and your sessions will be graphed here."));
            return card;
        }
        int lowest = samples.stream().mapToInt(FpsHistory.Sample::fps).min().orElse(0);
        int highest = samples.stream().mapToInt(FpsHistory.Sample::fps).max().orElse(0);
        card.getChildren().add(new HBox(12,
                UiKit.tile("Average FPS", Math.round(FpsHistory.overallAverage(samples)) + ""),
                UiKit.tile("Lowest FPS", lowest + ""),
                UiKit.tile("Highest FPS", highest + ""),
                UiKit.tile("Sessions", sessions.size() + "")));

        ComboBox<FpsHistory.Session> picker = new ComboBox<>();
        List<FpsHistory.Session> newestFirst = sessions.reversed();
        picker.getItems().addAll(newestFirst);
        picker.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(FpsHistory.Session session) {
                return session == null ? "" : UiKit.date(session.startSeconds() * 1000) + "  ·  "
                        + UiKit.duration((session.endSeconds() - session.startSeconds()) * 1000) + "  ·  avg "
                        + Math.round(session.average());
            }

            @Override
            public FpsHistory.Session fromString(String string) {
                return null;
            }
        });
        Canvas sessionGraph = new Canvas(760, 220);
        picker.setOnAction(event -> drawSeries(sessionGraph, picker.getValue()));
        picker.setValue(newestFirst.getFirst());
        drawSeries(sessionGraph, newestFirst.getFirst());
        Canvas history = new Canvas(760, 150);
        drawAverages(history, sessions);
        card.getChildren().addAll(ModsUi.sectionTitle("Session"), picker, sessionGraph,
                ModsUi.sectionTitle("Average FPS per session (oldest to newest)"), history);
        return card;
    }

    private static void drawSeries(Canvas canvas, FpsHistory.Session session) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (session == null) return;
        List<Double> points = FpsHistory.downsample(session.series(), 360);
        double max = Math.max(60, session.highest()) * 1.1;
        frame(g, canvas, max);
        g.setStroke(Color.web(ModsUi.ACCENT));
        g.setLineWidth(1.8);
        g.beginPath();
        double left = 44;
        double width = canvas.getWidth() - left - 8;
        double height = canvas.getHeight() - 22;
        for (int i = 0; i < points.size(); i++) {
            double x = left + (points.size() == 1 ? 0 : width * i / (points.size() - 1));
            double y = height - height * Math.min(points.get(i), max) / max + 6;
            if (i == 0) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
        g.stroke();
        // Mark the average.
        double avgY = height - height * session.average() / max + 6;
        g.setStroke(Color.web("#39d98a", 0.7));
        g.setLineDashes(6, 5);
        g.setLineWidth(1);
        g.strokeLine(left, avgY, left + width, avgY);
        g.setLineDashes(null);
    }

    private static void drawAverages(Canvas canvas, List<FpsHistory.Session> sessions) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        double max = sessions.stream().mapToDouble(FpsHistory.Session::average).max().orElse(60) * 1.15;
        max = Math.max(60, max);
        frame(g, canvas, max);
        int shown = Math.min(sessions.size(), 40);
        List<FpsHistory.Session> recent = sessions.subList(sessions.size() - shown, sessions.size());
        double left = 44;
        double width = canvas.getWidth() - left - 8;
        double height = canvas.getHeight() - 22;
        double slot = width / shown;
        for (int i = 0; i < shown; i++) {
            double barHeight = height * recent.get(i).average() / max;
            g.setFill(Color.web(ModsUi.ACCENT, 0.85));
            g.fillRoundRect(left + i * slot + slot * 0.12, height - barHeight + 6, slot * 0.76, barHeight, 4, 4);
        }
    }

    private static void frame(GraphicsContext g, Canvas canvas, double max) {
        double left = 44;
        double height = canvas.getHeight() - 22;
        g.setFont(Font.font("Segoe UI", 10));
        g.setLineWidth(1);
        for (int i = 0; i <= 4; i++) {
            double value = max * i / 4;
            double y = height - height * i / 4 + 6;
            g.setStroke(Color.web("#ffffff", 0.08));
            g.strokeLine(left, y, canvas.getWidth() - 8, y);
            g.setFill(Color.web("#9fc6dc"));
            g.fillText(String.valueOf(Math.round(value)), 6, y + 3);
        }
    }

    // ---- analyze PC --------------------------------------------------------------------------------

    private VBox analysisCard() {
        VBox card = UiKit.section("Analyze my PC",
                "Reads your RAM, CPU and graphics card and recommends a render distance, a memory size and the "
                        + "performance mods that suit it.");
        VBox result = new VBox(12);
        Button analyze = UiKit.button("ANALYZE PC", true, () -> {});
        analyze.setOnAction(event -> {
            analyze.setDisable(true);
            result.getChildren().setAll(ModsUi.busyLabel("Reading your hardware..."));
            host.executor().execute(() -> {
                PcAnalysis.Hardware hardware = PcAnalysis.detect();
                PcAnalysis.Advice advice = PcAnalysis.advise(hardware);
                Platform.runLater(() -> {
                    show(result, hardware, advice);
                    analyze.setDisable(false);
                });
            });
        });
        card.getChildren().addAll(analyze, result);
        return card;
    }

    private void show(VBox result, PcAnalysis.Hardware hardware, PcAnalysis.Advice advice) {
        VBox box = new VBox(12);
        box.getChildren().add(UiKit.muted(hardware.ramGb() + " GB RAM  ·  " + hardware.cpuThreads() + " CPU threads  ·  "
                + (hardware.gpuName().isBlank() ? "graphics card not detected" : hardware.gpuName())));
        box.getChildren().add(ModsUi.text(advice.tier().label() + " PC", 18, ModsUi.ACCENT, true));
        box.getChildren().add(new HBox(12,
                UiKit.tile("Render distance", advice.renderDistance() + " chunks"),
                UiKit.tile("Simulation distance", advice.simulationDistance() + " chunks"),
                UiKit.tile("Game memory", advice.memoryGb() + " GB"),
                UiKit.tile("Boost profile", advice.profile().label())));
        javafx.scene.layout.FlowPane mods = new javafx.scene.layout.FlowPane(6, 6);
        for (BoostProfile.BoostMod mod : advice.mods()) mods.getChildren().add(ModsUi.chip(mod.name(), ModsUi.MUTED));
        box.getChildren().addAll(ModsUi.sectionTitle("Recommended performance mods"), mods);
        for (String note : advice.notes()) box.getChildren().add(UiKit.muted("• " + note));
        Button apply = UiKit.button("USE " + advice.memoryGb() + " GB AND THE " + advice.profile().label().toUpperCase()
                + " PROFILE", true, () -> {
            host.settings().setMemoryGb(advice.memoryGb());
            host.settings().setBoostProfile(advice.profile());
            host.saveSettings();
            box.getChildren().add(ModsUi.text("Saved. Open the Boost tab to turn Boost on and install the mods.", 12,
                    ModsUi.GOOD, false));
        });
        box.getChildren().add(apply);
        box.setPadding(new Insets(4, 0, 0, 0));
        result.getChildren().setAll(box);
    }
}
