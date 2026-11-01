package com.vclient;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A tabbed web browser whose DNS lookups go over HTTPS (DNS-over-HTTPS) instead of the PC's own resolver. There is
 * one browser for the whole launcher, so its tabs survive moving between pages, and it can be popped out into a
 * full-screen window.
 */
final class BrowserPage {
    private static final String HOME = "https://duckduckgo.com/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";
    private static final String BUTTON = "-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: #f5f7fd;"
            + " -fx-background-radius: 9; -fx-font-size: 14px; -fx-font-family: 'Segoe UI Symbol', 'Segoe UI';"
            + " -fx-min-width: 36; -fx-min-height: 34; -fx-padding: 0; -fx-cursor: hand;";

    private static boolean proxyInstalled;
    private static BrowserPage shared;

    private final class BrowserTab {
        final WebView view = new WebView();
        final WebEngine engine = view.getEngine();
        final Label title = new Label("New tab");
        final Button close = new Button("✕");
        final HBox header = new HBox(6, title, close);

        BrowserTab(String url) {
            engine.setUserAgent(USER_AGENT);
            engine.setCreatePopupHandler(features -> open(null).engine);
            engine.titleProperty().addListener((o, old, text) -> title.setText(text == null || text.isBlank()
                    ? hostOf(engine.getLocation()) : text));
            engine.locationProperty().addListener((o, old, location) -> {
                if (selected == this && location != null) address.setText(location);
                if (engine.getTitle() == null) title.setText(hostOf(location));
            });
            title.setMaxWidth(Double.MAX_VALUE);
            title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            HBox.setHgrow(title, Priority.ALWAYS);
            close.setStyle("-fx-background-color: transparent; -fx-text-fill: #a9a9cc; -fx-font-size: 10px;"
                    + " -fx-padding: 2 5 2 5; -fx-cursor: hand; -fx-background-radius: 6;");
            close.setOnAction(event -> closeTab(this));
            header.setAlignment(Pos.CENTER_LEFT);
            header.setMinWidth(90);
            header.setPrefWidth(190);
            header.setMaxWidth(220);
            header.setPadding(new Insets(7, 8, 7, 12));
            header.setOnMouseClicked(event -> select(this));
            if (url != null) load(url);
        }

        void load(String url) {
            engine.load(url);
        }
    }

    private final List<BrowserTab> tabs = new ArrayList<>();
    private final HBox tabStrip = new HBox(4);
    private final StackPane content = new StackPane();
    private final TextField address = new TextField();
    private final Label status = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Button fullscreen = new Button("⛶");
    private final Button vpn = new Button();
    private final Button external = new Button("EDGE ↗");
    private boolean vpnBusy;
    private final VBox root = new VBox(8);
    private BrowserTab selected;
    private FeatureHost host;
    private Stage fullscreenStage;

