package com.vclient;

/** Build-time constants shared by the launcher's backend features. */
final class AppInfo {
    /** Launcher version reported to the update service. Bump together with the pom version and the published release. */
    static final String VERSION = "1.6.17";

    /**
     * Base URL of the V-Client backend (the {@code backend/} project), for example {@code https://vclient.example.com}.
     * Players can override it in the News page; the {@code VCLIENT_BACKEND_URL} environment variable also wins over this value.
     * Leave empty to ship a build that does not contact any backend until one is configured.
     */
    static final String DEFAULT_BACKEND_URL = "https://vsmp-client-backend.onrender.com";

    /** The old static-website service. It has no API, so a saved copy of this address is replaced by the default. */
    static final String RETIRED_BACKEND_URL = "https://vsmp-client.onrender.com";

    static final String PLATFORM = "windows";

    private AppInfo() {
    }
}
