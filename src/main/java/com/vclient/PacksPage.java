package com.vclient;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** The one-click packs: PvP, FPS, SMP and Builder. */
final class PacksPage {
    private final FeatureHost host;
    private final VBox root = new VBox(18);
    private final VBox result = new VBox(10);
    private final AtomicBoolean busy = new AtomicBoolean();

    private PacksPage(FeatureHost host) {
        this.host = host;
    }

    static VBox build(FeatureHost host) {
        PacksPage page = new PacksPage(host);
        page.assemble();
        return page.root;
    }

    private void assemble() {
        FlowPane cards = new FlowPane(16, 16);
        for (PackCatalog.Pack pack : PackCatalog.PACKS) cards.getChildren().add(packCard(pack));
        root.getChildren().addAll(cards, result, UiKit.muted(
                "Packs install from Modrinth into VYRN's mods folder for your Fabric profile. Mods that have no "
                        + "release for your Minecraft version are skipped, and anything already installed is left alone."));
    }

    private VBox packCard(PackCatalog.Pack pack) {
        VBox card = ModsUi.glassCard(12, new Insets(20));
        card.setPrefWidth(300);
        card.setMinWidth(260);
        Label title = ModsUi.text(pack.title(), 18, ModsUi.TEXT, true);
        FlowPane chips = new FlowPane(6, 6);
        for (PackCatalog.PackMod mod : pack.mods()) chips.getChildren().add(ModsUi.chip(mod.name(), ModsUi.MUTED));
        Button install = UiKit.button("INSTALL " + pack.title().toUpperCase(), true, () -> install(pack));
        card.getChildren().addAll(title, UiKit.muted(pack.tagline()), chips, install);
        return card;
    }

    private void install(PackCatalog.Pack pack) {
        if (host.settings().offlineMode()) {
            show(ModsUi.warningBox("Offline mode is on", "Turn it off on the Home page to install packs.", null));
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        Label progress = ModsUi.text("Installing " + pack.title() + "...", 12, ModsUi.MUTED, false);
        show(ModsUi.busyLabel("Installing " + pack.title() + "..."));
        host.executor().execute(() -> {
            try {
                ModrinthModService.Target target = host.fabricTarget();
                PackCatalog.Outcome outcome = PackCatalog.install(pack, host.modrinth(), target,
                        host.settings().minecraftDirectory(),
                        line -> Platform.runLater(() -> host.log(line)));
                Platform.runLater(() -> {
                    if (!outcome.installed().isEmpty() && host.stats().unlock(PlayerStats.FIRST_MOD)) host.saveStats();
                    host.log(pack.title() + ": installed " + outcome.installed() + ", already " + outcome.already()
                            + ", unavailable " + outcome.unavailable() + ", failed " + outcome.failed());
                    showOutcome(pack, outcome);
                });
            } catch (Exception e) {
                String[] message = ModsUi.describeFailure("Installing " + pack.title(), e.getMessage());
                Platform.runLater(() -> show(ModsUi.warningBox(message[0], message[1], null)));
            } finally {
                busy.set(false);
            }
        });
    }

    private void showOutcome(PackCatalog.Pack pack, PackCatalog.Outcome outcome) {
        VBox box = ModsUi.glassCard(8, new Insets(16));
        box.getChildren().add(ModsUi.text(pack.title() + " - " + outcome.installed().size() + " installed",
                15, outcome.failed().isEmpty() ? ModsUi.GOOD : ModsUi.WARNING, true));
        line(box, "Installed", outcome.installed());
        line(box, "Already there", outcome.already());
        line(box, "No release for your Minecraft version", outcome.unavailable());
        line(box, "Failed", outcome.failed());
        result.getChildren().setAll(box);
    }

    private static void line(VBox box, String heading, List<String> names) {
        if (!names.isEmpty()) box.getChildren().add(UiKit.muted(heading + ": " + String.join(", ", names)));
    }

    private void show(javafx.scene.Node node) {
        result.getChildren().setAll(node);
    }
}
