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
 * certificate), it opens a real Edge/Chrome window routed through VYRN's secure-DNS proxy instead, so the page
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
                if (!Desktop.isDesktopSupported()) throw new IOException("This computer does not support opening links from VYRN.");
                Desktop.getDesktop().browse(uri);
            } catch (IOException | RuntimeException e) {
                onProblem.accept("Could not open " + address + ": " + e.getMessage());
            }
        }, "open-link");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * A real, visible Edge/Chrome window with VYRN's own profile, routed through the secure-DNS proxy. False when
     * no browser exists.
     *
     * <p>Two things used to break this window with a "connection isn't private" page, so both are handled here:
     * a browser process still running for this profile from an earlier launcher run ignores the new window's
     * {@code --proxy-server} flag (the new address is just handed over to it), and a profile that is signed in
     * picks up the player's synced extensions and settings, which can reroute traffic around the proxy. The old
     * process of this profile is closed first, and extensions, sync and QUIC (which does not go through an HTTP
     * proxy) are turned off. Certificate checks are never weakened.
     */
    static boolean openProxied(String url) throws IOException {
        Path exe = ChromiumHost.findChromium();
        if (exe == null) return false;
        Path profile = AppInfo.dataDir().resolve("browser-real");
        Files.createDirectories(profile);
        closeBrowserUsing(profile);
        new ProcessBuilder(exe.toString(), "--user-data-dir=" + profile,
                "--proxy-server=http://127.0.0.1:" + DohProxy.shared().port(),
                "--disable-extensions", "--disable-sync", "--disable-quic", "--disable-background-mode",
                "--no-first-run", "--no-default-browser-check", "--new-window", url)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        return true;
    }

    /** Ends browser processes started for exactly this profile folder (only VYRN's own, never the player's browser). */
    private static void closeBrowserUsing(Path profile) {
        String marker = profile.toString().toLowerCase(java.util.Locale.ROOT);
        boolean closedAny = false;
        for (ProcessHandle process : (Iterable<ProcessHandle>) ProcessHandle.allProcesses()::iterator) {
            String command = process.info().commandLine().orElse("").toLowerCase(java.util.Locale.ROOT);
            if (command.contains("--user-data-dir=" + marker)) {
                process.destroy();
                closedAny = true;
            }
        }
        if (closedAny) {
            try {
                Thread.sleep(700); // let the profile's lock go before the new window opens
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
