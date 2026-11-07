package com.vclient;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class LauncherApp extends Application {
    private enum Page {
        HOME, BROWSER, SERVERS, LIBRARY, MODS, PACKS, BOOST, PERFORMANCE, WARDROBE, COSMETICS, PROFILE, RECORDINGS,
        SCREENSHOTS, FRIENDS, NEWS, ASSISTANT, LOGS, CONSOLE, SETTINGS, DISCORD
    }

    private record ServerEntry(String name, String host, int port) {}

    // VSMP's address: change it here when it moves (JOIN OUR SMP and the Servers page both use it).
    private static final String VSMP_HOST = "vsmp.meowlix.sh";
    private static final int VSMP_PORT = 19754;
    private static final List<ServerEntry> SERVER_LIST = List.of(
            new ServerEntry("VSMP", VSMP_HOST, VSMP_PORT),
            new ServerEntry("LifeAMC", "play.lifeamc.fun", 25565),
            new ServerEntry("WSMP", "play.wsmp.fun", 25565));

    private record VersionChoice(String id, String label, String status,
                                 MinecraftLauncher.AvailableVersion available,
                                 MinecraftLauncher.InstalledVersion installed,
                                 boolean preferredEntry) {
        @Override
        public String toString() {
            return label + "   ·   " + status;
        }
    }

    private static String BACKGROUND = "#08091a";
    private static final String MUTED = "#9999bd";
    private static String ACCENT = "#e6bd59";
    private static final HttpClient IMAGE_HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "vclient-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final JavaRuntimeManager javaRuntimeManager = new JavaRuntimeManager();
    private final ModrinthModService modrinthModService = new ModrinthModService();
    private MinecraftLauncher gameLauncher;
    private final MicrosoftAuthService authService = new MicrosoftAuthService();
    private final ElyByAuthService elyByAuthService = new ElyByAuthService();
    private final AccountStore accountStore = new AccountStore();
    private final LocalFriendsStore friendsStore = new LocalFriendsStore();
    private final List<PlayerAccount> accounts = new ArrayList<>();
    private final List<String> localFriends = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();
    private final Map<Page, Button> navigationButtons = new EnumMap<>(Page.class);
    private Page hoveredPage;
    private List<MinecraftLauncher.InstalledVersion> installedVersions = List.of();
    private MinecraftLauncher.VersionCatalog versionCatalog;
    private final java.util.Set<String> versionsDownloading = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> modsDownloading = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String selectedVersionId = "";
    private LauncherSettings settings;
    private PlayerStats stats;
    private RecordingLibrary recordingLibrary;
    private ReplayBuffer replayBuffer;
    private FeatureHost featureHost;
    private ActivityLog activityLog;

    static void setThemeColors(String accent, String background) {
        ACCENT = accent;
        BACKGROUND = background;
    }
    private volatile String replayStatus = "";
    private PlayerAccount account;
    private Stage stage;
    private ComboBox<VersionChoice> versionPicker;
    private ComboBox<PlayerAccount> accountPicker;
    private Label status;
    private Label accountLabel;
    private Button playButton;
    private Button singleplayerButton;
    private Button installVersionButton;
    private Button refreshVersionsButton;
    private Label versionStatusLabel;
    private Button connectionTestButton;
    private Label connectionResultLabel;
    private ImageView avatar;
    private Label headerAccountLabel;
    private TextArea log;
    private TextArea consoleLog;
    private boolean settingsPage;
    private Page activePage = Page.HOME;
    private boolean accountPersistenceInProgress;
    private boolean protectedAccountSavePending;
    private AnimationTimer backgroundAnimation;
    private final List<BackgroundStar> backgroundStars = createBackgroundStars();
    private long lastBackgroundFrame;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        settings = LauncherSettings.load();
        gameLauncher = new MinecraftLauncher((requiredMajor, onOutput, allowDownloads) -> {
            int selectedMajor = settings.javaMajorOverride() == 25
                    ? 25 : requiredMajor;
            return javaRuntimeManager.resolve(selectedMajor, onOutput, allowDownloads);
        });
        startFeatureServices();
        gameLauncher.setCheatPermission(settings::cheatsEnabled);
        gameLauncher.setAdminPermission(settings::isAdmin);
        gameLauncher.setBoostTuning(() -> settings.boostEnabled()
                ? settings.boostProfile().tuning(BoostProfile.physicalRamBytes()) : null);
        BackendClient.setRequestLog(line -> Platform.runLater(() -> appendLog(line)), settings.debugMode());
        gameLauncher.setBackendUrl(() -> BackendClient.resolveUrl(settings.backendUrl()));
        accounts.addAll(accountStore.load());
        localFriends.addAll(friendsStore.load());
        account = accounts.stream().filter(saved -> saved.id().equals(settings.lastAccountId()))
                .findFirst().orElse(accounts.isEmpty() ? null : accounts.getFirst());
        stage.setTitle("V-Client");
        stage.getIcons().add(createIcon());
        stage.setMinWidth(760);
        stage.setMinHeight(640);
        stage.setWidth(960);
        stage.setHeight(760);
        if (account == null) showLogin();
        else showHome();
        stage.show();
        String startPage = System.getProperty("vclient.startPage", "");
        if (!startPage.isBlank() && account != null) {
            // Debugging aid: -Dvclient.startPage=MODS opens that page straight away.
            try {
                navigate(Page.valueOf(startPage.strip().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                appendLog("Unknown vclient.startPage value: " + startPage);
            }
        }
        startBackendTasks();
    }

    /** Playtime, the recordings importer and the replay buffer all follow the game process. */
    private void startFeatureServices() {
        stats = PlayerStats.load(PlayerStats.defaultFile());
        stats.markSeen(java.time.LocalDate.now());
        stats.save();
        activityLog = new ActivityLog(ActivityLog.defaultFile());
        Theme.apply(settings.themeId(), settings.customAccent());
        recordingLibrary = new RecordingLibrary(RecordingLibrary.defaultFolder());
        replayBuffer = new ReplayBuffer(recordingLibrary.folder(), settings::clipHotkey,
                settings::replayBufferEnabled, settings::clipMicrophone, message -> replayStatus = message,
                file -> activityLog.add("Saved clip " + file.getFileName()),
                () -> settings.minecraftDirectory().resolve("vclient").resolve("recording.state"));
        GameSession.addListener(replayBuffer);
        GameSession.addListener(new RecordingWatcher(recordingLibrary, settings::minecraftDirectory,
                file -> {
                    appendLogLater("Imported recording " + file.getFileName());
                    activityLog.add("Imported recording " + file.getFileName());
                }));
        GameSession.addListener(new GameSession.Listener() {
            @Override
            public void started(String versionId, long startedAtMillis) {
                stats.recordLaunch(versionId);
                activityLog.add("Launched Minecraft " + versionId);
                stats.save();
            }

            @Override
            public void ended(String versionId, long startedAtMillis, long endedAtMillis) {
                stats.addPlaytime((endedAtMillis - startedAtMillis) / 1000);
                activityLog.add("Played " + UiKit.playtime((endedAtMillis - startedAtMillis) / 1000) + " of " + versionId);
                stats.save();
            }
        });
    }

    private void showHome() {
        activePage = Page.HOME;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = new VBox(22);
        content.setPadding(new Insets(38, 54, 32, 54));
        Label eyebrow = label("YOUR GAME, YOUR WAY", 11, ACCENT);
        eyebrow.setStyle(eyebrow.getStyle() + "-fx-font-weight: bold; -fx-letter-spacing: 2;");
        Label title = label("Ready to play?", 34, "#f3f6fc");
        Label subtitle = label("Choose a profile and jump back into your world.", 14, MUTED);
        VBox heading = new VBox(8, eyebrow, title, subtitle);
        Region homeStatusDot = dot("#64728a");
        Label homeStatus = label("", 12, "#b3c0d3");
        HBox homeStatusRow = new HBox(8, homeStatusDot, homeStatus);
        homeStatusRow.setAlignment(Pos.CENTER_LEFT);
        loadServerStatus(homeStatus, homeStatusDot);
        VBox homeAnnouncements = announcementBox();

        VBox versionCard = card();
        Label versionTitle = label("GAME VERSION", 11, MUTED);
        versionPicker = new ComboBox<>();
        versionPicker.setMaxWidth(Double.MAX_VALUE);
        versionPicker.setPrefHeight(48);
        versionPicker.setStyle("-fx-background-color: rgba(20, 20, 45, 0.92); -fx-text-fill: #f3f0ff; -fx-font-size: 14px; -fx-border-color: #48415f; -fx-border-radius: 8; -fx-background-radius: 8;");
        versionPicker.setPromptText("Scanning installed versions...");
        versionPicker.setCellFactory(list -> versionChoiceCell());
        versionPicker.setButtonCell(versionChoiceCell());
        versionPicker.valueProperty().addListener((observable, oldVersion, newVersion) -> {
            if (newVersion != null) {
                selectedVersionId = newVersion.id();
                versionStatusLabel.setText("Selected " + newVersion.id() + " · " + newVersion.status());
                if (newVersion.available() != null && newVersion.installed() == null) {
                    downloadSelectedVersion(newVersion);
                }
            }
        });
        versionCard.getChildren().addAll(versionTitle, versionPicker);
        javafx.scene.layout.FlowPane versionActions = new javafx.scene.layout.FlowPane(10, 10);
        versionActions.setAlignment(Pos.CENTER_LEFT);
        installVersionButton = button("INSTALL / REPAIR SELECTED VERSION", "#292642", 10);
        installVersionButton.setOnAction(event -> installSelectedVersion());
        refreshVersionsButton = button("REFRESH VERSIONS", "#292642", 10);
        refreshVersionsButton.setOnAction(event -> refreshVersions(selectedVersionId));
        versionStatusLabel = label("Fetching the Mojang version catalog...", 11, MUTED);
        versionStatusLabel.setWrapText(true);
        Button offlineToggle = button("", "#292642", 10);
        Runnable updateOfflineToggle = () -> {
            boolean offline = settings.offlineMode();
            offlineToggle.setText(offline ? "OFFLINE MODE: ON" : "OFFLINE MODE: OFF");
            offlineToggle.setStyle(offlineToggle.getStyle().replaceAll("-fx-background-color: [^;]+;",
                    "-fx-background-color: " + (offline ? "#244a38" : "#292642") + ";"));
            installVersionButton.setDisable(offline);
        };
        updateOfflineToggle.run();
        offlineToggle.setOnAction(event -> {
            settings.setOfflineMode(!settings.offlineMode());
            saveSettings();
            appendLog(settings.offlineMode()
                    ? "Offline mode on: V-Client will not contact Mojang, Modrinth, skin servers, or download anything."
                    : "Offline mode off: V-Client can download versions, libraries, assets, and Java again.");
            updateOfflineToggle.run();
            refreshVersions(selectedVersionId);
        });
        versionActions.getChildren().addAll(installVersionButton, refreshVersionsButton, offlineToggle);
        versionCard.getChildren().addAll(versionActions, versionStatusLabel);
        HBox connectionRow = new HBox(12);
        connectionRow.setAlignment(Pos.CENTER_LEFT);
        connectionTestButton = button("TEST CONNECTION", "#292642", 11);
        connectionTestButton.setOnAction(event -> runConnectionTest());
        connectionResultLabel = label("Check internet, DNS, TLS, and Mojang access.", 12, MUTED);
        connectionRow.getChildren().addAll(connectionTestButton, connectionResultLabel);
        versionCard.getChildren().add(connectionRow);

        HBox launchRow = new HBox(14);
        launchRow.setAlignment(Pos.CENTER_LEFT);
        playButton = button("▶    PLAY", ACCENT, 17);
        playButton.setPrefHeight(54);
        HBox.setHgrow(playButton, Priority.ALWAYS);
        playButton.setMaxWidth(Double.MAX_VALUE);
        playButton.setOnAction(event -> launchGame(null));
        singleplayerButton = button("JOIN OUR SMP", "#292642", 14);
        singleplayerButton.setPrefHeight(54);
        singleplayerButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(singleplayerButton, Priority.ALWAYS);
        singleplayerButton.setOnAction(event -> launchGame(SERVER_LIST.getFirst()));
        Button settingsButton = button("SETTINGS", "#292642", 12);
        settingsButton.setPrefHeight(54);
        settingsButton.setOnAction(event -> showSettings());
        launchRow.getChildren().addAll(playButton, singleplayerButton, settingsButton);

        HBox statusRow = new HBox(8, dot("#39d98a"), label("LAUNCHER READY", 11, "#b3c0d3"));
        statusRow.setAlignment(Pos.CENTER_LEFT);
        Label logTitle = label("LAUNCH OUTPUT", 11, MUTED);
        log = new TextArea();
        log.setEditable(false);
        log.setWrapText(true);
        log.setPrefRowCount(8);
        log.setStyle("-fx-control-inner-background: rgba(9, 10, 28, 0.82); -fx-text-fill: #c3bfdc; -fx-font-family: 'Consolas'; -fx-font-size: 11px; -fx-background-color: rgba(9, 10, 28, 0.82); -fx-border-color: #37334f; -fx-border-radius: 8; -fx-background-radius: 8;");
        log.setText(String.join(System.lineSeparator(), logLines));
        VBox.setVgrow(log, Priority.ALWAYS);
        content.getChildren().addAll(heading, launchEventBanner(), homeAnnouncements, homeStatusRow, versionCard, launchRow, statusRow, logTitle, log);
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
        refreshVersions(selectedVersionId);
    }

    private void showSettings() {
        activePage = Page.SETTINGS;
        settingsPage = true;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = new VBox(22);
        content.setPadding(new Insets(38, 54, 34, 54));
        Label title = label("Settings", 32, "#f3f6fc");
        Label subtitle = label("Tune your game setup. Changes are saved on this device.", 14, MUTED);

        VBox directoryCard = card();
        Label directoryTitle = label("MINECRAFT DIRECTORY", 11, MUTED);
        HBox directoryRow = new HBox(10);
        directoryRow.setAlignment(Pos.CENTER_LEFT);
        TextField directoryField = new TextField(settings.minecraftDirectory().toString());
        directoryField.setEditable(false);
        directoryField.setMaxWidth(Double.MAX_VALUE);
        directoryField.setStyle(fieldStyle());
        HBox.setHgrow(directoryField, Priority.ALWAYS);
        Button selectFolderButton = button("SELECT FOLDER", "#292642", 11);
        selectFolderButton.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose Minecraft directory");
            if (settings.minecraftDirectory().toFile().isDirectory()) {
                chooser.setInitialDirectory(settings.minecraftDirectory().toFile());
            }
            var selected = chooser.showDialog(stage);
            if (selected != null) {
                Path selectedDirectory = LauncherSettings.normalizeMinecraftDirectory(selected.toPath());
                settings.setMinecraftDirectory(selectedDirectory);
                directoryField.setText(selectedDirectory.toString());
                saveSettings();
                refreshVersions();
            }
        });
        directoryRow.getChildren().addAll(directoryField, selectFolderButton);
        Label privateFolderHint = label(
                "This is V-Client's private Minecraft folder. Import installed game files below to copy them here without deleting the original or downloading anything.",
                11, MUTED);
        privateFolderHint.setWrapText(true);
        Button importButton = button("IMPORT EXISTING GAME FILES", ACCENT, 10);
        importButton.setOnAction(event -> importLocalMinecraftFiles(importButton));
        directoryCard.getChildren().addAll(directoryTitle, directoryRow, privateFolderHint, importButton);

        VBox memoryCard = card();
        HBox memoryHeading = new HBox();
        memoryHeading.setAlignment(Pos.CENTER_LEFT);
        Label memoryTitle = label("MAXIMUM GAME MEMORY", 11, MUTED);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label memoryValue = label(settings.memoryGb() + " GB", 17, "#f3f6fc");
        memoryHeading.getChildren().addAll(memoryTitle, spacer, memoryValue);
        Slider memorySlider = new Slider(1, LauncherSettings.maxMemory(), settings.memoryGb());
        memorySlider.setMajorTickUnit(1);
        memorySlider.setMinorTickCount(0);
        memorySlider.setSnapToTicks(true);
        memorySlider.setShowTickMarks(true);
        memorySlider.setStyle("-fx-control-inner-background: " + ACCENT + "; -fx-accent: " + ACCENT + ";");
        Label memoryHint = label("More memory can help large modpacks. Leave some RAM for Windows.", 12, MUTED);
        memorySlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            int gigabytes = newValue.intValue();
            memoryValue.setText(gigabytes + " GB");
            settings.setMemoryGb(gigabytes);
            saveSettings();
        });
        memoryCard.getChildren().addAll(memoryHeading, memorySlider, memoryHint);

        VBox javaCard = card();
        Label javaTitle = label("MINECRAFT JAVA RUNTIME", 11, MUTED);
        ComboBox<String> javaChoice = new ComboBox<>();
        String automaticJava = "Automatic (Java 25 for Minecraft 1.21.1)";
        String java25Choice = "Always use Java 25";
        javaChoice.getItems().setAll(automaticJava, java25Choice);
        javaChoice.setValue(settings.javaMajorOverride() == 25 ? java25Choice : automaticJava);
        javaChoice.setMaxWidth(Double.MAX_VALUE);
        javaChoice.setStyle("-fx-background-color: rgba(20, 20, 45, 0.92); -fx-text-fill: #f3f0ff; -fx-font-size: 14px; -fx-border-color: #48415f; -fx-border-radius: 8; -fx-background-radius: 8;");
        Label javaStatus = label("Checking for an installed 64-bit Java 25 runtime...", 11, MUTED);
        javaStatus.setWrapText(true);
        javaChoice.valueProperty().addListener((observable, oldChoice, newChoice) -> {
            settings.setJavaMajorOverride(java25Choice.equals(newChoice) ? 25 : 0);
            saveSettings();
        });
        javaCard.getChildren().addAll(javaTitle, javaChoice, javaStatus);
        executor.execute(() -> {
            try {
                Path installedJava = JavaRuntimeManager.detectInstalledJava25();
                Platform.runLater(() -> javaStatus.setText(installedJava == null
                        ? "Java 25 not detected. V-Client will download it automatically when needed."
                        : "Detected Java 25: " + installedJava));
            } catch (IOException e) {
                Platform.runLater(() -> {
                    javaStatus.setText("Could not check installed Java 25: " + e.getMessage());
                    appendLog("Java 25 detection failed: " + e.getMessage());
                });
            }
        });

        VBox accountCard = buildAccountCard();
        VBox playerModeCard = buildPlayerModeCard();

        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_LEFT);
        Button backButton = button("←    BACK TO PLAY", ACCENT, 12);
        backButton.setPrefHeight(46);
        backButton.setOnAction(event -> showHome());
        status = label("Settings save automatically.", 12, MUTED);
        footer.getChildren().addAll(backButton, status);
        content.getChildren().addAll(title, subtitle, directoryCard, memoryCard, javaCard, buildVpnCard(), ThemeCard.build(featureHost()), accountCard);
        // Hacker / Cheater mode is for the admin account only, so nobody else even sees the option.
        if (settings.isAdmin(account)) content.getChildren().add(playerModeCard);
        content.getChildren().add(footer);
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
    }

    /** Imports a WireGuard config and connects/disconnects a system-wide VPN (Minecraft traffic included). */
    private VBox buildVpnCard() {
        VBox card = card();
        Label title = label("VPN", 11, MUTED);
        Label state = label("Checking VPN...", 14, "#f3f6fc");
        Label hint = label("Built-in VPN, nothing to install and no administrator prompt. Press CONNECT and V-Client "
                + "tunnels your VSMP connection (" + VSMP_HOST + ", from the Servers page or JOIN OUR SMP) through a "
                + "free Cloudflare WARP VPN. It keeps your connection private but does not change your country, and "
                + "only that server is covered, not the rest of your PC. Have your own VPN? Use IMPORT CONFIG with a "
                + "WireGuard .conf instead.", 11, MUTED);
        hint.setWrapText(true);
        Button importConfig = button("IMPORT CONFIG", "#292642", 11);
        Button toggle = button("CONNECT", ACCENT, 11);
        HBox row = new HBox(10, importConfig, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        AtomicBoolean busy = new AtomicBoolean();
        Runnable refresh = () -> executor.execute(() -> {
            boolean connected = VpnManager.isConnected();
            boolean configured = VpnManager.hasConfig();
            Platform.runLater(() -> {
                state.setText(connected ? "Connected" : configured ? "Disconnected" : "Off (free Cloudflare VPN, one click)");
                toggle.setText(connected ? "DISCONNECT" : "CONNECT");
                toggle.setDisable(false);
            });
        });
        importConfig.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose WireGuard config");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WireGuard config", "*.conf"));
            var selected = chooser.showOpenDialog(stage);
            if (selected == null) return;
            try {
                VpnManager.importConfig(selected.toPath());
                appendLog("VPN config imported.");
            } catch (IOException e) {
                showError("Could not import VPN config", e);
            }
            refresh.run();
        });
        toggle.setOnAction(event -> {
            if (!busy.compareAndSet(false, true)) return;
            toggle.setDisable(true);
            state.setText("Waiting for Windows...");
            executor.execute(() -> {
                try {
                    boolean wasConnected = VpnManager.isConnected();
                    if (wasConnected) VpnManager.disconnect();
                    else VpnManager.connect(List.of(InetSocketAddress.createUnresolved(VSMP_HOST, VSMP_PORT)),
                            line -> Platform.runLater(() -> appendLog(line)));
                    Platform.runLater(() -> appendLog(wasConnected ? "VPN disconnected." : "VPN connected."));
                } catch (IOException e) {
                    Platform.runLater(() -> {
                        appendLog("VPN error: " + e.getMessage());
                        showError("VPN", e);
                    });
                } finally {
                    busy.set(false);
                    refresh.run();
                }
            });
        });
        card.getChildren().addAll(title, state, row, hint);
        refresh.run();
        return card;
    }

    /** Normal player / Hacker-Cheater choice for the selected account; cheats also need an admin account. */
    private VBox buildPlayerModeCard() {
        VBox card = card();
        Label title = label("PLAYER MODE", 11, MUTED);
        ComboBox<String> choice = new ComboBox<>();
        String normal = "Normal player";
        String hacker = "Hacker / Cheater";
        choice.getItems().setAll(normal, hacker);
        choice.setValue(settings.hackerSelected(account) ? hacker : normal);
        choice.setMaxWidth(Double.MAX_VALUE);
        choice.setStyle("-fx-background-color: rgba(20, 20, 45, 0.92); -fx-text-fill: #f3f0ff; -fx-font-size: 14px; -fx-border-color: #48415f; -fx-border-radius: 8; -fx-background-radius: 8;");
        Label hint = label("", 11, MUTED);
        hint.setWrapText(true);
        Runnable refreshHint = () -> hint.setText(account == null
                ? "Select an account first."
                : !settings.hackerSelected(account)
                ? "Normal permissions: the Right Shift menu has the regular V-Client mods."
                : settings.isAdmin(account)
                ? "Hacker mode is active for " + account.name() + ": the Right Shift menu also has the hacker modules (X-Ray, Fly, Glow ESP, No Fall, Anti Knockback and more)."
                : "Only admin Ely.by accounts can use Hacker / Cheater. " + account.name() + " will play with normal permissions.");
        refreshHint.run();
        choice.valueProperty().addListener((observable, oldChoice, newChoice) -> {
            settings.setHackerSelected(account, hacker.equals(newChoice));
            saveSettings();
            refreshHint.run();
        });
        card.getChildren().addAll(title, choice, hint);
        return card;
    }

    private void importLocalMinecraftFiles(Button importButton) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose the Minecraft game folder to import from");
        Path initialDirectory = settings.minecraftDirectory().getParent();
        if (initialDirectory != null && Files.isDirectory(initialDirectory)) {
            chooser.setInitialDirectory(initialDirectory.toFile());
        }
        var selected = chooser.showDialog(stage);
        if (selected == null) return;
        Path source = LauncherSettings.normalizeMinecraftDirectory(selected.toPath());
        Path destination = settings.minecraftDirectory();
        importButton.setDisable(true);
        if (status != null) status.setText("Copying local Minecraft files into V-Client...");
        appendLog("Importing local Minecraft files from " + source + " into " + destination + ".");
        executor.execute(() -> {
            try {
                LocalMinecraftImporter.importGameFiles(source, destination, message ->
                        Platform.runLater(() -> {
                            if (status != null) status.setText(message);
                            appendLog(message);
                        }));
                List<MinecraftLauncher.InstalledVersion> versions = gameLauncher.findVersions(destination);
                Platform.runLater(() -> {
                    installedVersions = versions;
                    MinecraftLauncher.InstalledVersion preferred =
                            MinecraftLauncher.preferredInstalledVersion(versions);
                    if (preferred != null) selectedVersionId = preferred.id();
                    showHome();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (status != null) status.setText("Import failed: " + e.getMessage());
                    appendLog("Minecraft import failed: " + e.getMessage());
                    showError("Could not import Minecraft files", e);
                });
            } finally {
                Platform.runLater(() -> importButton.setDisable(false));
            }
        });
    }

    private VBox pageContent(String eyebrowText, String titleText, String subtitleText) {
        VBox content = new VBox(18);
        content.setPadding(new Insets(30, 32, 30, 32));
        Label eyebrow = label(eyebrowText.toUpperCase(), 10, ACCENT);
        Label title = label(titleText, 30, "#f3f6fc");
        Label subtitle = label(subtitleText, 13, MUTED);
        subtitle.setWrapText(true);
        content.getChildren().add(new VBox(7, eyebrow, title, subtitle));
        return content;
    }

    private void showLibrary() {
        activePage = Page.LIBRARY;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Your games", "Library", "Installed Minecraft profiles from your selected game folder.");
        HBox toolbar = new HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        Label directory = label(settings.minecraftDirectory().toString(), 11, MUTED);
        directory.setWrapText(true);
        HBox.setHgrow(directory, Priority.ALWAYS);
        Button refresh = button("REFRESH", "#292642", 10);
        Label result = label("Scanning installed profiles...", 11, MUTED);
        toolbar.getChildren().addAll(directory, refresh);
        ListView<MinecraftLauncher.InstalledVersion> profiles = new ListView<>();
        profiles.setPlaceholder(label("No installed profiles found.", 13, MUTED));
        profiles.setStyle("-fx-control-inner-background: rgba(9, 10, 28, 0.82); -fx-background-color: transparent; -fx-border-color: rgba(170, 151, 207, 0.24);");
        profiles.setPrefHeight(290);
        profiles.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(MinecraftLauncher.InstalledVersion version, boolean empty) {
                super.updateItem(version, empty);
                if (empty || version == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = label(version.displayName(), 14, "#f3f6fc");
                Label details = label(version.type().toUpperCase() + "  ·  " + version.jsonFile(), 10, MUTED);
                details.setWrapText(true);
                VBox item = new VBox(5, name, details);
                item.setPadding(new Insets(8, 2, 8, 2));
                setText(null);
                setGraphic(item);
            }
        });
        profiles.getItems().setAll(installedVersions);
        Button useProfile = button("USE SELECTED PROFILE ON HOME", ACCENT, 11);
        useProfile.setOnAction(event -> {
            MinecraftLauncher.InstalledVersion chosen = profiles.getSelectionModel().getSelectedItem();
            if (chosen == null) {
                showError("Select a profile", new IOException("Choose an installed Minecraft profile first."));
                return;
            }
            selectedVersionId = chosen.id();
            showHome();
        });
        refresh.setOnAction(event -> loadLibraryProfiles(profiles, result));
        content.getChildren().addAll(toolbar, profiles, useProfile, result);
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
        loadLibraryProfiles(profiles, result);
    }

    private void loadLibraryProfiles(ListView<MinecraftLauncher.InstalledVersion> profiles, Label result) {
        result.setText("Scanning installed profiles...");
        executor.execute(() -> {
            try {
                List<MinecraftLauncher.InstalledVersion> found = gameLauncher.findVersions(settings.minecraftDirectory());
                Platform.runLater(() -> {
                    installedVersions = found;
                    profiles.getItems().setAll(found);
                    result.setText(found.size() + (found.size() == 1 ? " installed profile." : " installed profiles."));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    result.setText("Could not scan profiles: " + e.getMessage());
                    showError("Library scan failed", e);
                });
            }
        });
    }

    /** Shows one of the feature pages built in their own classes. */
    private void showFeaturePage(Page page, String eyebrow, String title, String subtitle,
                                 java.util.function.Function<FeatureHost, javafx.scene.Node> builder) {
        activePage = page;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent(eyebrow, title, subtitle);
        content.getChildren().add(builder.apply(featureHost()));
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
    }

    private FeatureHost featureHost() {
        if (featureHost == null) {
            featureHost = new FeatureHost() {
                @Override public LauncherSettings settings() { return settings; }
                @Override public PlayerAccount account() { return account; }
                @Override public PlayerStats stats() { return stats; }
                @Override public RecordingLibrary recordings() { return recordingLibrary; }
                @Override public ReplayBuffer replayBuffer() { return replayBuffer; }
                @Override public ExecutorService executor() { return executor; }
                @Override public String replayStatus() { return replayStatus; }
                @Override public Stage stage() { return stage; }
                @Override public void log(String line) { appendLogLater(line); }
                @Override public void saveSettings() { LauncherApp.this.saveSettings(); }
                @Override public void saveStats() { stats.save(); }
                @Override public void showSettings() { LauncherApp.this.showSettings(); }
                @Override public boolean shown(javafx.scene.Node page) {
                    return page.getScene() != null && page.getScene() == stage.getScene();
                }
                @Override public String selectedVersionId() { return selectedVersionId; }
                @Override public void themeChanged() { navigate(activePage); }
                @Override public ActivityLog activity() { return activityLog; }
                @Override public BackendClient backend() { return LauncherApp.this.backend(); }
                @Override public void openFriends() { navigate(Page.FRIENDS); }
                @Override public ModrinthModService modrinth() { return modrinthModService; }
                @Override public ModrinthModService.Target fabricTarget() throws IOException {
                    Path directory = settings.minecraftDirectory();
                    MinecraftLauncher.InstalledVersion fabric = gameLauncher.findVersions(directory).stream()
                            .filter(version -> "Fabric".equals(version.loader()))
                            .sorted(java.util.Comparator.comparing((MinecraftLauncher.InstalledVersion version) ->
                                    !version.id().equals(selectedVersionId)))
                            .findFirst().orElse(null);
                    if (fabric == null) {
                        throw new IOException("No Fabric profile is installed. Install the V-Client Fabric profile on the Home page first.");
                    }
                    return ModrinthModService.resolveTarget(directory, fabric);
                }
                @Override public PlayerAccount refreshedAccount() throws Exception {
                    PlayerAccount current = account;
                    if (current == null) throw new IOException("No account is selected.");
                    PlayerAccount fresh = refreshAccount(current);
                    if (!fresh.equals(current)) Platform.runLater(() -> updateRefreshedAccount(current.id(), fresh));
                    return fresh;
                }
                @Override public Image downloadImage(String address) { return LauncherApp.this.downloadImage(address); }
                @Override public int ownedCosmeticsCount() {
                    PlayerAccount current = account;
                    if (current == null || current.uuid() == null || settings.offlineMode()) return -1;
                    try {
                        return backend().ownedCosmetics(current.uuid()).size();
                    } catch (IOException | InterruptedException e) {
                        return -1;
                    }
                }
            };
        }
        return featureHost;
    }

    private void showBoost() {
        activePage = Page.BOOST;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Performance", "VSMP Boost",
                "One switch for more FPS and fewer lag spikes: tuned video settings, optimisation mods, the right amount of memory and smoother garbage collection.");
        BoostPage page = new BoostPage(content);
        content.getChildren().addAll(page.masterCard(), page.status, page.profilesCard(), page.detailsCard(), page.manualMemoryCard());
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
        page.refresh();
    }

    /** The Boost page: master switch, four profile cards, what the selected profile changes, manual memory. */
    private final class BoostPage {
        private final VBox content;
        private final VBox status = new VBox(10);
        private final HBox estimateRow = new HBox(12);
        private final VBox detailsBody = new VBox(16);
        private final javafx.scene.layout.FlowPane profileFlow = new javafx.scene.layout.FlowPane(12, 12);
        private final List<VBox> profileCards = new ArrayList<>();
        private final Label masterState = ModsUi.text("", 13, ModsUi.MUTED, false);
        private final Label hardware = ModsUi.text("", 11, "#7f7fa6", false);
        private javafx.scene.layout.StackPane masterSwitch;
        private boolean working;

        BoostPage(VBox content) {
            this.content = content;
            status.setManaged(false);
            status.setVisible(false);
            profileFlow.setMaxWidth(Double.MAX_VALUE);
            profileFlow.widthProperty().addListener((observable, oldWidth, newWidth) -> layoutProfiles());
        }

        VBox masterCard() {
            VBox card = ModsUi.glassCard(14, new Insets(20));
            Label title = ModsUi.text("VSMP BOOST MODE", 16, ModsUi.TEXT, true);
            masterSwitch = ModsUi.switchPill(settings.boostEnabled(), this::onToggle);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox top = new HBox(14, title, spacer, masterState, masterSwitch);
            top.setAlignment(Pos.CENTER_LEFT);
            estimateRow.setAlignment(Pos.CENTER_LEFT);
            hardware.setWrapText(true);
            card.getChildren().addAll(top, estimateRow, hardware);
            return card;
        }

        VBox profilesCard() {
            VBox card = ModsUi.glassCard(14, new Insets(20));
            card.getChildren().addAll(ModsUi.sectionTitle("Choose a profile"), profileFlow);
            for (BoostProfile profile : BoostProfile.values()) {
                VBox profileCard = profileCard(profile);
                profileCards.add(profileCard);
                profileFlow.getChildren().add(profileCard);
            }
            return card;
        }

        VBox detailsCard() {
            VBox card = ModsUi.glassCard(14, new Insets(20));
            Button apply = new Button("APPLY AND INSTALL MODS");
            apply.setMinHeight(42);
            apply.setStyle("-fx-background-color: " + ACCENT + "; -fx-text-fill: #171326; -fx-font-size: 12px; -fx-font-weight: bold;"
                    + " -fx-background-radius: 12; -fx-padding: 0 22 0 22; -fx-cursor: hand;");
            apply.setOnAction(event -> apply());
            Button restore = new Button("RESTORE MY SETTINGS");
            restore.setMinHeight(42);
            restore.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: #f5f7fd; -fx-font-size: 12px;"
                    + " -fx-font-weight: bold; -fx-background-radius: 12; -fx-padding: 0 22 0 22; -fx-cursor: hand;");
            restore.setOnAction(event -> disable());
            HBox buttons = new HBox(10, apply, restore);
            buttons.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().addAll(ModsUi.sectionTitle("What this profile changes"), detailsBody, buttons,
                    ModsUi.text("Your own video settings are saved first and come back with RESTORE MY SETTINGS or when you switch Boost Mode off. "
                            + "While Boost Mode is on, the profile's video settings are applied at every launch.", 11, ModsUi.MUTED, false));
            ((Label) card.getChildren().get(card.getChildren().size() - 1)).setWrapText(true);
            return card;
        }

        VBox manualMemoryCard() {
            VBox card = ModsUi.glassCard(14, new Insets(20));
            HBox heading = new HBox();
            heading.setAlignment(Pos.CENTER_LEFT);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label value = ModsUi.text(settings.memoryGb() + " GB", 18, ModsUi.TEXT, true);
            heading.getChildren().addAll(ModsUi.sectionTitle("Manual game memory (used when Boost Mode is off)"), spacer, value);
            Slider memory = new Slider(1, LauncherSettings.maxMemory(), settings.memoryGb());
            memory.setMajorTickUnit(1);
            memory.setMinorTickCount(0);
            memory.setSnapToTicks(true);
            memory.setShowTickMarks(true);
            memory.setStyle("-fx-control-inner-background: " + ACCENT + "; -fx-accent: " + ACCENT + ";");
            long ramGb = BoostProfile.physicalRamBytes() / (1024L * 1024 * 1024);
            Label warning = ModsUi.text("", 11, ModsUi.WARNING, false);
            warning.setWrapText(true);
            Runnable checkRam = () -> warning.setText(settings.memoryGb() > ramGb * 3 / 4
                    ? "This PC has about " + ramGb + " GB of RAM. Giving the game this much can slow Windows and the game down; "
                    + "leave at least a quarter for the system." : "");
            memory.valueProperty().addListener((observable, oldValue, newValue) -> {
                settings.setMemoryGb(newValue.intValue());
                value.setText(newValue.intValue() + " GB");
                checkRam.run();
                saveSettings();
            });
            HBox presets = new HBox(8);
            for (int preset : List.of(2, 4, 6, 8, 12, 16, 24, 32)) {
                if (preset > LauncherSettings.maxMemory()) continue;
                Button choice = new Button(preset + " GB");
                choice.setMinHeight(32);
                choice.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: #f5f7fd; -fx-font-size: 11px;"
                        + " -fx-font-weight: bold; -fx-background-radius: 10; -fx-padding: 0 14 0 14; -fx-cursor: hand;");
                choice.setOnAction(event -> memory.setValue(preset));
                presets.getChildren().add(choice);
            }
            checkRam.run();
            card.getChildren().addAll(heading, memory, presets, warning);
            return card;
        }

        private VBox profileCard(BoostProfile profile) {
            Label name = ModsUi.text(profile.label(), 15, ModsUi.TEXT, true);
            Label tagline = ModsUi.text(profile.tagline(), 11, ModsUi.MUTED, false);
            tagline.setWrapText(true);
            tagline.setMinHeight(32);
            Label gain = ModsUi.chip("≈ " + profile.estimatedFpsGain() + " FPS", ModsUi.GOOD);
            var options = profile.options();
            Label facts = ModsUi.text("Render distance " + options.get("renderDistance") + "  ·  Simulation "
                    + options.get("simulationDistance") + "\n" + profile.mods().size() + " optimisation mods", 11, "#8f8fb4", false);
            facts.setWrapText(true);
            VBox card = new VBox(8, name, tagline, gain, facts);
            card.setPadding(new Insets(16));
            card.setUserData(profile);
            card.setCursor(javafx.scene.Cursor.HAND);
            card.setOnMouseClicked(event -> select(profile));
            return card;
        }

        private void styleProfileCards() {
            for (VBox card : profileCards) {
                boolean selected = card.getUserData() == settings.boostProfile();
                String base = "-fx-background-radius: 14; -fx-border-radius: 14; -fx-border-width: " + (selected ? "2" : "1") + ";"
                        + " -fx-background-color: " + (selected ? Theme.accentRgba(0.13) : "rgba(255,255,255,0.05)") + ";"
                        + " -fx-border-color: " + (selected ? Theme.accentRgba(0.85) : "rgba(255,255,255,0.10)") + ";";
                card.setStyle(base);
                ModsUi.hoverLift(card, base);
            }
        }

        private void layoutProfiles() {
            double available = profileFlow.getWidth();
            if (available <= 0) return;
            int columns = available >= 900 ? 4 : available >= 520 ? 2 : 1;
            double width = Math.floor((available - 12 * (columns - 1)) / columns) - 1;
            profileFlow.setPrefWrapLength(available);
            for (VBox card : profileCards) {
                card.setPrefWidth(width);
                card.setMinWidth(Math.min(width, 180));
                card.setMaxWidth(width);
            }
        }

        // ---- state -----------------------------------------------------------------------------------

        void refresh() {
            long ram = BoostProfile.physicalRamBytes();
            BoostProfile profile = settings.boostProfile();
            boolean enabled = settings.boostEnabled();
            masterState.setText(enabled ? profile.label() + " is on" : "Off");
            masterState.setStyle(masterState.getStyle() + " -fx-text-fill: " + (enabled ? ModsUi.GOOD : ModsUi.MUTED) + ";");
            ModsUi.setSwitch(masterSwitch, enabled);
            estimateRow.getChildren().setAll(
                    ModsUi.text("Estimated FPS gain", 12, ModsUi.MUTED, false),
                    ModsUi.chip("≈ " + profile.estimatedFpsGain(), ModsUi.GOOD),
                    ModsUi.text("typical range, not a measurement - it depends on your PC, world and server", 11, "#7f7fa6", false));
            hardware.setText("This PC has about " + (ram / (1024L * 1024 * 1024)) + " GB of RAM, so " + profile.label()
                    + " gives the game " + profile.recommendedMemoryGb(ram) + " GB. Java is chosen automatically: V-Client uses the Azul Zulu version your Minecraft version needs and downloads it when missing.");
            styleProfileCards();
            layoutProfiles();
            showDetails(profile, ram);
        }

        private void showDetails(BoostProfile profile, long ram) {
            Path modsDirectory = settings.minecraftDirectory().resolve("mods");
            VBox video = new VBox(4);
            video.getChildren().add(ModsUi.text("Video settings", 12, ModsUi.TEXT, true));
            profile.options().forEach((key, value) -> video.getChildren().add(
                    ModsUi.text(prettyOption(key) + "  →  " + value.replace("\"", ""), 11, ModsUi.MUTED, false)));
            VBox java = new VBox(4);
            int memory = profile.recommendedMemoryGb(ram);
            java.getChildren().addAll(ModsUi.text("Memory and JVM", 12, ModsUi.TEXT, true),
                    ModsUi.text("Heap  -Xmx" + memory + "G  -Xms" + profile.initialHeap(memory), 11, ModsUi.MUTED, false),
                    ModsUi.text(profile.jvmFlags().size() + " garbage-collector flags for short, even pauses", 11, ModsUi.MUTED, false),
                    ModsUi.text(String.join("  ", profile.jvmFlags()), 10, "#6f6f96", false));
            ((Label) java.getChildren().get(3)).setWrapText(true);
            VBox mods = new VBox(4);
            mods.getChildren().add(ModsUi.text("Optimisation mods (Fabric, from Modrinth)", 12, ModsUi.TEXT, true));
            for (BoostProfile.BoostMod mod : profile.mods()) {
                boolean present = modInstalled(modsDirectory, mod.slug());
                mods.getChildren().add(ModsUi.text((present ? "✓ " : "○ ") + mod.name() + "  —  " + mod.effect(), 11,
                        present ? ModsUi.GOOD : ModsUi.MUTED, false));
            }
            javafx.scene.layout.FlowPane columns = new javafx.scene.layout.FlowPane(28, 16, video, java, mods);
            detailsBody.getChildren().setAll(columns);
            columns.setPrefWrapLength(600);
        }

        private String prettyOption(String key) {
            return switch (key) {
                case "enableVsync" -> "VSync";
                case "maxFps" -> "Max FPS";
                case "graphicsPreset" -> "Graphics";
                case "ao" -> "Smooth lighting";
                case "entityShadows" -> "Entity shadows";
                case "renderClouds" -> "Clouds";
                case "particles" -> "Particles (0 all, 1 fewer, 2 minimal)";
                case "mipmapLevels" -> "Mipmap levels";
                case "renderDistance" -> "Render distance";
                case "simulationDistance" -> "Simulation distance";
                case "biomeBlendRadius" -> "Biome blend";
                case "entityDistanceScaling" -> "Entity distance";
                default -> key;
            };
        }

        private boolean modInstalled(Path modsDirectory, String slug) {
            String token = slug.replace("-", "");
            try (var files = Files.isDirectory(modsDirectory) ? Files.list(modsDirectory) : java.util.stream.Stream.<Path>empty()) {
                return files.anyMatch(file -> {
                    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
                    return name.contains(token) && name.endsWith(".jar");
                });
            } catch (IOException e) {
                return false;
            }
        }

        private void select(BoostProfile profile) {
            if (working) return;
            settings.setBoostProfile(profile);
            saveSettings();
            refresh();
            if (settings.boostEnabled()) apply();
        }

        private void onToggle(boolean on) {
            if (working) {
                refresh();
                return;
            }
            if (on) apply();
            else disable();
        }

        private void showStatus(javafx.scene.Node... nodes) {
            status.getChildren().setAll(nodes);
            status.setManaged(true);
            status.setVisible(true);
        }

        private HBox success(String message) {
            Label icon = ModsUi.text("✓", 16, "#08140e", true);
            icon.setMinSize(30, 30);
            icon.setAlignment(Pos.CENTER);
            icon.setStyle(icon.getStyle() + " -fx-background-color: " + ModsUi.GOOD + "; -fx-background-radius: 15;");
            Label text = ModsUi.text(message, 13, "#bff5d6", true);
            text.setWrapText(true);
            HBox box = new HBox(12, icon, text);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new Insets(12, 16, 12, 14));
            box.setStyle("-fx-background-color: rgba(57,217,138,0.10); -fx-background-radius: 14;"
                    + " -fx-border-color: rgba(57,217,138,0.40); -fx-border-radius: 14;");
            return box;
        }

        // ---- apply / restore ---------------------------------------------------------------------------

        void apply() {
            if (working) return;
            working = true;
            BoostProfile profile = settings.boostProfile();
            settings.setBoostEnabled(true);
            saveSettings();
            if (stats.unlock(PlayerStats.FPS_BOOST)) stats.save();
            refresh();
            showStatus(ModsUi.busyLabel("Applying " + profile.label() + "..."));
            Path minecraftDirectory = settings.minecraftDirectory();
            boolean offline = settings.offlineMode();
            executor.execute(() -> {
                List<javafx.scene.Node> notes = new ArrayList<>();
                int installed = 0;
                int already = 0;
                int unavailable = 0;
                String modProblem = null;
                int changed = 0;
                try {
                    changed = BoostOptions.apply(minecraftDirectory, profile.options(),
                            line -> Platform.runLater(() -> appendLog(line)));
                } catch (IOException e) {
                    modProblem = null;
                    String message = e.getMessage();
                    notes.add(ModsUi.warningBox("Couldn't write the video settings", message, null));
                    Platform.runLater(() -> appendLog("Boost: could not write options.txt: " + message));
                }
                Path modsDirectory = minecraftDirectory.resolve("mods");
                try {
                    if (offline) throw new IOException("Offline mode is on, so Modrinth is not contacted. Turn it off on the Home page to install the optimisation mods.");
                    MinecraftLauncher.InstalledVersion fabric = gameLauncher.findVersions(minecraftDirectory).stream()
                            .filter(version -> "Fabric".equals(version.loader()))
                            .sorted(java.util.Comparator.comparing((MinecraftLauncher.InstalledVersion version) ->
                                    !version.id().equals(selectedVersionId)))
                            .findFirst().orElse(null);
                    if (fabric == null) {
                        throw new IOException("No Fabric profile is installed, so the optimisation mods were skipped. Install the V-Client Fabric profile on the Home page first.");
                    }
                    ModrinthModService.Target target = ModrinthModService.resolveTarget(minecraftDirectory, fabric);
                    for (BoostProfile.BoostMod mod : profile.mods()) {
                        if (modInstalled(modsDirectory, mod.slug())) {
                            already++;
                            continue;
                        }
                        ModrinthModService.ModProject project = modrinthModService.findBySlug(mod.slug(), mod.name(), target,
                                line -> Platform.runLater(() -> appendLog(line)));
                        if (project == null) {
                            unavailable++;
                            Platform.runLater(() -> appendLog("Boost: " + mod.name() + " has no release for "
                                    + target.loader() + " " + target.gameVersion() + " yet; skipped."));
                            continue;
                        }
                        modrinthModService.install(project, minecraftDirectory, line -> Platform.runLater(() -> appendLog(line)));
                        installed++;
                    }
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    modProblem = e.getMessage();
                    Platform.runLater(() -> appendLog("Boost: optimisation mods could not be installed: " + e.getMessage()));
                }
                int changedSettings = changed;
                int installedMods = installed;
                int alreadyMods = already;
                int unavailableMods = unavailable;
                String problem = modProblem;
                Platform.runLater(() -> {
                    working = false;
                    refresh();
                    List<javafx.scene.Node> all = new ArrayList<>();
                    all.add(success(profile.label() + " applied: " + changedSettings + " video settings changed, "
                            + installedMods + " mods installed, " + alreadyMods + " already installed"
                            + (unavailableMods > 0 ? ", " + unavailableMods + " not available for this version yet" : "") + "."));
                    if (problem != null) {
                        String[] message = ModsUi.describeFailure("Installing the optimisation mods", problem);
                        all.add(ModsUi.warningBox(message[0], message[1], null));
                    }
                    all.addAll(notes);
                    showStatus(all.toArray(new javafx.scene.Node[0]));
                    appendLog("Boost: " + profile.label() + " applied.");
                });
            });
        }

        void disable() {
            if (working) return;
            settings.setBoostEnabled(false);
            saveSettings();
            Path minecraftDirectory = settings.minecraftDirectory();
            try {
                int restored = BoostOptions.restore(minecraftDirectory, line -> appendLog(line));
                refresh();
                showStatus(success(restored > 0 ? "Boost Mode is off and your own video settings are back (" + restored + " restored)."
                        : "Boost Mode is off."));
            } catch (IOException e) {
                refresh();
                showStatus(ModsUi.warningBox("Couldn't restore your settings", e.getMessage(), null));
            }
        }
    }

    private void showWardrobe() {
        activePage = Page.WARDROBE;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Player profile", "Wardrobe", "Preview the skin and cape saved with your selected account.");
        HBox preview = new HBox(22);
        preview.setAlignment(Pos.CENTER_LEFT);
        preview.setPadding(new Insets(22));
        preview.setStyle("-fx-background-color: rgba(15, 16, 39, 0.84); -fx-background-radius: 12; -fx-border-color: rgba(170, 151, 207, 0.24); -fx-border-radius: 12;");
        ImageView skin = new ImageView();
        skin.setFitWidth(192);
        skin.setFitHeight(192);
        skin.setPreserveRatio(true);
        ImageView cape = new ImageView();
        cape.setFitWidth(112);
        cape.setFitHeight(192);
        cape.setPreserveRatio(true);
        Label description = label("", 13, MUTED);
        description.setWrapText(true);
        VBox info = new VBox(12, label(account == null ? "No account selected" : account.name(), 20, "#f3f6fc"),
                description);
        preview.getChildren().addAll(skin, cape, info);
        Button reload = button("RELOAD SAVED PREVIEW", "#292642", 10);
        reload.setOnAction(event -> loadPreviews(account, skin, cape, description, () -> account));
        Button accountSettings = button("MANAGE ACCOUNTS", ACCENT, 10);
        accountSettings.setOnAction(event -> showSettings());
        content.getChildren().addAll(preview, reload, accountSettings,
                label("Wardrobe displays the saved skin/cape image. Uploading or changing Ely.by skins must be done through Ely.by.", 11, MUTED),
                SkinStudio.build(featureHost()));
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
        loadPreviews(account, skin, cape, description, () -> account);
    }

    private void showCosmetics() {
        activePage = Page.COSMETICS;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Your look", "Cosmetics",
                "Capes, wings, hats, emotes, animated pieces and trails. What you equip here is what you wear in game. "
                        + "Open the in-game menu with K for a live 3D preview. Cosmetics are visual only.");
        Path configFile = CosmeticsStore.configFile(settings.minecraftDirectory());
        boolean adminUnlock = account != null && settings.isAdmin(account);
        BackendClient client = backend();
        Label status = label("Loading cosmetics...", 12, MUTED);
        status.setWrapText(true);
        java.util.function.BiConsumer<String, Boolean> setStatus = (message, failed) -> {
            status.setText(message);
            status.setTextFill(Color.web(failed ? "#ff9aa5" : MUTED));
        };
        VBox diagnostics = new VBox(4);
        diagnostics.setVisible(false);
        diagnostics.setManaged(false);
        VBox list = new VBox(8);
        HBox tabs = new HBox(6);
        HBox tiers = new HBox(6);
        List<CosmeticsStore.Item> items = new ArrayList<>();
        java.util.Set<String> owned = new java.util.HashSet<>();
        String[] selectedCategory = {"cape"};
        String[] selectedTier = {""};
        Runnable[] render = new Runnable[1];

        render[0] = () -> {
            long now = System.currentTimeMillis() / 1000;
            Map<String, String> equipped = CosmeticsStore.equipped(configFile);
            tabs.getChildren().clear();
            for (String category : CosmeticsStore.CATEGORIES) {
                Button tab = button(categoryLabel(category), category.equals(selectedCategory[0]) ? ACCENT : "#292642", 10);
                tab.setOnAction(event -> {
                    selectedCategory[0] = category;
                    render[0].run();
                });
                tabs.getChildren().add(tab);
            }
            tiers.getChildren().clear();
            for (String tier : List.of("", "free", "premium", "event")) {
                Button chip = button(tier.isEmpty() ? "All" : Character.toUpperCase(tier.charAt(0)) + tier.substring(1),
                        tier.equals(selectedTier[0]) ? ACCENT : "#292642", 10);
                chip.setOnAction(event -> {
                    selectedTier[0] = tier;
                    render[0].run();
                });
                tiers.getChildren().add(chip);
            }
            list.getChildren().clear();
            int shown = 0;
            for (CosmeticsStore.Item item : items) {
                if (!item.category().equals(selectedCategory[0])
                        || (!selectedTier[0].isEmpty() && !item.tier().equals(selectedTier[0]))) continue;
                shown++;
                boolean unlocked = adminUnlock || CosmeticsStore.unlocked(item, owned, now);
                boolean isEquipped = item.id().equals(equipped.get(item.slot()));
                Label name = label(item.name(), 14, unlocked ? "#f3f6fc" : "#8e8eb2");
                String tierColor = switch (item.tier()) {
                    case "free" -> "#7cf29a";
                    case "event" -> "#ff8fd8";
                    default -> ACCENT;
                };
                Label tag = label(item.tier().toUpperCase() + "  ·  " + item.slot().charAt(0)
                        + item.slot().substring(1).toLowerCase(), 10, tierColor);
                Label description = label(item.description(), 11, MUTED);
                description.setWrapText(true);
                VBox text = new VBox(3, name, tag, description);
                HBox.setHgrow(text, Priority.ALWAYS);
                Button action = button(isEquipped ? "UNEQUIP" : unlocked ? "EQUIP"
                        : item.tier().equals("premium") ? "PREMIUM" : "LOCKED",
                        isEquipped ? "#482332" : unlocked ? ACCENT : "#2a2a3a", 10);
                action.setMinWidth(92);
                action.setDisable(!unlocked && !isEquipped);
                action.setOnAction(event -> {
                    try {
                        if (isEquipped) CosmeticsStore.unequip(configFile, item.slot());
                        else CosmeticsStore.equip(configFile, item.slot(), item.id());
                        status.setText((isEquipped ? "Unequipped " : "Equipped ") + item.name()
                                + ". It shows the next time you start the game.");
                        appendLog("Cosmetics: " + (isEquipped ? "unequipped " : "equipped ") + item.id() + ".");
                    } catch (IOException e) {
                        status.setText("Could not save cosmetics: " + e.getMessage());
                    }
                    render[0].run();
                });
                HBox row = new HBox(14, text, action);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(12, 14, 12, 14));
                row.setStyle("-fx-background-color: rgba(15, 16, 39, 0.84); -fx-background-radius: 10; -fx-border-radius: 10;"
                        + " -fx-border-color: " + (isEquipped ? Theme.accentRgba(0.7) : "rgba(170, 151, 207, 0.24)") + ";");
                list.getChildren().add(row);
            }
            if (shown == 0) list.getChildren().add(label("Nothing here yet.", 12, MUTED));
        };

        Button refresh = button("REFRESH", "#292642", 10);
        Button clear = button("UNEQUIP ALL", "#482332", 10);
        clear.setOnAction(event -> {
            try {
                for (String slot : List.of("CAPE", "WINGS", "HAT", "TRAIL", "EMOTE")) {
                    CosmeticsStore.unequip(configFile, slot);
                }
                status.setText("Everything unequipped.");
            } catch (IOException e) {
                status.setText("Could not save cosmetics: " + e.getMessage());
            }
            render[0].run();
        });
        Runnable load = () -> {
            setStatus.accept("Loading cosmetics...", false);
            refresh.setDisable(true);
            String uuid = account == null ? "" : account.uuid();
            boolean online = client.configured() && !settings.offlineMode();
            boolean checkOwned = account != null && !uuid.isBlank();
            appendLog("Cosmetics: loading the catalog (backend " + (client.configured() ? client.baseUrl() : "not set")
                    + ", " + (online ? "online" : "not contacting it") + ", account "
                    + (account == null ? "none" : account.name() + " / " + account.type().label()) + ").");
            // Every outcome ends in a result: success, or a failure with the real reason. A 10 second limit
            // (plus a little for the request itself) guarantees the page can never wait forever.
            CompletableFuture.supplyAsync(() -> {
                try {
                    com.fasterxml.jackson.databind.JsonNode remote = null;
                    java.util.Set<String> ownedNow = new java.util.HashSet<>();
                    if (online) {
                        remote = client.cosmeticsCatalog();
                        if (checkOwned) {
                            try {
                                ownedNow.addAll(client.ownedCosmetics(uuid));
                            } catch (IOException e) {
                                appendLogLater("Cosmetics: owned items could not be loaded (" + e.getMessage()
                                        + "); continuing without them.");
                            }
                        }
                    }
                    return new Object[]{CosmeticsStore.merge(CosmeticsStore.builtIn(), remote), ownedNow};
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, executor).orTimeout(CosmeticsDiagnostics.TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)
                    .whenComplete((result, failure) -> Platform.runLater(() -> {
                refresh.setDisable(false);
                List<CosmeticsStore.Item> loaded;
                if (failure == null) {
                    @SuppressWarnings("unchecked")
                    List<CosmeticsStore.Item> merged = (List<CosmeticsStore.Item>) ((Object[]) result)[0];
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> ownedNow = (java.util.Set<String>) ((Object[]) result)[1];
                    loaded = merged;
                    owned.clear();
                    owned.addAll(ownedNow);
                    setStatus.accept((online ? "Online catalog loaded. " : "Showing the built-in cosmetics"
                            + (client.configured() ? " (offline mode). " : " (set the backend address on the News page for more). "))
                            + loaded.size() + " items.", false);
                } else {
                    Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    String reason = cause instanceof java.util.concurrent.TimeoutException
                            ? "timed out after " + CosmeticsDiagnostics.TIMEOUT_SECONDS + " seconds"
                            : CosmeticsDiagnostics.describe(cause);
                    appendLog("Cosmetics: could not load the online catalog: " + reason
                            + (settings.debugMode() ? "\n" + BackendClient.stackTrace(cause) : ""));
                    loaded = CosmeticsStore.merge(CosmeticsStore.builtIn(), null);
                    setStatus.accept("Could not load the online catalog: " + reason + ". Showing the built-in "
                            + loaded.size() + " cosmetics. Press REFRESH to retry or DIAGNOSE to find the problem.", true);
                }
                items.clear();
                items.addAll(loaded);
                render[0].run();
            }));
        };
        refresh.setOnAction(event -> load.run());

        Button diagnose = button("DIAGNOSE", "#292642", 10);
        diagnose.setOnAction(event -> {
            diagnose.setDisable(true);
            diagnostics.getChildren().setAll(label("Checking... (each check gives up after "
                    + CosmeticsDiagnostics.TIMEOUT_SECONDS + " seconds)", 11, ACCENT));
            diagnostics.setVisible(true);
            diagnostics.setManaged(true);
            CosmeticsDiagnostics.Context context = new CosmeticsDiagnostics.Context(
                    BackendClient.resolveUrl(settings.backendUrl()), account, settings.backendToken(), settings.offlineMode());
            appendLog("Diagnostics: started.");
            executor.execute(() -> {
                CosmeticsDiagnostics.run(context, check -> Platform.runLater(() -> {
                    String color = switch (check.result()) {
                        case OK -> "#7cf29a";
                        case FAIL -> "#ff9aa5";
                        case INFO -> MUTED;
                    };
                    Label line = label((check.result() == CosmeticsDiagnostics.Result.OK ? "OK    "
                            : check.result() == CosmeticsDiagnostics.Result.FAIL ? "FAIL  " : "info  ")
                            + check.name() + " - " + check.detail(), 11, color);
                    line.setWrapText(true);
                    diagnostics.getChildren().add(line);
                    appendLog("Diagnostics: " + check.result() + " " + check.name() + " - " + check.detail());
                }));
                Platform.runLater(() -> {
                    diagnose.setDisable(false);
                    appendLog("Diagnostics: finished.");
                });
            });
        });
        javafx.scene.control.CheckBox debugBox = new javafx.scene.control.CheckBox(
                "Debug mode (log every request, URL, status and stack traces)");
        debugBox.setSelected(settings.debugMode());
        debugBox.setTextFill(Color.web(MUTED));
        debugBox.setOnAction(event -> {
            settings.setDebugMode(debugBox.isSelected());
            BackendClient.setRequestLog(line -> Platform.runLater(() -> appendLog(line)), settings.debugMode());
            saveSettings();
            appendLog("Debug mode " + (settings.debugMode() ? "on" : "off") + ".");
        });
        VBox controls = card();
        controls.getChildren().addAll(tabs, tiers, new HBox(10, refresh, clear, diagnose), status, debugBox, diagnostics);
        content.getChildren().addAll(controls, list,
                label((adminUnlock ? "Admin account: every cosmetic is unlocked for you. " : "")
                        + "The cosmetics file is shared with the game: " + configFile, 10, MUTED));
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
        render[0].run();
        load.run();
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "cape" -> "Capes";
            case "wings" -> "Wings";
            case "hat" -> "Hats";
            case "emote" -> "Emotes";
            case "animated" -> "Animated";
            default -> "Trails";
        };
    }

    private void showFriends() {
        activePage = Page.FRIENDS;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Community", "Friends",
                "Add friends by their V-Client username, see who is online and what they are playing. Needs a free V-Client account.");
        FriendsPage page = new FriendsPage();
        content.getChildren().addAll(page.notice, page.accountSection(), page.localCard());
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
        page.start();
    }

    /** What the launcher tells friends it is doing; sent as a heartbeat every 45 seconds while signed in. */
    private volatile String presenceActivity = "In the launcher";
    private volatile String presenceServer = "";
    private java.util.concurrent.ScheduledExecutorService presenceScheduler;

    private void startPresence() {
        if (presenceScheduler != null) return;
        presenceScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "vclient-presence");
            thread.setDaemon(true);
            return thread;
        });
        presenceScheduler.scheduleWithFixedDelay(this::sendPresenceNow, 3, 45, TimeUnit.SECONDS);
    }

    private void sendPresenceNow() {
        String token = settings.backendToken();
        BackendClient client = backend();
        if (token.isBlank() || !client.configured() || settings.offlineMode()) return;
        try {
            client.sendPresence(token, presenceActivity, presenceServer);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // A missed heartbeat only means friends see you as offline for a moment; the next one retries.
        }
    }

    private void setPresence(String activity, String server) {
        presenceActivity = activity;
        presenceServer = server;
        executor.execute(this::sendPresenceNow);
    }

    /** The Friends page: V-Client account friends with presence, plus the old on-device list of names. */
    private final class FriendsPage {
        private final VBox notice = new VBox(10);
        private final VBox requestsCard = ModsUi.glassCard(12, new Insets(20));
        private final VBox friendsCard = ModsUi.glassCard(12, new Insets(20));
        private final VBox requestsBody = new VBox(8);
        private final VBox friendsBody = new VBox(8);
        private final Label friendsCount = ModsUi.chip("", ModsUi.MUTED);
        private final TextField username = new TextField();
        private final Button add = new Button("ADD FRIEND");
        private javafx.animation.Timeline refreshTimer;

        FriendsPage() {
            notice.setManaged(false);
            notice.setVisible(false);
            requestsCard.setManaged(false);
            requestsCard.setVisible(false);
        }

        private boolean signedIn() {
            return !settings.backendToken().isBlank() && backend().configured();
        }

        private void showNotice(javafx.scene.Node... nodes) {
            notice.getChildren().setAll(nodes);
            notice.setManaged(nodes.length > 0);
            notice.setVisible(nodes.length > 0);
        }

        VBox accountSection() {
            VBox section = new VBox(16);
            if (!signedIn()) {
                VBox card = ModsUi.glassCard(14, new Insets(22));
                boolean noBackend = !backend().configured();
                Label title = ModsUi.text(noBackend ? "Connect to the V-Client backend first" : "Sign in to add friends", 16, ModsUi.TEXT, true);
                Label body = ModsUi.text(noBackend
                        ? "Friends are stored on the V-Client backend. Set its address on the News page (Backend & updates), then create your account there."
                        : "Create a free V-Client account or sign in on the News page. Then you can add friends by username, see who is online and what they are playing.",
                        12, ModsUi.MUTED, false);
                body.setWrapText(true);
                Button open = new Button("OPEN THE NEWS PAGE");
                open.setMinHeight(40);
                open.setStyle("-fx-background-color: " + ACCENT + "; -fx-text-fill: #171326; -fx-font-size: 12px; -fx-font-weight: bold;"
                        + " -fx-background-radius: 12; -fx-padding: 0 22 0 22; -fx-cursor: hand;");
                open.setOnAction(event -> showNews());
                card.getChildren().addAll(title, body, open);
                section.getChildren().add(card);
                return section;
            }
            VBox addCard = ModsUi.glassCard(12, new Insets(20));
            username.setPromptText("Your friend's V-Client username");
            username.setStyle("-fx-background-color: rgba(8, 9, 26, 0.85); -fx-text-fill: #f5f7fd; -fx-prompt-text-fill: #7f7fa6;"
                    + " -fx-font-size: 13px; -fx-border-color: rgba(255,255,255,0.14); -fx-background-radius: 12;"
                    + " -fx-border-radius: 12; -fx-padding: 0 14 0 14;");
            username.setMinHeight(44);
            username.setPrefHeight(44);
            HBox.setHgrow(username, Priority.ALWAYS);
            add.setMinHeight(44);
            add.setMinWidth(Region.USE_PREF_SIZE);
            add.setStyle("-fx-background-color: " + ACCENT + "; -fx-text-fill: #171326; -fx-font-size: 12px; -fx-font-weight: bold;"
                    + " -fx-background-radius: 12; -fx-padding: 0 24 0 24; -fx-cursor: hand;");
            add.setOnAction(event -> sendRequest());
            username.setOnAction(event -> sendRequest());
            HBox row = new HBox(12, username, add);
            row.setAlignment(Pos.CENTER_LEFT);
            addCard.getChildren().addAll(ModsUi.sectionTitle("Add a friend"), row);

            requestsCard.getChildren().addAll(ModsUi.sectionTitle("Friend requests"), requestsBody);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox heading = new HBox(10, ModsUi.sectionTitle("Friends"), friendsCount);
            heading.setAlignment(Pos.CENTER_LEFT);
            friendsCard.getChildren().addAll(heading, friendsBody);
            friendsBody.getChildren().add(ModsUi.busyLabel("Loading your friends…"));
            section.getChildren().addAll(addCard, requestsCard, friendsCard);
            return section;
        }

        void start() {
            if (!signedIn()) return;
            reload();
            refreshTimer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(20), event -> {
                if (activePage != Page.FRIENDS) {
                    refreshTimer.stop();
                    return;
                }
                reload();
            }));
            refreshTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
            refreshTimer.play();
        }

        private void reload() {
            String token = settings.backendToken();
            BackendClient client = backend();
            executor.execute(() -> {
                try {
                    BackendClient.FriendsOverview overview = client.friends(token);
                    Platform.runLater(() -> {
                        if (activePage == Page.FRIENDS) render(overview);
                    });
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    boolean expired = e instanceof BackendClient.BackendException && e.getMessage() != null
                            && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("sign in");
                    Platform.runLater(() -> {
                        if (activePage != Page.FRIENDS) return;
                        if (expired) {
                            settings.setBackendToken("");
                            saveSettings();
                            showFriends();
                            return;
                        }
                        String[] message = ModsUi.describeFailure("Loading your friends", e.getMessage());
                        showNotice(ModsUi.warningBox(message[0], message[1], this::reload));
                        friendsBody.getChildren().setAll(ModsUi.emptyCard("⚠", "Friends are unavailable right now",
                                "Check the message above, then try again."));
                    });
                }
            });
        }

        private void render(BackendClient.FriendsOverview overview) {
            showNotice();
            long online = overview.friends().stream().filter(BackendClient.Friend::online).count();
            friendsCount.setText(overview.friends().size() + " friends  ·  " + online + " online");
            if (overview.friends().isEmpty()) {
                friendsBody.getChildren().setAll(ModsUi.emptyCard("♢", "No friends yet",
                        "Type a friend's V-Client username above and send them a request."));
            } else {
                List<javafx.scene.Node> rows = new ArrayList<>();
                for (BackendClient.Friend friend : overview.friends()) rows.add(friendRow(friend));
                friendsBody.getChildren().setAll(rows);
            }
            boolean hasRequests = !overview.incoming().isEmpty() || !overview.outgoing().isEmpty();
            requestsCard.setManaged(hasRequests);
            requestsCard.setVisible(hasRequests);
            List<javafx.scene.Node> requests = new ArrayList<>();
            for (BackendClient.FriendRequest request : overview.incoming()) {
                Button accept = smallButton("ACCEPT", ACCENT, "#171326");
                accept.setOnAction(event -> respond(() -> backend().acceptFriend(settings.backendToken(), request.requestId())));
                Button decline = smallButton("DECLINE", "rgba(255,255,255,0.08)", "#f5f7fd");
                decline.setOnAction(event -> respond(() -> backend().dropFriendRequest(settings.backendToken(), request.requestId())));
                requests.add(personRow(ModsUi.text(request.username(), 13, ModsUi.TEXT, true),
                        ModsUi.text("wants to be your friend", 11, ModsUi.MUTED, false), accept, decline));
            }
            for (BackendClient.FriendRequest request : overview.outgoing()) {
                Button cancel = smallButton("CANCEL", "rgba(255,255,255,0.08)", "#f5f7fd");
                cancel.setOnAction(event -> respond(() -> backend().dropFriendRequest(settings.backendToken(), request.requestId())));
                requests.add(personRow(ModsUi.text(request.username(), 13, ModsUi.TEXT, true),
                        ModsUi.text("request sent, waiting for them", 11, ModsUi.MUTED, false), cancel));
            }
            requestsBody.getChildren().setAll(requests);
        }

        private Button smallButton(String label, String background, String textColor) {
            Button button = new Button(label);
            button.setMinHeight(32);
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setStyle("-fx-background-color: " + background + "; -fx-text-fill: " + textColor + "; -fx-font-size: 10px;"
                    + " -fx-font-weight: bold; -fx-background-radius: 9; -fx-padding: 0 14 0 14; -fx-cursor: hand;");
            return button;
        }

        private HBox personRow(javafx.scene.Node name, javafx.scene.Node detail, Button... actions) {
            VBox text = new VBox(2, name, detail);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(10, text);
            row.getChildren().addAll(actions);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 14, 10, 14));
            String base = "-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 12;";
            row.setStyle(base);
            row.setOnMouseEntered(event -> row.setStyle(base + " -fx-background-color: rgba(255,255,255,0.09);"));
            row.setOnMouseExited(event -> row.setStyle(base));
            return row;
        }

        private HBox friendRow(BackendClient.Friend friend) {
            Region dot = dot(friend.online() ? ModsUi.GOOD : "#5b5b78");
            Label name = ModsUi.text(friend.username(), 14, ModsUi.TEXT, true);
            String detail = friend.online()
                    ? (friend.activity().isBlank() ? "Online" : friend.activity()) + (friend.server().isBlank() ? "" : "  ·  " + friend.server())
                    : "Offline";
            Label state = ModsUi.text(detail, 11, friend.online() ? "#8ff0b8" : "#7f7fa6", false);
            HBox title = new HBox(8, dot, name);
            if (!friend.minecraftName().isBlank()) title.getChildren().add(ModsUi.chip("MC: " + friend.minecraftName(), ModsUi.MUTED));
            title.setAlignment(Pos.CENTER_LEFT);
            Button remove = smallButton("REMOVE", "rgba(255,99,120,0.14)", "#ff9aa8");
            remove.setOnAction(event -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Remove " + friend.username() + " from your friends?",
                        ButtonType.OK, ButtonType.CANCEL);
                confirm.initOwner(stage);
                confirm.setTitle("V-Client");
                confirm.setHeaderText("Remove friend");
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                    respond(() -> backend().removeFriend(settings.backendToken(), friend.userId()));
                }
            });
            return personRow(title, state, remove);
        }

        @FunctionalInterface
        private interface Call {
            void run() throws IOException, InterruptedException;
        }

        private void respond(Call call) {
            executor.execute(() -> {
                try {
                    call.run();
                    Platform.runLater(this::reload);
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    Platform.runLater(() -> showNotice(ModsUi.warningBox("That didn't work", e.getMessage(), null)));
                }
            });
        }

        private void sendRequest() {
            String name = username.getText() == null ? "" : username.getText().strip();
            if (name.isEmpty()) return;
            add.setDisable(true);
            executor.execute(() -> {
                try {
                    String result = backend().requestFriend(settings.backendToken(), name);
                    Platform.runLater(() -> {
                        username.clear();
                        showNotice(friendNotice("accepted".equals(result)
                                ? "You and " + name + " are now friends."
                                : "Friend request sent to " + name + "."));
                        reload();
                    });
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    Platform.runLater(() -> showNotice(ModsUi.warningBox("Couldn't add " + name, e.getMessage(), null)));
                } finally {
                    Platform.runLater(() -> add.setDisable(false));
                }
            });
        }

        private HBox friendNotice(String message) {
            Label icon = ModsUi.text("✓", 16, "#08140e", true);
            icon.setMinSize(30, 30);
            icon.setAlignment(Pos.CENTER);
            icon.setStyle(icon.getStyle() + " -fx-background-color: " + ModsUi.GOOD + "; -fx-background-radius: 15;");
            HBox box = new HBox(12, icon, ModsUi.text(message, 13, "#bff5d6", true));
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new Insets(12, 16, 12, 14));
            box.setStyle("-fx-background-color: rgba(57,217,138,0.10); -fx-background-radius: 14;"
                    + " -fx-border-color: rgba(57,217,138,0.40); -fx-border-radius: 14;");
            return box;
        }

        /** The original list of Minecraft names saved only on this PC (no account needed). */
        VBox localCard() {
            VBox card = ModsUi.glassCard(12, new Insets(20));
            TextField name = new TextField();
            name.setPromptText("Minecraft username");
            name.setStyle("-fx-background-color: rgba(8, 9, 26, 0.85); -fx-text-fill: #f5f7fd; -fx-prompt-text-fill: #7f7fa6;"
                    + " -fx-font-size: 13px; -fx-border-color: rgba(255,255,255,0.14); -fx-background-radius: 12;"
                    + " -fx-border-radius: 12; -fx-padding: 0 14 0 14;");
            name.setMinHeight(40);
            HBox.setHgrow(name, Priority.ALWAYS);
            Button save = smallButton("SAVE NAME", "rgba(255,255,255,0.08)", "#f5f7fd");
            save.setMinHeight(40);
            VBox list = new VBox(6);
            Runnable render = () -> {
                List<javafx.scene.Node> rows = new ArrayList<>();
                for (String saved : localFriends) {
                    Button remove = smallButton("REMOVE", "rgba(255,99,120,0.14)", "#ff9aa8");
                    remove.setOnAction(event -> {
                        try {
                            localFriends.clear();
                            localFriends.addAll(friendsStore.remove(saved));
                            showFriends();
                        } catch (IOException e) {
                            showNotice(ModsUi.warningBox("Couldn't remove the name", e.getMessage(), null));
                        }
                    });
                    rows.add(personRow(ModsUi.text(saved, 13, ModsUi.TEXT, true), ModsUi.text("saved on this PC only", 11, ModsUi.MUTED, false), remove));
                }
                if (rows.isEmpty()) rows.add(ModsUi.text("No names saved yet.", 12, ModsUi.MUTED, false));
                list.getChildren().setAll(rows);
            };
            save.setOnAction(event -> {
                try {
                    localFriends.clear();
                    localFriends.addAll(friendsStore.add(name.getText()));
                    name.clear();
                    render.run();
                } catch (IOException e) {
                    showNotice(ModsUi.warningBox("Couldn't save the name", e.getMessage(), null));
                }
            });
            render.run();
            HBox row = new HBox(12, name, save);
            row.setAlignment(Pos.CENTER_LEFT);
            Label hint = ModsUi.text("Just a private notepad of Minecraft usernames on this device. No account and no server needed.", 11, ModsUi.MUTED, false);
            hint.setWrapText(true);
            card.getChildren().addAll(ModsUi.sectionTitle("Saved names (this PC only)"), row, list, hint);
            return card;
        }
    }

    private void showConsole() {
        activePage = Page.CONSOLE;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Diagnostics", "Console", "Recent launcher and Minecraft output for this session.");
        HBox actions = new HBox(10);
        Button copy = button("COPY LOG", "#292642", 10);
        Button clear = button("CLEAR LOG", "#482332", 10);
        actions.getChildren().addAll(copy, clear);
        consoleLog = new TextArea(String.join(System.lineSeparator(), logLines));
        consoleLog.setEditable(false);
        consoleLog.setWrapText(true);
        consoleLog.setPrefRowCount(22);
        consoleLog.setStyle("-fx-control-inner-background: rgba(9, 10, 28, 0.82); -fx-text-fill: #c3bfdc; -fx-font-family: 'Consolas'; -fx-font-size: 11px; -fx-background-color: rgba(9, 10, 28, 0.82); -fx-border-color: #37334f; -fx-border-radius: 8; -fx-background-radius: 8;");
        VBox.setVgrow(consoleLog, Priority.ALWAYS);
        copy.setOnAction(event -> {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString(consoleLog.getText());
            Clipboard.getSystemClipboard().setContent(clipboard);
        });
        clear.setOnAction(event -> {
            logLines.clear();
            consoleLog.clear();
            if (log != null) log.clear();
        });
        content.getChildren().addAll(actions, consoleLog);
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
    }

    // ---- VSMP backend: news, announcements, server status, accounts, updates, error logs ----

    private BackendClient backend() {
        return new BackendClient(BackendClient.resolveUrl(settings.backendUrl()));
    }

    private static String announcementColor(String level) {
        return switch (level) {
            case "critical" -> "#5a1c26";
            case "warning" -> "#4a3a12";
            default -> "#17315a";
        };
    }

    /** A box that fills itself with active announcements; stays empty when the backend is unavailable. */
    /** Tells the backend which account is playing so admins can find the player later; never blocks or fails the launch. */
    private void recordPlayerAsync(PlayerAccount player) {
        BackendClient client = backend();
        if (player == null || !client.configured() || settings.offlineMode()) return;
        String type = switch (player.type()) {
            case MICROSOFT -> "microsoft";
            case ELY_BY -> "ely_by";
            case OFFLINE -> "offline";
        };
        executor.execute(() -> client.recordPlayer(player.uuid(), player.name(), type));
    }

    private static String timeAgo(String iso) {
        try {
            long seconds = Math.max(0, java.time.Duration.between(java.time.Instant.parse(iso), java.time.Instant.now()).getSeconds());
            if (seconds < 90) return "just now";
            if (seconds < 3600) return seconds / 60 + " min ago";
            if (seconds < 86400) return seconds / 3600 + " h ago";
            return seconds / 86400 + " d ago";
        } catch (java.time.format.DateTimeParseException e) {
            return "";
        }
    }

    /** The admin panel on the News page: only shown (and only works) when the signed-in V-Client account is the admin. */
    private VBox adminCard() {
        VBox box = card();
        box.setManaged(false);
        box.setVisible(false);
        BackendClient client = backend();
        String token = settings.backendToken();
        // Only the admin game account (Izyrn on Ely.by) gets the panel, whatever V-Client account is signed in.
        if (account == null || !settings.isAdmin(account) || token.isBlank() || !client.configured()) return box;

        Label summary = label("Loading players…", 12, "#e6ebf7");
        TextField search = new TextField();
        search.setPromptText("Search players by name");
        search.setStyle(fieldStyle());
        HBox.setHgrow(search, Priority.ALWAYS);
        Button refresh = button("REFRESH", "#292642", 10);
        HBox searchRow = new HBox(10, search, refresh);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        VBox rows = new VBox(8);
        TextField byName = new TextField();
        byName.setPromptText("Player name (even if they have not played yet)");
        byName.setStyle(fieldStyle());
        HBox.setHgrow(byName, Priority.ALWAYS);
        Button grantByName = button("UNLOCK FOR A NAME", ACCENT, 10);
        HBox nameRow = new HBox(10, byName, grantByName);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(label("ADMIN PANEL", 11, ACCENT), summary, searchRow, rows,
                label("Not in the list yet?", 11, MUTED), nameRow);

        Runnable load = () -> {
            summary.setText("Loading players…");
            executor.execute(() -> {
                try {
                    BackendClient.AdminOverview overview = client.adminPlayers(token, search.getText().trim());
                    Platform.runLater(() -> {
                        summary.setText(overview.total() + " players have played  ·  " + overview.activeToday()
                                + " in the last 24 hours  ·  " + overview.accounts() + " V-Client accounts");
                        rows.getChildren().clear();
                        if (overview.players().isEmpty()) {
                            rows.getChildren().add(label("No players found yet. They appear here after they start the game from V-Client 1.3.0 or newer.", 12, MUTED));
                        }
                        for (BackendClient.AdminPlayer player : overview.players()) {
                            Label name = label(player.name(), 13, "#f3f6fc");
                            name.setStyle(name.getStyle() + "-fx-font-weight: bold;");
                            Label kind = ModsUi.chip(switch (player.accountType()) {
                                case "ely_by" -> "ELY.BY";
                                case "microsoft" -> "PREMIUM";
                                default -> "OFFLINE";
                            }, ModsUi.MUTED);
                            Label seen = label(timeAgo(player.lastSeen()) + "  ·  " + player.launches() + " launches"
                                    + (player.grants() > 0 ? "  ·  " + player.grants() + " unlocked" : ""), 11, MUTED);
                            Region spacer = new Region();
                            HBox.setHgrow(spacer, Priority.ALWAYS);
                            Button manage = button("UNLOCK", "#292642", 10);
                            manage.setOnAction(event -> openGrantDialog(client, token, player.name(), player.uuid(), refresh::fire));
                            HBox row = new HBox(10, name, kind, seen, spacer, manage);
                            row.setAlignment(Pos.CENTER_LEFT);
                            rows.getChildren().add(row);
                        }
                    });
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    Platform.runLater(() -> summary.setText("Could not load players: " + e.getMessage()));
                }
            });
        };
        refresh.setOnAction(event -> load.run());
        search.setOnAction(event -> load.run());
        grantByName.setOnAction(event -> {
            String typed = byName.getText().trim();
            if (typed.isEmpty()) return;
            openGrantDialog(client, token, typed, typed, load);
        });
        executor.execute(() -> {
            try {
                if (client.isAdmin(token)) {
                    Platform.runLater(() -> {
                        box.setManaged(true);
                        box.setVisible(true);
                        load.run();
                    });
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        });
        return box;
    }

    /** Dialog that unlocks or removes cosmetics, or gives an invite reward, for one player (a name or a UUID). */
    private void openGrantDialog(BackendClient client, String token, String displayName, String player, Runnable onChanged) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (CosmeticsStore.Item item : CosmeticsStore.builtIn()) names.put(item.id(), item.name());
        java.util.function.Function<String, String> nameOf = id -> names.getOrDefault(id, id);

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Unlock cosmetics");
        dialog.setHeaderText(displayName);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(560);

        Label owned = label("Loading what they have…", 12, "#e6ebf7");
        owned.setWrapText(true);
        Label result = label("", 12, MUTED);
        result.setWrapText(true);

        ComboBox<CosmeticsStore.Item> cosmetic = new ComboBox<>();
        cosmetic.setMaxWidth(Double.MAX_VALUE);
        cosmetic.getItems().addAll(CosmeticsStore.builtIn().stream().filter(item -> !"free".equals(item.tier())).toList());
        cosmetic.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(CosmeticsStore.Item item) {
                return item == null ? "" : item.name() + "  —  " + item.category() + " (" + item.tier() + ")";
            }
            @Override public CosmeticsStore.Item fromString(String text) { return null; }
        });
        cosmetic.setPromptText("Choose a cosmetic");
        Button unlock = button("UNLOCK", ACCENT, 10);
        Button revoke = button("TAKE AWAY", "#482332", 10);
        HBox cosmeticRow = new HBox(10, cosmetic, unlock, revoke);
        HBox.setHgrow(cosmetic, Priority.ALWAYS);
        cosmeticRow.setAlignment(Pos.CENTER_LEFT);

        ComboBox<BackendClient.AdminReward> reward = new ComboBox<>();
        reward.setMaxWidth(Double.MAX_VALUE);
        reward.setPromptText("Invite reward");
        reward.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(BackendClient.AdminReward value) {
                return value == null ? "" : value.invites() + " invites  —  "
                        + value.cosmetics().stream().map(nameOf).collect(java.util.stream.Collectors.joining(", "));
            }
            @Override public BackendClient.AdminReward fromString(String text) { return null; }
        });
        Button giveReward = button("GIVE REWARD", ACCENT, 10);
        HBox rewardRow = new HBox(10, reward, giveReward);
        HBox.setHgrow(reward, Priority.ALWAYS);
        rewardRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, label("UNLOCKED NOW", 11, MUTED), owned, label("UNLOCK ONE COSMETIC", 11, MUTED), cosmeticRow,
                label("INVITE REWARD (includes every tier below it)", 11, MUTED), rewardRow, result);
        content.setPadding(new Insets(14));
        content.setStyle("-fx-background-color: #101321;");
        dialog.getDialogPane().setStyle("-fx-background-color: #101321;");
        dialog.getDialogPane().setContent(content);

        java.util.concurrent.atomic.AtomicReference<List<String>> uuids = new java.util.concurrent.atomic.AtomicReference<>(List.of());
        Runnable showOwned = () -> executor.execute(() -> {
            try {
                List<String> lines = new ArrayList<>();
                for (String uuid : uuids.get()) {
                    List<String> ids = client.adminGrants(token, uuid);
                    lines.add(ids.isEmpty() ? "Nothing granted yet." : ids.stream().map(nameOf).collect(java.util.stream.Collectors.joining(", ")));
                }
                Platform.runLater(() -> owned.setText(String.join("   |   ", lines)));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                Platform.runLater(() -> owned.setText("Could not load: " + e.getMessage()));
            }
        });
        java.util.function.Consumer<String> fail = message -> Platform.runLater(() -> {
            result.setStyle("-fx-text-fill: #ff9aa5; -fx-font-size: 12px;");
            result.setText(message);
        });
        java.util.function.Consumer<String> ok = message -> Platform.runLater(() -> {
            result.setStyle("-fx-text-fill: #6fd39b; -fx-font-size: 12px;");
            result.setText(message);
            onChanged.run();
        });

        executor.execute(() -> {
            try {
                // A name can be an offline account and an Ely.by account (different UUIDs); the backend returns both.
                uuids.set(client.adminGrant(token, player, null, 0).uuids());
                List<BackendClient.AdminReward> rewards = client.adminRewards(token);
                Platform.runLater(() -> reward.getItems().setAll(rewards));
                showOwned.run();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                fail.accept(e.getMessage());
            }
        });

        unlock.setOnAction(event -> {
            CosmeticsStore.Item item = cosmetic.getValue();
            if (item == null) return;
            executor.execute(() -> {
                try {
                    client.adminGrant(token, player, item.id(), null);
                    ok.accept("Unlocked " + item.name() + " for " + displayName + ". They see it after restarting the game or pressing Refresh in the Cosmetics menu.");
                    showOwned.run();
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    fail.accept(e.getMessage());
                }
            });
        });
        revoke.setOnAction(event -> {
            CosmeticsStore.Item item = cosmetic.getValue();
            if (item == null) return;
            executor.execute(() -> {
                try {
                    for (String uuid : uuids.get()) client.adminRevoke(token, uuid, item.id());
                    ok.accept("Took " + item.name() + " away from " + displayName + ".");
                    showOwned.run();
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    fail.accept(e.getMessage());
                }
            });
        });
        giveReward.setOnAction(event -> {
            BackendClient.AdminReward tier = reward.getValue();
            if (tier == null) return;
            executor.execute(() -> {
                try {
                    BackendClient.GrantResult granted = client.adminGrant(token, player, null, tier.invites());
                    ok.accept("Gave the " + tier.invites() + "-invite reward to " + displayName + ": "
                            + granted.granted().stream().map(nameOf).collect(java.util.stream.Collectors.joining(", ")) + ".");
                    showOwned.run();
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    fail.accept(e.getMessage());
                }
            });
        });
        dialog.show();
    }

    private static final java.time.LocalDate LAUNCH_DATE = java.time.LocalDate.of(2026, 11, 10);

    /** The built-in launch-event notice: always shown, needs no backend. */
    private VBox launchEventBanner() {
        long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), LAUNCH_DATE);
        String countdown = days > 1 ? days + " DAYS TO GO" : days == 1 ? "TOMORROW" : days == 0 ? "TODAY" : "LIVE NOW";
        Label tag = label("NEWS  ·  EVENT  ·  " + countdown, 10, "#ff8fd8");
        tag.setStyle(tag.getStyle() + "-fx-font-weight: bold; -fx-letter-spacing: 2;");
        Label headline = label("EVENT! We are launching V-Client on the 10th of November 2026", 16, "#f3f6fc");
        headline.setStyle(headline.getStyle() + "-fx-font-weight: bold;");
        headline.setWrapText(true);
        Label detail = label("Exclusive launch-event capes, wings, hats, trails and emotes unlock that day in the Cosmetics menu.", 12, "#c9d3e6");
        detail.setWrapText(true);
        VBox box = new VBox(6, tag, headline, detail);
        box.setPadding(new Insets(16, 20, 16, 20));
        box.setStyle("-fx-background-color: linear-gradient(to right, rgba(120, 40, 110, 0.55), rgba(40, 30, 90, 0.7)); -fx-background-radius: 12; -fx-border-color: rgba(255, 143, 216, 0.55); -fx-border-radius: 12;");
        return box;
    }

    private VBox announcementBox() {
        VBox box = new VBox(8);
        BackendClient client = backend();
        if (!client.configured()) return box;
        executor.execute(() -> {
            try {
                List<BackendClient.Announcement> items = client.announcements();
                Platform.runLater(() -> {
                    for (BackendClient.Announcement item : items) {
                        Label message = label(item.message(), 12, "#f3f6fc");
                        message.setWrapText(true);
                        HBox row = new HBox(message);
                        row.setPadding(new Insets(10, 14, 10, 14));
                        row.setStyle("-fx-background-color: " + announcementColor(item.level()) + "; -fx-background-radius: 9;");
                        box.getChildren().add(row);
                    }
                });
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        });
        return box;
    }

    /** Fills {@code target} with the VSMP server state; safe to call from the FX thread. */
    private void loadServerStatus(Label target, Region indicator) {
        BackendClient client = backend();
        if (!client.configured()) {
            target.setText("VSMP status unavailable — set the backend address on the News page.");
            return;
        }
        target.setText("Checking VSMP…");
        executor.execute(() -> {
            try {
                BackendClient.ServerStatus state = client.serverStatus();
                Platform.runLater(() -> {
                    indicator.setStyle("-fx-background-color: " + (state.online() ? "#39d98a" : "#e5626f") + "; -fx-background-radius: 50%;");
                    target.setText(state.online()
                            ? "VSMP ONLINE  ·  " + state.players() + " / " + state.maxPlayers() + " players  ·  " + state.version()
                            : "VSMP is offline right now");
                });
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                Platform.runLater(() -> target.setText("VSMP status unavailable (" + e.getMessage() + ")"));
            }
        });
    }

    private void showServers() {
        activePage = Page.SERVERS;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Multiplayer", "Servers", "Live status of our servers, pinged straight from your PC. Press JOIN to launch the selected version and connect.");
        List<Runnable> refreshers = new ArrayList<>();
        Button refreshAll = button("REFRESH ALL", "#292642", 10);
        refreshAll.setOnAction(event -> refreshers.forEach(Runnable::run));
        content.getChildren().add(refreshAll);
        for (ServerEntry server : SERVER_LIST) {
            VBox box = card();
            Region indicator = dot("#64728a");
            Label name = label(server.name(), 17, "#f3f6fc");
            String address = server.port() == 25565 ? server.host() : server.host() + ":" + server.port();
            Label addressLabel = label(address, 11, MUTED);
            Label state = label("Pinging…", 12, "#b3c0d3");
            Label motd = label("", 11, "#c3bfdc");
            motd.setWrapText(true);
            motd.managedProperty().bind(motd.visibleProperty());
            motd.setVisible(false);
            Button join = button("JOIN", ACCENT, 12);
            join.setOnAction(event -> launchGame(server));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox header = new HBox(10, indicator, new VBox(2, name, addressLabel), spacer, join);
            header.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().addAll(header, state, motd);
            Runnable refresh = () -> {
                state.setText("Pinging…");
                indicator.setStyle("-fx-background-color: #64728a; -fx-background-radius: 50%;");
                executor.execute(() -> {
                    ServerPinger.Result result = ServerPinger.ping(server.host(), server.port());
                    Platform.runLater(() -> {
                        indicator.setStyle("-fx-background-color: " + (result.online() ? "#39d98a" : "#e5626f") + "; -fx-background-radius: 50%;");
                        state.setText(result.online()
                                ? "ONLINE  ·  " + result.players() + " / " + result.maxPlayers() + " players"
                                + (result.latencyMs() >= 0 ? "  ·  " + result.latencyMs() + " ms" : "")
                                + (result.version().isBlank() ? "" : "  ·  " + result.version())
                                : "OFFLINE  ·  " + result.error());
                        motd.setText(result.motd());
                        motd.setVisible(!result.motd().isBlank());
                    });
                });
            };
            refreshers.add(refresh);
            refresh.run();
            content.getChildren().add(box);
        }
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
    }

    private void showNews() {
        activePage = Page.NEWS;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Community", "News", "Launcher updates and VSMP server announcements, shared with the Windows and Android apps.");
        BackendClient client = backend();

        VBox statusCard = card();
        Region indicator = dot("#64728a");
        Label statusText = label("", 13, "#e6ebf7");
        Button refreshStatus = button("REFRESH", "#292642", 10);
        refreshStatus.setOnAction(event -> loadServerStatus(statusText, indicator));
        Button website = button("OPEN WEBSITE", "#292642", 10);
        website.setDisable(!client.configured());
        website.setOnAction(event -> openExternalPage(client.baseUrl()));
        HBox statusRow = new HBox(10, indicator, statusText);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusCard.getChildren().addAll(label("SERVER STATUS", 11, MUTED), statusRow, new HBox(10, refreshStatus, website));
        loadServerStatus(statusText, indicator);

        VBox posts = new VBox(12);
        if (client.configured()) {
            posts.getChildren().add(label("Loading news…", 12, MUTED));
            executor.execute(() -> {
                try {
                    List<BackendClient.NewsPost> items = client.news(20);
                    Platform.runLater(() -> {
                        posts.getChildren().clear();
                        if (items.isEmpty()) posts.getChildren().add(label("No news yet. Check back soon.", 12, MUTED));
                        for (BackendClient.NewsPost post : items) posts.getChildren().add(newsCard(post));
                    });
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    Platform.runLater(() -> {
                        posts.getChildren().setAll(label("News could not be loaded: " + e.getMessage(), 12, "#ff9aa5"));
                    });
                }
            });
        } else {
            posts.getChildren().add(label("Set the V-Client backend address below to load news.", 12, MUTED));
        }

        content.getChildren().addAll(launchEventBanner(), announcementBox(), statusCard, posts, accountCard(root), adminCard(), backendCard());
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
    }

    private VBox newsCard(BackendClient.NewsPost post) {
        VBox box = card();
        String tag = (post.pinned() ? "PINNED  ·  " : "") + post.category().toUpperCase()
                + "  ·  " + (post.createdAt().length() >= 10 ? post.createdAt().substring(0, 10) : post.createdAt());
        Label meta = label(tag, 10, post.category().equals("server") ? "#6fd39b" : ACCENT);
        Label title = label(post.title(), 17, "#f3f6fc");
        title.setWrapText(true);
        Label body = label(post.body(), 12, "#c3bfdc");
        body.setWrapText(true);
        box.getChildren().addAll(meta, title, body);
        return box;
    }

    private VBox accountCard(StackPane root) {
        VBox box = card();
        box.getChildren().add(label("V-CLIENT ACCOUNT", 11, MUTED));
        BackendClient client = backend();
        Label result = label("", 11, MUTED);
        result.setWrapText(true);
        if (!settings.backendToken().isBlank() && client.configured()) {
            Label who = label("Checking your account…", 13, "#e6ebf7");
            TextField minecraft = new TextField();
            minecraft.setPromptText("Minecraft username");
            minecraft.setStyle(fieldStyle());
            TextField ely = new TextField();
            ely.setPromptText("Ely.by username");
            ely.setStyle(fieldStyle());
            Button save = button("SAVE LINKED NAMES", ACCENT, 10);
            Button signOut = button("SIGN OUT", "#482332", 10);
            save.setDisable(true);
            box.getChildren().addAll(who, minecraft, ely, new HBox(10, save, signOut), result);
            executor.execute(() -> {
                try {
                    BackendClient.Session session = client.profile(settings.backendToken());
                    Platform.runLater(() -> {
                        who.setText("Signed in as " + session.username());
                        minecraft.setText(session.minecraftName());
                        ely.setText(session.elybyName());
                        save.setDisable(false);
                    });
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    boolean expired = e instanceof BackendClient.BackendException;
                    Platform.runLater(() -> {
                        if (expired) {
                            settings.setBackendToken("");
                            saveSettings();
                            showNews();
                        } else {
                            who.setText("Could not reach the backend: " + e.getMessage());
                        }
                    });
                }
            });
            save.setOnAction(event -> executor.execute(() -> {
                try {
                    client.linkNames(settings.backendToken(), minecraft.getText().strip(), ely.getText().strip());
                    Platform.runLater(() -> result.setText("Linked names saved to your V-Client account."));
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    Platform.runLater(() -> result.setText(e.getMessage()));
                }
            }));
            signOut.setOnAction(event -> {
                settings.setBackendToken("");
                saveSettings();
                showNews();
            });
        } else {
            box.getChildren().add(label("One account for Windows, Android and the website. Optional — you can play without it.", 11, MUTED));
            TextField login = new TextField();
            login.setPromptText("Username or email  (for a new account: the username you want)");
            login.setStyle(fieldStyle());
            TextField email = new TextField();
            email.setPromptText("Email (only needed to create an account)");
            email.setStyle(fieldStyle());
            PasswordField password = new PasswordField();
            password.setPromptText("Password (8+ characters)");
            password.setStyle(fieldStyle());
            Button signIn = button("SIGN IN", ACCENT, 10);
            Button create = button("CREATE ACCOUNT", "#292642", 10);
            if (!client.configured()) {
                box.getChildren().add(ModsUi.warningBox("The V-Client backend address is not set",
                        "Accounts and friends live on the V-Client backend. Enter its address in the \"Backend & updates\" box below and press SAVE ADDRESS, then come back here.",
                        null));
            }
            box.getChildren().addAll(login, email, password, new HBox(10, signIn, create), result);
            java.util.function.Consumer<Boolean> submit = register -> {
                if (!backend().configured()) {
                    result.setText("Set the backend address in the box below first.");
                    return;
                }
                if (login.getText().isBlank() || password.getText().isEmpty() || (register && email.getText().isBlank())) {
                    result.setText(register ? "Enter a username, an email and a password (8+ characters)."
                            : "Enter your username (or email) and your password.");
                    return;
                }
                signIn.setDisable(true);
                create.setDisable(true);
                String loginText = login.getText().strip();
                String emailText = email.getText().strip();
                String passwordText = password.getText();
                result.setText("Contacting the backend…");
                executor.execute(() -> {
                    try {
                        BackendClient.Session session = register
                                ? client.register(loginText, emailText, passwordText)
                                : client.login(loginText, passwordText);
                        Platform.runLater(() -> {
                            settings.setBackendToken(session.token());
                            saveSettings();
                            showNews();
                        });
                    } catch (IOException | InterruptedException e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        Platform.runLater(() -> {
                            result.setText(e.getMessage());
                            signIn.setDisable(false);
                            create.setDisable(false);
                        });
                    }
                });
            };
            signIn.setOnAction(event -> submit.accept(false));
            create.setOnAction(event -> submit.accept(true));
        }
        return box;
    }

    private VBox backendCard() {
        VBox box = card();
        box.getChildren().add(label("BACKEND & UPDATES", 11, MUTED));
        TextField url = new TextField(settings.backendUrl());
        url.setPromptText("https://your-vclient-backend.example.com");
        url.setStyle(fieldStyle());
        Label result = label("V-Client " + AppInfo.VERSION
                + (BackendClient.resolveUrl("").equals(System.getenv("VCLIENT_BACKEND_URL")) ? "  ·  backend set by VCLIENT_BACKEND_URL" : ""), 11, MUTED);
        result.setWrapText(true);
        Button save = button("SAVE ADDRESS", ACCENT, 10);
        Button check = button("CHECK FOR UPDATES", "#292642", 10);
        Button auto = button("", "#292642", 10);
        Runnable refreshAuto = () -> auto.setText(settings.autoUpdate() ? "AUTO-UPDATE: ON" : "AUTO-UPDATE: OFF");
        refreshAuto.run();
        save.setOnAction(event -> {
            String address = BackendClient.normalize(url.getText());
            if (!address.isEmpty() && !BackendClient.isValidUrl(address)) {
                result.setText("Enter a full address starting with https:// (or http:// for a local test server).");
                return;
            }
            settings.setBackendUrl(address);
            saveSettings();
            showNews();
        });
        check.setOnAction(event -> checkForUpdates(true));
        auto.setOnAction(event -> {
            settings.setAutoUpdate(!settings.autoUpdate());
            saveSettings();
            refreshAuto.run();
        });
        box.getChildren().addAll(url, new HBox(10, save, check, auto), result);
        return box;
    }

    // ---- Updates ----

    private void checkForUpdates(boolean manual) {
        BackendClient client = backend();
        if (!client.configured()) {
            if (manual) showError("Updates unavailable", new IOException("Set the V-Client backend address first."));
            return;
        }
        executor.execute(() -> {
            try {
                BackendClient.UpdateInfo info = client.checkForUpdate(AppInfo.VERSION);
                Platform.runLater(() -> {
                    if (info.updateAvailable() && info.latest() != null && info.latest().url().endsWith(".exe")) {
                        // A startup check installs the update on its own and reopens V-Client; only the manual
                        // button asks first.
                        if (manual) promptUpdate(info);
                        else downloadUpdate(info.latest());
                    } else if (manual) {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION, "You are running the latest version (" + AppInfo.VERSION + ").");
                        alert.initOwner(stage);
                        alert.setHeaderText("V-Client is up to date");
                        alert.show();
                    }
                });
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                Platform.runLater(() -> {
                    appendLog("Update check failed: " + e.getMessage());
                    if (manual) showError("Could not check for updates", e);
                });
            }
        });
    }

    private void promptUpdate(BackendClient.UpdateInfo info) {
        BackendClient.Release release = info.latest();
        ButtonType install = new ButtonType("Update now", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType later = info.mandatory()
                ? new ButtonType("Quit", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE)
                : new ButtonType("Later", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", install, later);
        alert.initOwner(stage);
        alert.setTitle("V-Client update");
        alert.setHeaderText("V-Client " + release.version() + " is available" + (info.mandatory() ? " (required)" : ""));
        alert.setContentText((release.notes().isBlank() ? "Bug fixes and improvements." : release.notes())
                + "\n\nYou have " + AppInfo.VERSION + ". The installer will be downloaded, checked, and started; V-Client closes while it installs.");
        alert.getDialogPane().setPrefWidth(520);
        alert.showAndWait().ifPresent(choice -> {
            if (choice == install) downloadUpdate(release);
            else if (info.mandatory()) Platform.exit();
        });
    }

    private void downloadUpdate(BackendClient.Release release) {
        javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
        bar.setPrefWidth(420);
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Updating V-Client");
        dialog.setHeaderText("Downloading V-Client " + release.version() + "…");
        dialog.getDialogPane().setContent(bar);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Thread worker = new Thread(() -> {
            try {
                Path installer = UpdateService.download(release, fraction -> Platform.runLater(() -> bar.setProgress(fraction)));
                backend().reportEvent(settings.installId(), "update");
                UpdateService.launchInstallerAfterExit(installer);
                Platform.runLater(() -> {
                    dialog.close();
                    Platform.exit();
                    System.exit(0);
                });
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> {
                    dialog.close();
                    if (!(e instanceof InterruptedException)) showError("Update failed", e);
                });
            }
        }, "vclient-update");
        worker.setDaemon(true);
        worker.start();
        dialog.showAndWait();
        worker.interrupt();
    }

    // ---- Error logs ----

    private void showLogs() {
        activePage = Page.LOGS;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Diagnostics", "Error logs",
                "The latest launch log, Minecraft crash report, and this session's launcher output. Copy any of them when asking for help.");
        Path logsDirectory = settings.minecraftDirectory().resolve("logs");
        Path crashDirectory = settings.minecraftDirectory().resolve("crash-reports");
        TextArea viewer = new TextArea("Choose a log above.");
        viewer.setEditable(false);
        viewer.setWrapText(false);
        viewer.setPrefRowCount(24);
        viewer.setStyle("-fx-control-inner-background: rgba(9, 10, 28, 0.82); -fx-text-fill: #c3bfdc; -fx-font-family: 'Consolas'; -fx-font-size: 11px; -fx-background-color: rgba(9, 10, 28, 0.82); -fx-border-color: #37334f; -fx-border-radius: 8; -fx-background-radius: 8;");
        VBox.setVgrow(viewer, Priority.ALWAYS);
        Button launchLog = button("LAUNCH LOG", "#292642", 10);
        Button crash = button("LATEST CRASH REPORT", "#292642", 10);
        Button session = button("LAUNCHER OUTPUT", "#292642", 10);
        Button copy = button("COPY", "#292642", 10);
        Button openFolder = button("OPEN LOGS FOLDER", "#292642", 10);
        launchLog.setOnAction(event -> showLogFile(viewer, logsDirectory.resolve("vclient-launch.log"), "No launch log yet. It is written the first time you press Play."));
        crash.setOnAction(event -> showLogFile(viewer, newestFile(crashDirectory), "No crash reports found. That is good news."));
        session.setOnAction(event -> viewer.setText(logLines.isEmpty() ? "No launcher output in this session yet." : String.join(System.lineSeparator(), logLines)));
        copy.setOnAction(event -> {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString(viewer.getText());
            Clipboard.getSystemClipboard().setContent(clipboard);
        });
        openFolder.setOnAction(event -> {
            try {
                Files.createDirectories(logsDirectory);
                Desktop.getDesktop().open(logsDirectory.toFile());
            } catch (IOException | UnsupportedOperationException e) {
                showError("Could not open the logs folder", e);
            }
        });
        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(10, 10, launchLog, crash, session, copy, openFolder);
        content.getChildren().addAll(actions, viewer);
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
        launchLog.fire();
    }

    private void showLogFile(TextArea viewer, Path file, String missingMessage) {
        if (file == null || !Files.isRegularFile(file)) {
            viewer.setText(missingMessage);
            return;
        }
        viewer.setText("Loading " + file.getFileName() + "…");
        executor.execute(() -> {
            String text;
            try {
                text = tail(file, 120_000);
            } catch (IOException e) {
                text = "Could not read " + file + ": " + e.getMessage();
            }
            String shown = text;
            Platform.runLater(() -> {
                viewer.setText(shown);
                viewer.positionCaret(shown.length());
            });
        });
    }

    /** Reads at most the last {@code maxChars} characters of a text file. */
    static String tail(Path file, int maxChars) throws IOException {
        String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        return text.length() <= maxChars ? text : "… (earlier output omitted)\n" + text.substring(text.length() - maxChars);
    }

    private static Path newestFile(Path directory) {
        if (!Files.isDirectory(directory)) return null;
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .max(java.util.Comparator.comparingLong(file -> file.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Anonymous usage counters, on a background thread so the UI never waits for the network. */
    private void reportEventAsync(String event) {
        BackendClient client = backend();
        if (!client.configured()) return;
        executor.execute(() -> client.reportEvent(settings.installId(), event));
    }

    private void startBackendTasks() {
        reportEventAsync("launch");
        recordPlayerAsync(account);
        startPresence();
        if (settings.autoUpdate()) checkForUpdates(false);
    }

    private void showDiscord() {
        activePage = Page.DISCORD;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Community", "Discord", "Save an invite URL to open your community in the system browser.");
        VBox inviteCard = card();
        TextField invite = new TextField(settings.discordInvite());
        invite.setPromptText("https://discord.gg/your-invite");
        invite.setStyle(fieldStyle());
        Button save = button("SAVE INVITE", ACCENT, 10);
        Button open = button("OPEN DISCORD INVITE", "#292642", 10);
        Label result = label("Invite URL is stored locally in launcher settings.", 11, MUTED);
        save.setOnAction(event -> {
            String address = invite.getText() == null ? "" : invite.getText().strip();
            if (!validDiscordInvite(address)) {
                showError("Invalid Discord invite", new IOException("Enter an HTTPS discord.gg invite or a discord.com/invite URL."));
                return;
            }
            settings.setDiscordInvite(address);
            saveSettings();
            result.setText("Discord invite saved.");
        });
        open.setOnAction(event -> {
            String address = settings.discordInvite();
            if (!validDiscordInvite(address)) {
                showError("Discord invite not set", new IOException("Save your community's Discord invite URL first."));
                return;
            }
            openExternalPage(address);
        });
        inviteCard.getChildren().addAll(label("COMMUNITY INVITE URL", 11, MUTED), invite, new HBox(10, save, open), result);
        content.getChildren().addAll(inviteCard,
                label("V-Client does not have a configured community invite yet. Add one above; no URL is invented or contacted automatically.", 12, MUTED));
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
    }

    private static boolean validDiscordInvite(String address) {
        try {
            URI uri = URI.create(address);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && (host.equalsIgnoreCase("discord.gg")
                    || host.equalsIgnoreCase("discord.com") && uri.getPath() != null
                    && uri.getPath().startsWith("/invite/"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void openExternalPage(String address) {
        try {
            if (!Desktop.isDesktopSupported()) throw new IOException("This computer does not support opening links from V-Client.");
            Desktop.getDesktop().browse(URI.create(address));
        } catch (IOException | IllegalArgumentException e) {
            showError("Could not open link", e);
        }
    }

    private void showMods() {
        activePage = Page.MODS;
        settingsPage = false;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = pageContent("Modrinth", "Mods",
                "Browse Modrinth and install mods straight into your mods folder. The folder is created automatically on your first install.");
        ModsPage page = new ModsPage(content);
        content.getChildren().addAll(page.controlsCard(), page.notice, page.resultsCard(), page.installedCard());
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
        page.start();
    }

    /** The Mods page: one glass card for the controls, one for the results, one for what is installed. */
    private final class ModsPage {
        private final Path modsDirectory = settings.minecraftDirectory().resolve("mods").toAbsolutePath().normalize();
        private final AtomicInteger generation = new AtomicInteger();
        private final List<javafx.animation.Animation> animations = new ArrayList<>();
        private final java.util.Map<String, Image> iconCache = new java.util.HashMap<>();
        private final VBox content;
        private final ComboBox<MinecraftLauncher.InstalledVersion> profilePicker = new ComboBox<>();
        private final TextField query = new TextField();
        private final Button search = new Button("SEARCH");
        private final VBox notice = new VBox(10);
        private final VBox resultsBody = new VBox(12);
        private final HBox resultsHeader = new HBox(12);
        private final Label resultsCount = ModsUi.chip("", ModsUi.MUTED);
        private final VBox installedBody = new VBox(8);
        private final Label installedCount = ModsUi.chip("", ModsUi.MUTED);
        private List<ModrinthModService.ModProject> current = List.of();
        private javafx.scene.layout.FlowPane flow;

        ModsPage(VBox content) {
            this.content = content;
            notice.setMaxWidth(Double.MAX_VALUE);
            notice.setManaged(false);
            notice.setVisible(false);
            // The two-column grid follows the real width of the results area.
            resultsBody.widthProperty().addListener((observable, oldWidth, newWidth) -> layoutColumns());
        }

        private static final String FIELD = "-fx-background-color: rgba(8, 9, 26, 0.85); -fx-text-fill: #f5f7fd;"
                + " -fx-prompt-text-fill: #7f7fa6; -fx-font-size: 13px; -fx-border-color: rgba(255,255,255,0.14);"
                + " -fx-background-radius: 12; -fx-border-radius: 12; -fx-padding: 0 14 0 14;";

        VBox controlsCard() {
            VBox card = ModsUi.glassCard(14, new Insets(20));
            profilePicker.setPromptText("Scanning profiles...");
            profilePicker.setStyle(FIELD);
            // The closed box and the dropdown rows both need light text on the dark glass.
            profilePicker.setButtonCell(profileCell());
            profilePicker.setCellFactory(list -> profileCell());
            profilePicker.setPrefWidth(250);
            profilePicker.setMinWidth(170);
            query.setPromptText("Search Modrinth (leave empty for the most popular)");
            query.setStyle(FIELD);
            search.setStyle("-fx-background-color: " + ACCENT + "; -fx-text-fill: #171326; -fx-font-size: 12px;"
                    + " -fx-font-weight: bold; -fx-background-radius: 12; -fx-padding: 0 24 0 24; -fx-cursor: hand;");
            for (javafx.scene.layout.Region control : List.of(profilePicker, query, search)) {
                control.setMinHeight(44);
                control.setPrefHeight(44);
                control.setMaxHeight(44);
            }
            search.setMinWidth(Region.USE_PREF_SIZE);
            query.setMinWidth(120);
            HBox.setHgrow(query, Priority.ALWAYS);
            HBox row = new HBox(12, profilePicker, query, search);
            row.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().addAll(ModsUi.sectionTitle("Find mods"), row);
            search.setOnAction(event -> runSearch());
            query.setOnAction(event -> runSearch());
            profilePicker.valueProperty().addListener((observable, oldProfile, newProfile) -> {
                if (newProfile != null) runSearch();
            });
            return card;
        }

        VBox resultsCard() {
            VBox card = ModsUi.glassCard(14, new Insets(20));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            resultsHeader.setAlignment(Pos.CENTER_LEFT);
            resultsHeader.getChildren().addAll(ModsUi.sectionTitle("Results"), resultsCount, spacer);
            resultsCount.setVisible(false);
            resultsCount.setManaged(false);
            resultsBody.getChildren().add(ModsUi.emptyCard("✦", "Pick a profile to browse mods",
                    "Choose a Fabric, Forge, NeoForge or Quilt profile above and the compatible mods appear here."));
            card.getChildren().addAll(resultsHeader, resultsBody);
            return card;
        }

        VBox installedCard() {
            VBox card = ModsUi.glassCard(14, new Insets(20));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button refresh = secondaryButton("REFRESH");
            refresh.setOnAction(event -> loadInstalled());
            Button openFolder = secondaryButton("OPEN FOLDER");
            openFolder.setOnAction(event -> {
                try {
                    Files.createDirectories(modsDirectory);
                    if (!Desktop.isDesktopSupported()) throw new IOException("This computer does not support opening folders from V-Client.");
                    Desktop.getDesktop().open(modsDirectory.toFile());
                } catch (IOException | UnsupportedOperationException e) {
                    showNotice(ModsUi.warningBox("Couldn't open the mods folder", e.getMessage(), null));
                }
            });
            HBox header = new HBox(10, ModsUi.sectionTitle("Installed mods"), installedCount, spacer, refresh, openFolder);
            header.setAlignment(Pos.CENTER_LEFT);
            Label path = ModsUi.text(modsDirectory.toString(), 10, "#7f7fa6", false);
            path.setWrapText(true);
            card.getChildren().addAll(header, path, installedBody);
            return card;
        }

        private ListCell<MinecraftLauncher.InstalledVersion> profileCell() {
            return new ListCell<>() {
                @Override
                protected void updateItem(MinecraftLauncher.InstalledVersion version, boolean empty) {
                    super.updateItem(version, empty);
                    setText(empty || version == null ? null : version.displayName());
                    setStyle("-fx-text-fill: #f5f7fd; -fx-font-size: 13px; -fx-background-color: transparent;");
                }
            };
        }

        private Button secondaryButton(String label) {
            Button button = new Button(label);
            button.setMinHeight(34);
            button.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: #f5f7fd; -fx-font-size: 11px;"
                    + " -fx-font-weight: bold; -fx-background-radius: 10; -fx-padding: 0 14 0 14; -fx-cursor: hand;");
            return button;
        }

        void start() {
            loadInstalled();
            executor.execute(() -> {
                try {
                    List<MinecraftLauncher.InstalledVersion> found = gameLauncher.findVersions(settings.minecraftDirectory());
                    List<MinecraftLauncher.InstalledVersion> loaderProfiles = found.stream()
                            .filter(version -> !"Vanilla".equals(version.loader())).toList();
                    Platform.runLater(() -> {
                        installedVersions = found;
                        if (activePage != Page.MODS) return;
                        profilePicker.getItems().setAll(loaderProfiles);
                        if (loaderProfiles.isEmpty()) {
                            profilePicker.setPromptText("No mod-loader profiles");
                            showNotice(ModsUi.warningBox("No mod-loader profile installed",
                                    "Mods need a Fabric, Forge, NeoForge or Quilt profile. Install one in V-Client's game folder, then come back.",
                                    null));
                            return;
                        }
                        profilePicker.setValue(loaderProfiles.stream()
                                .filter(version -> version.id().equals(selectedVersionId))
                                .findFirst().orElse(loaderProfiles.getFirst()));
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> showNotice(ModsUi.warningBox("Couldn't scan your profiles", e.getMessage(), null)));
                }
            });
        }

        // ---- notices and states ----------------------------------------------------------------------

        private void showNotice(javafx.scene.Node box) {
            notice.getChildren().setAll(box);
            notice.setManaged(true);
            notice.setVisible(true);
        }

        private void clearNotice() {
            notice.getChildren().clear();
            notice.setManaged(false);
            notice.setVisible(false);
        }

        private void showLoading(String message) {
            ModsUi.stop(animations);
            HBox busy = ModsUi.busyLabel(message);
            resultsCount.setVisible(false);
            resultsCount.setManaged(false);
            resultsBody.getChildren().setAll(busy, ModsUi.skeleton(3, animations));
        }

        private void showResults(List<ModrinthModService.ModProject> found, ModrinthModService.Target target) {
            ModsUi.stop(animations);
            current = found;
            resultsCount.setText(found.size() + (found.size() == 1 ? " mod" : " mods") + "  ·  " + target.loader()
                    + "  ·  " + target.gameVersion());
            resultsCount.setVisible(true);
            resultsCount.setManaged(true);
            if (found.isEmpty()) {
                resultsBody.getChildren().setAll(ModsUi.emptyCard("⌕", "No mods found",
                        "Nothing compatible with " + target.loader() + " on Minecraft " + target.gameVersion()
                                + (query.getText() == null || query.getText().isBlank() ? "" : " matches “" + query.getText().strip() + "”")
                                + ". Try another search or profile."));
                return;
            }
            renderCards();
        }

        private void renderCards() {
            flow = new javafx.scene.layout.FlowPane(12, 12);
            flow.setMaxWidth(Double.MAX_VALUE);
            for (ModrinthModService.ModProject project : current) {
                boolean installed = Files.isRegularFile(modsDirectory.resolve(project.fileName()));
                boolean downloading = modsDownloading.contains(project.fileName());
                Image icon = iconCache.computeIfAbsent(project.iconUrl(), url -> modIcon(url));
                flow.getChildren().add(ModsUi.modCard(project, icon, installed, downloading,
                        formatDownloads(project.downloads()), () -> install(project)));
            }
            resultsBody.getChildren().setAll(flow);
            layoutColumns();
        }

        private void layoutColumns() {
            if (flow == null) return;
            double available = resultsBody.getWidth() > 0 ? resultsBody.getWidth() : Math.max(300, content.getWidth() - 110);
            flow.setPrefWrapLength(available);
            ModsUi.applyColumns(flow, available);
        }

        private void showFailure(Exception error) {
            ModsUi.stop(animations);
            resultsCount.setVisible(false);
            resultsCount.setManaged(false);
            String[] message = ModsUi.describeFailure("Modrinth", error.getMessage());
            resultsBody.getChildren().setAll(ModsUi.warningBox(message[0], message[1], this::runSearch));
        }

        // ---- search ----------------------------------------------------------------------------------

        void runSearch() {
            if (settings.offlineMode()) {
                showNotice(ModsUi.warningBox("Offline mode is on",
                        "Modrinth is not contacted while offline mode is on. Turn it off on the Home page to browse and install mods.", null));
                return;
            }
            MinecraftLauncher.InstalledVersion profile = profilePicker.getValue();
            if (profile == null) {
                showNotice(ModsUi.warningBox("Choose a profile first",
                        "Install a Fabric, Forge, NeoForge or Quilt profile, then pick it in the list above.", null));
                return;
            }
            clearNotice();
            int mine = generation.incrementAndGet();
            search.setDisable(true);
            showLoading("Searching Modrinth…");
            Path minecraftDirectory = settings.minecraftDirectory();
            String text = query.getText() == null ? "" : query.getText();
            executor.execute(() -> {
                try {
                    ModrinthModService.Target target = ModrinthModService.resolveTarget(minecraftDirectory, profile);
                    List<ModrinthModService.ModProject> found = modrinthModService.search(text, target, message -> {
                        Platform.runLater(() -> appendLog(message));
                    });
                    Platform.runLater(() -> {
                        if (generation.get() == mine) showResults(found, target);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        if (generation.get() != mine) return;
                        showFailure(e);
                        appendLog("Modrinth search failed: " + e.getMessage());
                    });
                } finally {
                    Platform.runLater(() -> {
                        if (generation.get() == mine) search.setDisable(false);
                    });
                }
            });
        }

        // ---- install / installed list ------------------------------------------------------------------

        private void install(ModrinthModService.ModProject project) {
            if (settings.offlineMode()) {
                showNotice(ModsUi.warningBox("Offline mode is on", "Turn it off on the Home page to install mods.", null));
                return;
            }
            if (!modsDownloading.add(project.fileName())) return;
            clearNotice();
            renderCardsIfShown();
            appendLog("Installing mod " + project.title() + " (" + project.fileName() + ") into " + modsDirectory + ".");
            Path minecraftDirectory = modsDirectory.getParent();
            executor.execute(() -> {
                try {
                    Path installed = modrinthModService.install(project, minecraftDirectory,
                            message -> Platform.runLater(() -> appendLog(message)));
                    if (stats.unlock(PlayerStats.FIRST_MOD)) stats.save();
                    Platform.runLater(() -> {
                        appendLog("Installed mod " + installed + ".");
                        showNotice(successBox(project.title() + " installed"));
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        appendLog("Mod install failed for " + project.title() + ": " + e.getMessage());
                        String[] message = ModsUi.describeFailure("Installing " + project.title(), e.getMessage());
                        showNotice(ModsUi.warningBox(message[0], message[1], null));
                    });
                } finally {
                    modsDownloading.remove(project.fileName());
                    Platform.runLater(() -> {
                        renderCardsIfShown();
                        loadInstalled();
                    });
                }
            });
        }

        private HBox successBox(String message) {
            Label icon = ModsUi.text("✓", 16, "#08140e", true);
            icon.setMinSize(30, 30);
            icon.setAlignment(Pos.CENTER);
            icon.setStyle(icon.getStyle() + " -fx-background-color: " + ModsUi.GOOD + "; -fx-background-radius: 15;");
            HBox box = new HBox(12, icon, ModsUi.text(message, 13, "#bff5d6", true));
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new Insets(12, 16, 12, 14));
            box.setStyle("-fx-background-color: rgba(57,217,138,0.10); -fx-background-radius: 14;"
                    + " -fx-border-color: rgba(57,217,138,0.40); -fx-border-radius: 14;");
            return box;
        }

        private void renderCardsIfShown() {
            if (flow != null && !current.isEmpty() && resultsBody.getChildren().contains(flow)) renderCards();
        }

        void loadInstalled() {
            executor.execute(() -> {
                List<Path> mods = new ArrayList<>();
                String error = null;
                if (Files.isDirectory(modsDirectory)) {
                    try (var files = Files.list(modsDirectory)) {
                        files.filter(Files::isRegularFile)
                                .filter(file -> {
                                    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                                    return name.endsWith(".jar") || name.endsWith(".jar.disabled");
                                })
                                .sorted(java.util.Comparator.comparing(file -> file.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                                .forEach(mods::add);
                    } catch (IOException e) {
                        error = e.getMessage();
                    }
                }
                String scanError = error;
                Platform.runLater(() -> showInstalled(mods, scanError));
            });
        }

        private void showInstalled(List<Path> mods, String scanError) {
            installedCount.setText(String.valueOf(mods.size()));
            if (scanError != null) {
                installedBody.getChildren().setAll(ModsUi.warningBox("Couldn't read the mods folder", scanError, null));
                return;
            }
            if (mods.isEmpty()) {
                installedBody.getChildren().setAll(ModsUi.emptyCard("▣", "No mods installed yet",
                        Files.isDirectory(modsDirectory) ? "Install a mod from the results above."
                                : "The mods folder is created on your first install."));
                return;
            }
            List<javafx.scene.Node> rows = new ArrayList<>();
            for (Path file : mods) {
                String fileName = file.getFileName().toString();
                boolean disabled = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".disabled");
                Label name = ModsUi.text(fileName, 13, disabled ? ModsUi.MUTED : ModsUi.TEXT, true);
                name.setMinWidth(0);
                HBox.setHgrow(name, Priority.ALWAYS);
                name.setMaxWidth(Double.MAX_VALUE);
                Label state = ModsUi.chip(disabled ? "Disabled" : "Enabled", disabled ? "#7f7fa6" : ModsUi.GOOD);
                Button remove = new Button("REMOVE");
                remove.setMinHeight(32);
                remove.setStyle("-fx-background-color: rgba(255,99,120,0.14); -fx-text-fill: #ff9aa8; -fx-font-size: 10px;"
                        + " -fx-font-weight: bold; -fx-background-radius: 9; -fx-padding: 0 14 0 14; -fx-cursor: hand;");
                remove.setOnAction(event -> removeMod(file));
                HBox row = new HBox(12, name, state, remove);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8, 12, 8, 14));
                String base = "-fx-background-color: rgba(255,255,255,0.045); -fx-background-radius: 12;";
                row.setStyle(base);
                row.setOnMouseEntered(event -> row.setStyle(base + " -fx-background-color: rgba(255,255,255,0.09);"));
                row.setOnMouseExited(event -> row.setStyle(base));
                rows.add(row);
            }
            installedBody.getChildren().setAll(rows);
        }

        private void removeMod(Path selected) {
            Path file = selected.toAbsolutePath().normalize();
            if (!modsDirectory.equals(file.getParent())) {
                showNotice(ModsUi.warningBox("Couldn't remove the mod", "That file is not inside V-Client's mods folder.", null));
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Remove " + file.getFileName() + " from your mods folder?", ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(stage);
            confirm.setTitle("V-Client");
            confirm.setHeaderText("Remove mod");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            try {
                Files.deleteIfExists(file);
                appendLog("Removed mod " + file + ".");
            } catch (IOException e) {
                showNotice(ModsUi.warningBox("Couldn't remove the mod", e.getMessage(), null));
            }
            renderCardsIfShown();
            loadInstalled();
        }
    }

    private static Image modIcon(String iconUrl) {
        try {
            URI uri = URI.create(iconUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"cdn.modrinth.com".equalsIgnoreCase(uri.getHost())) return null;
            return new Image(uri.toString(), 112, 112, true, true, true);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String formatDownloads(long downloads) {
        if (downloads >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", downloads / 1_000_000.0);
        if (downloads >= 1_000) return String.format(java.util.Locale.ROOT, "%.1fK", downloads / 1_000.0);
        return Long.toString(downloads);
    }

    private void showLogin() {
        activePage = Page.SETTINGS;
        settingsPage = true;
        updateNavigationState();
        StackPane root = baseLayout();
        VBox content = new VBox(20);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(42, 72, 42, 72));
        Canvas logo = logo(72);
        Label heading = label("Choose an account", 30, "#f3f6fc");
        Label subtitle = label("Sign in to Minecraft or create an offline profile.", 14, MUTED);
        Button microsoftButton = button("MICROSOFT  ·  PREMIUM", ACCENT, 14);
        Button elyButton = button("ELY.BY", "#292642", 14);
        Button offlineButton = button("OFFLINE ACCOUNT", "#292642", 14);
        for (Button option : List.of(microsoftButton, elyButton, offlineButton)) {
            option.setMaxWidth(Double.MAX_VALUE);
            option.setPrefHeight(48);
        }
        microsoftButton.setOnAction(event -> signInMicrosoft());
        elyButton.setOnAction(event -> signInElyBy());
        offlineButton.setOnAction(event -> addOfflineAccount());
        VBox buttons = new VBox(11, microsoftButton, elyButton, offlineButton);
        if (!accounts.isEmpty()) {
            ComboBox<PlayerAccount> savedAccounts = new ComboBox<>();
            savedAccounts.getItems().setAll(accounts);
            savedAccounts.setButtonCell(accountCell());
            savedAccounts.setCellFactory(list -> accountCell());
            savedAccounts.setMaxWidth(Double.MAX_VALUE);
            savedAccounts.setValue(account);
            savedAccounts.setOnAction(event -> {
                if (savedAccounts.getValue() != null) selectAccount(savedAccounts.getValue());
            });
            content.getChildren().addAll(logo, heading, subtitle, savedAccounts, buttons);
        } else {
            content.getChildren().addAll(logo, heading, subtitle, buttons);
        }
        status = label("", 12, MUTED);
        content.getChildren().add(status);
        setPageContent(root, content);
        stage.setScene(new Scene(root, stage.getWidth(), stage.getHeight()));
    }

    private VBox buildAccountCard() {
        VBox accountCard = card();
        Label accountTitle = label("SAVED PLAYER ACCOUNTS", 11, MUTED);
        HBox accountRow = new HBox(12);
        accountRow.setAlignment(Pos.CENTER_LEFT);
        accountPicker = new ComboBox<>();
        accountPicker.getItems().setAll(accounts);
        accountPicker.setCellFactory(list -> accountCell());
        accountPicker.setButtonCell(accountCell());
        accountPicker.setPromptText("Add or select an account");
        accountPicker.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(accountPicker, Priority.ALWAYS);
        accountPicker.setValue(account);
        accountPicker.setOnAction(event -> {
            if (accountPicker.getValue() != null && accountPicker.getValue() != account) {
                selectAccount(accountPicker.getValue());
            }
        });
        Button removeAccount = button("REMOVE", "#482332", 10);
        removeAccount.setOnAction(event -> removeSelectedAccount());
        accountRow.getChildren().addAll(accountPicker, removeAccount);
        HBox addButtons = new HBox(9);
        addButtons.setAlignment(Pos.CENTER_LEFT);
        Button microsoftButton = button("+ MICROSOFT", ACCENT, 10);
        Button elyButton = button("+ ELY.BY", "#292642", 10);
        Button offlineButton = button("+ OFFLINE", "#292642", 10);
        microsoftButton.setOnAction(event -> signInMicrosoft());
        elyButton.setOnAction(event -> signInElyBy());
        offlineButton.setOnAction(event -> addOfflineAccount());
        addButtons.getChildren().addAll(microsoftButton, elyButton, offlineButton);

        HBox previews = new HBox(16);
        previews.setAlignment(Pos.CENTER_LEFT);
        ImageView skin = new ImageView();
        skin.setFitWidth(64);
        skin.setFitHeight(64);
        skin.setPreserveRatio(true);
        ImageView cape = new ImageView();
        cape.setFitWidth(40);
        cape.setFitHeight(64);
        cape.setPreserveRatio(true);
        Label previewsLabel = label("Skin and cape previews load for the selected account.", 12, MUTED);
        previews.getChildren().addAll(skin, cape, previewsLabel);
        accountLabel = label(account == null ? "Choose Microsoft, Ely.by, or an offline username."
                : account.name() + "  ·  " + account.type().label(), 13,
                account == null ? MUTED : "#f3f6fc");
        accountPicker.valueProperty().addListener((observable, oldAccount, newAccount) ->
                loadPreviews(newAccount, skin, cape, previewsLabel));
        loadPreviews(account, skin, cape, previewsLabel);
        accountCard.getChildren().addAll(accountTitle, accountRow, addButtons, accountLabel, previews);
        return accountCard;
    }

    private StackPane baseLayout() {
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: " + BACKGROUND + "; -fx-font-family: 'Segoe UI';");
        Canvas nightSky = new Canvas();
        nightSky.widthProperty().bind(root.widthProperty());
        nightSky.heightProperty().bind(root.heightProperty());
        nightSky.setMouseTransparent(true);
        root.getChildren().add(nightSky);
        if (backgroundAnimation != null) backgroundAnimation.stop();
        backgroundAnimation = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (now - lastBackgroundFrame < 33_000_000L) return;
                lastBackgroundFrame = now;
                drawNightSky(nightSky, now / 1_000_000_000.0);
            }
        };
        backgroundAnimation.start();
        BorderPane layout = new BorderPane();
        layout.setStyle("-fx-background-color: transparent; -fx-font-family: 'Segoe UI';");
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 30, 18, 30));
        avatar = new ImageView(createIcon());
        avatar.setFitWidth(38);
        avatar.setFitHeight(38);
        avatar.setPreserveRatio(true);
        avatar.setClip(new Circle(19, 19, 19));
        if (account != null) loadAvatar(account, avatar);
        Canvas logo = logo(34);
        Label brand = label("V-CLIENT", 15, "#eff4ff");
        brand.setStyle(brand.getStyle() + "-fx-font-weight: bold; -fx-letter-spacing: 1.5;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        headerAccountLabel = label(account == null ? "NO ACCOUNT" : account.name().toUpperCase()
                + "  ·  " + account.type().label().toUpperCase(), 10,
                account == null ? MUTED : ACCENT);
        Button switchAccount = button("ACCOUNT", "#292642", 10);
        switchAccount.setOnAction(event -> showSettings());
        header.getChildren().addAll(avatar, logo, brand, spacer,
                dot(account == null ? "#64728a" : "#39d98a"), headerAccountLabel, switchAccount);
        header.setStyle("-fx-background-color: rgba(9, 10, 27, 0.78); -fx-border-color: transparent transparent rgba(145, 127, 186, 0.22) transparent;");
        layout.setTop(header);
        VBox navigation = new VBox(3);
        navigation.setAlignment(Pos.TOP_CENTER);
        navigation.setPadding(new Insets(10, 6, 10, 6));
        navigation.setSpacing(3);
        navigation.setPrefWidth(92);
        navigation.setStyle("-fx-background-color: rgba(9, 10, 27, 0.68); -fx-border-color: transparent rgba(145, 127, 186, 0.18) transparent transparent;");
        for (Page page : Page.values()) {
            String[] parts = navigationText(page).split("\n", 2);
            Label glyph = new Label(parts[0]);
            glyph.setMinSize(32, 24);
            glyph.setPrefSize(32, 24);
            glyph.setMaxSize(32, 24);
            glyph.setAlignment(Pos.CENTER);
            Label caption = new Label(parts.length > 1 ? parts[1] : "");
            caption.setAlignment(Pos.CENTER);
            VBox stack = new VBox(3, glyph, caption);
            stack.setAlignment(Pos.CENTER);
            Button item = new Button();
            item.setGraphic(stack);
            item.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            item.setMinSize(78, 50);
            item.setPrefSize(78, 50);
            item.setMaxSize(78, 50);
            item.setOnAction(event -> navigate(page));
            item.setOnMouseEntered(event -> {
                hoveredPage = page;
                updateNavigationState();
            });
            item.setOnMouseExited(event -> {
                if (hoveredPage == page) hoveredPage = null;
                updateNavigationState();
            });
            navigationButtons.put(page, item);
            navigation.getChildren().add(item);
        }
        // In a short window the sidebar scrolls instead of pushing the whole layout past the window edges
        // (the root centres its content, so an over-tall sidebar used to clip the header and the last items).
        javafx.scene.control.ScrollPane navigationScroll = new javafx.scene.control.ScrollPane(navigation);
        navigationScroll.setFitToWidth(true);
        navigationScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navigationScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        navigationScroll.setMinHeight(0);
        navigationScroll.setPrefWidth(100);
        navigationScroll.setMinWidth(100);
        navigationScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-padding: 0;");
        layout.setMinSize(0, 0);
        layout.setLeft(navigationScroll);
        updateNavigationState();
        root.getChildren().add(layout);
        return root;
    }

    private void navigate(Page page) {
        switch (page) {
            case HOME -> showHome();
            case SERVERS -> showServers();
            case LIBRARY -> showLibrary();
            case MODS -> showMods();
            case BOOST -> showBoost();
            case BROWSER -> showFeaturePage(Page.BROWSER, "Web", "Browser",
                    "Browse the web with DNS lookups sent over HTTPS (Cloudflare DoH).", BrowserPage::build);
            case PACKS -> showFeaturePage(Page.PACKS, "One click", "Mod Packs",
                    "PvP, FPS, SMP and Builder packs, installed in one click.", PacksPage::build);
            case PERFORMANCE -> showFeaturePage(Page.PERFORMANCE, "Performance", "FPS & PC analysis",
                    "How the game runs on your PC, and what to change to make it run better.", PerformancePage::build);
            case RECORDINGS -> showFeaturePage(Page.RECORDINGS, "Capture", "Recordings",
                    "Videos recorded while Minecraft runs are imported here automatically.", RecordingsPage::build);
            case SCREENSHOTS -> showFeaturePage(Page.SCREENSHOTS, "Capture", "Screenshots",
                    "Your in-game screenshots, picked up automatically.", ScreenshotsPage::build);
            case PROFILE -> showFeaturePage(Page.PROFILE, "You", "Profile",
                    "Your playtime, achievements, daily rewards and music.", ProfilePage::build);
            case ASSISTANT -> showFeaturePage(Page.ASSISTANT, "Help", "VSMP Assistant",
                    "Fix launch errors, find crashes and free up disk space.", AssistantPage::build);
            case WARDROBE -> showWardrobe();
            case COSMETICS -> showCosmetics();
            case FRIENDS -> showFriends();
            case NEWS -> showNews();
            case LOGS -> showLogs();
            case CONSOLE -> showConsole();
            case SETTINGS -> showSettings();
            case DISCORD -> showDiscord();
        }
    }

    private void updateNavigationState() {
        navigationButtons.forEach((page, item) -> {
            boolean selected = page == activePage;
            boolean hovered = page == hoveredPage;
            String color = selected ? ACCENT : hovered ? "#f5f7fd" : "#a9a9cc";
            item.setStyle("-fx-background-color: " + (selected ? Theme.accentRgba(0.17)
                    : hovered ? "rgba(255, 255, 255, 0.07)" : "transparent")
                    + "; -fx-background-radius: 14; -fx-border-radius: 14; -fx-padding: 0; -fx-cursor: hand;"
                    + " -fx-border-color: " + (selected ? Theme.accentRgba(0.55) : "transparent") + "; -fx-border-width: 1;");
            if (item.getGraphic() instanceof VBox stack && stack.getChildren().size() == 2) {
                stack.getChildren().get(0).setStyle("-fx-font-size: 17px; -fx-font-family: 'Segoe UI Symbol', 'Segoe UI';"
                        + " -fx-text-fill: " + color + ";");
                stack.getChildren().get(1).setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
            }
        });
    }

    private static String navigationText(Page page) {
        return switch (page) {
            case HOME -> "⌂\nHome";
            case SERVERS -> "☰\nServers";
            case LIBRARY -> "▤\nLibrary";
            case MODS -> "✚\nMods";
            case BOOST -> "ϟ\nBoost";
            case BROWSER -> "◎\nBrowser";
            case PACKS -> "▦\nPacks";
            case PERFORMANCE -> "◔\nFPS";
            case PROFILE -> "☺\nProfile";
            case RECORDINGS -> "●\nClips";
            case SCREENSHOTS -> "▣\nShots";
            case ASSISTANT -> "?\nHelp";
            case WARDROBE -> "♧\nWardrobe";
            case COSMETICS -> "✦\nCosmetics";
            case FRIENDS -> "♟\nFriends";
            case NEWS -> "✉\nNews";
            case LOGS -> "⚠\nLogs";
            case CONSOLE -> ">_\nConsole";
            case SETTINGS -> "⚙\nSettings";
            case DISCORD -> "◉\nDiscord";
        };
    }

    private static void setPageContent(StackPane root, javafx.scene.Node content) {
        BorderPane layout = (BorderPane) root.getChildren().get(1);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        layout.setCenter(scroll);
    }

    private void refreshVersions() {
        refreshVersions("");
    }

    private void refreshVersions(String selectId) {
        if (versionPicker == null) return;
        versionPicker.setDisable(true);
        if (refreshVersionsButton != null) refreshVersionsButton.setDisable(true);
        boolean offline = settings.offlineMode();
        versionPicker.setPromptText(offline ? "Scanning installed versions..." : "Fetching Mojang versions...");
        Path directory = settings.minecraftDirectory();
        executor.execute(() -> {
            List<MinecraftLauncher.InstalledVersion> versions;
            try {
                try {
                    VClientModInstaller.ensureInstalled(directory,
                            line -> Platform.runLater(() -> appendLog(line)));
                } catch (IOException e) {
                    Platform.runLater(() -> appendLog("Could not install the V-Client mod profile: " + e.getMessage()));
                }
                try {
                    BundledResourcePack.ensureInstalled(directory,
                            line -> Platform.runLater(() -> appendLog(line)));
                } catch (IOException e) {
                    Platform.runLater(() -> appendLog("Could not install the bundled resource pack: " + e.getMessage()));
                }
                versions = gameLauncher.findVersions(directory);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (refreshVersionsButton != null) refreshVersionsButton.setDisable(false);
                    if (versionPicker != null) versionPicker.setDisable(false);
                    versionStatusLabel.setText("Could not scan installed versions: " + e.getMessage());
                    showError("Could not scan installed Minecraft versions", e);
                });
                return;
            }
            if (offline) {
                Platform.runLater(() -> showInstalledVersionsOnly(versions, selectId, directory,
                        "Offline mode · nothing is contacted"));
                return;
            }
            try {
                MinecraftLauncher.VersionCatalog catalog = gameLauncher.fetchVersionCatalog(
                        line -> Platform.runLater(() -> appendLog(line)));
                Platform.runLater(() -> {
                    installedVersions = versions;
                    versionCatalog = catalog;
                    if (settingsPage || versionPicker == null) return;
                    versionPicker.getItems().setAll(buildVersionChoices(catalog, versions));
                    versionPicker.setDisable(false);
                    versionPicker.setPromptText("Choose a Mojang release or installed profile");
                    if (refreshVersionsButton != null) refreshVersionsButton.setDisable(false);
                    VersionChoice chosen = versionPicker.getItems().stream()
                            .filter(choice -> choice.id().equals(selectId))
                            .filter(VersionChoice::preferredEntry).findFirst()
                            .orElseGet(() -> defaultVersionChoice(catalog, versions));
                    if (chosen != null) {
                        versionPicker.getSelectionModel().select(chosen);
                        selectedVersionId = chosen.id();
                    }
                    versionStatusLabel.setText("Mojang versions: " + catalog.versions().size()
                            + " · Installed profiles: " + versions.size()
                            + " · Game folder: " + directory);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendLog("Mojang version list unavailable (" + e.getMessage()
                            + "); showing installed versions only.");
                    showInstalledVersionsOnly(versions, selectId, directory,
                            "Mojang unreachable · showing installed versions · turn on Offline mode to stop connecting");
                });
            }
        });
    }

    private void showInstalledVersionsOnly(List<MinecraftLauncher.InstalledVersion> versions, String selectId,
                                           Path directory, String reason) {
        installedVersions = versions;
        versionCatalog = null;
        if (settingsPage || versionPicker == null) return;
        if (refreshVersionsButton != null) refreshVersionsButton.setDisable(false);
        List<VersionChoice> choices = versions.stream()
                .map(profile -> new VersionChoice(profile.id(), profile.displayName(), "Installed",
                        null, profile, true))
                .toList();
        versionPicker.getItems().setAll(choices);
        versionPicker.setDisable(false);
        versionPicker.setPromptText(choices.isEmpty() ? "No installed versions found" : "Choose an installed version");
        MinecraftLauncher.InstalledVersion preferred = MinecraftLauncher.preferredInstalledVersion(versions);
        VersionChoice chosen = choices.stream().filter(choice -> choice.id().equals(selectId)).findFirst()
                .orElseGet(() -> choices.stream()
                        .filter(choice -> preferred != null && choice.id().equals(preferred.id()))
                        .findFirst().orElse(choices.isEmpty() ? null : choices.getFirst()));
        if (chosen != null) {
            versionPicker.getSelectionModel().select(chosen);
            selectedVersionId = chosen.id();
        }
        versionStatusLabel.setText(reason + " · Installed profiles: " + versions.size()
                + " · Game folder: " + directory);
    }

    private List<VersionChoice> buildVersionChoices(MinecraftLauncher.VersionCatalog catalog,
                                                   List<MinecraftLauncher.InstalledVersion> installed) {
        List<VersionChoice> choices = new ArrayList<>();
        MinecraftLauncher.AvailableVersion latestRelease = findAvailable(catalog, catalog.latestReleaseId());
        MinecraftLauncher.AvailableVersion latestSnapshot = findAvailable(catalog, catalog.latestSnapshotId());
        if (latestRelease != null) {
            choices.add(versionChoice(latestRelease, installed, "Latest Release — " + latestRelease.id(), true));
        }
        if (latestSnapshot != null) {
            choices.add(versionChoice(latestSnapshot, installed, "Latest Snapshot — " + latestSnapshot.id(), true));
        }
        for (MinecraftLauncher.AvailableVersion available : catalog.versions()) {
            if (available.id().equals(catalog.latestReleaseId())
                    || available.id().equals(catalog.latestSnapshotId())) continue;
            choices.add(versionChoice(available, installed, available.id(), false));
        }
        for (MinecraftLauncher.InstalledVersion profile : installed) {
            boolean listed = choices.stream().anyMatch(choice -> choice.id().equals(profile.id()));
            if (!listed) {
                choices.add(new VersionChoice(profile.id(), profile.displayName(), "Installed",
                        null, profile, true));
            }
        }
        return List.copyOf(choices);
    }

    private static MinecraftLauncher.AvailableVersion findAvailable(
            MinecraftLauncher.VersionCatalog catalog, String id) {
        return catalog.versions().stream().filter(version -> version.id().equals(id))
                .findFirst().orElse(null);
    }

    private VersionChoice versionChoice(MinecraftLauncher.AvailableVersion available,
                                        List<MinecraftLauncher.InstalledVersion> installed,
                                        String label, boolean preferredEntry) {
        MinecraftLauncher.InstalledVersion profile = installed.stream()
                .filter(version -> version.id().equals(available.id())).findFirst().orElse(null);
        String status = versionsDownloading.contains(available.id()) ? "Downloading"
                : profile == null ? "Not Installed" : "Installed";
        return new VersionChoice(available.id(), label,
                status,
                available, profile, preferredEntry);
    }

    private VersionChoice defaultVersionChoice(MinecraftLauncher.VersionCatalog catalog,
                                               List<MinecraftLauncher.InstalledVersion> installed) {
        MinecraftLauncher.InstalledVersion preferred = MinecraftLauncher.preferredInstalledVersion(installed);
        String defaultId = preferred == null ? catalog.latestReleaseId() : preferred.id();
        return versionPicker.getItems().stream().filter(choice -> choice.id().equals(defaultId))
                .filter(VersionChoice::preferredEntry).findFirst()
                .orElseGet(() -> versionPicker.getItems().stream().findFirst().orElse(null));
    }

    private void installSelectedVersion() {
        VersionChoice selected = versionPicker == null ? null : versionPicker.getValue();
        if (selected == null) {
            showError("Select a Minecraft version", new IOException("Choose a version before installing."));
            return;
        }
        if (selected.available() == null) {
            versionStatusLabel.setText(selected.id() + " is an installed mod-loader profile.");
            return;
        }
        downloadSelectedVersion(selected);
    }

    private void downloadSelectedVersion(VersionChoice selected) {
        if (selected.available() == null || !versionsDownloading.add(selected.id())) return;
        if (versionPicker != null && versionCatalog != null) {
            List<VersionChoice> downloadingChoices = buildVersionChoices(versionCatalog, installedVersions);
            versionPicker.getItems().setAll(downloadingChoices);
            downloadingChoices.stream().filter(choice -> choice.id().equals(selected.id()))
                    .filter(VersionChoice::preferredEntry).findFirst()
                    .ifPresent(choice -> versionPicker.getSelectionModel().select(choice));
        }
        if (versionStatusLabel != null) {
            versionStatusLabel.setText("Downloading " + selected.id()
                    + " into " + settings.minecraftDirectory().resolve("versions") + "...");
        }
        if (playButton != null) playButton.setDisable(true);
        if (singleplayerButton != null) singleplayerButton.setDisable(true);
        if (versionPicker != null) versionPicker.setDisable(true);
        if (installVersionButton != null) installVersionButton.setDisable(true);
        if (refreshVersionsButton != null) refreshVersionsButton.setDisable(true);
        appendLog("Downloading Minecraft " + selected.id() + " and preparing its libraries, assets, and Java runtime.");
        executor.execute(() -> {
            try {
                MinecraftLauncher.InstalledVersion installed = gameLauncher.installVersion(
                        settings.minecraftDirectory(), selected.available(),
                        line -> Platform.runLater(() -> appendLog(line)));
                List<MinecraftLauncher.InstalledVersion> profiles =
                        gameLauncher.findVersions(settings.minecraftDirectory());
                Platform.runLater(() -> {
                    installedVersions = profiles;
                    if (versionCatalog != null && versionPicker != null) {
                        versionPicker.getItems().setAll(buildVersionChoices(versionCatalog, profiles));
                        VersionChoice completed = versionPicker.getItems().stream()
                                .filter(choice -> choice.id().equals(installed.id()))
                                .filter(VersionChoice::preferredEntry).findFirst().orElse(null);
                        if (completed != null) versionPicker.getSelectionModel().select(completed);
                    }
                    versionStatusLabel.setText(installed.id()
                            + " · Installed · libraries, assets, and Java are ready.");
                    appendLog("Minecraft " + installed.id() + " installation completed.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    versionStatusLabel.setText(selected.id() + " · Not Installed · " + e.getMessage());
                    appendLog("Minecraft " + selected.id() + " installation failed: " + e.getMessage());
                    showError("Could not install Minecraft " + selected.id(), e);
                });
            } finally {
                versionsDownloading.remove(selected.id());
                Platform.runLater(() -> {
                    if (versionPicker != null) versionPicker.setDisable(false);
                    if (installVersionButton != null) installVersionButton.setDisable(false);
                    if (refreshVersionsButton != null) refreshVersionsButton.setDisable(false);
                    if (playButton != null) playButton.setDisable(false);
                    if (singleplayerButton != null) singleplayerButton.setDisable(false);
                    if (versionCatalog != null && versionPicker != null) {
                        VersionChoice current = versionPicker.getValue();
                        versionPicker.getItems().setAll(buildVersionChoices(versionCatalog, installedVersions));
                        if (current != null) versionPicker.getSelectionModel().select(
                                versionPicker.getItems().stream().filter(choice -> choice.id().equals(current.id()))
                                        .filter(VersionChoice::preferredEntry).findFirst().orElse(null));
                    }
                });
            }
        });
    }

    private void runConnectionTest() {
        if (settings.offlineMode()) {
            connectionResultLabel.setText("Offline mode is on, so nothing is contacted. Turn it off to run the connection test.");
            return;
        }
        connectionTestButton.setDisable(true);
        connectionTestButton.setText("TESTING...");
        connectionResultLabel.setText("Checking internet, DNS, TLS, and Mojang...");
        executor.execute(() -> {
            NetworkDiagnostics.Report report = NetworkDiagnostics.test(
                    line -> Platform.runLater(() -> appendLog(line)));
            Platform.runLater(() -> {
                connectionTestButton.setDisable(false);
                connectionTestButton.setText("TEST CONNECTION");
                connectionResultLabel.setText(report.passed()
                        ? "Connection checks passed." : "One or more connection checks failed.");
                Alert alert = new Alert(report.passed()
                        ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR);
                alert.initOwner(stage);
                alert.setTitle("V-Client Connection Test");
                alert.setHeaderText(report.passed()
                        ? "Network connection is ready" : "Connection test found a problem");
                alert.setContentText(report.format());
                alert.getDialogPane().setPrefWidth(640);
                alert.show();
                appendLog(report.format());
            });
        });
    }

    /** Starts the selected version; with a {@code server} it connects straight to that server. */
    private void launchGame(ServerEntry server) {
        boolean joinSmp = server != null;
        if (account == null) {
            showLogin();
            return;
        }
        VersionChoice choice = versionPicker == null ? null : versionPicker.getValue();
        if (choice == null && versionCatalog != null) {
            MinecraftLauncher.AvailableVersion latest = findAvailable(
                    versionCatalog, versionCatalog.latestReleaseId());
            if (latest != null) {
                downloadSelectedVersion(new VersionChoice(latest.id(), "Latest Release — " + latest.id(),
                        "Not Installed", latest, null, true));
                return;
            }
        }
        if (choice == null || choice.installed() == null) {
            if (versionStatusLabel != null) {
                versionStatusLabel.setText(choice == null
                        ? (versionCatalog == null
                        ? "No installed versions found. Turn off Offline mode once to download one, or import game files in Settings."
                        : "Refresh the Mojang version catalog before playing.")
                        : choice.id() + " is downloading. Wait for installation to finish.");
            }
            return;
        }
        MinecraftLauncher.InstalledVersion selected = choice.installed();
        PlayerAccount selectedAccount = account;
        reportEventAsync(joinSmp ? "join" : "play");
        recordPlayerAsync(selectedAccount);
        boolean offline = settings.offlineMode();
        Button launchingButton = joinSmp ? singleplayerButton : playButton;
        playButton.setDisable(true);
        playButton.setText(joinSmp ? "▶    PLAY" : "STARTING...");
        if (singleplayerButton != null) singleplayerButton.setDisable(true);
        if (joinSmp && launchingButton != null) {
            launchingButton.setText("CONNECTING...");
        }
        appendLog((joinSmp ? "Preparing a launch for " + server.name() + " (" + server.host() + ":" + server.port() + ") with "
                : "Starting Minecraft from V-Client's private folder ")
                + selected.displayName()
                + " for " + selectedAccount.name() + " (" + selectedAccount.type().label() + ")...");
        setPresence("Playing Minecraft", joinSmp ? server.name() : "");
        // With the VPN on, join through its local forwarding port instead of the server's own address.
        Integer vpnPort = joinSmp ? VpnManager.route(server.host(), server.port()) : null;
        String vpnHost = vpnPort != null ? "127.0.0.1" : joinSmp ? server.host() : "";
        int vpnJoinPort = vpnPort != null ? vpnPort : joinSmp ? server.port() : 0;
        if (vpnPort != null) appendLog("VPN is on: joining " + server.name() + " through the tunnel.");
        executor.execute(() -> {
            try {
                Consumer<String> output = line -> Platform.runLater(() -> appendLog(line));
                if (settings.replayBufferEnabled()) {
                    try {
                        if (GameOptionsFix.keepRunningWhenUnfocused(settings.minecraftDirectory())) {
                            output.accept("Turned off \"pause on lost focus\" so saving a clip cannot open the pause menu.");
                        }
                    } catch (IOException e) {
                        output.accept("Could not update options.txt: " + e.getMessage());
                    }
                }
                String joinHost = vpnHost;
                int joinPort = vpnJoinPort;
                boolean viaVpn = vpnPort != null;
                if (viaVpn && !ServerPinger.ping("127.0.0.1", vpnPort, false).online()) {
                    // The tunnel is up as a process but never completed its handshake (the network blocks it), so
                    // joining through it would just time out. Join directly instead.
                    output.accept("The VPN tunnel is not carrying traffic (the network is probably blocking it); joining "
                            + server.name() + " directly instead. Turn the VPN off in Settings if this keeps happening.");
                    viaVpn = false;
                    joinHost = server.host();
                    joinPort = server.port();
                }
                // A DNS filter that blocks the server's name is handled when the game starts: its secure-DNS helper looks
                // the name up itself (keeping the hostname, which some hosts need), or the launcher joins on the real address.
                MinecraftLauncher.InstalledVersion launchVersion = selected;
                if (offline) {
                    if (!joinSmp) {
                        gameLauncher.launchSingleplayerLocal(settings.minecraftDirectory(), launchVersion,
                                settings.memoryGb(), selectedAccount, output);
                    } else {
                        gameLauncher.launchToServerLocally(settings.minecraftDirectory(), launchVersion,
                                settings.memoryGb(), selectedAccount, joinHost, joinPort, output);
                    }
                } else if (!joinSmp) {
                    gameLauncher.launchSingleplayer(settings.minecraftDirectory(), launchVersion,
                            settings.memoryGb(), selectedAccount, output);
                } else {
                    gameLauncher.launchToServer(settings.minecraftDirectory(), launchVersion, settings.memoryGb(),
                            selectedAccount, joinHost, joinPort, output);
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendLog("Launch failed: " + e.getMessage());
                    showLaunchFailure(e, settings.minecraftDirectory());
                });
            } finally {
                setPresence("In the launcher", "");
                Platform.runLater(() -> {
                    if (playButton != null) {
                        playButton.setDisable(false);
                        playButton.setText("▶    PLAY");
                    }
                    if (singleplayerButton != null) {
                        singleplayerButton.setDisable(false);
                        singleplayerButton.setText("JOIN OUR SMP");
                    }
                });
            }
        });
    }

    private record ElyCredentials(String username, char[] password) {}

    private void signInMicrosoft() {
        MicrosoftAuthService service = authService;
        CompletableFuture<PlayerAccount> signIn = service.signIn(executor, device -> Platform.runLater(() -> {
            if (status != null) status.setText("Enter the sign-in code on " + device.url() + ".");
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(device.url()));
            } catch (IOException e) {
                if (status != null) status.setText("Open " + device.url() + " and enter code " + device.code());
            }
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(stage);
            alert.setTitle("Microsoft sign-in");
            alert.setHeaderText("Sign in to your Premium account");
            alert.setContentText("Open " + device.url() + " and enter this code:\n\n" + device.code());
            alert.show();
        }));
        showSignInProgress("Waiting for Microsoft account authorization...");
        signIn.whenComplete((signedIn, error) -> Platform.runLater(() -> {
            if (error != null) {
                showSignInError("Microsoft sign-in failed", error);
                return;
            }
            addAccount(signedIn);
        }));
    }

    private void signInElyBy() {
        Dialog<ElyCredentials> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Sign in to Ely.by");
        dialog.setHeaderText("Use your Ely.by account credentials.");
        ButtonType loginType = new ButtonType("SIGN IN", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(loginType, ButtonType.CANCEL);
        TextField username = new TextField();
        username.setPromptText("Ely.by username or email");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(12);
        fields.add(label("Username", 12, MUTED), 0, 0);
        fields.add(username, 1, 0);
        fields.add(label("Password", 12, MUTED), 0, 1);
        fields.add(password, 1, 1);
        Label note = label("Your password is used for sign-in and is never saved.", 11, MUTED);
        fields.add(note, 0, 2, 2, 1);
        dialog.getDialogPane().setContent(fields);
        dialog.setResultConverter(button -> button == loginType
                ? new ElyCredentials(username.getText(), password.getText().toCharArray()) : null);
        ElyCredentials credentials = dialog.showAndWait().orElse(null);
        if (credentials == null) return;
        showSignInProgress("Contacting Ely.by to verify your account (12-second timeout)...");
        attemptElyByLogin(credentials.username(), credentials.password(), false);
    }

    private void attemptElyByLogin(String username, char[] password, boolean retriedWithTotp) {
        elyByAuthService.signIn(username, password, executor, message -> Platform.runLater(() -> {
            if (status != null) status.setText(message);
            appendLog(message);
        })).whenComplete((signedIn, error) ->
                Platform.runLater(() -> {
                    if (error == null) {
                        java.util.Arrays.fill(password, '\0');
                        addAccount(signedIn);
                        return;
                    }
                    Throwable cause = unwrap(error);
                    String message = cause.getMessage() == null ? "" : cause.getMessage();
                    if (!retriedWithTotp && message.toLowerCase(java.util.Locale.ROOT).contains("two factor auth")) {
                        TextInputDialog totp = new TextInputDialog();
                        totp.initOwner(stage);
                        totp.setTitle("Ely.by two-factor verification");
                        totp.setHeaderText("This Ely.by account requires an authenticator code.");
                        totp.setContentText("Six-digit verification code:");
                        String code = totp.showAndWait().orElse("");
                        if (code.matches("\\d{6,8}")) {
                            char[] totpPassword = java.util.Arrays.copyOf(password,
                                    password.length + code.length() + 1);
                            totpPassword[password.length] = ':';
                            code.getChars(0, code.length(), totpPassword, password.length + 1);
                            java.util.Arrays.fill(password, '\0');
                            attemptElyByLogin(username, totpPassword, true);
                        } else {
                            java.util.Arrays.fill(password, '\0');
                            showError("Invalid verification code",
                                    new IOException("Enter the current 6–8 digit authenticator code."));
                        }
                    } else {
                        java.util.Arrays.fill(password, '\0');
                        showSignInError("Ely.by sign-in failed", cause);
                    }
                }));
    }

    private void addOfflineAccount() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(stage);
        dialog.setTitle("Add offline account");
        dialog.setHeaderText("Choose a Minecraft username for offline play.");
        dialog.setContentText("Username (3–16 letters, numbers, or underscores):");
        dialog.showAndWait().ifPresent(username -> {
            try {
                addAccount(PlayerAccount.offline(username));
            } catch (IllegalArgumentException e) {
                showError("Invalid Minecraft username", e);
            }
        });
    }

    private void addAccount(PlayerAccount added) {
        if (accountPersistenceInProgress || protectedAccountSavePending) {
            showError("Account change in progress",
                    new IOException("Wait for the current account update to finish. The account is already "
                            + "signed in and available for this session."));
            return;
        }
        List<PlayerAccount> updated = new ArrayList<>(accounts);
        int existing = -1;
        for (int index = 0; index < updated.size(); index++) {
            if (updated.get(index).id().equals(added.id())) {
                existing = index;
                break;
            }
        }
        if (existing >= 0) updated.set(existing, added);
        else updated.add(added);
        if (added.type() != PlayerAccount.Type.OFFLINE) {
            persistNewProtectedAccount(updated, added);
            return;
        }
        persistAccounts(updated, () -> {
            account = added;
            settings.setLastAccountId(added.id());
            saveSettings();
            updateAccountControls();
            showHome();
            if (added.type() == PlayerAccount.Type.ELY_BY) loadElyByTextures(added);
        }, "Could not save account", added.type() == PlayerAccount.Type.OFFLINE);
    }

    private void persistNewProtectedAccount(List<PlayerAccount> updated, PlayerAccount added) {
        accountPersistenceInProgress = true;
        protectedAccountSavePending = true;
        accounts.clear();
        accounts.addAll(updated);
        account = added;
        settings.setLastAccountId(added.id());
        saveSettings();
        updateAccountControls();
        showHome();
        appendLog("Signed in as " + added.name() + ". Saving account credentials securely in the background...");
        AtomicBoolean timeoutReported = new AtomicBoolean();
        CompletableFuture<Void> save = CompletableFuture.runAsync(() -> {
            try {
                accountStore.saveProtectedAccounts(updated, message ->
                        Platform.runLater(() -> appendLog(message)));
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
        CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(() -> {
            if (!save.isDone() && timeoutReported.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    accountPersistenceInProgress = false;
                    String message = "Windows secure storage has not responded after 15 seconds. "
                            + "Sign-in succeeded and the account works for this session, but saving "
                            + "has not been confirmed. Do not enter your password again.";
                    appendLog(message);
                    if (status != null) status.setText(message);
                });
            }
        });
        save.whenComplete((ignored, error) -> Platform.runLater(() -> {
            accountPersistenceInProgress = false;
            protectedAccountSavePending = false;
            if (error != null) {
                String message = "Sign-in succeeded, but the account could not be saved securely: "
                        + unwrap(error).getMessage();
                appendLog(message);
                showError("Account is signed in but not saved", new IOException(message, unwrap(error)));
                return;
            }
            appendLog(timeoutReported.get()
                    ? "Windows secure storage responded late; the account is now saved."
                    : "Account saved securely.");
            if (added.type() == PlayerAccount.Type.ELY_BY) loadElyByTextures(added);
        }));
    }

    private void loadElyByTextures(PlayerAccount signedIn) {
        appendLog("Ely.by profile: loading skin and cape for " + signedIn.name() + " in the background...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return elyByAuthService.loadTextures(signedIn,
                        line -> Platform.runLater(() -> appendLog(line)));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor).whenComplete((profile, error) -> Platform.runLater(() -> {
            if (error != null) {
                appendLog("Ely.by is signed in, but its optional skin/cape lookup failed: "
                        + unwrap(error).getMessage());
                return;
            }
            if (profile.skinUrl().isBlank() && profile.capeUrl().isBlank()) {
                appendLog("Ely.by is signed in. No skin or cape is currently equipped.");
                return;
            }
            int savedIndex = -1;
            for (int index = 0; index < accounts.size(); index++) {
                if (accounts.get(index).id().equals(profile.id())) {
                    savedIndex = index;
                    break;
                }
            }
            if (savedIndex < 0) return;
            PlayerAccount saved = accounts.get(savedIndex);
            if (saved.skinUrl().equals(profile.skinUrl()) && saved.capeUrl().equals(profile.capeUrl())) {
                appendLog("Ely.by skin is up to date.");
                if (account != null && account.id().equals(profile.id()) && avatar != null) loadAvatar(account, avatar);
                return;
            }
            if (accountPersistenceInProgress) {
                appendLog("The Ely.by skin/cape loaded, but another account update is in progress; "
                        + "the profile preview will update next time you sign in.");
                return;
            }
            List<PlayerAccount> updated = new ArrayList<>(accounts);
            updated.set(savedIndex, profile);
            persistAccounts(updated, () -> {
                if (account != null && account.id().equals(profile.id())) {
                    account = profile;
                    if (avatar != null) loadAvatar(profile, avatar);
                }
                updateAccountControls();
                appendLog("Ely.by skin and cape profile loaded.");
            }, "Could not save Ely.by skin and cape data", false);
        }));
    }

    private void selectAccount(PlayerAccount selected) {
        account = selected;
        settings.setLastAccountId(selected.id());
        saveSettings();
        updateAccountControls();
        if (headerAccountLabel != null) {
            headerAccountLabel.setText(selected.name().toUpperCase()
                    + "  ·  " + selected.type().label().toUpperCase());
            headerAccountLabel.setTextFill(Color.web(ACCENT));
        }
        if (avatar != null) loadAvatar(selected, avatar);
        if (status != null) status.setText(selected.name() + " selected. Type: " + selected.type().label() + ".");
        if (selected.type() == PlayerAccount.Type.ELY_BY && !settings.offlineMode()) loadElyByTextures(selected);
    }

    private void updateAccountControls() {
        if (accountPicker != null) {
            accountPicker.getItems().setAll(accounts);
            accountPicker.setValue(account);
        }
        if (accountLabel != null) {
            accountLabel.setText(account == null ? "Choose an account." : account.name()
                    + "  ·  " + account.type().label());
        }
    }

    private void removeSelectedAccount() {
        if (accountPersistenceInProgress || protectedAccountSavePending) {
            showError("Account change in progress",
                    new IOException("Wait for the current account update to finish. The account is already "
                            + "signed in and available for this session."));
            return;
        }
        PlayerAccount selected = accountPicker == null ? null : accountPicker.getValue();
        if (selected == null) {
            showError("No account selected", new IOException("Select the saved account you want to remove."));
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(stage);
        confirmation.setTitle("Remove account");
        confirmation.setHeaderText("Remove " + selected.name() + "?");
        confirmation.setContentText("The locally saved account token will be deleted.");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        List<PlayerAccount> updated = accounts.stream()
                .filter(saved -> !saved.id().equals(selected.id())).toList();
        persistAccounts(updated, () -> {
            if (account != null && account.id().equals(selected.id())) {
                account = accounts.isEmpty() ? null : accounts.getFirst();
            }
            settings.setLastAccountId(account == null ? "" : account.id());
            saveSettings();
            updateAccountControls();
            if (account == null) showLogin();
            else {
                if (headerAccountLabel != null) {
                    headerAccountLabel.setText(account.name().toUpperCase()
                            + "  ·  " + account.type().label().toUpperCase());
                }
                if (avatar != null) loadAvatar(account, avatar);
            }
        }, "Could not remove saved account", selected.type() == PlayerAccount.Type.OFFLINE);
    }

    private PlayerAccount refreshAccount(PlayerAccount selected) throws IOException, InterruptedException {
        return switch (selected.type()) {
            case MICROSOFT -> authService.refresh(selected);
            case ELY_BY -> elyByAuthService.refresh(selected);
            case OFFLINE -> selected;
        };
    }

    private void updateRefreshedAccount(String id, PlayerAccount refreshed) {
        if (protectedAccountSavePending) {
            appendLog("The account remains signed in for this session. Token refresh will not be saved "
                    + "until Windows secure storage responds.");
            return;
        }
        if (accountPersistenceInProgress) {
            if (status != null) status.setText(
                    "Could not save the refreshed sign-in because another account change is in progress.");
            return;
        }
        int savedIndex = -1;
        for (int index = 0; index < accounts.size(); index++) {
            if (accounts.get(index).id().equals(id)) {
                savedIndex = index;
                break;
            }
        }
        if (savedIndex < 0) return;
        List<PlayerAccount> updated = new ArrayList<>(accounts);
        updated.set(savedIndex, refreshed);
        persistAccounts(updated, () -> {
            if (account != null && account.id().equals(id)) {
                account = refreshed;
                if (headerAccountLabel != null) {
                    headerAccountLabel.setText(refreshed.name().toUpperCase()
                            + "  ·  " + refreshed.type().label().toUpperCase());
                }
                if (avatar != null) loadAvatar(refreshed, avatar);
                if (accountLabel != null) accountLabel.setText(refreshed.name()
                        + "  ·  " + refreshed.type().label());
            }
            updateAccountControls();
        }, "Could not securely save refreshed sign-in", false);
    }

    private void persistAccounts(List<PlayerAccount> updated, Runnable onSaved, String failureTitle,
                                 boolean offlineOnly) {
        if (protectedAccountSavePending && !offlineOnly) {
            appendLog("Account storage is still waiting for Windows secure storage; no second write was started.");
            return;
        }
        accountPersistenceInProgress = true;
        if (status != null) {
            status.setText(offlineOnly ? "Saving offline account locally..." : "Saving account securely...");
        }
        appendLog(offlineOnly ? "Saving offline account locally..." : "Saving account securely...");
        executor.execute(() -> {
            try {
                if (offlineOnly) accountStore.saveOfflineAccounts(updated);
                else accountStore.saveProtectedAccounts(updated,
                        message -> Platform.runLater(() -> {
                            if (status != null) status.setText(message);
                            appendLog(message);
                        }));
                Platform.runLater(() -> {
                    accountPersistenceInProgress = false;
                    accounts.clear();
                    accounts.addAll(updated);
                    appendLog("Account data saved.");
                    if (status != null) status.setText("Account saved. Opening launcher...");
                    try {
                        onSaved.run();
                    } catch (RuntimeException e) {
                        if (status != null) status.setText("Account saved, but the launcher could not finish updating.");
                        showError("Account saved, but launcher update failed", e);
                    }
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    accountPersistenceInProgress = false;
                    if (status != null) status.setText("Could not save account.");
                    showError(failureTitle, e);
                });
            }
        });
    }

    private void showSignInProgress(String message) {
        if (status != null) status.setText(message);
        else {
            TextInputDialog progress = new TextInputDialog();
            progress.initOwner(stage);
            progress.setTitle("V-Client account sign-in");
            progress.setHeaderText(message);
            progress.setContentText("Complete the sign-in steps; this window updates after authentication.");
            progress.show();
        }
    }

    private void showSignInError(String title, Throwable error) {
        Throwable cause = unwrap(error);
        if (status != null) status.setText(title + ": " + cause.getMessage());
        showError(title, cause);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null
                && (cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static ListCell<PlayerAccount> accountCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(PlayerAccount saved, boolean empty) {
                super.updateItem(saved, empty);
                setText(empty || saved == null ? null : saved.name() + "   ·   " + saved.type().label());
                setTextFill(Color.web("#eaf0fb"));
            }
        };
    }

    private void loadPreviews(PlayerAccount selected, ImageView skin, ImageView cape, Label message) {
        loadPreviews(selected, skin, cape, message,
                () -> accountPicker == null ? null : accountPicker.getValue());
    }

    private void loadPreviews(PlayerAccount selected, ImageView skin, ImageView cape, Label message,
                              java.util.function.Supplier<PlayerAccount> currentSelection) {
        if (selected == null) {
            skin.setImage(null);
            cape.setImage(null);
            message.setText("Add or select a saved account.");
            return;
        }
        message.setText(selected.name() + "  ·  " + selected.type().label()
                + (selected.type() == PlayerAccount.Type.OFFLINE ? "  ·  Local offline UUID" : ""));
        skin.setImage(null);
        cape.setImage(null);
        String skinUrl = selected.skinUrl();
        String capeUrl = selected.capeUrl();
        executor.execute(() -> {
            Image skinImage = downloadImage(skinUrl);
            Image capeImage = downloadImage(capeUrl);
            Platform.runLater(() -> {
                PlayerAccount current = currentSelection.get();
                if (current == null || !current.id().equals(selected.id())) return;
                skin.setImage(skinImage);
                cape.setImage(capeImage);
                message.setText((skinImage == null ? "No saved skin image  ·  " : "")
                        + selected.name() + "  ·  " + selected.type().label()
                        + (capeImage == null ? "  ·  No cape equipped" : "  ·  Cape loaded"));
            });
        });
    }

    private void loadAvatar(PlayerAccount selected, ImageView target) {
        if (selected.skinUrl().isBlank()) {
            target.setImage(createIcon());
            return;
        }
        executor.execute(() -> {
            Image source = downloadImage(selected.skinUrl());
            if (source == null) return;
            javafx.scene.image.PixelReader pixels = source.getPixelReader();
            int pixelScale = Math.max(1, (int) source.getWidth() / 64);
            int cropSize = 8 * pixelScale;
            if (pixels == null || source.getHeight() < 16 * pixelScale
                    || source.getWidth() < 16 * pixelScale) return;
            Image head = new WritableImage(pixels, 8 * pixelScale, 8 * pixelScale,
                    cropSize, cropSize);
            Platform.runLater(() -> {
                if (account != null && account.id().equals(selected.id()) && target == avatar) {
                    target.setImage(head);
                }
            });
        });
    }

    private final SkinImageCache skinImageCache = new SkinImageCache(SkinImageCache.defaultDirectory(),
            line -> Platform.runLater(() -> appendLog(line)));

    private Image downloadImage(String address) {
        if (address == null || address.isBlank()) return null;
        try {
            URI uri = URI.create(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
            byte[] bytes = skinImageCache.load(address, this::fetchImageBytes, !settings.offlineMode());
            return bytes == null ? null : new Image(new ByteArrayInputStream(bytes), 0, 0, true, true);
        } catch (IllegalArgumentException | IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private byte[] fetchImageBytes(String address) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(20))
                .header("Accept", "image/png")
                .GET().build();
        HttpResponse<byte[]> response = IMAGE_HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || response.body().length > SkinImageCache.MAX_BYTES) return null;
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return null;
        return response.body();
    }

    private void saveSettings() {
        try {
            settings.save();
            if (status != null) status.setText("Settings saved.");
        } catch (IOException e) {
            if (status != null) status.setText("Could not save settings: " + e.getMessage());
            showError("Could not save settings", e);
        }
    }

    private void appendLogLater(String line) {
        Platform.runLater(() -> appendLog(line));
    }

    private void appendLog(String line) {
        logLines.add(line);
        int length = logLines.stream().mapToInt(String::length).sum();
        while (length > 40_000 && !logLines.isEmpty()) {
            length -= logLines.removeFirst().length();
        }
        if (log != null) {
            log.appendText(line + System.lineSeparator());
            int excess = log.getText().length() - 40_000;
            if (excess > 0) log.deleteText(0, excess);
        }
        if (consoleLog != null) {
            consoleLog.appendText(line + System.lineSeparator());
            int excess = consoleLog.getText().length() - 40_000;
            if (excess > 0) consoleLog.deleteText(0, excess);
        }
    }

    private void showError(String title, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("V-Client");
        alert.setHeaderText(title);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        alert.show();
    }

    private void showLaunchFailure(Throwable error, Path gameDirectory) {
        StringBuilder details = new StringBuilder("Launch failure details:\n");
        Throwable cause = error;
        int causeDepth = 0;
        while (cause != null && causeDepth++ < 12) {
            details.append(causeDepth == 1 ? "- " : "Caused by: ")
                    .append(cause.getClass().getSimpleName())
                    .append(": ")
                    .append(cause.getMessage() == null ? "(no message)" : cause.getMessage())
                    .append(System.lineSeparator());
            cause = cause.getCause();
        }
        Path launchLog = gameDirectory.resolve("logs").resolve("vclient-launch.log");
        details.append("\nDetailed V-Client launch log:\n").append(launchLog)
                .append(System.lineSeparator());
        appendDiagnosticFile(details, launchLog, "V-Client launch log", 18_000);
        appendLatestDiagnosticFile(details, gameDirectory.resolve("crash-reports"),
                "crash-", ".txt", "Minecraft crash report", 20_000);
        appendDiagnosticFile(details, gameDirectory.resolve("logs").resolve("latest.log"),
                "Minecraft latest.log", 12_000);

        TextArea diagnosticText = new TextArea(details.toString());
        diagnosticText.setEditable(false);
        diagnosticText.setWrapText(true);
        diagnosticText.setPrefColumnCount(78);
        diagnosticText.setPrefRowCount(24);
        diagnosticText.setStyle("-fx-control-inner-background: rgba(9, 10, 28, 0.96); "
                + "-fx-text-fill: #e8e8f5; -fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("V-Client launch diagnostics");
        alert.setHeaderText("Minecraft could not finish launching");
        alert.getDialogPane().setContent(diagnosticText);
        alert.getDialogPane().setPrefSize(760, 620);
        alert.show();
    }

    private static void appendDiagnosticFile(StringBuilder details, Path file, String label,
                                             int maximumCharacters) {
        if (!Files.isRegularFile(file)) return;
        details.append("\n--- ").append(label).append(": ").append(file).append(" ---\n");
        try {
            details.append(MinecraftLauncher.readTail(file, maximumCharacters))
                    .append(System.lineSeparator());
        } catch (IOException e) {
            details.append("Could not read diagnostic file: ").append(e.getMessage())
                    .append(System.lineSeparator());
        }
    }

    private static void appendLatestDiagnosticFile(StringBuilder details, Path directory,
                                                  String prefix, String suffix, String label,
                                                  int maximumCharacters) {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            Path latest = files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().startsWith(prefix)
                            && file.getFileName().toString().endsWith(suffix))
                    .max((left, right) -> {
                        try {
                            return Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right));
                        } catch (IOException e) {
                            return left.getFileName().compareTo(right.getFileName());
                        }
                    }).orElse(null);
            if (latest != null) appendDiagnosticFile(details, latest, label, maximumCharacters);
        } catch (IOException e) {
            details.append("\nCould not list ").append(label).append(" files in ")
                    .append(directory).append(": ").append(e.getMessage()).append(System.lineSeparator());
        }
    }

    private static ListCell<VersionChoice> versionChoiceCell() {
        return new ListCell<>() {
            {
                selectedProperty().addListener((observable, wasSelected, isSelected) -> updateCellStyle());
                hoverProperty().addListener((observable, wasHovered, isHovered) -> updateCellStyle());
            }

            @Override
            protected void updateItem(VersionChoice choice, boolean empty) {
                super.updateItem(choice, empty);
                setText(empty || choice == null ? null : choice.toString());
                updateCellStyle();
            }

            private void updateCellStyle() {
                String background = isSelected() ? "#806c2f" : isHover() ? "#292642" : "#14142d";
                setStyle("-fx-background-color: " + background + "; -fx-padding: 7 9 7 9;");
                setTextFill(Color.web("#f3f0ff"));
            }
        };
    }

    private static VBox card() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color: rgba(15, 16, 39, 0.84); -fx-background-radius: 12; -fx-border-color: rgba(170, 151, 207, 0.24); -fx-border-radius: 12;");
        return box;
    }

    private static Label label(String text, int size, String color) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: " + size + "px; -fx-text-fill: " + color + ";");
        return label;
    }

    private static Button button(String text, String color, int size) {
        Button button = new Button(text);
        String foreground = color.equalsIgnoreCase(ACCENT) ? "#171326" : "#f3f0ff";
        button.setStyle("-fx-background-color: " + color + "; -fx-text-fill: " + foreground + "; -fx-font-size: " + size
                + "px; -fx-font-weight: bold; -fx-background-radius: 9; -fx-padding: 12 18 12 18; -fx-cursor: hand;");
        return button;
    }

    private static String fieldStyle() {
        return "-fx-background-color: rgba(9, 10, 28, 0.88); -fx-text-fill: #e6e1f4; -fx-border-color: #48415f; -fx-background-radius: 8; -fx-border-radius: 8; -fx-padding: 12;";
    }

    private static Region dot(String color) {
        Region dot = new Region();
        dot.setMinSize(8, 8);
        dot.setMaxSize(8, 8);
        dot.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 50%;");
        return dot;
    }

    private static Canvas logo(double size) {
        Canvas canvas = new Canvas(size, size);
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setFill(Color.web(ACCENT));
        graphics.fillRoundRect(0, 0, size, size, size * 0.27, size * 0.27);
        graphics.setFill(Color.web("#171326"));
        graphics.fillPolygon(new double[]{size * .57, size * .35, size * .35, size * .57},
                new double[]{size * .19, size * .38, size * .57, size * .45}, 4);
        graphics.setFill(Color.web("#fff0bd"));
        graphics.fillPolygon(new double[]{size * .57, size * .35, size * .57},
                new double[]{size * .54, size * .72, size * .68}, 3);
        return canvas;
    }

    private static javafx.scene.image.Image createIcon() {
        Canvas canvas = logo(64);
        return canvas.snapshot(null, null);
    }

    private record BackgroundStar(double x, double y, double size, double phase, double drift) {}

    private static List<BackgroundStar> createBackgroundStars() {
        Random random = new Random(731921);
        List<BackgroundStar> stars = new ArrayList<>(180);
        for (int index = 0; index < 180; index++) {
            stars.add(new BackgroundStar(random.nextDouble(), random.nextDouble(),
                    1 + random.nextInt(3), random.nextDouble() * Math.PI * 2,
                    0.0003 + random.nextDouble() * 0.0011));
        }
        return List.copyOf(stars);
    }

    private void drawNightSky(Canvas canvas, double time) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) return;
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setFill(Color.web(BACKGROUND));
        graphics.fillRect(0, 0, width, height);

        double moonX = width * 0.76;
        double moonY = height * 0.31;
        double moonRadius = Math.max(38, Math.min(width * 0.09, height * 0.15));
        for (int layer = 12; layer >= 1; layer--) {
            double glowRadius = moonRadius * (1.0 + layer * 0.17);
            double opacity = 0.008 + (12 - layer) * 0.003;
            graphics.setFill(Color.rgb(Theme.glow()[0], Theme.glow()[1], Theme.glow()[2], opacity));
            graphics.fillOval(moonX - glowRadius, moonY - glowRadius,
                    glowRadius * 2, glowRadius * 2);
        }
        graphics.setFill(Color.rgb(230, 218, 168, 0.95));
        graphics.fillOval(moonX - moonRadius, moonY - moonRadius,
                moonRadius * 2, moonRadius * 2);
        graphics.setFill(Color.rgb(15, 15, 40));
        graphics.fillOval(moonX - moonRadius * 0.18, moonY - moonRadius * 1.10,
                moonRadius * 1.86, moonRadius * 1.86);

        double dotSpacing = Math.max(7, moonRadius * 0.17);
        for (double y = moonY - moonRadius * 2.2; y <= moonY + moonRadius * 2.2; y += dotSpacing) {
            for (double x = moonX - moonRadius * 2.2; x <= moonX + moonRadius * 2.2; x += dotSpacing) {
                double distance = Math.hypot(x - moonX, y - moonY) / moonRadius;
                if (distance < 1.18 || distance > 2.15) continue;
                double opacity = (2.15 - distance) * 0.14;
                graphics.setFill(Color.rgb(Math.min(255, Theme.glow()[0] + 42), Math.min(255, Theme.glow()[1] + 44), Math.min(255, Theme.glow()[2] + 28), opacity));
                graphics.fillRect(x, y, Math.max(2, dotSpacing * 0.42), Math.max(2, dotSpacing * 0.42));
            }
        }

        for (BackgroundStar star : backgroundStars) {
            double x = (star.x() + time * star.drift()) % 1.0 * width;
            double y = star.y() * height;
            double twinkle = 0.42 + 0.58 * (0.5 + 0.5 * Math.sin(time * 1.8 + star.phase()));
            graphics.setFill(Color.rgb(234, 225, 190, twinkle));
            graphics.fillRect(x, y, star.size(), star.size());
        }

        double cometPhase = (time % 13.0) / 13.0;
        if (cometPhase < 0.09) {
            double x = width * (0.23 + cometPhase * 5.2);
            double y = height * (0.13 + cometPhase * 1.7);
            graphics.setStroke(Color.rgb(235, 220, 176, (0.09 - cometPhase) * 8));
            graphics.setLineWidth(2);
            graphics.strokeLine(x - 42, y - 15, x, y);
        }

        graphics.setFill(Color.rgb(12, 13, 34, 0.72));
        graphics.fillPolygon(new double[]{0, 0, width * 0.15, width * 0.27, width * 0.42,
                        width * 0.55, width * 0.68, width * 0.84, width, width},
                new double[]{height, height * 0.82, height * 0.77, height * 0.86, height * 0.79,
                        height * 0.88, height * 0.78, height * 0.85, height * 0.76, height}, 10);
        graphics.setFill(Color.rgb(8, 10, 26, 0.78));
        graphics.fillPolygon(new double[]{0, width * 0.12, width * 0.24, width * 0.38,
                        width * 0.5, width * 0.63, width * 0.76, width * 0.9, width, width},
                new double[]{height, height * 0.9, height * 0.94, height * 0.85,
                        height * 0.94, height * 0.86, height * 0.93, height * 0.86, height * 0.92, height}, 10);
    }

    @Override
    public void stop() {
        if (backgroundAnimation != null) backgroundAnimation.stop();
        VpnManager.disconnect();
        executor.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
