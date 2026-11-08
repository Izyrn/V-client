package com.vclient;

/** Build-time constants shared by the launcher's backend features. */
final class AppInfo {
    /** Launcher version reported to the update service. Bump together with the pom version and the published release. */
    static final String VERSION = "1.6.26";

    /**
     * The one place the V-Client API address lives (a Cloudflare Worker, see {@code cloudflare/}). Players can override it
     * on the News page and the {@code VCLIENT_BACKEND_URL} environment variable wins over both. Change it here after
     * deploying the Worker to your own workers.dev address or custom domain.
     */
    static final String DEFAULT_BACKEND_URL = "https://vclient-api.vclient-api.workers.dev";

    /** Old backends that no longer exist; a saved copy of one of these is replaced by the default. */
    static final java.util.List<String> RETIRED_BACKEND_URLS = java.util.List.of(
            "https://vsmp-client.onrender.com", "https://vsmp-client-backend.onrender.com");

    static final String PLATFORM = "windows";

    private AppInfo() {
    }

    static boolean isRetiredBackend(String url) {
        String normalized = BackendClient.normalize(url);
        return RETIRED_BACKEND_URLS.stream().anyMatch(normalized::equalsIgnoreCase);
    }
}
