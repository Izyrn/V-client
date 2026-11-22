package com.vclient;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Backup and restore of mods, settings, cosmetics and profiles. */
final class BackupCard {
    private final FeatureHost host;
    private final VBox card = UiKit.section("Backup & restore",
            "Saves your mods, settings, cosmetics and profiles into one zip so you can move to a new PC or undo a "
                    + "mistake. Sign-in tokens and saved accounts are never included.");
    private final Map<BackupService.Category, CheckBox> boxes = new EnumMap<>(BackupService.Category.class);
    private final VBox list = new VBox(8);
    private final Label message = ModsUi.text("", 12, ModsUi.MUTED, false);

    private BackupCard(FeatureHost host) {
        this.host = host;
    }

    static VBox build(FeatureHost host) {
        BackupCard backup = new BackupCard(host);
        backup.assemble();
        return backup.card;
    }

    private Path launcherDirectory() {
        return PlayerStats.defaultFile().getParent();
    }

    private Path folder() {
        return BackupService.defaultFolder(launcherDirectory());
    }

    private void assemble() {
        message.setWrapText(true);
        HBox choices = new HBox(16);
        for (BackupService.Category category : BackupService.Category.values()) {
            CheckBox box = new CheckBox(category.label());
            box.setSelected(true);
            box.setStyle("-fx-text-fill: " + ModsUi.TEXT + ";");
            boxes.put(category, box);
            choices.getChildren().add(box);
        }
        Button create = UiKit.button("CREATE BACKUP", true, this::create);
        Button open = UiKit.button("OPEN BACKUP FOLDER", false, () -> {
            try {
                Files.createDirectories(folder());
                Desktop.getDesktop().open(folder().toFile());
            } catch (IOException | UnsupportedOperationException e) {
                message.setText("Could not open the folder: " + e.getMessage());
            }
        });
        card.getChildren().addAll(choices, UiKit.row(10, create, open), message,
                ModsUi.sectionTitle("Your backups"), list);
        reload();
    }

    private Set<BackupService.Category> selected() {
        Set<BackupService.Category> chosen = EnumSet.noneOf(BackupService.Category.class);
        boxes.forEach((category, box) -> {
            if (box.isSelected()) chosen.add(category);
        });
        return chosen;
    }

    private void create() {
        Set<BackupService.Category> chosen = selected();
        if (chosen.isEmpty()) {
            message.setText("Tick at least one thing to back up.");
            return;
        }
        message.setText("Creating backup...");
        host.executor().execute(() -> {
            try {
                Path file = BackupService.create(folder(), host.settings().minecraftDirectory(), launcherDirectory(), chosen);
                host.activity().add("Created backup " + file.getFileName());
                Platform.runLater(() -> {
                    message.setText("Saved " + file.getFileName() + " (" + CleanupService.formatBytes(sizeOf(file)) + ").");
                    reload();
                });
            } catch (IOException e) {
                Platform.runLater(() -> message.setText("Backup failed: " + e.getMessage()));
            }
        });
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private void reload() {
        list.getChildren().clear();
        List<BackupService.Backup> backups = BackupService.list(folder());
        if (backups.isEmpty()) {
            list.getChildren().add(UiKit.muted("No backups yet."));
            return;
        }
        for (BackupService.Backup backup : backups) {
            Label name = ModsUi.text(backup.file().getFileName().toString(), 13, ModsUi.TEXT, true);
            Label detail = ModsUi.text(UiKit.date(backup.modifiedMillis()) + "  ·  "
                    + CleanupService.formatBytes(backup.sizeBytes()), 11, ModsUi.MUTED, false);
            HBox row = new HBox(12, new VBox(2, name, detail), UiKit.grow(),
                    UiKit.button("RESTORE", true, () -> restore(backup)),
                    UiKit.dangerButton("DELETE", () -> delete(backup)));
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 14, 10, 14));
            row.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 12;");
            list.getChildren().add(row);
        }
    }

    private void restore(BackupService.Backup backup) {
        if (GameSession.running()) {
            message.setText("Close Minecraft before restoring a backup.");
            return;
        }
        Set<BackupService.Category> chosen = selected();
        Set<BackupService.Category> available;
        try {
            available = BackupService.contents(backup.file());
        } catch (IOException e) {
            message.setText("That backup can't be read: " + e.getMessage());
            return;
        }
        chosen.retainAll(available);
        if (chosen.isEmpty()) {
            message.setText("The ticked categories are not in this backup (it holds: " + available + ").");
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Restore " + chosen + " from " + backup.file().getFileName()
                + "?\n\nFiles with the same name are overwritten. Nothing is deleted. This PC's game folder and sign-in "
                + "are kept.", ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("Restore backup");
        alert.initOwner(host.stage());
        if (alert.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) return;
        message.setText("Restoring...");
        host.executor().execute(() -> {
            try {
                BackupService.Result result = BackupService.restore(backup.file(), host.settings().minecraftDirectory(),
                        launcherDirectory(), chosen);
                host.activity().add("Restored " + result.files() + " files from " + backup.file().getFileName());
                Platform.runLater(() -> message.setText("Restored " + result.files() + " files. Restart VYRN to "
                        + "apply restored settings."));
            } catch (IOException e) {
                Platform.runLater(() -> message.setText("Restore failed: " + e.getMessage()));
            }
        });
    }

    private void delete(BackupService.Backup backup) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Delete " + backup.file().getFileName() + "?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(host.stage());
        alert.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
            try {
                Files.deleteIfExists(backup.file());
            } catch (IOException e) {
                message.setText("Could not delete: " + e.getMessage());
            }
            reload();
        });
    }
}
