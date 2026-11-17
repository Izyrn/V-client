package com.vclient;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Assistant tab: the log-reading VSMP Assistant and the one-click cleanup. */
final class AssistantPage {
    private final FeatureHost host;
    private final VBox root = new VBox(18);

    private AssistantPage(FeatureHost host) {
        this.host = host;
    }

    static VBox build(FeatureHost host) {
        AssistantPage page = new AssistantPage(host);
        page.root.getChildren().addAll(page.assistantCard(), page.cleanupCard(), BackupCard.build(host));
        return page.root;
    }

    // ---- assistant ---------------------------------------------------------------------------------

    private VBox assistantCard() {
        VBox card = UiKit.section("VSMP Assistant",
                "Reads your newest game log, launch log and crash reports for known problems and tells you how to fix "
                        + "them. It runs entirely on this PC.");
        VBox result = new VBox(10);
        Button check = UiKit.button("CHECK MY GAME", true, () -> {});
        check.setOnAction(event -> {
            check.setDisable(true);
            result.getChildren().setAll(ModsUi.busyLabel("Reading logs..."));
            host.executor().execute(() -> {
                List<LogAssistant.Finding> findings = LogAssistant.diagnose(host.settings().minecraftDirectory(),
                        host.settings().memoryGb(), BoostProfile.physicalRamBytes());
                String crash = LogAssistant.latestCrashSummary(host.settings().minecraftDirectory());
                Platform.runLater(() -> {
                    show(result, findings, crash);
                    check.setDisable(false);
                });
            });
        });
        card.getChildren().addAll(check, result);
        return card;
    }

    private void show(VBox result, List<LogAssistant.Finding> findings, String crash) {
        result.getChildren().clear();
        if (!crash.isBlank()) result.getChildren().add(UiKit.muted("Newest crash report: " + crash));
        for (LogAssistant.Finding finding : findings) {
            boolean ok = finding.title().equals("No problems found");
            VBox box = new VBox(6);
            box.setPadding(new Insets(12, 14, 12, 14));
            box.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 12;");
            box.getChildren().addAll(ModsUi.text((ok ? "✓ " : "⚠ ") + finding.title(), 14,
                    ok ? ModsUi.GOOD : ModsUi.WARNING, true), UiKit.muted(finding.explanation()));
            Button fix = fixButton(finding, box);
            if (fix != null) box.getChildren().add(fix);
            result.getChildren().add(box);
        }
    }

    private Button fixButton(LogAssistant.Finding finding, VBox box) {
        LauncherSettings settings = host.settings();
        long ramGb = Math.max(1, BoostProfile.physicalRamBytes() / (1024L * 1024 * 1024));
        return switch (finding.fix()) {
            case RAISE_MEMORY -> UiKit.button("RAISE MEMORY", true, () -> {
                int target = (int) Math.min(Math.max(settings.memoryGb() + 2, 4), Math.max(2, ramGb / 2));
                settings.setMemoryGb(target);
                host.saveSettings();
                done(box, "Game memory is now " + target + " GB.");
            });
            case LOWER_MEMORY -> UiKit.button("LOWER MEMORY", true, () -> {
                int target = (int) Math.max(2, Math.min(settings.memoryGb() - 2, ramGb / 2));
                settings.setMemoryGb(target);
                host.saveSettings();
                done(box, "Game memory is now " + target + " GB.");
            });
            case USE_JAVA_25 -> UiKit.button("SWITCH TO JAVA 25", true, () -> {
                settings.setJavaMajorOverride(25);
                host.saveSettings();
                done(box, "The game will use Java 25 from the next launch.");
            });
            case CLEAN_CACHE -> UiKit.button("CLEAR CACHE", true, () -> {
                List<CleanupService.Item> items = CleanupService.scan(settings.minecraftDirectory(), launcherDirectory(),
                        Set.of(CleanupService.Category.CACHE), Set.of());
                CleanupService.Result outcome = CleanupService.delete(items, settings.minecraftDirectory(),
                        launcherDirectory());
                done(box, "Cleared " + CleanupService.formatBytes(outcome.bytesFreed()) + " of cache.");
            });
            case OPEN_FAQ_DRIVER -> UiKit.button("OPEN GPU DRIVER DOWNLOADS", false, () -> openPage(
                    "https://www.nvidia.com/Download/index.aspx"));
            case NONE -> null;
        };
    }

    private void done(VBox box, String message) {
        box.getChildren().add(ModsUi.text("✓ " + message, 12, ModsUi.GOOD, false));
    }

    private void openPage(String address) {
        ExternalLinks.open(address, host::log);
    }

