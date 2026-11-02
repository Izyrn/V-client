package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One tab drawn from the hidden Edge/Chrome engine: its screen is streamed in and mouse/keys are sent back. */
final class ChromiumContent implements TabContent {
    private final StackPane node = new StackPane();
    private final ImageView image = new ImageView();
    private final StringProperty title = new SimpleStringProperty("New tab");
    private final StringProperty location = new SimpleStringProperty("");
    private final BooleanProperty loading = new SimpleBooleanProperty();
    private final AtomicReference<Image> latest = new AtomicReference<>();
    private final PauseTransition resize = new PauseTransition(Duration.millis(150));
    private final Consumer<Throwable> onFailure;

    private volatile ChromiumHost host;
    private volatile String targetId;
    private volatile String sessionId;
    private volatile boolean active;
    private volatile boolean closed;
    private String pendingUrl;
    private volatile int buttons;

    /** Opens a new page; {@code existingTarget} is set for a window the page itself opened. */
    ChromiumContent(CompletableFuture<ChromiumHost> engine, String url, String existingTarget,
                    Consumer<Throwable> onFailure) {
        this.onFailure = onFailure;
        this.pendingUrl = url;
        node.setMinSize(0, 0);
        node.setStyle("-fx-background-color: #ffffff;");
        node.setFocusTraversable(true);
        image.setManaged(false);
        image.setPreserveRatio(false);
        image.setSmooth(true);
        node.getChildren().add(image);
        node.widthProperty().addListener((o, a, b) -> resize.playFromStart());
        node.heightProperty().addListener((o, a, b) -> resize.playFromStart());
        resize.setOnFinished(event -> applyViewport());
        installInput();
        engine.whenComplete((started, error) -> {
            if (error != null) {
                Platform.runLater(() -> onFailure.accept(error));
                return;
            }
            host = started;
            attach(existingTarget);
        });
    }

    private void attach(String existingTarget) {
        CompletableFuture<String> target = existingTarget != null ? CompletableFuture.completedFuture(existingTarget)
                : host.send("Target.createTarget", ChromiumHost.params().put("url", "about:blank"), null)
                .thenApply(result -> result.path("targetId").asText());
        target.thenCompose(id -> {
            targetId = id;
            host.onTarget(id, this::targetInfo);
            return host.send("Target.attachToTarget", ChromiumHost.params().put("targetId", id).put("flatten", true), null);
        }).thenAccept(result -> {
            sessionId = result.path("sessionId").asText();
            host.onSession(sessionId, this::event);
            send("Page.enable", null);
            send("Runtime.enable", null);
            send("Network.enable", null);
            send("Log.enable", null);
            send("Emulation.setFocusEmulationEnabled", ChromiumHost.params().put("enabled", true));
            send("Emulation.setUserAgentOverride", ChromiumHost.params().put("userAgent", host.userAgent()));
            Platform.runLater(this::applyViewport);
            String url = pendingUrl;
            pendingUrl = null;
            if (url != null) send("Page.navigate", ChromiumHost.params().put("url", url));
        }).exceptionally(error -> {
            if (!closed) Platform.runLater(() -> onFailure.accept(error));
            return null;
        });
    }

    private CompletableFuture<JsonNode> send(String method, ObjectNode params) {
        ChromiumHost engine = host;
        String session = sessionId;
        if (engine == null || session == null || closed) return CompletableFuture.completedFuture(null);
        CompletableFuture<JsonNode> result = engine.send(method, params, session);
        if (DEBUG && method.startsWith("Input.")) {
            System.err.println("CDP " + method + " " + params);
            result.whenComplete((r, e) -> { if (e != null) System.err.println("CDP error " + method + ": " + e); });
        }
        return result;
    }

    private static final boolean DEBUG = Boolean.getBoolean("vclient.cdpdebug");

    // ---- events from the engine ----------------------------------------------------------------------

