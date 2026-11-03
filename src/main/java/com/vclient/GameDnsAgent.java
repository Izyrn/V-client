package com.vclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Builds the small {@code -javaagent} jar that gives the game a secure-DNS fallback (see {@code com.vclient.agent}).
 * The game is a separate Java process, so the launcher's own resolver does not help it: on a network whose DNS filter
 * blocks a server's name, the game could not even find the server. The jar needs Java 21 or newer.
 */
final class GameDnsAgent {
    static final int MINIMUM_JAVA = 21;
    private static final String[] CLASSES = {"DohAgent", "GameResolverProvider", "GameResolver", "GameResolver$Client"};
    private static final String SERVICE = "META-INF/services/java.net.spi.InetAddressResolverProvider";

    private GameDnsAgent() {}

    static boolean supports(int javaMajor) {
        return javaMajor >= MINIMUM_JAVA;
    }

    /** Writes the agent jar (if it is missing or out of date) and returns its path. */
    static Path ensure() throws IOException {
        Path jar = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")),
                "V-Client", "dns-agent.jar");
        byte[] content = build();
        if (Files.isRegularFile(jar) && Arrays.equals(Files.readAllBytes(jar), content)) return jar;
        Files.createDirectories(jar.getParent());
        Path temporary = Files.createTempFile(jar.getParent(), "dns-agent-", ".tmp");
        try {
            Files.write(temporary, content);
            Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // A game that is already running can hold the old jar open; it still works, so use it.
            if (!Files.isRegularFile(jar)) throw e;
        } finally {
            Files.deleteIfExists(temporary);
        }
        return jar;
    }

    static byte[] build() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", "com.vclient.agent.DohAgent");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes, manifest)) {
            for (String name : CLASSES) {
                String path = "com/vclient/agent/" + name + ".class";
                try (InputStream in = GameDnsAgent.class.getResourceAsStream("/" + path)) {
                    if (in == null) throw new IOException("The launcher does not contain " + path);
                    entry(out, path, in.readAllBytes());
                }
            }
            entry(out, SERVICE, "com.vclient.agent.GameResolverProvider\n".getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static void entry(JarOutputStream out, String name, byte[] data) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(315532800000L); // fixed time, so the same launcher always builds an identical jar
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }
}
