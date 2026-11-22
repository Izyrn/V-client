package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class ModrinthModService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI API_BASE = URI.create("https://api.modrinth.com/v2/");
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,199}\\.jar");
    private static final Pattern PROJECT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int SEARCH_LIMIT = 12;
    private static final HttpClient HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    record Target(String gameVersion, String loader) {}

    record ModProject(String id, String title, String description, String iconUrl, long downloads,
                      String versionNumber, String fileName, String downloadUrl, String sha512) {}

    private record ModFile(String name, String url, String sha512) {}

    List<ModProject> search(String query, Target target, Consumer<String> onProgress)
            throws IOException, InterruptedException {
        if (target == null || !target.gameVersion().matches("[A-Za-z0-9._+-]{1,64}")
                || !List.of("fabric", "forge", "neoforge", "quilt").contains(target.loader())) {
            throw new IOException("Choose an installed Fabric, Forge, NeoForge, or Quilt profile first.");
        }
        String facets = "[[\"project_type:mod\"],[\"versions:" + target.gameVersion()
                + "\"],[\"categories:" + target.loader() + "\"]]";
        String endpoint = "search?limit=" + SEARCH_LIMIT + "&index="
                + (query.isBlank() ? "downloads" : "relevance")
                + "&query=" + encode(query.strip())
                + "&facets=" + encode(facets);
        onProgress.accept("Searching Modrinth for " + target.loader() + " mods for Minecraft "
                + target.gameVersion() + "...");
        JsonNode response = getJson(API_BASE.resolve(endpoint), onProgress);
        JsonNode hits = response.path("hits");
        if (!hits.isArray()) {
            throw new IOException("Modrinth returned an invalid mod search response.");
        }

        List<ModProject> projects = new ArrayList<>();
        int incompatible = 0;
        for (JsonNode hit : hits) {
            String id = hit.path("project_id").asText("");
            if (!PROJECT_ID.matcher(id).matches()
                    || !"mod".equals(hit.path("project_type").asText(""))) continue;
            try {
                ModrinthModService.ModProject project = findCompatibleVersion(
                        hit, id, target, onProgress);
                if (project != null) projects.add(project);
                else incompatible++;
            } catch (IOException e) {
                incompatible++;
                onProgress.accept("Skipping " + hit.path("title").asText(id)
                        + ": " + e.getMessage());
            }
        }
        onProgress.accept("Found " + projects.size() + " compatible mods"
                + (incompatible == 0 ? "." : "; skipped " + incompatible + " unavailable or incompatible results."));
        return List.copyOf(projects);
    }

    static Target resolveTarget(Path minecraftDirectory, MinecraftLauncher.InstalledVersion profile)
            throws IOException {
        if (profile == null) throw new IOException("Select an installed mod-loader profile.");
        Path versionsDirectory = minecraftDirectory.resolve("versions").toAbsolutePath().normalize();
        Path profileFile = profile.jsonFile().toAbsolutePath().normalize();
        if (!profileFile.startsWith(versionsDirectory) || !Files.isRegularFile(profileFile)
                || profileFile.getParent() == null
                || !profileFile.getParent().getFileName().toString().equals(profile.id())) {
            throw new IOException("The selected Minecraft profile is not inside VYRN's game folder.");
        }
        String loader = switch (profile.loader()) {
            case "Fabric" -> "fabric";
            case "Forge" -> "forge";
            case "NeoForge" -> "neoforge";
            case "Quilt" -> "quilt";
            default -> throw new IOException("Install a Fabric, Forge, NeoForge, or Quilt profile to browse mods.");
        };
        String gameVersion = resolveGameVersion(profileFile, versionsDirectory, new ArrayList<>());
        if (!gameVersion.matches("[A-Za-z0-9._+-]{1,64}")) {
            throw new IOException("The selected profile has an invalid Minecraft version.");
        }
        return new Target(gameVersion, loader);
    }

    private static String resolveGameVersion(Path profileFile, Path versionsDirectory, List<Path> visited)
            throws IOException {
        Path normalized = profileFile.toAbsolutePath().normalize();
        if (!normalized.startsWith(versionsDirectory) || visited.contains(normalized)) {
            throw new IOException("The selected profile has invalid or circular version inheritance.");
        }
        visited.add(normalized);
        JsonNode metadata = JSON.readTree(normalized.toFile());
        String parent = metadata.path("inheritsFrom").asText("");
        if (parent.isBlank()) return metadata.path("id").asText("");
        if (!parent.matches("[A-Za-z0-9._+-]{1,64}")) {
            throw new IOException("The selected mod-loader profile has an invalid Minecraft parent.");
        }
        Path parentFile = versionsDirectory.resolve(parent).resolve(parent + ".json").normalize();
        if (!parentFile.startsWith(versionsDirectory) || !Files.isRegularFile(parentFile)) {
            throw new IOException("Install the Minecraft version inherited by this mod-loader profile first.");
        }
        return resolveGameVersion(parentFile, versionsDirectory, visited);
    }

    /** Finds the newest compatible release of a project by its Modrinth slug, or null when there is none. */
    ModProject findBySlug(String slug, String title, Target target, Consumer<String> onProgress)
            throws IOException, InterruptedException {
        if (!PROJECT_ID.matcher(slug).matches()) throw new IOException("Invalid Modrinth project: " + slug);
        com.fasterxml.jackson.databind.node.ObjectNode hit = JSON.createObjectNode();
        hit.put("title", title);
        return findCompatibleVersion(hit, slug, target, onProgress);
    }

    private ModProject findCompatibleVersion(JsonNode hit, String projectId, Target target,
                                             Consumer<String> onProgress)
            throws IOException, InterruptedException {
        String endpoint = "project/" + encodePathSegment(projectId) + "/version?game_versions="
                + encode("[\"" + target.gameVersion() + "\"]")
                + "&loaders=" + encode("[\"" + target.loader() + "\"]");
        JsonNode versions = getJson(API_BASE.resolve(endpoint), onProgress);
        if (!versions.isArray()) {
            throw new IOException("Modrinth returned an invalid version list.");
        }
        for (JsonNode version : versions) {
            if (!containsText(version.path("game_versions"), target.gameVersion())
                    || !containsText(version.path("loaders"), target.loader())) continue;
            ModFile file = primaryJar(version.path("files"));
            if (file == null) continue;
            validateDownload(file.url(), file.name(), file.sha512());
            onProgress.accept("Compatible version found for " + hit.path("title").asText(projectId) + ".");
            return new ModProject(projectId,
                    hit.path("title").asText(projectId),
                    hit.path("description").asText(""),
                    hit.path("icon_url").asText(""),
                    hit.path("downloads").asLong(0),
                    version.path("version_number").asText(""),
                    file.name(), file.url(), file.sha512());
        }
        return null;
    }

    private static ModFile primaryJar(JsonNode files) {
        if (!files.isArray()) return null;
        JsonNode fallback = null;
        for (JsonNode file : files) {
            String filename = file.path("filename").asText("");
            if (!filename.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (file.path("primary").asBoolean(false)) return toModFile(file);
            if (fallback == null) fallback = file;
        }
        return fallback == null ? null : toModFile(fallback);
    }

    private static ModFile toModFile(JsonNode file) {
        return new ModFile(file.path("filename").asText(""),
                file.path("url").asText(""),
                file.path("hashes").path("sha512").asText(""));
    }

    Path install(ModProject project, Path minecraftDirectory, Consumer<String> onProgress)
            throws IOException, InterruptedException {
        validateDownload(project.downloadUrl(), project.fileName(), project.sha512());
        Path modsDirectory = minecraftDirectory.resolve("mods").toAbsolutePath().normalize();
        Path output = modsDirectory.resolve(project.fileName()).normalize();
        if (!output.startsWith(modsDirectory)) {
            throw new IOException("The mod filename escapes VYRN's mods folder.");
        }
        Files.createDirectories(modsDirectory);
        if (Files.exists(output)) {
            if (project.sha512().equalsIgnoreCase(sha512(output))) {
                onProgress.accept(project.title() + " is already installed.");
                return output;
            }
            throw new IOException("A different file named " + project.fileName()
                    + " already exists in the mods folder. Remove it before installing this version.");
        }

        Path temporary = Files.createTempFile(modsDirectory, ".vclient-mod-", ".tmp");
        try {
            onProgress.accept("Downloading " + project.title() + " from Modrinth...");
            HttpResponse<Path> response = PlatformTls.sendWithRetries(HTTP,
                    HttpRequest.newBuilder(URI.create(project.downloadUrl()))
                            .timeout(Duration.ofMinutes(3))
                            .header("User-Agent", "VYRN/1.0.0 (https://github.com/Izyrn/V-client)")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofFile(temporary), onProgress);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Modrinth's file server returned HTTP " + response.statusCode() + ".");
            }
            if (!"cdn.modrinth.com".equalsIgnoreCase(response.uri().getHost())) {
                throw new IOException("The mod download redirected away from Modrinth's official CDN.");
            }
            if (!project.sha512().equalsIgnoreCase(sha512(temporary))) {
                throw new IOException("The downloaded mod failed its SHA-512 integrity check.");
            }
            try {
                return Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                return Files.move(temporary, output);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IOException("A mod with the same filename was installed while this download was running.", e);
            }
        } catch (InterruptedException | IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateDownload(String url, String fileName, String sha512) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Modrinth returned an invalid download URL.", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"cdn.modrinth.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IOException("Refusing a mod file URL outside Modrinth's official HTTPS CDN.");
        }
        if (!SAFE_FILE_NAME.matcher(fileName).matches() || fileName.contains("..")) {
            throw new IOException("Modrinth returned an unsafe or invalid JAR filename.");
        }
        if (!sha512.matches("[0-9a-fA-F]{128}")) {
            throw new IOException("Modrinth did not provide a valid SHA-512 hash for the mod.");
        }
    }

    private static boolean containsText(JsonNode array, String expected) {
        if (!array.isArray()) return false;
        for (JsonNode value : array) if (expected.equals(value.asText())) return true;
        return false;
    }

    private static String sha512(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("This Java runtime does not support SHA-512 file verification.", e);
        }
    }

    private static JsonNode getJson(URI uri, Consumer<String> log) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.modrinth.com".equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Refusing to contact a non-Modrinth API host.");
        }
        log.accept("Modrinth request: GET " + uri.getScheme() + "://" + uri.getHost() + uri.getRawPath()
                + " (Java " + System.getProperty("java.version") + ", TLS " + PlatformTls.enabledProtocols()
                + ", route " + PlatformTls.proxyDescription(uri) + ").");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "VYRN/1.0.0 (https://github.com/Izyrn/V-client)")
                .GET().build();
        HttpResponse<String> response;
        try {
            response = PlatformTls.sendWithRetries(
                    HTTP, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), log);
        } catch (IOException e) {
            IOException explained = JavaRuntimeManager.explainTlsFailure(uri.toString(), e);
            log.accept("Modrinth request failed: " + explained.getMessage());
            throw explained;
        }
        log.accept("Modrinth answered HTTP " + response.statusCode() + " (" + response.body().length() + " characters).");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth API returned HTTP " + response.statusCode() + ".");
        }
        try {
            return JSON.readTree(response.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("Modrinth returned invalid JSON.", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePathSegment(String value) {
        return encode(value).replace("+", "%20");
    }
}