    private void event(String method, JsonNode params) {
        switch (method) {
            case "Page.screencastFrame" -> {
                send("Page.screencastFrameAck", ChromiumHost.params().put("sessionId", params.path("sessionId").asInt()));
                if (!active) return;
                Image frame = new Image(new ByteArrayInputStream(Base64.getDecoder().decode(params.path("data").asText())));
                if (latest.getAndSet(frame) == null) {
                    Platform.runLater(() -> {
                        Image next = latest.getAndSet(null);
                        if (next != null) image.setImage(next);
                    });
                }
            }
            case "Runtime.exceptionThrown" -> BrowserLog.write("script error: " + params.path("exceptionDetails").path("text").asText()
                    + " " + params.path("exceptionDetails").path("exception").path("description").asText("").lines().findFirst().orElse("")
                    + " @ " + params.path("exceptionDetails").path("url").asText(""));
            case "Runtime.consoleAPICalled" -> {
                if (params.path("type").asText().equals("error")) {
                    BrowserLog.write("console.error: " + params.path("args").path(0).path("value").asText(
                            params.path("args").path(0).path("description").asText("")));
                }
            }
            case "Log.entryAdded" -> {
                JsonNode entry = params.path("entry");
                if (entry.path("level").asText().equals("error")) {
                    BrowserLog.write("log: " + entry.path("text").asText() + " " + entry.path("url").asText(""));
                }
            }
            case "Network.requestWillBeSent" -> BrowserLog.remember(params.path("requestId").asText(), params.path("request").path("url").asText());
            case "Network.loadingFailed" -> {
                if (!params.path("canceled").asBoolean()) {
                    BrowserLog.write("network failed: " + params.path("errorText").asText() + " "
                            + BrowserLog.url(params.path("requestId").asText()));
                }
            }
            case "Network.webSocketCreated" -> BrowserLog.write("websocket opened: " + params.path("url").asText());
            case "Network.webSocketClosed" -> BrowserLog.write("websocket closed (request " + params.path("requestId").asText() + ")");
            case "Network.webSocketFrameError" -> BrowserLog.write("websocket error: " + params.path("errorMessage").asText());
            case "Page.frameStartedLoading" -> Platform.runLater(() -> loading.set(true));
            case "Page.frameStoppedLoading" -> Platform.runLater(() -> loading.set(false));
            case "Page.navigatedWithinDocument" -> Platform.runLater(() -> location.set(params.path("url").asText()));
            case "Page.javascriptDialogOpening" ->
                    send("Page.handleJavaScriptDialog", ChromiumHost.params().put("accept", true));
            case "ChromiumHost.closed" -> {
                if (!closed) Platform.runLater(() -> onFailure.accept(new java.io.IOException("The browser engine stopped.")));
            }
            default -> { }
        }
    }

    private void targetInfo(JsonNode info) {
        String text = info.path("title").asText("");
        String url = info.path("url").asText("");
        Platform.runLater(() -> {
            if (!text.isEmpty()) title.set(text);
            if (!url.isEmpty() && !url.equals("about:blank")) location.set(url);
        });
    }

    // ---- drawing -------------------------------------------------------------------------------------

    private void applyViewport() {
        double width = node.getWidth();
        double height = node.getHeight();
        if (!active || width < 50 || height < 50 || sessionId == null) return;
        double scale = node.getScene() != null && node.getScene().getWindow() != null
                ? node.getScene().getWindow().getOutputScaleX() : 1;
        image.setFitWidth(width);
        image.setFitHeight(height);
        send("Emulation.setDeviceMetricsOverride", ChromiumHost.params().put("width", (int) width)
                .put("height", (int) height).put("deviceScaleFactor", scale).put("mobile", false));
        send("Page.bringToFront", null);
        send("Page.stopScreencast", null);
        send("Page.startScreencast", ChromiumHost.params().put("format", "jpeg").put("quality", 80)
                .put("maxWidth", (int) (width * scale)).put("maxHeight", (int) (height * scale)).put("everyNthFrame", 1));
    }

    // ---- input ---------------------------------------------------------------------------------------

