package com.vclient;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Opens a web address from the launcher. Normally that is the player's own browser. When this network's DNS
 * filter answers the site with a block page (Cisco Umbrella / OpenDNS style, which shows up as an invalid
 * certificate), it opens a real Edge/Chrome window routed through V-Client's secure-DNS proxy instead, so the page
 * loads without changing anything on the PC.
 */
final class ExternalLinks {
    private ExternalLinks() {}

    /** Opens {@code address}; {@code onProblem} gets a short message if nothing could open it. Never blocks the caller. */
    static void open(String address, Consumer<String> onProblem) {
        Thread worker = new Thread(() -> {
            try {
                URI uri = URI.create(address);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
                if ((scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null
                        && DnsBlock.inspect(uri.getHost()) != null && openProxied(address)) {
                    return;
                }
                if (!Desktop.isDesktopSupported()) throw new IOException("This computer does not support opening links from V-Client.");
                Desktop.getDesktop().browse(uri);
            } catch (IOException | RuntimeException e) {
                onProblem.accept("Could not open " + address + ": " + e.getMessage());
            }
        }, "open-link");
        worker.setDaemon(true);
        worker.start();
    }

    /** A real, visible Edge/Chrome window with its own profile and the secure-DNS proxy. False when no browser exists. */
    static boolean openProxied(String url) throws IOException {
        Path exe = ChromiumHost.findChromium();
        if (exe == null) return false;
        Path profile = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")),
                "V-Client", "browser-real");
        Files.createDirectories(profile);
        new ProcessBuilder(exe.toString(), "--user-data-dir=" + profile,
                "--proxy-server=http://127.0.0.1:" + DohProxy.shared().port(), "--no-first-run", "--new-window", url)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        return true;
    }
}
