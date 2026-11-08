package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Export / import of a launcher profile: the game setup (memory, Java, JVM arguments, theme, clip and boost options).
 * Accounts, sign-in tokens, the backend address and the install id are never exported, and an import is validated
 * (memory clamped, JVM arguments re-checked) so a shared file cannot change anything sensitive.
 */
final class ProfileTransfer {
    static final String KIND = "v-client-profile";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProfileTransfer() {}

    static void export(LauncherSettings settings, Path target) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", KIND);
        root.put("version", 1);
        root.put("launcherVersion", AppInfo.VERSION);
        root.put("memoryGb", settings.memoryGb());
        root.put("javaMajorOverride", settings.javaMajorOverride());
        root.put("jvmArguments", settings.jvmArguments());
        root.put("themeId", settings.themeId());
        root.put("customAccent", settings.customAccent());
        root.put("replayBufferEnabled", settings.replayBufferEnabled());
        root.put("clipHotkey", settings.clipHotkey());
        root.put("boostEnabled", settings.boostEnabled());
        root.put("boostProfile", settings.boostProfile().name());
        root.put("autoUpdate", settings.autoUpdate());
        JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), root);
    }

    /** Applies the file to {@code settings} (in memory; the caller saves) and returns a one-line summary. */
    static String importInto(LauncherSettings settings, Path source) throws IOException {
        if (Files.size(source) > 256 * 1024) throw new IOException("That file is too large to be a V-Client profile.");
        JsonNode root = JSON.readTree(source.toFile());
        if (!KIND.equals(root.path("kind").asText())) throw new IOException("This is not a V-Client profile file.");
        int applied = 0;
        if (root.has("memoryGb")) {
            settings.setMemoryGb(Math.clamp(root.path("memoryGb").asInt(settings.memoryGb()), 1, LauncherSettings.maxMemory()));
            applied++;
        }
        if (root.has("javaMajorOverride") && LauncherSettings.isSelectableJava(root.path("javaMajorOverride").asInt(0))) {
            settings.setJavaMajorOverride(root.path("javaMajorOverride").asInt(0));
            applied++;
        }
        if (root.has("jvmArguments")) {
            // Re-validated: refused arguments are dropped, never trusted because they came from a file.
            settings.setJvmArguments(String.join(" ", JvmArgs.parse(root.path("jvmArguments").asText(""))));
            applied++;
        }
        if (root.has("themeId")) {
            settings.setTheme(root.path("themeId").asText("night"), root.path("customAccent").asText(settings.customAccent()));
            applied++;
        }
        if (root.has("replayBufferEnabled")) {
            settings.setReplayBufferEnabled(root.path("replayBufferEnabled").asBoolean(true));
            applied++;
        }
        if (root.has("clipHotkey")) settings.setClipHotkey(root.path("clipHotkey").asText(settings.clipHotkey()));
        if (root.has("boostEnabled")) {
            settings.setBoostEnabled(root.path("boostEnabled").asBoolean(false));
            settings.setBoostProfile(BoostProfile.parse(root.path("boostProfile").asText(""), settings.boostProfile()));
            applied++;
        }
        if (root.has("autoUpdate")) settings.setAutoUpdate(root.path("autoUpdate").asBoolean(true));
        return "Imported " + applied + " setting groups from " + source.getFileName() + ".";
    }
}