    private BrowserPage() {
        Button newTab = button("+", "New tab (Ctrl+T)", () -> open(HOME));
        newTab.setStyle(BUTTON + " -fx-font-size: 17px;");
        tabStrip.setAlignment(Pos.CENTER_LEFT);

        Button back = button("◀", "Back", () -> step(-1));
        Button forward = button("▶", "Forward", () -> step(1));
        Button reload = button("⟳", "Reload", () -> {
            if (selected != null) selected.engine.reload();
        });
        fullscreen.setStyle(BUTTON);
        fullscreen.setTooltip(new javafx.scene.control.Tooltip("Full screen (F11)"));
        fullscreen.setOnAction(event -> toggleFullscreen());

        external.setStyle(BUTTON + " -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 0 12 0 12;"
                + " -fx-background-color: " + Theme.accentRgba(0.30) + ";");
        external.setTooltip(new javafx.scene.control.Tooltip(
                "Open this page in Edge/Chrome through the same secure DNS. Use it for Discord and other heavy sites."));
        external.setOnAction(event -> openExternal());

        vpn.setTooltip(new javafx.scene.control.Tooltip(
                "Send the browser through V-Client's free VPN (Cloudflare WARP), DNS included"));
        vpn.setOnAction(event -> toggleVpn());
        refreshVpn();

        address.setPromptText("Search or type an address");
        address.setStyle("-fx-background-color: rgba(255,255,255,0.09); -fx-text-fill: #f5f7fd;"
                + " -fx-prompt-text-fill: #8f90b3; -fx-background-radius: 17; -fx-padding: 8 16 8 16;"
                + " -fx-font-size: 13px;");
        address.setOnAction(event -> go(address.getText()));
        address.focusedProperty().addListener((o, old, focused) -> {
            if (focused) Platform.runLater(address::selectAll);
        });
        HBox.setHgrow(address, Priority.ALWAYS);

        HBox bar = new HBox(6, back, forward, reload, address, external, vpn, fullscreen);
        bar.setAlignment(Pos.CENTER);

        HBox tabRow = new HBox(6, tabStrip, newTab);
        tabRow.setAlignment(Pos.CENTER_LEFT);

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(3);
        progress.setMinHeight(3);
        progress.setStyle("-fx-accent: " + ModsUi.ACCENT + "; -fx-control-inner-background: transparent;"
                + " -fx-box-border: transparent;");
        status.setStyle("-fx-text-fill: " + ModsUi.MUTED + "; -fx-font-size: 11px;");

        content.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 10;");
        VBox.setVgrow(content, Priority.ALWAYS);
        root.getChildren().addAll(tabRow, bar, progress, content, status);

        root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (new KeyCodeCombination(KeyCode.T, KeyCombination.CONTROL_DOWN).match(event)) {
                open(HOME);
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.W, KeyCombination.CONTROL_DOWN).match(event)) {
                if (selected != null) closeTab(selected);
                event.consume();
            } else if (event.getCode() == KeyCode.F11) {
                toggleFullscreen();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN).match(event)) {
                address.requestFocus();
                event.consume();
            }
        });
        open(HOME);
    }

    static VBox build(FeatureHost host) {
        try {
            installProxy(DohProxy.shared().port());
        } catch (IOException e) {
            return new VBox(ModsUi.warningBox("Secure DNS is unavailable",
                    "The browser could not start its DNS-over-HTTPS proxy: " + e.getMessage(), null));
        }
        if (shared == null) shared = new BrowserPage();
        BrowserPage page = shared;
        page.host = host;
        page.refreshVpn();
        // Bring the browser back from its own window, then size it to the launcher page.
        if (page.fullscreenStage != null) page.fullscreenStage.close();
        page.root.setPrefHeight(Math.max(480, host.stage().getHeight() - 210));
        page.root.minHeightProperty().bind(host.stage().heightProperty().subtract(210));
        return page.root;
    }

    private static Button button(String text, String tip, Runnable action) {
        Button button = new Button(text);
        button.setStyle(BUTTON);
        button.setTooltip(new javafx.scene.control.Tooltip(tip));
        button.setOnAction(event -> action.run());
        return button;
    }

    // ---- tabs ----------------------------------------------------------------------------------------

    private BrowserTab open(String url) {
        BrowserTab tab = new BrowserTab(url);
        tabs.add(tab);
        tabStrip.getChildren().add(tab.header);
        content.getChildren().add(tab.view);
        select(tab);
        if (url != null) address.requestFocus();
        return tab;
    }

    private void closeTab(BrowserTab tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) return;
        tab.engine.load(null);
        tabs.remove(tab);
        tabStrip.getChildren().remove(tab.header);
        content.getChildren().remove(tab.view);
        if (tabs.isEmpty()) {
            open(HOME);
        } else if (selected == tab) {
            select(tabs.get(Math.min(index, tabs.size() - 1)));
        }
    }

    private void select(BrowserTab tab) {
        if (selected != null) progress.progressProperty().unbind();
        selected = tab;
        for (BrowserTab each : tabs) {
            boolean active = each == tab;
            each.view.setVisible(active);
            each.view.setManaged(active);
            each.header.setStyle("-fx-background-radius: 10; -fx-cursor: hand; -fx-background-color: "
                    + (active ? Theme.accentRgba(0.28) : "rgba(255,255,255,0.06)") + ";");
            each.title.setStyle("-fx-text-fill: " + (active ? "#f5f7fd" : "#a9a9cc")
                    + "; -fx-font-size: 12px; -fx-font-weight: " + (active ? "bold" : "normal") + ";");
        }
        String location = tab.engine.getLocation();
        address.setText(location == null ? "" : location);
        progress.progressProperty().bind(tab.engine.getLoadWorker().progressProperty());
        progress.visibleProperty().bind(tab.engine.getLoadWorker().stateProperty().isEqualTo(Worker.State.RUNNING));
        status.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(() -> {
            Worker.State state = tab.engine.getLoadWorker().getState();
            if (state == Worker.State.RUNNING) return "Loading...";
            if (state == Worker.State.FAILED) return "Could not load this page.";
            return "";
        }, tab.engine.getLoadWorker().stateProperty()));
    }

    private void step(int offset) {
        if (selected == null) return;
        var history = selected.engine.getHistory();
        int target = history.getCurrentIndex() + offset;
        if (target >= 0 && target < history.getEntries().size()) history.go(offset);
    }

    /** Loads a typed address, or searches for it when it does not look like one. HTTP is upgraded to HTTPS. */
    private void go(String input) {
        String url = toUrl(input);
        if (url == null || selected == null) return;
        selected.load(url);
        selected.view.requestFocus();
    }

    private static String toUrl(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return null;
        String url;
        if (text.matches("(?i)https?://.+")) {
            url = text.replaceFirst("(?i)^http://", "https://");
        } else if (!text.contains(" ") && text.contains(".")) {
            url = "https://" + text;
        } else {
            url = "https://duckduckgo.com/?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        }
        return url;
    }

    private static String hostOf(String location) {
        try {
            String host = location == null ? null : URI.create(location).getHost();
            return host == null ? "New tab" : host;
        } catch (IllegalArgumentException e) {
            return "New tab";
        }
    }

    // ---- Edge / Chrome ---------------------------------------------------------------------------------

    /**
     * JavaFX's built-in engine is too old for apps like Discord, so this hands the page to the PC's Edge (or Chrome)
     * in its own profile, routed through the same secure-DNS proxy. WebSockets work there too (they use CONNECT).
     */
    private void openExternal() {
        String typed = address.getText();
        String url = toUrl(typed == null || typed.isBlank() ? HOME : typed);
        try {
            Path exe = findChromium();
            if (exe == null) throw new IOException("Microsoft Edge or Google Chrome was not found on this PC.");
            Path profile = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")),
                    "V-Client", "browser-profile");
            Files.createDirectories(profile);
            new ProcessBuilder(exe.toString(), "--user-data-dir=" + profile,
                    "--proxy-server=http://127.0.0.1:" + DohProxy.shared().port(),
                    "--no-first-run", "--no-default-browser-check", url).start();
        } catch (IOException e) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR, e.getMessage());
            alert.setHeaderText("Could not open Edge");
            if (host != null && host.stage() != null) alert.initOwner(host.stage());
            alert.show();
        }
    }

    private static Path findChromium() {
        String[][] candidates = {
                {"ProgramFiles(x86)", "Microsoft/Edge/Application/msedge.exe"},
                {"ProgramFiles", "Microsoft/Edge/Application/msedge.exe"},
                {"ProgramFiles", "Google/Chrome/Application/chrome.exe"},
                {"ProgramFiles(x86)", "Google/Chrome/Application/chrome.exe"},
                {"LOCALAPPDATA", "Google/Chrome/Application/chrome.exe"}};
        for (String[] candidate : candidates) {
            String base = System.getenv(candidate[0]);
            if (base == null) continue;
            Path exe = Path.of(base, candidate[1]);
            if (Files.isRegularFile(exe)) return exe;
        }
        return null;
    }

    // ---- VPN -----------------------------------------------------------------------------------------

    private void refreshVpn() {
        boolean on = VpnManager.isConnected();
        vpn.setText(vpnBusy ? "VPN..." : on ? "VPN ON" : "VPN OFF");
        vpn.setDisable(vpnBusy);
        vpn.setStyle(BUTTON + " -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 0 12 0 12;"
                + (on ? " -fx-background-color: rgba(124,242,154,0.25); -fx-text-fill: #7cf29a;" : ""));
    }

    private void toggleVpn() {
        if (vpnBusy) return;
        vpnBusy = true;
        refreshVpn();
        boolean wasOn = VpnManager.isConnected();
        host.executor().execute(() -> {
            String failure = null;
            try {
                if (wasOn) VpnManager.disconnect();
                else VpnManager.connect(List.of(), line -> Platform.runLater(() -> host.log(line)));
            } catch (IOException e) {
                failure = e.getMessage();
            }
            String message = failure;
            Platform.runLater(() -> {
                vpnBusy = false;
                refreshVpn();
                if (message != null) {
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.ERROR, message);
                    alert.setHeaderText("The VPN could not start");
                    if (host.stage() != null) alert.initOwner(host.stage());
                    alert.show();
                } else if (selected != null) {
                    selected.engine.reload();
                }
            });
        });
    }

    // ---- full screen ---------------------------------------------------------------------------------

    private void toggleFullscreen() {
        if (fullscreenStage != null) {
            fullscreenStage.close();
            return;
        }
        root.minHeightProperty().unbind();
        root.setMinHeight(0);
        root.setPrefHeight(-1);
        Stage stage = new Stage();
        stage.setTitle("V-Client Browser");
        VBox frame = new VBox(root);
        frame.setPadding(new Insets(8));
        frame.setStyle("-fx-background-color: #0b0c1d;");
        VBox.setVgrow(root, Priority.ALWAYS);
        Scene scene = new Scene(frame, 1200, 760);
        stage.setScene(scene);
        stage.setFullScreenExitHint("Press F11 or Esc to leave full screen");
        stage.setFullScreen(true);
        fullscreenStage = stage;
        stage.fullScreenProperty().addListener((o, was, full) -> {
            if (!full) stage.close();
        });
        stage.setOnHidden(event -> {
            // Free the node for the launcher page to take it back.
            scene.setRoot(new Group());
            fullscreenStage = null;
            if (host != null) host.themeChanged();
        });
        stage.show();
    }

    // ---- DNS-over-HTTPS proxy ------------------------------------------------------------------------

    /** Sends the web view's traffic (not the launcher's own loopback calls) through the DoH proxy. */
    private static synchronized void installProxy(int port) {
        if (proxyInstalled) return;
        proxyInstalled = true;
        ProxySelector previous = ProxySelector.getDefault();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                String scheme = uri.getScheme();
                String hostName = uri.getHost();
                boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
                if (!web || hostName == null || hostName.equals("localhost") || hostName.startsWith("127.")) {
                    return previous == null ? List.of(Proxy.NO_PROXY) : previous.select(uri);
                }
                return List.of(proxy);
            }

            @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // the proxy falls back to system DNS itself; nothing to switch here
            }
        });
    }
}
