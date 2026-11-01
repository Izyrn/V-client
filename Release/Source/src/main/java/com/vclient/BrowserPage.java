package com.vclient;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** A small web browser whose DNS lookups go over HTTPS (DNS-over-HTTPS) instead of the PC's own resolver. */
final class BrowserPage {
    private static final String HOME = "https://duckduckgo.com/";
    private static boolean proxyInstalled;

    private final FeatureHost host;
    private final WebView view = new WebView();
    private final WebEngine engine = view.getEngine();
    private final TextField address = new TextField();
    private final Label status = new Label();

    private BrowserPage(FeatureHost host) {
        this.host = host;
    }

    static VBox build(FeatureHost host) {
        BrowserPage page = new BrowserPage(host);
        VBox root = new VBox(10);
        try {
            installProxy(DohProxy.shared().port());
        } catch (IOException e) {
            root.getChildren().add(ModsUi.warningBox("Secure DNS is unavailable",
                    "The browser could not start its DNS-over-HTTPS proxy: " + e.getMessage(), null));
            return root;
        }
        page.setup();
        root.getChildren().addAll(page.toolbar(), page.frame(), page.status);
        page.go(HOME);
        return root;
    }

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

    private void setup() {
        engine.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "Version/17.0 Safari/605.1.15");
        engine.locationProperty().addListener((observable, old, location) -> {
            if (location != null && !location.isEmpty()) address.setText(location);
        });
        engine.getLoadWorker().stateProperty().addListener((observable, old, state) ->
                status.setText(switch (state) {
                    case RUNNING, SCHEDULED -> "Loading...";
                    case FAILED -> "Could not load this page.";
                    default -> engine.getTitle() == null ? "" : engine.getTitle();
                }));
        status.setStyle("-fx-text-fill: " + ModsUi.MUTED + "; -fx-font-size: 11px;");
        address.setOnAction(event -> go(address.getText()));
        address.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: " + ModsUi.TEXT
                + "; -fx-background-radius: 10; -fx-padding: 9 12 9 12;");
        HBox.setHgrow(address, Priority.ALWAYS);
    }

    private HBox toolbar() {
        HBox bar = UiKit.row(8,
                UiKit.button("<", false, () -> step(-1)),
                UiKit.button(">", false, () -> step(1)),
                UiKit.button("RELOAD", false, engine::reload),
                address,
                UiKit.button("GO", true, () -> go(address.getText())),
                UiKit.button("HOME", false, () -> go(HOME)));
        return bar;
    }

    private VBox frame() {
        view.setPrefHeight(Math.max(420, host.stage().getHeight() - 300));
        VBox frame = new VBox(view);
        frame.setPadding(new Insets(2));
        frame.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 12;");
        return frame;
    }

    private void step(int offset) {
        int target = engine.getHistory().getCurrentIndex() + offset;
        if (target >= 0 && target < engine.getHistory().getEntries().size()) {
            Platform.runLater(() -> engine.getHistory().go(offset));
        }
    }

    /** Loads a typed address, or searches for it when it does not look like one. HTTP is upgraded to HTTPS. */
    private void go(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return;
        String url;
        if (text.matches("(?i)https?://.+")) {
            url = text.replaceFirst("(?i)^http://", "https://");
        } else if (!text.contains(" ") && text.contains(".")) {
            url = "https://" + text;
        } else {
            url = "https://duckduckgo.com/?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        }
        engine.load(url);
    }
}