    private void installInput() {
        node.addEventHandler(MouseEvent.MOUSE_MOVED, e -> move(e));
        node.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> move(e));
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            node.requestFocus();
            pendingMove.set(null);
            buttons |= mask(e.getButton());
            mouse("mousePressed", e, Math.max(1, e.getClickCount()));
            e.consume();
        });
        node.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            pendingMove.set(null);
            buttons &= ~mask(e.getButton());
            mouse("mouseReleased", e, Math.max(1, e.getClickCount()));
            e.consume();
        });
        node.addEventHandler(ScrollEvent.SCROLL, e -> { e.consume(); send("Input.dispatchMouseEvent", ChromiumHost.params()
                .put("type", "mouseWheel").put("x", e.getX()).put("y", e.getY())
                .put("deltaX", -e.getDeltaX()).put("deltaY", -e.getDeltaY()).put("modifiers", modifiers(e.isAltDown(),
                        e.isControlDown(), e.isMetaDown(), e.isShiftDown()))); });
        node.addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        node.addEventFilter(KeyEvent.KEY_TYPED, this::keyTyped);
        node.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() != KeyCode.UNDEFINED) key("keyUp", e, null);
            e.consume();
        });
    }

    /** Mouse movement is sent at most every 16 ms with the latest position, so it can never queue up behind clicks and keys. */
    private static final java.util.concurrent.ScheduledExecutorService MOVES =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chromium-mouse");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicReference<double[]> pendingMove = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean moveScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    private void move(MouseEvent e) {
        pendingMove.set(new double[]{e.getX(), e.getY(), modifiers(e.isAltDown(), e.isControlDown(), e.isMetaDown(), e.isShiftDown())});
        if (!moveScheduled.compareAndSet(false, true)) return;
        MOVES.schedule(() -> {
            moveScheduled.set(false);
            double[] at = pendingMove.getAndSet(null);
            if (at == null) return;
            int held = buttons;
            send("Input.dispatchMouseEvent", ChromiumHost.params().put("type", "mouseMoved").put("x", at[0]).put("y", at[1])
                    .put("button", (held & 1) != 0 ? "left" : (held & 2) != 0 ? "right" : (held & 4) != 0 ? "middle" : "none")
                    .put("buttons", held).put("modifiers", (int) at[2]));
            long now = System.currentTimeMillis();
            if (now - lastCursorQuery >= 70) {
                lastCursorQuery = now;
                queryCursor(at[0], at[1]);
            }
        }, 16, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private volatile long lastCursorQuery;

    /** The engine does not report the mouse pointer, so ask the page what its style says under the pointer. */
    private void queryCursor(double x, double y) {
        String script = "(function(x,y){var e=document.elementFromPoint(x,y);if(!e)return 'default';"
                + "var c=getComputedStyle(e).cursor;if(c&&c!=='auto')return c;var t=e.tagName;"
                + "if(t==='INPUT'||t==='TEXTAREA'||e.isContentEditable)return 'text';"
                + "if(t==='A'||t==='BUTTON')return 'pointer';"
                + "if(getComputedStyle(e).userSelect!=='none'){for(var n=e.firstChild;n;n=n.nextSibling){"
                + "if(n.nodeType===3&&n.textContent.trim())return 'text';}}return 'default';})(" + (int) x + "," + (int) y + ")";
        send("Runtime.evaluate", ChromiumHost.params().put("expression", script).put("returnByValue", true))
                .thenAccept(result -> {
                    if (result == null) return;
                    String css = result.path("result").path("value").asText("default");
                    css = css.substring(css.lastIndexOf(',') + 1).trim();
                    javafx.scene.Cursor cursor = cursorFor(css);
                    Platform.runLater(() -> node.setCursor(cursor));
                });
    }

    private static javafx.scene.Cursor cursorFor(String css) {
        return switch (css) {
            case "pointer" -> javafx.scene.Cursor.HAND;
            case "text", "vertical-text" -> javafx.scene.Cursor.TEXT;
            case "crosshair" -> javafx.scene.Cursor.CROSSHAIR;
            case "move", "all-scroll" -> javafx.scene.Cursor.MOVE;
            case "wait", "progress" -> javafx.scene.Cursor.WAIT;
            case "grab" -> javafx.scene.Cursor.OPEN_HAND;
            case "grabbing" -> javafx.scene.Cursor.CLOSED_HAND;
            case "ew-resize", "col-resize", "e-resize", "w-resize" -> javafx.scene.Cursor.H_RESIZE;
            case "ns-resize", "row-resize", "n-resize", "s-resize" -> javafx.scene.Cursor.V_RESIZE;
            case "nesw-resize" -> javafx.scene.Cursor.NE_RESIZE;
            case "nwse-resize" -> javafx.scene.Cursor.NW_RESIZE;
            default -> javafx.scene.Cursor.DEFAULT;
        };
    }

    private static int mask(MouseButton button) {
        return switch (button) {
            case PRIMARY -> 1;
            case SECONDARY -> 2;
            case MIDDLE -> 4;
            default -> 0;
        };
    }

    private void mouse(String type, MouseEvent e, int clickCount) {
        String button = switch (e.getButton()) {
            case PRIMARY -> "left";
            case SECONDARY -> "right";
            case MIDDLE -> "middle";
            default -> "none";
        };
        send("Input.dispatchMouseEvent", ChromiumHost.params().put("type", type).put("x", e.getX()).put("y", e.getY())
                .put("button", type.equals("mouseMoved") ? (buttons == 0 ? "none" : button) : button)
                .put("buttons", buttons).put("clickCount", clickCount)
                .put("modifiers", modifiers(e.isAltDown(), e.isControlDown(), e.isMetaDown(), e.isShiftDown())));
    }

    private static int modifiers(boolean alt, boolean ctrl, boolean meta, boolean shift) {
        return (alt ? 1 : 0) | (ctrl ? 2 : 0) | (meta ? 4 : 0) | (shift ? 8 : 0);
    }

    private void keyPressed(KeyEvent e) {
        e.consume();
        if (e.isShortcutDown() && !e.isAltDown()) {
            switch (e.getCode()) {
                case V -> {
                    String text = Clipboard.getSystemClipboard().getString();
                    if (text != null) send("Input.insertText", ChromiumHost.params().put("text", text));
                    return;
                }
                case C, X -> {
                    boolean cut = e.getCode() == KeyCode.X;
                    send("Runtime.evaluate", ChromiumHost.params().put("expression", "String(window.getSelection())"))
                            .thenAccept(result -> {
                                String text = result == null ? "" : result.path("result").path("value").asText("");
                                if (text.isEmpty()) return;
                                Platform.runLater(() -> {
                                    ClipboardContent content = new ClipboardContent();
                                    content.putString(text);
                                    Clipboard.getSystemClipboard().setContent(content);
                                });
                                if (cut) send("Input.dispatchKeyEvent", ChromiumHost.params().put("type", "rawKeyDown")
                                        .put("windowsVirtualKeyCode", 8).put("key", "Backspace"));
                            });
                    return;
                }
                case A -> {
                    key("rawKeyDown", e, "selectAll");
                    return;
                }
                case Z -> {
                    key("rawKeyDown", e, e.isShiftDown() ? "redo" : "undo");
                    return;
                }
                case Y -> {
                    key("rawKeyDown", e, "redo");
                    return;
                }
                default -> { }
            }
        }
        if (e.getCode() != KeyCode.UNDEFINED) key("rawKeyDown", e, null);
    }

    private void keyTyped(KeyEvent e) {
        e.consume();
        String text = e.getCharacter();
        if (text == null || text.isEmpty() || e.isShortcutDown() || e.isAltDown()) return;
        char first = text.charAt(0);
        if (first < 32 && first != '\r') return;
        send("Input.dispatchKeyEvent", ChromiumHost.params().put("type", "char").put("text", text)
                .put("unmodifiedText", text).put("modifiers", 0));
    }

    private void key(String type, KeyEvent e, String command) {
        ObjectNode params = ChromiumHost.params().put("type", type).put("windowsVirtualKeyCode", e.getCode().getCode())
                .put("key", keyName(e)).put("modifiers", modifiers(e.isAltDown(), e.isControlDown(),
                        e.isMetaDown(), e.isShiftDown()));
        if (command != null) params.putArray("commands").add(command);
        send("Input.dispatchKeyEvent", params);
    }

    private static String keyName(KeyEvent e) {
        return switch (e.getCode()) {
            case ENTER -> "Enter";
            case BACK_SPACE -> "Backspace";
            case TAB -> "Tab";
            case ESCAPE -> "Escape";
            case LEFT -> "ArrowLeft";
            case RIGHT -> "ArrowRight";
            case UP -> "ArrowUp";
            case DOWN -> "ArrowDown";
            case DELETE -> "Delete";
            case HOME -> "Home";
            case END -> "End";
            case PAGE_UP -> "PageUp";
            case PAGE_DOWN -> "PageDown";
            case SPACE -> " ";
            case SHIFT -> "Shift";
            case CONTROL -> "Control";
            case ALT -> "Alt";
            default -> e.getText() == null || e.getText().isEmpty() ? e.getCode().getName() : e.getText();
        };
    }

    // ---- TabContent ----------------------------------------------------------------------------------

    @Override public Node node() { return node; }

    @Override public void load(String url) {
        if (sessionId == null) pendingUrl = url;
        else send("Page.navigate", ChromiumHost.params().put("url", url));
    }

    @Override public void reload() { send("Page.reload", null); }

    @Override public void back() { history(-1); }

    @Override public void forward() { history(1); }

    private void history(int offset) {
        send("Page.getNavigationHistory", null).thenAccept(result -> {
            if (result == null) return;
            int index = result.path("currentIndex").asInt() + offset;
            JsonNode entries = result.path("entries");
            if (index >= 0 && index < entries.size()) {
                send("Page.navigateToHistoryEntry", ChromiumHost.params().put("entryId", entries.get(index).path("id").asInt()));
            }
        });
    }

    @Override public void focus() { node.requestFocus(); }

    @Override public void setActive(boolean value) {
        active = value;
        node.setVisible(value);
        node.setManaged(value);
        if (value) Platform.runLater(this::applyViewport);
        else send("Page.stopScreencast", null);
    }

    @Override public void close() {
        closed = true;
        ChromiumHost engine = host;
        if (engine != null) {
            engine.forget(sessionId, targetId);
            if (targetId != null) engine.send("Target.closeTarget", ChromiumHost.params().put("targetId", targetId), null);
        }
    }

    @Override public StringProperty titleProperty() { return title; }

    @Override public StringProperty locationProperty() { return location; }

    @Override public BooleanProperty loadingProperty() { return loading; }
}
