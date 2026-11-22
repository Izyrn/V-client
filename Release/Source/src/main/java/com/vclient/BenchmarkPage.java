package com.vclient;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The FPS comparison screen: this PC's hardware, the before / after benchmark with the percentage gain, the profile
 * recommended from the measurement, live diagnostics (average, 1% lows, memory, CPU / GPU bottleneck) and a check
 * that the Boost settings really are what the game runs with.
 */
final class BenchmarkPage {
    private final FeatureHost host;
    private final VBox root = new VBox(18);
    private final VBox hardwareBody = new VBox(8);

    private BenchmarkPage(FeatureHost host) {
        this.host = host;
    }

    static VBox build(FeatureHost host) {
        BenchmarkPage page = new BenchmarkPage(host);
        Path directory = host.settings().minecraftDirectory();
        BenchmarkStore.ingest(directory).ifPresent(result -> host.log("FPS benchmark finished: " + BenchmarkStore.summary(result)));
        List<BenchmarkStore.Result> results = BenchmarkStore.load(directory);
        page.root.getChildren().addAll(page.hardwareCard(), page.comparisonCard(results), page.diagnosticsCard(),
                page.verificationCard());
        return page.root;
    }

    // ---- hardware --------------------------------------------------------------------------------------

    private VBox hardwareCard() {
        VBox card = UiKit.section("Your PC", "What FrostBoost tunes for. Low-end, mid-range and high-end PCs get different settings.");
        hardwareBody.getChildren().setAll(ModsUi.busyLabel("Reading your hardware..."));
        card.getChildren().add(hardwareBody);
        host.executor().execute(() -> {
            PcAnalysis.Hardware hardware = PcAnalysis.cached();
            PcAnalysis.Advice advice = PcAnalysis.advise(hardware);
            String minecraft = host.selectedVersionId().isBlank() ? "not selected" : host.selectedVersionId();
            Platform.runLater(() -> showHardware(hardware, advice, minecraft));
        });
        return card;
    }

    private void showHardware(PcAnalysis.Hardware hardware, PcAnalysis.Advice advice, String minecraft) {
        VBox box = new VBox(10);
        box.getChildren().add(new HBox(12,
                UiKit.tile("CPU", hardware.cpuName().isBlank() ? hardware.cpuThreads() + " threads" : hardware.cpuName()),
                UiKit.tile("GPU", hardware.gpuName().isBlank() ? "not detected" : hardware.bestGpu()),
                UiKit.tile("RAM", hardware.ramGb() + " GB"),
                UiKit.tile("Minecraft", minecraft)));
        box.getChildren().add(ModsUi.text(advice.tier().label() + " PC  ·  suggested profile: " + advice.profile().label(),
                15, ModsUi.ACCENT, true));
        for (String note : advice.notes()) box.getChildren().add(UiKit.muted("• " + note));
        hardwareBody.getChildren().setAll(box);
    }

    // ---- before / after -----------------------------------------------------------------------------------

