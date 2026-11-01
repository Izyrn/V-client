package com.vclient;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.util.function.Supplier;

/** The fallback engine (JavaFX's built-in one) for PCs without Edge or Chrome. It cannot run the heaviest sites. */
final class WebViewContent implements TabContent {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";

    private final WebView view = new WebView();
    private final WebEngine engine = view.getEngine();
    private final StringProperty title = new SimpleStringProperty("New tab");
    private final StringProperty location = new SimpleStringProperty("");
    private final BooleanProperty loading = new SimpleBooleanProperty();

    WebViewContent(String url, Supplier<WebViewContent> popups) {
        engine.setUserAgent(USER_AGENT);
        engine.setCreatePopupHandler(features -> popups.get().engine);
        engine.titleProperty().addListener((o, old, text) -> title.set(text == null ? "" : text));
        engine.locationProperty().addListener((o, old, text) -> location.set(text == null ? "" : text));
        engine.getLoadWorker().stateProperty().addListener((o, old, state) ->
                loading.set(state == Worker.State.RUNNING || state == Worker.State.SCHEDULED));
        if (url != null) load(url);
    }

    @Override public Node node() { return view; }

    @Override public void load(String url) { engine.load(url); }

    @Override public void reload() { engine.reload(); }

    @Override public void back() { step(-1); }

    @Override public void forward() { step(1); }

    private void step(int offset) {
        var history = engine.getHistory();
        int target = history.getCurrentIndex() + offset;
        if (target >= 0 && target < history.getEntries().size()) history.go(offset);
    }

    @Override public void focus() { view.requestFocus(); }

    @Override public void setActive(boolean active) {
        view.setVisible(active);
        view.setManaged(active);
    }

    @Override public void close() { engine.load(null); }

    @Override public StringProperty titleProperty() { return title; }

    @Override public StringProperty locationProperty() { return location; }

    @Override public BooleanProperty loadingProperty() { return loading; }
}
