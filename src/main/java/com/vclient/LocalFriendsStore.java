package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class LocalFriendsStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;

    LocalFriendsStore() {
        this(AppInfo.dataDir().resolve("friends.json"));
    }

    LocalFriendsStore(Path file) {
        this.file = file;
    }

    List<String> load() throws IOException {
        if (!Files.exists(file)) return List.of();
        JsonNode saved = JSON.readTree(file.toFile());
        if (!saved.isArray()) throw new IOException("The saved local friends list is not a JSON array.");
        List<String> names = new ArrayList<>();
        for (JsonNode entry : saved) {
            String name = entry.asText("");
            validateName(name);
            if (names.stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
                throw new IOException("The saved local friends list contains duplicate usernames.");
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    List<String> add(String username) throws IOException {
        String name = username == null ? "" : username.strip();
        validateName(name);
        List<String> names = new ArrayList<>(load());
        if (names.stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
            throw new IOException(name + " is already in your local friends list.");
        }
        names.add(name);
        save(names);
        return List.copyOf(names);
    }

    List<String> remove(String username) throws IOException {
        List<String> names = new ArrayList<>(load());
        if (!names.removeIf(existing -> existing.equalsIgnoreCase(username))) {
            throw new IOException("That username is not in your local friends list.");
        }
        save(names);
        return List.copyOf(names);
    }

    private void save(List<String> names) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "friends-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), names);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateName(String username) throws IOException {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IOException("Minecraft usernames must be 3–16 letters, numbers, or underscores.");
        }
    }
}
