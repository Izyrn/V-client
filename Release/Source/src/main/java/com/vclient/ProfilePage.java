package com.vclient;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.util.List;

/** The Profile tab: your numbers, achievements, daily login rewards and the Spotify player. */
final class ProfilePage {
    private final FeatureHost host;
    private final VBox root = new VBox(18);

    private ProfilePage(FeatureHost host) {
        this.host = host;
    }

    static VBox build(FeatureHost host) {
        ProfilePage page = new ProfilePage(host);
        page.root.getChildren().addAll(page.summaryCard(), page.badgesCard(), page.dailyCard(), page.achievementsCard(), page.spotifyCard());
        return page.root;
    }

    // ---- summary -----------------------------------------------------------------------------------

    private VBox summaryCard() {
        PlayerAccount account = host.account();
        PlayerStats stats = host.stats();
        VBox card = UiKit.section(account == null ? "No account selected" : account.name(),
                account == null ? "Add an account in Settings." : account.type().label() + " account");
        String favorite = stats.favoriteVersion().isBlank() ? "—" : stats.favoriteVersion();
        List<FpsHistory.Sample> samples = FpsHistory.read(FpsHistory.file(host.settings().minecraftDirectory()));
        String fps = samples.isEmpty() ? "—" : Math.round(FpsHistory.overallAverage(samples)) + " avg";
        VBox cosmetics = UiKit.tile("Cosmetics owned", "…");
        Label cosmeticsValue = (Label) cosmetics.getChildren().getFirst();
        HBox tiles = new HBox(12, UiKit.tile("Favorite version", favorite),
                UiKit.tile("Playtime", UiKit.playtime(stats.playtimeSeconds())),
                UiKit.tile("FPS", fps), cosmetics);
        if (account != null && !account.skinUrl().isBlank()) {
            ImageView head = new ImageView();
            head.setFitWidth(64);
            head.setFitHeight(64);
            host.executor().execute(() -> {
                javafx.scene.image.Image skin = host.downloadImage(account.skinUrl());
                if (skin == null || skin.getPixelReader() == null) return;
                int scale = Math.max(1, (int) skin.getWidth() / 64);
                javafx.scene.image.WritableImage face = new javafx.scene.image.WritableImage(
                        skin.getPixelReader(), 8 * scale, 8 * scale, 8 * scale, 8 * scale);
                Platform.runLater(() -> head.setImage(face));
            });
            head.setSmooth(false);
            card.getChildren().add(head);
        }
        card.getChildren().add(tiles);
        host.executor().execute(() -> {
            int owned = host.ownedCosmeticsCount();
            Platform.runLater(() -> cosmeticsValue.setText(owned < 0 ? "—" : String.valueOf(owned)));
        });
        card.getChildren().add(UiKit.muted(stats.totalLaunches() + (stats.totalLaunches() == 1 ? " launch" : " launches") + " through VYRN. Playtime counts the time "
                + "the game was open; the FPS number needs the VYRN in-game mod."));
        return card;
    }

    // ---- badges ------------------------------------------------------------------------------------

    private VBox badgesCard() {
        VBox card = UiKit.section("Badges", "Early User and Developer are detected automatically. The other badges are "
                + "awarded by the VSMP team.");
        FlowPane flow = new FlowPane(10, 10);
        for (Badges.Badge badge : Badges.ALL) {
            boolean earned = Badges.earned(badge, host.settings(), host.account(), host.stats());
            VBox box = new VBox(4, ModsUi.text((earned ? "★ " : "🔒 ") + badge.title(), 14,
                    earned ? ModsUi.ACCENT : ModsUi.MUTED, true), UiKit.muted(badge.how()));
            box.setPadding(new Insets(12, 16, 12, 16));
            box.setPrefWidth(250);
            box.setStyle("-fx-background-color: rgba(255,255,255," + (earned ? "0.09" : "0.03")
                    + "); -fx-background-radius: 12;");
            flow.getChildren().add(box);
        }
        card.getChildren().add(flow);
        return card;
    }

    // ---- daily rewards -----------------------------------------------------------------------------

