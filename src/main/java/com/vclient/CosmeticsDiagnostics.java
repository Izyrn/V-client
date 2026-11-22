package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The launcher's "Diagnose" button on the Cosmetics page. It checks, one at a time and each with a hard
 * 10-second limit, everything cosmetics and skins depend on, and reports which part is broken and why.
 * Tokens are only sent to the account's own login server and are never written to a log or to the screen.
 */
final class CosmeticsDiagnostics {
    enum Result { OK, FAIL, INFO }

    record Check(String name, Result result, String detail) {}

    /** Everything a run needs to know; null/blank fields mean "not available" and the check is skipped. */
    record Context(String backendUrl, PlayerAccount account, String backendToken, boolean offlineMode) {}

    static final int TIMEOUT_SECONDS = 10;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private CosmeticsDiagnostics() {}

    private record Reply(int status, String contentType, String body) {}

    /** Runs every check, reporting each as soon as it finishes. Blocking: call it from a background thread. */
    static List<Check> run(Context context, Consumer<Check> sink) {
        List<Check> all = new ArrayList<>();
        Consumer<Check> add = check -> {
            all.add(check);
            sink.accept(check);
        };
        try {
            backend(context, add);
            skinApi(context, add);
            texture("Skin texture URL", context.account() == null ? "" : context.account().skinUrl(), add);
            texture("Cape texture URL", context.account() == null ? "" : context.account().capeUrl(), add);
            authentication(context, add);
            mojangTextures(add);
        } catch (RuntimeException e) {
            add.accept(new Check("Diagnostics", Result.FAIL, "crashed: " + describe(e)));
        }
        return all;
    }

    static String describe(Throwable error) {
        if (error instanceof java.net.http.HttpTimeoutException) return "timed out after " + TIMEOUT_SECONDS + " seconds";
        if (error instanceof UnknownHostException) return "unknown host " + error.getMessage()
                + " (DNS failed or is blocked on this network)";
        if (error instanceof javax.net.ssl.SSLException) return "TLS/HTTPS failure: " + error.getMessage()
                + " (a DNS filter, proxy or antivirus may be blocking this host)";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }

    private static Reply request(String method, String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json, image/png, */*").header("User-Agent", "VYRN/" + AppInfo.VERSION);
        switch (method) {
            case "HEAD" -> request.method("HEAD", HttpRequest.BodyPublishers.noBody());
            case "POST" -> request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            default -> request.GET();
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse("unknown"),
                response.body() == null ? "" : response.body());
    }

    private static void backend(Context context, Consumer<Check> add) throws RuntimeException {
        String base = BackendClient.normalize(context.backendUrl());
        if (!BackendClient.isValidUrl(base)) {
            add.accept(new Check("Backend address", Result.INFO, "none set - only the built-in cosmetics are available. "
                    + "Set it on the News page (Backend & updates)."));
            return;
        }
        if (context.offlineMode()) {
            add.accept(new Check("Backend address", Result.INFO, base + " (offline mode is on, so it is not contacted)"));
            return;
        }
        add.accept(new Check("Backend address", Result.INFO, base));
        try {
            Reply health = request("GET", base + "/api/health", null);
            add.accept(new Check("Cosmetic API reachable", health.status() == 200 ? Result.OK : Result.FAIL,
                    "GET /api/health -> HTTP " + health.status()));
            if (health.status() != 200) return;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            add.accept(new Check("Cosmetic API reachable", Result.FAIL, describe(e)));
            return;
        }
        JsonNode catalog = null;
        try {
            Reply reply = request("GET", base + "/api/cosmetics/catalog", null);
            if (reply.status() != 200) {
                add.accept(new Check("Cosmetic catalog", Result.FAIL, "HTTP " + reply.status()
                        + (reply.status() == 404 ? " - the backend has no cosmetics API yet (redeploy it)" : "")));
            } else {
                try {
                    catalog = JSON.readTree(reply.body());
                    boolean valid = catalog.path("items").isArray();
                    add.accept(new Check("Cosmetic catalog", valid ? Result.OK : Result.FAIL,
                            valid ? catalog.path("items").size() + " items in the online catalog"
                                    : "the response has no items list"));
                } catch (IOException e) {
                    add.accept(new Check("Cosmetic catalog", Result.FAIL, "not JSON (" + reply.contentType()
                            + ") - wrong backend address, or the service is still waking up"));
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            add.accept(new Check("Cosmetic catalog", Result.FAIL, describe(e)));
        }
        PlayerAccount account = context.account();
        if (account != null && account.type() != PlayerAccount.Type.OFFLINE) {
            try {
                Reply owned = request("GET", base + "/api/cosmetics/owned/" + account.uuid().replace("-", ""), null);
                add.accept(new Check("Owned cosmetics", owned.status() == 200 ? Result.OK : Result.FAIL,
                        "GET /api/cosmetics/owned/<uuid> -> HTTP " + owned.status()));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                add.accept(new Check("Owned cosmetics", Result.FAIL, describe(e)));
            }
        }
        if (catalog != null && catalog.path("items").isArray()) {
            int checked = 0;
            int missing = 0;
            StringBuilder detail = new StringBuilder();
            for (JsonNode item : catalog.path("items")) {
                for (JsonNode asset : item.path("assets")) {
                    if (checked >= 15) break;
                    checked++;
                    String file = asset.path("file").asText("?");
                    try {
                        String url = asset.path("url").asText("");
                        Reply head = request("HEAD", url.startsWith("http") ? url : base + url, null);
                        if (head.status() != 200) {
                            missing++;
                            detail.append(file).append(" (HTTP ").append(head.status()).append(") ");
                        }
                    } catch (IOException | InterruptedException | RuntimeException e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        missing++;
                        detail.append(file).append(" (").append(describe(e)).append(") ");
                    }
                }
            }
            add.accept(new Check("Asset files exist", checked == 0 ? Result.INFO : missing == 0 ? Result.OK : Result.FAIL,
                    checked == 0 ? "the online catalog has no downloadable assets"
                            : (checked - missing) + " of " + checked + " reachable" + (missing > 0 ? " - missing: " + detail : "")));
        }
        if (context.backendToken() != null && !context.backendToken().isBlank()) {
            try {
                HttpRequest me = HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                        .header("Authorization", "Bearer " + context.backendToken()).GET().build();
                int status = HTTP.send(me, HttpResponse.BodyHandlers.discarding()).statusCode();
                add.accept(new Check("VYRN account token", status == 200 ? Result.OK : Result.FAIL,
                        status == 200 ? "valid" : "rejected (HTTP " + status + ") - sign in again on the News page"));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                add.accept(new Check("VYRN account token", Result.FAIL, describe(e)));
            }
        } else {
            add.accept(new Check("VYRN account token", Result.INFO, "not signed in to the VYRN backend (not needed for cosmetics)"));
        }
    }

    private static void skinApi(Context context, Consumer<Check> add) {
        PlayerAccount account = context.account();
        if (account == null || account.type() != PlayerAccount.Type.ELY_BY) {
            add.accept(new Check("Skin API (Ely.by)", Result.INFO, "the selected account is not an Ely.by account"));
            return;
        }
        if (context.offlineMode()) {
            add.accept(new Check("Skin API (Ely.by)", Result.INFO, "offline mode is on, so it is not contacted"));
            return;
        }
        try {
            Reply reply = request("GET", "https://authserver.ely.by/api/authlib-injector/sessionserver/session/minecraft/profile/"
                    + account.uuid().replace("-", ""), null);
            boolean ok = reply.status() == 200;
            String detail = "session profile for " + account.name() + " -> HTTP " + reply.status();
            if (ok) {
                ElyByAuthService.SkinProfile profile = ElyByAuthService.parseSessionProfile(JSON.readTree(reply.body()));
                detail += profile.hasSkin() ? ", skin " + profile.skinUrl() + " (" + profile.model() + ")" : ", no skin equipped";
            }
            add.accept(new Check("Skin API (Ely.by)", ok ? Result.OK : Result.FAIL, detail));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            add.accept(new Check("Skin API (Ely.by)", Result.FAIL, describe(e)));
        }
    }

    private static void texture(String name, String url, Consumer<Check> add) {
        if (url == null || url.isBlank()) {
            add.accept(new Check(name, Result.INFO, "none saved for this account"));
            return;
        }
        try {
            Reply reply = request("HEAD", url, null);
            boolean ok = reply.status() == 200 && reply.contentType().startsWith("image/");
            add.accept(new Check(name, ok ? Result.OK : Result.FAIL,
                    "HTTP " + reply.status() + ", " + reply.contentType() + " - " + url
                            + (reply.status() == 404 ? " (the file no longer exists; the launcher refreshes it at launch)" : "")));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            add.accept(new Check(name, Result.FAIL, describe(e) + " - " + url));
        }
    }

    private static void authentication(Context context, Consumer<Check> add) {
        PlayerAccount account = context.account();
        if (account == null) {
            add.accept(new Check("Game account", Result.FAIL, "no account is selected"));
            return;
        }
        if (account.type() != PlayerAccount.Type.ELY_BY) {
            add.accept(new Check("Game account", Result.INFO, account.name() + " (" + account.type().label()
                    + "): the token is not checked here"));
            return;
        }
        if (context.offlineMode()) {
            add.accept(new Check("Game account", Result.INFO, account.name() + ": offline mode is on, token not checked"));
            return;
        }
        try {
            ObjectNode body = JSON.createObjectNode().put("accessToken", account.accessToken());
            Reply reply = request("POST", "https://authserver.ely.by/auth/validate", body.toString());
            boolean valid = reply.status() == 200 || reply.status() == 204;
            add.accept(new Check("Game account", valid ? Result.OK : Result.FAIL, account.name() + ": the Ely.by token is "
                    + (valid ? "valid" : "invalid or expired (HTTP " + reply.status() + ") - sign in again")));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            add.accept(new Check("Game account", Result.FAIL, "could not validate the token: " + describe(e)));
        }
    }

    /** Some networks answer Mojang's skin host with a block page, so other players' skins never load. */
    private static void mojangTextures(Consumer<Check> add) {
        String host = "textures.minecraft.net";
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            boolean blocked = false;
            for (InetAddress address : addresses) {
                if (address.getHostAddress().startsWith("146.112.") || address.isLoopbackAddress()
                        || address.isAnyLocalAddress() || address.isSiteLocalAddress()) blocked = true;
            }
            if (blocked) {
                add.accept(new Check("Mojang skin server", Result.FAIL, host + " resolves to " + addresses[0].getHostAddress()
                        + ", a DNS filter's block page (OpenDNS / Cisco Umbrella style). Other players' skins and server-set "
                        + "skins cannot load on this network. Fix: set this PC's or your router's DNS to 1.1.1.1 / 8.8.8.8."));
                return;
            }
            Reply reply = request("HEAD", "https://" + host + "/", null);
            add.accept(new Check("Mojang skin server", Result.OK, host + " -> " + addresses[0].getHostAddress()
                    + ", HTTPS answered HTTP " + reply.status()));
        } catch (UnknownHostException e) {
            add.accept(new Check("Mojang skin server", Result.FAIL, "cannot resolve " + host + " (DNS blocked or offline)"));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            add.accept(new Check("Mojang skin server", Result.FAIL, describe(e)));
        }
    }
}
