package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Discord Rich Presence over Discord's local pipe (no SDK, no login): shows "In the VYRN launcher" or "Playing
 * Minecraft 1.21.11" with the current server, world or activity and the play time. Needs the Discord desktop app
 * and a Discord application id (AppInfo.DISCORD_APP_ID, or the VYRN_DISCORD_APP_ID environment variable). Off when
 * there is no id or Discord is not running; it simply retries now and then.
 */
final class DiscordPresence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long UPDATE_MS = 15_000;

    private static volatile String gameVersion;
    private static volatile long gameStarted;
    private static volatile boolean started;
    private static RandomAccessFile pipe;
    private static String lastSent = "";

    private DiscordPresence() {}

    static String appId() {
        String fromEnvironment = System.getenv("VYRN_DISCORD_APP_ID");
        if (fromEnvironment != null && fromEnvironment.matches("\\d{15,25}")) return fromEnvironment;
        return AppInfo.DISCORD_APP_ID;
    }

    static boolean configured() {
        return appId().matches("\\d{15,25}");
    }

    static void gameStarted(String versionId, long startedAtMillis) {
        gameVersion = versionId;
        gameStarted = startedAtMillis;
    }

    static void gameEnded() {
        gameVersion = null;
        gameStarted = 0;
    }

    /** Starts the background updater once. {@code gameFolder} gives the folder whose config holds the mod's status. */
    static synchronized void start(Supplier<Path> gameFolder) {
        if (started || !configured()) return;
        started = true;
        long launcherStarted = System.currentTimeMillis();
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    update(gameFolder.get(), launcherStarted);
                } catch (IOException | RuntimeException e) {
                    close();
                }
                try {
                    Thread.sleep(UPDATE_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "discord-presence");
        thread.setDaemon(true);
        thread.start();
    }

    private static void update(Path gameFolder, long launcherStarted) throws IOException {
        if (pipe == null && !connect()) return;
        ObjectNode activity = JSON.createObjectNode();
        String version = gameVersion;
        if (version != null) {
            activity.put("details", "Playing Minecraft " + version.replace("VYRN ", ""));
            activity.put("state", gameState(gameFolder));
            activity.putObject("timestamps").put("start", gameStarted / 1000);
        } else {
            activity.put("details", "In the " + AppInfo.NAME + " launcher");
            activity.put("state", "Getting ready to play");
            activity.putObject("timestamps").put("start", launcherStarted / 1000);
        }
        ObjectNode assets = activity.putObject("assets");
        assets.put("large_image", "vyrn");
        assets.put("large_text", AppInfo.NAME + " " + AppInfo.VERSION);
        activity.putArray("buttons").addObject().put("label", "Get " + AppInfo.NAME)
                .put("url", BackendClient.SITE_URL + "/downloads.html");
        String key = activity.toString().replaceAll("\"start\":\\d+", "");
        if (key.equals(lastSent)) return;
        ObjectNode payload = JSON.createObjectNode();
        payload.put("cmd", "SET_ACTIVITY");
        payload.putObject("args").put("pid", ProcessHandle.current().pid()).set("activity", activity);
        payload.put("nonce", UUID.randomUUID().toString());
        write(1, payload.toString());
        read();
        lastSent = key;
    }

    /** What the VYRN mod reports: the server, singleplayer or the menus. */
    static String gameState(Path gameFolder) {
        try {
            Path file = gameFolder.resolve("config").resolve("vclient-presence.json");
            if (Files.isRegularFile(file)) {
                JsonNode json = JSON.readTree(file.toFile());
                String server = json.path("server").asText("");
                String activity = json.path("activity").asText("");
                String dimension = json.path("dimension").asText("");
                String where = dimension.equals("the_nether") ? " (Nether)" : dimension.equals("the_end") ? " (The End)" : "";
                if (!server.isBlank()) return "On " + server + where;
                if (!activity.isBlank()) return activity + where;
            }
        } catch (IOException | RuntimeException ignored) {
            // the game may be writing it right now
        }
        return "In game";
    }

    private static boolean connect() {
        for (int i = 0; i < 10; i++) {
            try {
                RandomAccessFile file = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
                pipe = file;
                ObjectNode hello = JSON.createObjectNode().put("v", 1).put("client_id", appId());
                write(0, hello.toString());
                read();
                lastSent = "";
                return true;
            } catch (IOException e) {
                close();
            }
        }
        return false;
    }

    private static void write(int op, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(op).putInt(body.length).put(body);
        pipe.write(frame.array());
    }

    private static String read() throws IOException {
        byte[] header = new byte[8];
        pipe.readFully(header);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int op = buffer.getInt();
        int length = buffer.getInt();
        if (length < 0 || length > 1_000_000) throw new IOException("bad Discord frame");
        byte[] body = new byte[length];
        pipe.readFully(body);
        if (op == 2) throw new IOException("Discord closed the connection: " + new String(body, StandardCharsets.UTF_8));
        return new String(body, StandardCharsets.UTF_8);
    }

    private static void close() {
        if (pipe != null) {
            try {
                pipe.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
        pipe = null;
    }
}