    private VBox comparisonCard(List<BenchmarkStore.Result> results) {
        VBox card = UiKit.section("❄ FPS comparison",
                "A 60-second test in a world, before FrostBoost and after it. Max FPS and VSync are lifted during the test so "
                        + "the two runs can be compared fairly.");
        BenchmarkStore.Comparison comparison = BenchmarkStore.compare(results);
        LauncherSettings settings = host.settings();

        if (comparison.before() == null) {
            card.getChildren().add(ModsUi.emptyCard("📈", "No benchmark yet",
                    "Run the test, start the game, and join any world. FrostBoost measures 60 seconds and shows the result here."));
        } else {
            card.getChildren().add(resultRow("Before", comparison.before()));
            if (comparison.after() != null) {
                card.getChildren().add(resultRow("After", comparison.after()));
                double gain = comparison.avgGainPercent();
                String improvement = BenchmarkStore.formatPercent(gain);
                VBox verdict = new VBox(4,
                        ModsUi.text("Before: " + Math.round(comparison.before().avgFps()) + " FPS", 14, ModsUi.MUTED, false),
                        ModsUi.text("After: " + Math.round(comparison.after().avgFps()) + " FPS", 14, ModsUi.TEXT, true),
                        ModsUi.text("Improvement: " + improvement, 22, gain >= 0 ? ModsUi.GOOD : ModsUi.WARNING, true),
                        UiKit.muted("1% lows: " + Math.round(comparison.before().low1()) + " → " + Math.round(comparison.after().low1())
                                + " FPS (" + BenchmarkStore.formatPercent(comparison.lowGainPercent()) + ")"));
                verdict.setPadding(new Insets(12, 16, 12, 16));
                verdict.setStyle("-fx-background-color: rgba(0,207,255,0.10); -fx-background-radius: 12;"
                        + " -fx-border-color: rgba(124,235,255,0.35); -fx-border-radius: 12;");
                card.getChildren().add(verdict);
                if (gain < 3) {
                    card.getChildren().add(UiKit.muted("Little or no gain: the game was probably limited by something Boost cannot change "
                            + "(see Diagnostics below), or both runs were taken in different places. Test in the same spot."));
                }
            } else {
                card.getChildren().add(UiKit.muted("Now turn FrostBoost on (FrostBoost page) and launch again: the \"after\" test runs by itself."));
            }
            if (comparison.before() != null) card.getChildren().add(recommendation(comparison.before()));
        }

        String pending = settings.benchmarkPending();
        Button before = UiKit.button("TEST BEFORE FROSTBOOST", false, () -> request("before"));
        Button after = UiKit.button("TEST AFTER FROSTBOOST", false, () -> request("after"));
        CheckBox auto = new CheckBox("Run the test automatically on the first launch and recommend a profile");
        auto.setSelected(settings.autoBenchmark());
        auto.setStyle("-fx-text-fill: " + ModsUi.MUTED + ";");
        auto.setOnAction(event -> {
            settings.setAutoBenchmark(auto.isSelected());
            host.saveSettings();
        });
        card.getChildren().addAll(new HBox(10, before, after), auto);
        if (!pending.isEmpty()) {
            card.getChildren().add(ModsUi.text("The next game launch will run the \"" + pending + "\" test: join a world and play normally for about 65 seconds.",
                    12, ModsUi.GOOD, false));
        }
        return card;
    }

    private void request(String label) {
        host.settings().setBenchmarkPending(label);
        host.saveSettings();
        host.log("FPS benchmark \"" + label + "\" will run on the next launch.");
        host.showFpsComparison();
    }

    private HBox resultRow(String title, BenchmarkStore.Result result) {
        HBox row = new HBox(12,
                UiKit.tile(title + " · average FPS", Math.round(result.avgFps()) + ""),
                UiKit.tile("1% lows", Math.round(result.low1()) + ""),
                UiKit.tile("Memory", result.memoryUsedMb() + " / " + result.memoryMaxMb() + " MB"),
                UiKit.tile("Bottleneck", FpsDiagnostics.bottleneck(result.cpuBoundPct(), result.gpuBoundPct(), result.cappedPct()).label()));
        return row;
    }

    private VBox recommendation(BenchmarkStore.Result baseline) {
        BoostAdvisor.Recommendation recommendation = BoostAdvisor.recommend(baseline, PcAnalysis.cachedOrBasic());
        VBox box = new VBox(8, ModsUi.sectionTitle("Recommended profile"),
                ModsUi.text(recommendation.profile().label(), 18, ModsUi.ACCENT, true), UiKit.muted(recommendation.reason()));
        boolean current = host.settings().boostProfile() == recommendation.profile() && host.settings().boostEnabled();
        if (!current) {
            box.getChildren().add(UiKit.button("USE " + recommendation.profile().label().toUpperCase(Locale.ROOT), true, () -> {
                host.settings().setBoostProfile(recommendation.profile());
                host.saveSettings();
                host.openBoost();
            }));
        } else {
            box.getChildren().add(ModsUi.text("This profile is already on.", 12, ModsUi.GOOD, false));
        }
        return box;
    }

