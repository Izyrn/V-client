package com.vclient.mod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a feature so that a bug in it can never crash the game: the first failure is logged with its stack trace and the
 * feature is switched off for the rest of the session; everything else keeps working.
 */
public final class Safe {
    private static final Set<String> BROKEN = ConcurrentHashMap.newKeySet();

    private Safe() {}

    public static void run(String feature, Runnable action) {
        if (BROKEN.contains(feature)) return;
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            if (BROKEN.add(feature)) {
                VClientMod.LOGGER.error("[VYRN] {} stopped working and was switched off for this session", feature, e);
            }
        }
    }

    public static boolean broken(String feature) {
        return BROKEN.contains(feature);
    }
}
