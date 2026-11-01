package com.vclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.DoubleConsumer;

/** Downloads a launcher update, verifies its SHA-256, and hands it to the Windows installer. */
final class UpdateService {
    private static final HttpClient HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private UpdateService() {
    }

    /** True when {@code candidate} is a newer dotted-numeric version than {@code current}. */
    static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    static int compare(String a, String b) {
        String[] left = a.split("[.\\-+]");
        String[] right = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? parse(left[i]) : 0;
            int r = i < right.length ? parse(right[i]) : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    private static int parse(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Downloads the release installer into a temp folder and checks its size and SHA-256 against the backend's
     * metadata. Nothing is run if verification fails.
     */
    static Path download(BackendClient.Release release, DoubleConsumer progress) throws IOException, InterruptedException {
        if (release.url().isBlank()) throw new IOException("This release has no installer file.");
        URI uri = URI.create(release.url());
        String fileName = Path.of(uri.getPath()).getFileName().toString();
        if (!fileName.toLowerCase().endsWith(".exe")) throw new IOException("The update is not a Windows installer (" + fileName + ").");
        Path directory = Files.createTempDirectory("vclient-update-");
        Path target = directory.resolve(fileName);
        Path partial = directory.resolve(fileName + ".part");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(20))
                .header("User-Agent", "V-Client/" + AppInfo.VERSION).GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("Update download failed (HTTP " + response.statusCode() + ").");
        long total = response.headers().firstValueAsLong("Content-Length").orElse(release.size());
        MessageDigest digest = sha256();
        long received = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (Thread.interrupted()) throw new InterruptedException("Update download cancelled.");
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                received += read;
                if (total > 0) progress.accept(Math.min(1.0, (double) received / total));
            }
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        if (release.size() > 0 && received != release.size()) {
            Files.deleteIfExists(partial);
            throw new IOException("The downloaded update is the wrong size (" + received + " of " + release.size() + " bytes).");
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!release.sha256().isBlank() && !actual.equalsIgnoreCase(release.sha256())) {
            Files.deleteIfExists(partial);
            throw new IOException("The downloaded update failed its SHA-256 check and was discarded.");
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * Starts the installer a few seconds from now. The installer refuses to run while V-Client is open,
     * so the caller must exit right after this returns.
     */
    static void launchInstallerAfterExit(Path installer) throws IOException {
        String quoted = installer.toAbsolutePath().toString().replace("\"", "");
        new ProcessBuilder("cmd.exe", "/c", "ping -n 3 127.0.0.1 >nul & start \"\" \"" + quoted + "\" --silent")
                .redirectErrorStream(true).start();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
