package com.vclient;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/** What the feature pages need from the launcher window, kept narrow so they stay independent of LauncherApp. */
interface FeatureHost {
    LauncherSettings settings();

    PlayerAccount account();

    PlayerStats stats();

    RecordingLibrary recordings();

    ReplayBuffer replayBuffer();

    ExecutorService executor();

    /** The latest message from the replay buffer (for example "Saved the last 30 seconds"), or empty. */
    String replayStatus();

    Stage stage();

    void log(String line);

    void saveSettings();

    void saveStats();

    void showSettings();

    /** Opens the FrostBoost page. */
    void openBoost();

    /** Opens the FPS comparison screen (also refreshes it when it is already open). */
    void showFpsComparison();

    /** True while {@code page} is still the page on screen, so background timers can stop themselves. */
    boolean shown(Node page);

    /** The Modrinth target (game version and loader) of the Fabric profile mods are installed for. */
    ModrinthModService.Target fabricTarget() throws IOException;

    ModrinthModService modrinth();

    /** Re-draws the current page after the colour theme changed. */
    void themeChanged();

    ActivityLog activity();

    BackendClient backend();

    /** Opens the Friends page. */
    void openFriends();

    /** Id of the version picked on the Home page, or an empty string. */
    String selectedVersionId();

    /** The selected account with a fresh access token, or an exception explaining why it cannot be refreshed. */
    PlayerAccount refreshedAccount() throws Exception;

    /** Downloads (and caches) an image; null when it cannot be fetched. */
    Image downloadImage(String address);

    /** Number of cosmetics the account owns according to the backend, or -1 when that cannot be read. */
    int ownedCosmeticsCount();
}
