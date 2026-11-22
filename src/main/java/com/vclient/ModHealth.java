package com.vclient;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Smart Mod Manager checks: duplicate mods, missing dependencies, mods that declare they break each other, mods made
 * for another Minecraft version, and known bad pairs. "Fix all" disables the problem jar (renamed to .jar.disabled,
 * never deleted) or installs a missing dependency from Modrinth.
 */
final class ModHealth {
    enum Kind { DUPLICATE, MISSING_DEPENDENCY, BREAKS, WRONG_VERSION, KNOWN_CONFLICT }

    record ModInfo(Path file, String id, String name, String version, Map<String, JsonNode> depends,
                   Map<String, JsonNode> breaks, Set<String> provides) {}

    /** One problem; {@code disable} is the jar a fix turns off, {@code install} the mod id a fix installs. */
    record Issue(Kind kind, String message, Path disable, String install) {}

    private static final JsonMapper JSON = JsonMapper.builder().enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA).enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build();
    /** Ids that are always there: the game, the loader, Java, and what the VYRN profile itself brings. */
    private static final Set<String> BUILT_IN = Set.of("minecraft", "fabricloader", "fabric-loader", "java", "vclient",
            "mixinextras");
    /** Mod ids whose Modrinth project has another name. */
    private static final Map<String, String> SLUGS = Map.ofEntries(Map.entry("fabric", "fabric-api"),
            Map.entry("fabric-api", "fabric-api"), Map.entry("cloth-config2", "cloth-config"),
            Map.entry("cloth-config", "cloth-config"), Map.entry("yet_another_config_lib_v3", "yacl"),
            Map.entry("architectury", "architectury-api"), Map.entry("owo", "owo-lib"),
            Map.entry("fabric-language-kotlin", "fabric-language-kotlin"), Map.entry("modmenu", "modmenu"));
    /** Pairs known to crash together, whatever their metadata says. */
    private static final List<String[]> KNOWN_CONFLICTS = List.of(
            new String[]{"optifabric", "sodium", "OptiFine (OptiFabric) and Sodium cannot run together"},
            new String[]{"optifabric", "iris", "OptiFine (OptiFabric) and Iris cannot run together"},
            new String[]{"rubidium", "sodium", "Rubidium is a Sodium copy; keep only one"},
            new String[]{"canvas", "sodium", "Canvas and Sodium both replace the renderer"});

    private ModHealth() {}

    static List<ModInfo> scan(Path modsDirectory) {
        List<ModInfo> mods = new ArrayList<>();
        if (!Files.isDirectory(modsDirectory)) return mods;
        try (Stream<Path> files = Files.list(modsDirectory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                ModInfo info = read(file);
                if (info != null) mods.add(info);
            }
        } catch (IOException e) {
            System.err.println("Could not scan mods: " + e);
        }
        return mods;
    }

    private static ModInfo read(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return null;
            JsonNode json;
            try (InputStream input = zip.getInputStream(entry)) {
                json = JSON.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            Set<String> provides = new HashSet<>();
            json.path("provides").forEach(node -> provides.add(node.asText()));
            for (JsonNode nested : json.path("jars")) {
                ZipEntry inner = zip.getEntry(nested.path("file").asText(""));
                if (inner == null) continue;
                try (InputStream input = zip.getInputStream(inner)) {
                    collectNested(input.readAllBytes(), provides, 0);
                }
            }
            return new ModInfo(file, json.path("id").asText(""), json.path("name").asText(json.path("id").asText("")),
                    json.path("version").asText(""), fields(json.path("depends")), fields(json.path("breaks")), provides);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Ids provided by jars-in-jars (Fabric API ships its modules this way), a few levels deep. */
    private static void collectNested(byte[] jar, Set<String> provides, int depth) throws IOException {
        if (depth > 3) return;
        Map<String, byte[]> nested = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("fabric.mod.json")) {
                    JsonNode json = JSON.readTree(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    provides.add(json.path("id").asText(""));
                    json.path("provides").forEach(node -> provides.add(node.asText()));
                } else if (entry.getName().startsWith("META-INF/jars/") && entry.getName().endsWith(".jar")) {
                    nested.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        for (byte[] inner : nested.values()) collectNested(inner, provides, depth + 1);
    }

    private static Map<String, JsonNode> fields(JsonNode node) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (node != null && node.isObject()) node.fields().forEachRemaining(field -> map.put(field.getKey(), field.getValue()));
        return map;
    }

    static List<Issue> check(List<ModInfo> mods, String gameVersion) {
        List<Issue> issues = new ArrayList<>();
        Map<String, List<ModInfo>> byId = new LinkedHashMap<>();
        Set<String> present = new HashSet<>(BUILT_IN);
        for (ModInfo mod : mods) {
            byId.computeIfAbsent(mod.id(), ignored -> new ArrayList<>()).add(mod);
            present.add(mod.id());
            present.addAll(mod.provides());
        }
        for (List<ModInfo> same : byId.values()) {
            if (same.size() < 2) continue;
            same.sort((a, b) -> Long.compare(modified(b.file()), modified(a.file())));
            for (ModInfo older : same.subList(1, same.size())) {
                issues.add(new Issue(Kind.DUPLICATE, older.name() + " is installed twice (" + older.file().getFileName()
                        + " and " + same.getFirst().file().getFileName() + "): the older copy will be turned off", older.file(), null));
            }
        }
        Set<String> missingReported = new HashSet<>();
        for (ModInfo mod : mods) {
            for (Map.Entry<String, JsonNode> dependency : mod.depends().entrySet()) {
                String id = dependency.getKey();
                if (id.equals("minecraft")) {
                    if (gameVersion != null && !gameVersion.isBlank() && !matchesAny(dependency.getValue(), gameVersion)) {
                        issues.add(new Issue(Kind.WRONG_VERSION, mod.name() + " is made for Minecraft "
                                + text(dependency.getValue()) + ", not " + gameVersion + ": it will be turned off", mod.file(), null));
                    }
                    continue;
                }
                if (present.contains(id) || BUILT_IN.contains(id) || id.startsWith("fabric-") && present.contains("fabric-api")) continue;
                if (missingReported.add(id)) {
                    issues.add(new Issue(Kind.MISSING_DEPENDENCY, mod.name() + " needs " + id + ", which is not installed: "
                            + "it will be installed from Modrinth", null, id));
                }
            }
            for (String broken : mod.breaks().keySet()) {
                if (present.contains(broken) && !broken.equals(mod.id())) {
                    issues.add(new Issue(Kind.BREAKS, mod.name() + " says it does not work with " + broken
                            + ": " + mod.name() + " will be turned off", mod.file(), null));
                }
            }
        }
        for (String[] pair : KNOWN_CONFLICTS) {
            if (present.contains(pair[0]) && present.contains(pair[1]) && byId.containsKey(pair[0])) {
                issues.add(new Issue(Kind.KNOWN_CONFLICT, pair[2] + ": " + pair[0] + " will be turned off",
                        byId.get(pair[0]).getFirst().file(), null));
            }
        }
        return issues;
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String text(JsonNode node) {
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(each -> parts.add(each.asText()));
            return String.join(" or ", parts);
        }
        return node.asText();
    }

    /** Fabric version predicates: "1.21.11", "1.21.x", "~1.21", ">=1.21 <1.22", "*", or an array of those (any). */
    static boolean matchesAny(JsonNode predicate, String version) {
        if (predicate.isArray()) {
            for (JsonNode each : predicate) if (matches(each.asText(), version)) return true;
            return predicate.isEmpty();
        }
        return matches(predicate.asText(), version);
    }

    private static final Pattern TERM = Pattern.compile("(>=|<=|>|<|=|~|\\^)?\\s*([0-9][0-9A-Za-z.\\-+*xX]*)");

    static boolean matches(String predicate, String version) {
        String text = predicate == null ? "" : predicate.trim();
        if (text.isEmpty() || text.equals("*")) return true;
        if (!version.matches("\\d+(\\.\\d+)*")) return true; // snapshots: do not guess
        Matcher matcher = TERM.matcher(text);
        boolean any = false;
        while (matcher.find()) {
            any = true;
            String op = matcher.group(1) == null ? "=" : matcher.group(1);
            String target = matcher.group(2).replaceAll("[-+].*$", "");
            if (target.contains("x") || target.contains("X") || target.contains("*")) {
                String prefix = target.replaceAll("[.]?[xX*].*$", "");
                if (!(version.equals(prefix) || version.startsWith(prefix + "."))) return false;
                continue;
            }
            int compare = compare(version, target);
            boolean ok = switch (op) {
                case ">=" -> compare >= 0;
                case "<=" -> compare <= 0;
                case ">" -> compare > 0;
                case "<" -> compare < 0;
                case "~" -> compare >= 0 && sameParts(version, target, 2);
                case "^" -> compare >= 0 && sameParts(version, target, 1);
                default -> compare == 0;
            };
            if (!ok) return false;
        }
        return any;
    }

    private static boolean sameParts(String a, String b, int parts) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < parts; i++) {
            String left = i < x.length ? x[i] : "0", right = i < y.length ? y[i] : "0";
            if (!left.equals(right)) return false;
        }
        return true;
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int left = i < x.length ? parse(x[i]) : 0, right = i < y.length ? parse(y[i]) : 0;
            if (left != right) return Integer.compare(left, right);
        }
        return 0;
    }

    private static int parse(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Turns a jar off by renaming it to .jar.disabled (Fabric ignores it; renaming it back turns it on). */
    static void disable(Path jar) throws IOException {
        if (!Files.exists(jar)) return;
        Path target = jar.resolveSibling(jar.getFileName() + ".disabled");
        Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static String slugFor(String modId) {
        return SLUGS.getOrDefault(modId, modId.replace('_', '-'));
    }

    /** Applies every fix; returns one line per action. Runs off the UI thread. */
    static List<String> fixAll(List<Issue> issues, FeatureHost host, Consumer<String> progress) throws InterruptedException {
        List<String> done = new ArrayList<>();
        Set<Path> disabled = new HashSet<>();
        for (Issue issue : issues) {
            try {
                if (issue.disable() != null && disabled.add(issue.disable())) {
                    disable(issue.disable());
                    done.add("Turned off " + issue.disable().getFileName());
                } else if (issue.install() != null) {
                    ModrinthModService.Target target = host.fabricTarget();
                    String slug = slugFor(issue.install());
                    progress.accept("Installing " + slug + " from Modrinth...");
                    ModrinthModService.ModProject project = host.modrinth().findBySlug(slug, issue.install(), target, progress);
                    if (project == null) {
                        done.add("No " + target.gameVersion() + " version of " + slug + " on Modrinth - install it by hand");
                    } else {
                        host.modrinth().install(project, host.settings().minecraftDirectory(), progress);
                        done.add("Installed " + slug);
                    }
                }
            } catch (IOException e) {
                done.add("Could not fix: " + issue.message() + " (" + e.getMessage() + ")");
            }
        }
        return done;
    }

    // ---- the card on the Mods page --------------------------------------------------------------------------

    static VBox card(FeatureHost host) {
        VBox card = ModsUi.glassCard(10, new Insets(20));
        VBox body = new VBox(6);
        Label status = ModsUi.text("Checking your mods...", 12, ModsUi.MUTED, false);
        status.setWrapText(true);
        List<Issue> found = new ArrayList<>();
        Button fix = UiKit.button("FIX ALL", true, () -> {});
        Button recheck = UiKit.button("CHECK AGAIN", false, () -> {});
        Runnable[] run = new Runnable[1];
        run[0] = () -> {
            status.setText("Checking your mods...");
            fix.setDisable(true);
            host.executor().execute(() -> {
                Path mods = host.settings().minecraftDirectory().resolve("mods");
                String gameVersion = "";
                try {
                    gameVersion = host.fabricTarget().gameVersion();
                } catch (IOException | RuntimeException ignored) {
                    // no Fabric profile selected: skip the version check
                }
                List<ModInfo> scanned = scan(mods);
                List<Issue> issues = check(scanned, gameVersion);
                Platform.runLater(() -> {
                    found.clear();
                    found.addAll(issues);
                    body.getChildren().clear();
                    if (issues.isEmpty()) {
                        status.setText("✓  " + scanned.size() + " mods checked: no conflicts, nothing missing.");
                    } else {
                        status.setText("⚠  " + issues.size() + " problem" + (issues.size() == 1 ? "" : "s") + " in "
                                + scanned.size() + " mods. FIX ALL turns problem mods off (renamed, not deleted) and installs what is missing.");
                        for (Issue issue : issues) {
                            Label line = ModsUi.text("•  " + issue.message(), 12,
                                    issue.kind() == Kind.MISSING_DEPENDENCY ? ModsUi.WARNING : ModsUi.TEXT, false);
                            line.setWrapText(true);
                            body.getChildren().add(line);
                        }
                    }
                    fix.setDisable(issues.isEmpty());
                });
            });
        };
        fix.setOnAction(event -> {
            fix.setDisable(true);
            List<Issue> issues = List.copyOf(found);
            host.executor().execute(() -> {
                try {
                    List<String> done = fixAll(issues, host, line -> Platform.runLater(() -> status.setText(line)));
                    Platform.runLater(() -> {
                        host.log("Mod repair: " + String.join("; ", done));
                        run[0].run();
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        recheck.setOnAction(event -> run[0].run());
        card.getChildren().addAll(ModsUi.sectionTitle("Mod health"), status, body, UiKit.row(10, fix, recheck));
        run[0].run();
        return card;
    }
}