    private static Path launcherDirectory() {
        return PlayerStats.defaultFile().getParent();
    }

    // ---- cleanup -----------------------------------------------------------------------------------

    private VBox cleanupCard() {
        VBox card = UiKit.section("One-click cleanup",
                "Frees disk space by deleting logs, caches, temporary files and old versions. Worlds, mods, settings, "
                        + "screenshots, recordings and your accounts are never touched.");
        Map<CleanupService.Category, CheckBox> boxes = new EnumMap<>(CleanupService.Category.class);
        Label summary = ModsUi.text("", 12, ModsUi.MUTED, false);
        VBox list = new VBox(8);
        for (CleanupService.Category category : CleanupService.Category.values()) {
            CheckBox box = new CheckBox(category.label() + "  -  " + category.detail());
            box.setSelected(category != CleanupService.Category.OLD_VERSIONS);
            box.setStyle("-fx-text-fill: " + ModsUi.TEXT + ";");
            boxes.put(category, box);
            list.getChildren().add(box);
        }
        Button scan = UiKit.button("SCAN", false, () -> {});
        Button clean = UiKit.button("CLEAN UP", true, () -> {});
        scan.setOnAction(event -> scan(boxes, summary, scan));
        clean.setOnAction(event -> clean(boxes, summary, clean));
        card.getChildren().addAll(list, UiKit.row(10, scan, clean), summary);
        return card;
    }

    private Set<CleanupService.Category> selected(Map<CleanupService.Category, CheckBox> boxes) {
        Set<CleanupService.Category> chosen = EnumSet.noneOf(CleanupService.Category.class);
        boxes.forEach((category, box) -> {
            if (box.isSelected()) chosen.add(category);
        });
        return chosen;
    }

    /** Versions that must survive: the one the player has selected is protected by the caller via the home page. */
    private Set<String> keepVersions() {
        String selected = host.selectedVersionId();
        return selected == null || selected.isBlank() ? Set.of() : Set.of(selected);
    }

    private void scan(Map<CleanupService.Category, CheckBox> boxes, Label summary, Button scan) {
        Set<CleanupService.Category> chosen = selected(boxes);
        scan.setDisable(true);
        summary.setText("Scanning...");
        host.executor().execute(() -> {
            List<CleanupService.Item> items = CleanupService.scan(host.settings().minecraftDirectory(),
                    launcherDirectory(), chosen, keepVersions());
            StringBuilder text = new StringBuilder();
            for (CleanupService.Category category : chosen) {
                text.append(category.label()).append(": ")
                        .append(CleanupService.formatBytes(CleanupService.totalBytes(items, category))).append("   ");
            }
            long total = items.stream().mapToLong(CleanupService.Item::bytes).sum();
            String message = chosen.isEmpty() ? "Tick something to clean."
                    : text + "\nTotal: " + CleanupService.formatBytes(total) + " in " + items.size() + " items.";
            Platform.runLater(() -> {
                summary.setText(message);
                scan.setDisable(false);
            });
        });
    }

    private void clean(Map<CleanupService.Category, CheckBox> boxes, Label summary, Button clean) {
        Set<CleanupService.Category> chosen = selected(boxes);
        if (chosen.isEmpty()) {
            summary.setText("Tick something to clean.");
            return;
        }
        if (GameSession.running()) {
            summary.setText("Close Minecraft first - cleaning while the game runs can break it.");
            return;
        }
        List<CleanupService.Item> items = CleanupService.scan(host.settings().minecraftDirectory(), launcherDirectory(),
                chosen, keepVersions());
        long total = items.stream().mapToLong(CleanupService.Item::bytes).sum();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Delete " + items.size() + " items ("
                + CleanupService.formatBytes(total) + ")?\n\nThis cannot be undone.", ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("Clean up");
        alert.initOwner(host.stage());
        if (alert.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) return;
        clean.setDisable(true);
        summary.setText("Cleaning...");
        host.executor().execute(() -> {
            CleanupService.Result outcome = CleanupService.delete(items, host.settings().minecraftDirectory(),
                    launcherDirectory());
            Platform.runLater(() -> {
                summary.setText("Freed " + CleanupService.formatBytes(outcome.bytesFreed()) + " ("
                        + outcome.deleted() + " items" + (outcome.failed() > 0 ? ", " + outcome.failed()
                        + " could not be removed" : "") + ").");
                host.log("Cleanup freed " + CleanupService.formatBytes(outcome.bytesFreed()) + ".");
                clean.setDisable(false);
            });
        });
    }
}
