package com.vclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameOptionsFixTest {
    @Test
    void replacesTheOptionAndKeepsEverythingElse(@TempDir Path directory) throws Exception {
        Path options = directory.resolve("options.txt");
        Files.writeString(options, "fov:70.0\npauseOnLostFocus:true\nguiScale:2\n");
        assertTrue(GameOptionsFix.keepRunningWhenUnfocused(directory));
        assertEquals("fov:70.0\npauseOnLostFocus:false\nguiScale:2\n", Files.readString(options).replace("\r\n", "\n"));
        assertFalse(GameOptionsFix.keepRunningWhenUnfocused(directory));
    }

    @Test
    void addsTheOptionWhenMissingAndSkipsAMissingFile(@TempDir Path directory) throws Exception {
        assertFalse(GameOptionsFix.keepRunningWhenUnfocused(directory));
        Files.writeString(directory.resolve("options.txt"), "fov:90.0\n");
        assertTrue(GameOptionsFix.keepRunningWhenUnfocused(directory));
        assertTrue(Files.readString(directory.resolve("options.txt")).contains("pauseOnLostFocus:false"));
    }
}
