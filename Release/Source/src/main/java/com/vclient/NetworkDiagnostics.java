package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class NetworkDiagnostics {
    private static final URI MOJANG_MANIFEST = URI.create(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final URI INTERNET_PROBE = URI.create("https://www.msftconnecttest.com/connecttest.txt");
    private static final List<String> DNS_HOSTS = List.of(
            "piston-meta.mojang.com", "www.msftconnecttest.com", "api.modrinth.com", "textures.minecraft.net",
            "authserver.ely.by");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** {@code hint} is a one-line fix suggestion shown next to a failed check; empty when the check passed. */
    record Check(String name, boolean passed, String detail, String hint) {
        Check(String name, boolean passed, String detail) {
            this(name, passed, detail, "");
        }

        Check withHint(String hint) {
            return new Check(name, passed, detail, passed ? "" : hint);
        }
    }

    record Report(List<Check> checks, String proxy, String tlsProtocols) {
        boolean passed() {
            return checks.stream().allMatch(Check::passed);
        }

        String format() {
            StringBuilder result = new StringBuilder();
            result.append(passed() ? "CONNECTION TEST PASSED" : "CONNECTION TEST FAILED").append('\n')
                    .append("Proxy: ").append(proxy).append('\n')
                    .append("TLS enabled: ").append(tlsProtocols).append("\n\n");
            for (Check check : checks) {
                result.append(check.passed() ? "[PASS] " : "[FAIL] ")
                        .append(check.name()).append(": ").append(check.detail()).append('\n');
            }
            return result.toString().stripTrailing();
        }
    }

    private NetworkDiagnostics() {}

    static Report test(Consumer<String> log) {
        List<Check> checks = new ArrayList<>();
        String proxy = PlatformTls.proxyDescription(MOJANG_MANIFEST);
        log.accept("Connection test starting. Proxy: " + proxy
                + "; enabled TLS protocols: " + PlatformTls.enabledProtocols() + ".");

        Check dns = resolveHosts();
        checks.add(dns);
        log.accept((dns.passed() ? "PASS " : "FAIL ") + "DNS resolution: " + dns.detail());

        Check internet = testInternet(log);
        checks.add(internet);

        Check tlsCheck = testMojangTls(log);
        checks.add(tlsCheck);
        Check apiCheck = testMojangApi(log);
        checks.add(apiCheck);
        return new Report(List.copyOf(checks), proxy, PlatformTls.enabledProtocols());
    }

    /**
     * The four-line startup checklist: DNS, TLS, Mojang and the V-Client backend, each with a fix hint when it fails.
     * Never called in Offline mode (the caller skips it, because nothing may be contacted then).
     */
    static List<Check> checklist(String backendUrl, Consumer<String> log) {
        List<Check> result = new ArrayList<>();
        Check dns = resolveHosts();
        result.add(new Check("DNS", dns.passed(), dns.detail()).withHint(
                "Set this PC's DNS to automatic, or to 1.1.1.1 / 8.8.8.8, and make sure no filter or VPN blocks Mojang hosts."));
        Check tls = testMojangTls(log);
        result.add(new Check("TLS", tls.passed(), tls.detail(), tls.hint()));
        Check mojang = testMojangApi(log);
        result.add(new Check("Mojang", mojang.passed(), mojang.detail(), "").withHint(
                "Mojang may be down or blocked by your network. Check status.minecraft.net, or try another network."));
        result.add(testBackend(backendUrl, log));
        return List.copyOf(result);
    }

    /** Reaches the V-Client backend; a sleeping free-tier server counts as "waking up", not as a failure. */
    static Check testBackend(String backendUrl, Consumer<String> log) {
        BackendClient client = new BackendClient(BackendClient.resolveUrl(backendUrl));
        if (!client.configured()) {
            return new Check("Backend", false, "No backend address is set.",
                    "Open News → Backend & updates and enter the V-Client backend address.");
        }
        try {
            client.serverStatus();
            return new Check("Backend", true, "Backend answered at " + client.baseUrl() + ".");
        } catch (IOException e) {
            log.accept("FAIL Backend: " + PlatformTls.describeFailure(e));
            if (BackendClient.isWaking(e)) {
                return new Check("Backend", false, "Server waking up… " + e.getMessage(),
                        "The free backend sleeps when idle and takes up to a minute to start. Wait a moment and press Test again.");
            }
            String interception = PlatformTls.interceptionHint(e);
            return new Check("Backend", false, PlatformTls.describeFailure(e),
                    interception.isEmpty() ? "Check the backend address on the News page and your internet connection." : interception);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check("Backend", false, "Backend check was interrupted.", "");
        }
    }

    static void requireInternetAccess(Consumer<String> log) throws IOException, InterruptedException {
        Check internet = testInternet(log);
        if (internet.passed()) return;
        log.accept("General HTTPS probe failed; checking Mojang HTTPS before blocking the Minecraft download.");
        Check mojang = testMojangTls(log);
        if (mojang.passed()) {
            log.accept("Mojang HTTPS is reachable despite the general connectivity probe failure; continuing.");
            return;
        }
        throw new IOException("Internet connectivity check failed before Minecraft download: "
                + internet.detail() + " Mojang HTTPS preflight also failed: " + mojang.detail());
    }

    private static Check testInternet(Consumer<String> log) {
        HttpRequest request = HttpRequest.newBuilder(INTERNET_PROBE)
                .timeout(Duration.ofSeconds(15)).header("Cache-Control", "no-cache").GET().build();
        try {
            HttpResponse<String> response = PlatformTls.sendWithRetries(
                    HTTP, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), log);
            if (response.statusCode() >= 200 && response.statusCode() < 300
                    && response.body().strip().equals("Microsoft Connect Test")) {
                String detail = "HTTPS connectivity verified at " + response.uri().getHost()
                        + " (HTTP " + response.statusCode() + ").";
                log.accept(detail);
                return new Check("Internet access", true, detail);
            }
            String detail = "Unexpected response from " + response.uri().getHost() + " (HTTP "
                    + response.statusCode() + "); a proxy or captive portal may be intercepting the probe. "
                    + "Selected route: " + PlatformTls.proxyDescription(INTERNET_PROBE) + ".";
            log.accept("FAIL Internet access: " + detail);
            return new Check("Internet access", false, detail);
        } catch (IOException e) {
            String detail = PlatformTls.describeFailure(e) + ". Selected route: "
                    + PlatformTls.proxyDescription(INTERNET_PROBE) + ".";
            log.accept("FAIL Internet access: " + detail);
            return new Check("Internet access", false, detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check("Internet access", false, "Connectivity check was interrupted.");
        }
    }

    private static Check testMojangTls(Consumer<String> log) {
        try {
            HttpRequest request = HttpRequest.newBuilder(MOJANG_MANIFEST).timeout(Duration.ofSeconds(20))
                    .header("Range", "bytes=0-0").GET().build();
            HttpResponse<Void> response = PlatformTls.sendWithRetries(
                    HTTP, request, HttpResponse.BodyHandlers.discarding(), log);
            String detail = "HTTPS certificate chain validated for " + response.uri().getHost()
                    + " (HTTP " + response.statusCode() + ").";
            log.accept("PASS TLS certificate validation: " + detail);
            return new Check("TLS certificate validation", true, detail);
        } catch (IOException e) {
            String detail = PlatformTls.describeFailure(e);
            String interception = PlatformTls.interceptionHint(e);
            if (!interception.isEmpty()) detail += ". " + interception;
            detail += " Proxy: " + PlatformTls.proxyDescription(MOJANG_MANIFEST) + ".";
            log.accept("FAIL TLS certificate validation: " + detail);
            return new Check("TLS certificate validation", false, detail,
                    interception.isEmpty() ? "Check your PC clock and Windows Update, then run the test again." : interception);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check("TLS certificate validation", false, "TLS check was interrupted.");
        }
    }

    private static Check testMojangApi(Consumer<String> log) {
        HttpRequest request = HttpRequest.newBuilder(MOJANG_MANIFEST).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = PlatformTls.sendWithRetries(
                    HTTP, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), log);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = "HTTP " + response.statusCode() + " from " + response.uri()
                        + ". Proxy: " + PlatformTls.proxyDescription(MOJANG_MANIFEST) + ".";
                log.accept("FAIL Mojang API: " + detail);
                return new Check("Mojang API access", false, detail);
            }
            JsonNode manifest = JSON.readTree(response.body());
            String latest = manifest.path("latest").path("release").asText("");
            if (latest.isBlank() || !manifest.path("versions").isArray()) {
                return new Check("Mojang API access", false,
                        "Mojang responded, but the version manifest was missing expected fields.");
            }
            String detail = "Mojang version manifest validated; latest release is " + latest + ".";
            log.accept("PASS Mojang API: " + detail);
            return new Check("Mojang API access", true, detail);
        } catch (IOException e) {
            String detail = PlatformTls.describeFailure(e) + ". Proxy: "
                    + PlatformTls.proxyDescription(MOJANG_MANIFEST) + ".";
            log.accept("FAIL Mojang API: " + detail);
            return new Check("Mojang API access", false, detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check("Mojang API access", false, "Mojang API check was interrupted.");
        }
    }

    private static Check resolveHosts() {
        CompletableFuture<String> resolution = CompletableFuture.supplyAsync(() -> {
            List<String> addresses = new ArrayList<>();
            try {
                for (String host : DNS_HOSTS) {
                    String resolved = java.util.Arrays.stream(InetAddress.getAllByName(host))
                            .map(InetAddress::getHostAddress).distinct()
                            .reduce((left, right) -> left + ", " + right).orElse("no addresses");
                    addresses.add(host + " -> " + resolved);
                }
                return String.join("; ", addresses);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        try {
            String detail = resolution.get(8, TimeUnit.SECONDS);
            List<String> blocked = new ArrayList<>();
            for (String host : DNS_HOSTS) {
                DnsBlock.Finding finding = DnsBlock.inspect(host);
                if (finding != null) blocked.add(DnsBlock.explain(finding));
            }
            if (!blocked.isEmpty()) return new Check("DNS resolution", false, String.join(" ", blocked) + " [" + detail + "]");
            return new Check("DNS resolution", true, detail);
        } catch (java.util.concurrent.TimeoutException e) {
            resolution.cancel(true);
            return new Check("DNS resolution", false,
                    "DNS lookup timed out after 8 seconds for " + String.join(", ", DNS_HOSTS) + ".");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Check("DNS resolution", false,
                    "DNS lookup failed: " + PlatformTls.describeFailure(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check("DNS resolution", false, "DNS lookup was interrupted.");
        }
    }
}
