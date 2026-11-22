package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class AccountStore {
    private static final int DPAPI_UI_FORBIDDEN = 0x1;
    private static final String FORMAT = "V-CLIENT-DPAPI-1:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DEFAULT_DATA_DIRECTORY = AppInfo.dataDir();
    private final Path dataDirectory;
    private final Path accountsFile;
    private final Path offlineAccountsFile;

    AccountStore() {
        this(DEFAULT_DATA_DIRECTORY);
    }

    AccountStore(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        accountsFile = dataDirectory.resolve("accounts.dat");
        offlineAccountsFile = dataDirectory.resolve("offline-accounts.json");
    }

    List<PlayerAccount> load() throws IOException {
        List<PlayerAccount> result = new ArrayList<>();
        if (Files.exists(accountsFile)) result.addAll(loadProtectedAccounts());
        if (Files.exists(offlineAccountsFile)) result.addAll(loadOfflineAccounts());
        return List.copyOf(result);
    }

    private List<PlayerAccount> loadProtectedAccounts() throws IOException {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            throw new IOException("Saved accounts are protected with Windows DPAPI and can only be opened on this Windows account.");
        }
        String stored = Files.readString(accountsFile, StandardCharsets.US_ASCII);
        if (!stored.startsWith(FORMAT)) throw new IOException("VYRN account data has an unsupported format.");
        byte[] protectedData;
        try {
            protectedData = java.util.Base64.getDecoder().decode(stored.substring(FORMAT.length()).strip());
        } catch (IllegalArgumentException e) {
            throw new IOException("VYRN account data is corrupted.", e);
        }
        final byte[] clearText;
        try {
            clearText = Crypt32Util.cryptUnprotectData(protectedData, DPAPI_UI_FORBIDDEN);
        } catch (RuntimeException e) {
            throw new IOException("VYRN could not unlock saved accounts for this Windows user.", e);
        }
        try {
            JsonNode root = JSON.readTree(clearText);
            ArrayNode accounts = root.path("accounts").isArray()
                    ? (ArrayNode) root.path("accounts") : JSON.createArrayNode();
            List<PlayerAccount> result = new ArrayList<>(accounts.size());
            for (JsonNode saved : accounts) result.add(readAccount(saved));
            return List.copyOf(result);
        } finally {
            java.util.Arrays.fill(clearText, (byte) 0);
            java.util.Arrays.fill(protectedData, (byte) 0);
        }
    }

    void save(List<PlayerAccount> accounts) throws IOException {
        saveOfflineAccounts(accounts);
        saveProtectedAccounts(accounts);
    }

    void saveOfflineAccounts(List<PlayerAccount> accounts) throws IOException {
        Files.createDirectories(dataDirectory);
        ObjectNode root = JSON.createObjectNode();
        ArrayNode values = root.putArray("accounts");
        for (PlayerAccount account : accounts) {
            if (account.type() == PlayerAccount.Type.OFFLINE) values.add(account.name());
        }
        writeJsonAtomically(offlineAccountsFile, root);
    }

    void saveProtectedAccounts(List<PlayerAccount> accounts) throws IOException {
        saveProtectedAccounts(accounts, ignored -> {});
    }

    void saveProtectedAccounts(List<PlayerAccount> accounts, Consumer<String> onProgress) throws IOException {
        List<PlayerAccount> protectedAccounts = accounts.stream()
                .filter(account -> account.type() != PlayerAccount.Type.OFFLINE).toList();
        if (protectedAccounts.isEmpty()) {
            Files.deleteIfExists(accountsFile);
            return;
        }
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            throw new IOException("Saved-account protection is supported on Windows only.");
        }
        Files.createDirectories(dataDirectory);
        ObjectNode root = JSON.createObjectNode();
        ArrayNode values = root.putArray("accounts");
        for (PlayerAccount account : protectedAccounts) values.add(writeAccount(account));
        byte[] clearText = JSON.writeValueAsBytes(root);
        byte[] protectedData;
        try {
            onProgress.accept("Protecting account credentials with Windows DPAPI...");
            protectedData = Crypt32Util.cryptProtectData(clearText, DPAPI_UI_FORBIDDEN);
        } catch (RuntimeException e) {
            throw new IOException("Windows could not protect your saved accounts.", e);
        } finally {
            java.util.Arrays.fill(clearText, (byte) 0);
        }
        Path temporary = Files.createTempFile(dataDirectory, "accounts-", ".tmp");
        try {
            onProgress.accept("Writing the protected account file...");
            Files.writeString(temporary, FORMAT + java.util.Base64.getEncoder().encodeToString(protectedData),
                    StandardCharsets.US_ASCII);
            moveAtomically(temporary, accountsFile);
        } finally {
            java.util.Arrays.fill(protectedData, (byte) 0);
            Files.deleteIfExists(temporary);
        }
    }

    private List<PlayerAccount> loadOfflineAccounts() throws IOException {
        JsonNode root = JSON.readTree(offlineAccountsFile.toFile());
        JsonNode accounts = root == null ? null : root.path("accounts");
        if (accounts == null || !accounts.isArray()) {
            throw new IOException("VYRN offline account data is invalid.");
        }
        List<PlayerAccount> result = new ArrayList<>(accounts.size());
        for (JsonNode saved : accounts) {
            try {
                result.add(PlayerAccount.offline(saved.asText()));
            } catch (IllegalArgumentException e) {
                throw new IOException("VYRN offline account data contains an invalid username.", e);
            }
        }
        return result;
    }

    private static void writeJsonAtomically(Path destination, JsonNode value) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), "accounts-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            moveAtomically(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomically(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ObjectNode writeAccount(PlayerAccount account) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", account.id());
        node.put("type", account.type().name());
        node.put("name", account.name());
        node.put("uuid", account.uuid());
        node.put("accessToken", account.accessToken());
        node.put("refreshToken", account.refreshToken());
        node.put("clientToken", account.clientToken());
        node.put("xuid", account.xuid());
        node.put("userProperties", account.userProperties());
        node.put("skinUrl", account.skinUrl());
        node.put("capeUrl", account.capeUrl());
        return node;
    }

    private static PlayerAccount readAccount(JsonNode node) throws IOException {
        try {
            return new PlayerAccount(text(node, "id"), PlayerAccount.Type.valueOf(text(node, "type")),
                    text(node, "name"), text(node, "uuid"), text(node, "accessToken"),
                    text(node, "refreshToken"), text(node, "clientToken"), text(node, "xuid"),
                    text(node, "userProperties"), text(node, "skinUrl"), text(node, "capeUrl"));
        } catch (IllegalArgumentException e) {
            throw new IOException("VYRN contains an invalid saved account.", e);
        }
    }

    private static String text(JsonNode node, String property) {
        return node.path(property).asText("");
    }
}
