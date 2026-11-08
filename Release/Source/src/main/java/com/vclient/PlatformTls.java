package com.vclient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class PlatformTls {
    private static final List<String> TLS_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");
    private static final int MAX_ATTEMPTS = 3;

    static {
        System.setProperty("java.net.useSystemProxies", "true");
    }

    private PlatformTls() {}

    static SSLContext windowsTrustContext() {
        try {
            KeyStore windowsRoots = KeyStore.getInstance("Windows-ROOT");
            windowsRoots.load(null);
            TrustManagerFactory windowsFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            windowsFactory.init(windowsRoots);
            TrustManagerFactory javaFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            javaFactory.init((KeyStore) null);

            List<X509ExtendedTrustManager> managers = new ArrayList<>();
            addTrustManagers(managers, windowsFactory.getTrustManagers());
            addTrustManagers(managers, javaFactory.getTrustManagers());
            if (managers.isEmpty()) throw new GeneralSecurityException("No X.509 trust managers are available.");

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new WindowsAndJavaTrustManager(managers)}, null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(
                    "Could not initialize TLS with the Windows trusted root store and Java public roots.", e);
        }
    }

    static HttpClient.Builder httpClientBuilder(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .sslContext(windowsTrustContext())
                .sslParameters(secureProtocols());
        ProxySelector selector = proxySelector();
        if (selector != null) builder.proxy(selector);
        return builder;
    }

    static String enabledProtocols() {
        return String.join(", ", TLS_PROTOCOLS);
    }

    static SSLParameters secureProtocolParameters() {
        return secureProtocols();
    }

    static String proxyDescription(URI endpoint) {
        try {
            List<Proxy> proxies = proxySelector().select(endpoint);
            if (proxies == null || proxies.isEmpty()
                    || proxies.stream().allMatch(proxy -> proxy.type() == Proxy.Type.DIRECT)) {
                return "DIRECT (no system or environment proxy selected)";
            }
            return proxies.stream().map(proxy -> {
                if (proxy.type() == Proxy.Type.DIRECT) return "DIRECT";
                if (proxy.address() instanceof InetSocketAddress address) {
                    return proxy.type() + " " + address.getHostString() + ":" + address.getPort();
                }
                return proxy.type() + " " + proxy.address();
            }).reduce((left, right) -> left + ", " + right).orElse("unknown proxy");
        } catch (RuntimeException e) {
            return "Proxy detection failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static <T> HttpResponse<T> sendWithRetries(
            HttpClient client,
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler,
            Consumer<String> log) throws IOException, InterruptedException {
        if ("https".equalsIgnoreCase(request.uri().getScheme())) requireHostName(request.uri());
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.accept("HTTPS " + request.method() + " " + request.uri()
                        + " via " + proxyDescription(request.uri())
                        + " (TLS " + enabledProtocols() + "), attempt " + attempt + "/" + MAX_ATTEMPTS + ".");
                HttpResponse<T> response = client.send(request, bodyHandler);
                if (!retryableStatus(response.statusCode()) || attempt == MAX_ATTEMPTS) return response;
                log.accept("HTTP " + response.statusCode() + " from " + response.uri()
                        + "; retrying a transient server response.");
                long retryDelay = retryDelayMillis(response);
                log.accept("Waiting " + retryDelay + " ms before retrying "
                        + response.uri().getHost() + ".");
                TimeUnit.MILLISECONDS.sleep(retryDelay);
                continue;
            } catch (IOException e) {
                if (hasCause(e, SSLException.class) || attempt == MAX_ATTEMPTS) {
                    log.accept("HTTPS failed for " + request.uri() + ": " + describeFailure(e));
                    throw e;
                }
                log.accept("Attempt " + attempt + " failed for " + request.uri()
                        + ": " + describeFailure(e) + "; retrying.");
            }
            TimeUnit.MILLISECONDS.sleep(350L * attempt);
        }
        throw new IOException("HTTPS request exhausted its retry limit: " + request.uri());
    }

    static String describeFailure(Throwable error) {
        List<String> causes = new ArrayList<>();
        Throwable current = error;
        while (current != null && causes.size() < 8) {
            String detail = current.getClass().getSimpleName()
                    + (current.getMessage() == null || current.getMessage().isBlank()
                    ? "" : ": " + current.getMessage());
            if (causes.isEmpty() || !causes.getLast().equals(detail)) causes.add(detail);
            current = current.getCause();
        }
        return String.join(" <- ", causes);
    }

    static boolean hasCertificateFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CertificateException) return true;
            String message = current.getMessage();
            if (message != null && (message.toLowerCase(java.util.Locale.ROOT).contains("certificate")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("certpath")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("trust anchor"))) return true;
            current = current.getCause();
        }
        return false;
    }

    static boolean hasHostnameMismatch(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("no subject alternative dns name")
                        || normalized.contains("no subject alternative names matching")
                        || normalized.contains("no name matching")
                        || normalized.contains("doesn't match") && normalized.contains("certificate")) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static SSLParameters secureProtocols() {
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(TLS_PROTOCOLS.toArray(String[]::new));
        // Always verify that the certificate matches the host name we asked for (the JDK also sends it as SNI).
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return parameters;
    }

    /** True for URLs like https://150.171.109.163/..., which can never pass host name verification. */
    static boolean isIpLiteral(URI uri) {
        String host = uri == null ? null : uri.getHost();
        return host != null && (host.matches("[0-9.]+") || host.startsWith("[") || host.contains(":"));
    }

    /** Refuses IP-address URLs: every Mojang / Microsoft / backend request must use a host name (SNI + verification). */
    static URI requireHostName(URI uri) {
        if (isIpLiteral(uri)) {
            throw new IllegalArgumentException("Refusing to connect to an IP address (" + uri.getHost()
                    + "): V-Client only makes HTTPS requests by host name so the certificate can be verified.");
        }
        return uri;
    }

    private static final List<String> INTERCEPTORS = List.of(
            "kaspersky", "eset", "avast", "avg ", "bitdefender", "mcafee", "norton", "symantec", "sophos", "trend micro",
            "malwarebytes", "webroot", "panda", "f-secure", "zscaler", "fortinet", "fortigate", "palo alto", "cisco",
            "umbrella", "netskope", "forcepoint", "blue coat", "barracuda", "fiddler", "charles proxy", "mitmproxy",
            "burp", "proxyman", "adguard", "dr.web", "comodo");

    /**
     * Explains a TLS failure in plain words: antivirus / proxy interception (the certificate chain ends in a security
     * product's own root), a host name mismatch (captive portal or DNS redirection), or a missing root. Returns an
     * empty string when the failure does not look like a certificate problem. Certificate validation is never relaxed.
     */
    static String interceptionHint(Throwable error) {
        if (error == null) return "";
        String all = describeFailure(error).toLowerCase(java.util.Locale.ROOT);
        if (hasHostnameMismatch(error)) {
            return "The server certificate does not belong to the host V-Client asked for. This usually means a captive portal "
                    + "(hotel / school Wi-Fi sign-in), a DNS filter, or antivirus \"web protection\" is answering instead of the real server. "
                    + "Open a browser, finish any sign-in page, or switch off HTTPS scanning in your antivirus.";
        }
        if (!hasCertificateFailure(error)) return "";
        for (String vendor : INTERCEPTORS) {
            if (all.contains(vendor)) {
                return "The certificate chain was issued by \"" + vendor.strip() + "\", which is a security product or proxy, not a public "
                        + "certificate authority. Its HTTPS scanning is replacing Mojang's / Microsoft's certificates. Turn off HTTPS / SSL "
                        + "scanning for V-Client in that product (or add V-Client.exe and javaw.exe to its exclusions). "
                        + "V-Client will not bypass certificate checks.";
            }
        }
        return "The certificate chain could not be verified against the Windows or the bundled Java trust store ("
                + truststoreSummary() + "). Possible causes: antivirus / proxy HTTPS scanning, a wrong PC clock, or an outdated "
                + "Windows root certificate list (run Windows Update).";
    }

    /** One line about the trust stores V-Client uses: the Windows root store and the bundled Java public roots. */
    static String truststoreSummary() {
        int windows = -1;
        int java = -1;
        try {
            KeyStore store = KeyStore.getInstance("Windows-ROOT");
            store.load(null);
            windows = store.size();
        } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
            // not on Windows or the store is unreadable
        }
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof javax.net.ssl.X509TrustManager x509) java = x509.getAcceptedIssuers().length;
            }
        } catch (GeneralSecurityException | RuntimeException ignored) {
            // reported as unavailable
        }
        return "Windows roots: " + (windows < 0 ? "unavailable" : windows)
                + ", bundled Java roots: " + (java < 0 ? "unavailable" : java)
                + ", Java " + System.getProperty("java.version");
    }

    private static ProxySelector proxySelector() {
        ProxySelector system = ProxySelector.getDefault();
        String address = environmentProxy();
        if (system != null) {
            try {
                if (system.select(URI.create("https://piston-meta.mojang.com")).stream()
                        .anyMatch(proxy -> proxy.type() != Proxy.Type.DIRECT)) return system;
            } catch (RuntimeException ignored) {
                return system;
            }
        }
        if (address == null || address.isBlank()) return system;
        try {
            URI uri = URI.create(address.contains("://") ? address : "http://" + address);
            int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            if (uri.getHost() == null || port < 1 || port > 65535) return system;
            Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), port));
            return new ProxySelector() {
                @Override
                public List<Proxy> select(URI target) {
                    if (bypassesEnvironmentProxy(target.getHost())) return List.of(Proxy.NO_PROXY);
                    return List.of(proxy);
                }

                @Override
                public void connectFailed(URI uri, java.net.SocketAddress socketAddress, IOException e) {
                    System.err.println("V-Client proxy connection failed for " + uri.getHost() + ": "
                            + describeFailure(e));
                }
            };
        } catch (IllegalArgumentException e) {
            return system;
        }
    }

    private static boolean bypassesEnvironmentProxy(String host) {
        if (host == null) return false;
        String bypassList = System.getenv("NO_PROXY");
        if (bypassList == null || bypassList.isBlank()) bypassList = System.getenv("no_proxy");
        if (bypassList == null || bypassList.isBlank()) return false;
        for (String entry : bypassList.split(",")) {
            String pattern = entry.strip().toLowerCase(java.util.Locale.ROOT);
            String target = host.toLowerCase(java.util.Locale.ROOT);
            if (pattern.equals("*") || target.equals(pattern)
                    || pattern.startsWith(".") && target.endsWith(pattern)
                    || !pattern.startsWith(".") && target.endsWith("." + pattern)) return true;
        }
        return false;
    }

    private static String environmentProxy() {
        String address = System.getenv("HTTPS_PROXY");
        if (address == null || address.isBlank()) address = System.getenv("https_proxy");
        if (address == null || address.isBlank()) address = System.getenv("HTTP_PROXY");
        if (address == null || address.isBlank()) address = System.getenv("http_proxy");
        return address;
    }

    private static long retryDelayMillis(HttpResponse<?> response) {
        if (response.statusCode() == 429 || response.statusCode() == 503) {
            String value = response.headers().firstValue("Retry-After").orElse("");
            try {
                long seconds = Long.parseLong(value);
                return Math.min(5_000, Math.max(350, seconds * 1_000));
            } catch (NumberFormatException ignored) {
                try {
                    long delay = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - Instant.now().toEpochMilli();
                    return Math.min(5_000, Math.max(350, delay));
                } catch (java.time.format.DateTimeParseException ignoredAgain) {
                    // Fall through to the bounded exponential backoff.
                }
            }
        }
        return Math.min(2_000, 350L * (MAX_ATTEMPTS - 1));
    }

    private static void addTrustManagers(List<X509ExtendedTrustManager> result, TrustManager[] managers)
            throws GeneralSecurityException {
        for (TrustManager manager : managers) {
            if (manager instanceof X509ExtendedTrustManager extended) {
                result.add(extended);
            } else if (manager instanceof javax.net.ssl.X509TrustManager basic) {
                result.add(new BasicExtendedTrustManager(basic));
            }
        }
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static final class WindowsAndJavaTrustManager extends X509ExtendedTrustManager {
        private final List<X509ExtendedTrustManager> delegates;

        private WindowsAndJavaTrustManager(List<X509ExtendedTrustManager> delegates) {
            this.delegates = List.copyOf(delegates);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, manager -> manager.checkClientTrusted(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, manager -> manager.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            check(chain, authType, manager -> manager.checkClientTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            check(chain, authType, manager -> manager.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine)
                throws CertificateException {
            check(chain, authType, manager -> manager.checkClientTrusted(chain, authType, engine));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine)
                throws CertificateException {
            check(chain, authType, manager -> manager.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegates.stream().flatMap(manager -> List.of(manager.getAcceptedIssuers()).stream())
                    .distinct().toArray(X509Certificate[]::new);
        }

        private void check(X509Certificate[] chain, String authType, TrustCheck verification)
                throws CertificateException {
            CertificateException failure = null;
            for (X509ExtendedTrustManager manager : delegates) {
                try {
                    verification.verify(manager);
                    return;
                } catch (CertificateException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            String certificateIssuer = chain == null || chain.length == 0
                    ? "unavailable" : chain[chain.length - 1].getIssuerX500Principal().getName();
            throw new CertificateException("Certificate chain was rejected by both Windows and Java trust stores; "
                    + "peer issuer: " + certificateIssuer + "; authentication type: " + authType, failure);
        }
    }

    @FunctionalInterface
    private interface TrustCheck {
        void verify(X509ExtendedTrustManager manager) throws CertificateException;
    }

    private static final class BasicExtendedTrustManager extends X509ExtendedTrustManager {
        private final javax.net.ssl.X509TrustManager delegate;

        private BasicExtendedTrustManager(javax.net.ssl.X509TrustManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine)
                throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine)
                throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }
}