    // ---- diagnostics -------------------------------------------------------------------------------------

    private VBox diagnosticsCard() {
        VBox card = UiKit.section("FPS diagnostics", "Measured by the in-game mod during your last play session.");
        FpsDiagnostics.Snapshot live = FpsDiagnostics.read(host.settings().minecraftDirectory());
        if (live == null) {
            card.getChildren().add(ModsUi.emptyCard("📊", "No data yet",
                    "Play for a minute with the VYRN profile and the numbers appear here."));
            return card;
        }
        FpsDiagnostics.Bottleneck bottleneck = FpsDiagnostics.bottleneck(live);
        card.getChildren().add(new FlowPane(12, 12,
                UiKit.tile("Average FPS", Math.round(live.avgFps()) + ""),
                UiKit.tile("1% lows", Math.round(live.low1()) + ""),
                UiKit.tile("0.1% lows", Math.round(live.low01()) + ""),
                UiKit.tile("Memory", live.memoryUsedMb() + " / " + live.memoryMaxMb() + " MB"),
                UiKit.tile("CPU bound", Math.round(live.cpuBoundPct()) + "%"),
                UiKit.tile("GPU bound", Math.round(live.gpuBoundPct()) + "%"),
                UiKit.tile("Main limit", bottleneck.label())));
        for (String note : FpsDiagnostics.findings(live, PcAnalysis.cachedOrBasic())) card.getChildren().add(UiKit.muted("• " + note));
        card.getChildren().add(UiKit.muted("Recorded " + UiKit.date(live.updatedAt()) + " over " + live.seconds() + " seconds."));
        return card;
    }

    // ---- verification ------------------------------------------------------------------------------------

    private VBox verificationCard() {
        VBox card = UiKit.section("Is FrostBoost really applied?",
                "Compares what the profile promised (memory, JVM flags, video settings, mods) with what the game actually ran with.");
        LauncherSettings settings = host.settings();
        if (!settings.boostEnabled()) {
            card.getChildren().add(UiKit.muted("FrostBoost is off, so there is nothing to verify. Turn it on in the FrostBoost page."));
            return card;
        }
        Path directory = settings.minecraftDirectory();
        BoostProfile.Tuning tuning = settings.boostProfile().tuning(PcAnalysis.cachedOrBasic());
        FpsDiagnostics.Snapshot live = FpsDiagnostics.read(directory);
        List<BoostVerifier.Check> checks = BoostVerifier.verify(tuning, live, directory);
        long ok = BoostVerifier.count(checks, BoostVerifier.Status.OK);
        long bad = BoostVerifier.count(checks, BoostVerifier.Status.MISMATCH);
        long unknown = BoostVerifier.count(checks, BoostVerifier.Status.UNKNOWN);
        card.getChildren().add(ModsUi.text(ok + " of " + checks.size() + " applied" + (bad > 0 ? ", " + bad + " not as expected" : "")
                + (unknown > 0 ? ", " + unknown + " not checked yet (launch the game once)" : ""), 14,
                bad > 0 ? ModsUi.WARNING : ModsUi.GOOD, true));
        String group = "";
        for (BoostVerifier.Check check : checks) {
            if (!check.group().equals(group)) {
                group = check.group();
                card.getChildren().add(ModsUi.sectionTitle(group));
            }
            String mark = check.status() == BoostVerifier.Status.OK ? "✓ " : check.status() == BoostVerifier.Status.MISMATCH ? "✗ " : "○ ";
            String colour = check.status() == BoostVerifier.Status.OK ? ModsUi.GOOD
                    : check.status() == BoostVerifier.Status.MISMATCH ? ModsUi.WARNING : ModsUi.MUTED;
            card.getChildren().add(ModsUi.text(mark + check.name() + "  expected " + check.expected() + ", actual " + check.actual(),
                    11, colour, false));
        }
        return card;
    }
}
