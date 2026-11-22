package com.vclient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tracks whether a game launched by VYRN is running, so recordings, playtime and the replay buffer can follow it. */
final class GameSession {
    interface Listener {
        void started(String versionId, long startedAtMillis);

        void ended(String versionId, long startedAtMillis, long endedAtMillis);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile long startedAt;
    private static volatile String versionId = "";

    private GameSession() {}

    static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    static boolean running() {
        return startedAt != 0;
    }

    static long startedAtMillis() {
        return startedAt;
    }

    static void started(String version) {
        versionId = version == null ? "" : version;
        startedAt = System.currentTimeMillis();
        for (Listener listener : LISTENERS) listener.started(versionId, startedAt);
    }

    static void ended() {
        long start = startedAt;
        if (start == 0) return;
        startedAt = 0;
        long end = System.currentTimeMillis();
        for (Listener listener : LISTENERS) listener.ended(versionId, start, end);
    }
}