    private VBox dailyCard() {
        PlayerStats stats = host.stats();
        LocalDate today = LocalDate.now();
        VBox card = UiKit.section("Daily login rewards",
                "Open VYRN every day to keep your streak. Day 1 earns a cosmetic, day 7 an exclusive cosmetic and "
                        + "day 30 a legend cosmetic. Miss a day and the streak starts again.");
        Label status = ModsUi.text("", 12, ModsUi.MUTED, false);
        status.setWrapText(true);
        ProgressBar progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        FlowPane milestones = new FlowPane(12, 12);
        Button claim = UiKit.button("CLAIM TODAY'S REWARD", true, () -> {});
        Runnable refresh = () -> {
            int next = stats.nextStreakDay(LocalDate.now());
            boolean can = stats.canClaim(LocalDate.now());
            progress.setProgress((can ? next - 1 : stats.streak()) / 30.0);
            claim.setDisable(!can);
            claim.setText(can ? "CLAIM DAY " + next : "CLAIMED TODAY");
            status.setText(can ? "Today's streak day would be " + next + " of 30."
                    : "Come back tomorrow for day " + stats.nextStreakDay(LocalDate.now().plusDays(1)) + ".");
            milestones.getChildren().setAll(milestone(1, stats), milestone(7, stats), milestone(30, stats));
        };
        claim.setOnAction(event -> {
            PlayerStats.Reward reward = stats.claim(LocalDate.now());
            if (reward == null) return;
            host.saveStats();
            refresh.run();
            status.setText(reward == PlayerStats.Reward.NONE ? "Streak day " + stats.streak() + " claimed."
                    : "Day " + stats.streak() + " claimed - you earned the " + reward.label() + "! "
                    + "Cosmetic delivery is not connected to the VSMP backend yet, so it is recorded here until it is.");
        });
        refresh.run();
        card.getChildren().addAll(progress, milestones, UiKit.row(12, claim), status);
        return card;
    }

    private VBox milestone(int day, PlayerStats stats) {
        PlayerStats.Reward reward = PlayerStats.rewardForDay(day);
        boolean earned = stats.earnedRewardDays().contains(day);
        VBox box = new VBox(4, ModsUi.text("Day " + day, 12, ModsUi.MUTED, true),
                ModsUi.text(reward.label(), 14, earned ? ModsUi.GOOD : ModsUi.TEXT, true),
                ModsUi.text(earned ? "✓ Earned" : "Locked", 11, earned ? ModsUi.GOOD : ModsUi.MUTED, false));
        box.setPadding(new Insets(12, 16, 12, 16));
        box.setMinWidth(150);
        box.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 12;");
        return box;
    }

    // ---- achievements ------------------------------------------------------------------------------

    private VBox achievementsCard() {
        PlayerStats stats = host.stats();
        long unlocked = PlayerStats.ACHIEVEMENTS.stream().filter(a -> stats.unlocked(a.id())).count();
        VBox card = UiKit.section("Achievements", unlocked + " of " + PlayerStats.ACHIEVEMENTS.size() + " unlocked");
        for (PlayerStats.Achievement achievement : PlayerStats.ACHIEVEMENTS) {
            boolean done = stats.unlocked(achievement.id());
            Label icon = ModsUi.text(done ? "★" : "☆", 20, done ? ModsUi.ACCENT : "#5d5d80", false);
            VBox text = new VBox(2, ModsUi.text(achievement.title(), 14, done ? ModsUi.TEXT : ModsUi.MUTED, true),
                    UiKit.muted(achievement.description()));
            HBox row = new HBox(14, icon, text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 14, 10, 14));
            row.setStyle("-fx-background-color: rgba(255,255,255," + (done ? "0.08" : "0.03") + "); -fx-background-radius: 12;");
            card.getChildren().add(row);
        }
        return card;
    }

    // ---- spotify -----------------------------------------------------------------------------------

    private VBox spotifyCard() {
        VBox card = UiKit.section("Spotify", "Shows what the Spotify desktop app is playing and controls it. No login "
                + "needed - it reads the app's window and uses your media keys.");
        Label song = ModsUi.text("Looking for Spotify...", 16, ModsUi.TEXT, true);
        song.setWrapText(true);
        Button previous = UiKit.button("⏮", false, SpotifyBridge::previous);
        Button toggle = UiKit.button("⏯  PLAY / PAUSE", true, SpotifyBridge::playPause);
        Button next = UiKit.button("⏭", false, SpotifyBridge::next);
        card.getChildren().addAll(song, UiKit.row(10, previous, toggle, next));
        UiKit.poll(host, card, 2, () -> host.executor().execute(() -> {
            SpotifyBridge.Now now = SpotifyBridge.current();
            Platform.runLater(() -> song.setText("♫  " + now.display()));
        }));
        return card;
    }
}
