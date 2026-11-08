package com.vclient.agent;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.spi.InetAddressResolver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Name lookups for the game: the system resolver answers first, and when it fails or returns a DNS filter's block page
 * (Cisco Umbrella / OpenDNS, 146.112.0.0/16) the name is looked up over DNS-over-HTTPS instead. The DoH servers are
 * reached by host name (normal SNI and certificate checks) and answers keep the looked-up name. JDK classes only.
 */
public final class GameResolver implements InetAddressResolver {
    private static final String[] DOH_SERVERS = {
            "https://cloudflare-dns.com/dns-query", "https://dns.google/resolve",
            "https://dns.quad9.net:5053/dns-query"};
    private static final java.util.Set<String> DOH_HOSTS = java.util.Set.of(
            "cloudflare-dns.com", "dns.google", "dns.quad9.net");
    /** The first A record in a DNS-JSON answer (CNAMEs, type 5, come first and are skipped). */
    private static final Pattern A_RECORD = Pattern.compile(
            "\"type\"\\s*:\\s*1\\s*,[^}]*?\"data\"\\s*:\\s*\"(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\"");
    private static final long BACKOFF_MILLIS = 30_000;
    private static final AtomicLong dohDownUntil = new AtomicLong();
    private static volatile int preferredServer;

    /** Created on first use: the resolver may be called while the JVM is still setting up networking. */
    private static final class Client {
        static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    private final InetAddressResolver builtin;

    GameResolver(InetAddressResolver builtin) {
        this.builtin = builtin;
    }

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
        UnknownHostException failure;
        try {
            List<InetAddress> found = builtin.lookupByName(host, policy).toList();
            if (found.stream().noneMatch(GameResolver::isBlockPage)) return found.stream();
            failure = new UnknownHostException(host + " is blocked by this network's DNS filter");
        } catch (UnknownHostException e) {
            failure = e;
        }
        InetAddress secure = secureLookup(host);
        if (secure == null) throw failure;
        return Stream.of(secure);
    }

    @Override
    public String lookupByAddress(byte[] address) throws UnknownHostException {
        return builtin.lookupByAddress(address);
    }

    /** Cisco Umbrella / OpenDNS block pages live in 146.112.0.0/16. */
    static boolean isBlockPage(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 146 && (bytes[1] & 0xFF) == 112;
    }

    /** The IPv4 address in a DNS-JSON answer, or null when it has none. */
    static String firstARecord(String json) {
        Matcher matcher = A_RECORD.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static InetAddress secureLookup(String host) {
        if (host == null || host.isEmpty() || host.indexOf('.') < 0 || host.equals("localhost")
                || DOH_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT)) || host.matches("[0-9.]+")
                || host.endsWith(".local") || host.endsWith(".localhost")
                || System.currentTimeMillis() < dohDownUntil.get()) {
            return null;
        }
        String encoded = host.replace(" ", "");
        for (int i = 0; i < DOH_SERVERS.length; i++) {
            int index = (preferredServer + i) % DOH_SERVERS.length;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(DOH_SERVERS[index] + "?type=A&name=" + encoded))
                        .timeout(Duration.ofSeconds(3)).header("Accept", "application/dns-json").GET().build();
                HttpResponse<String> response = Client.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) continue;
                preferredServer = index;
                String address = firstARecord(response.body());
                // Keep the host name on the answer: a nameless address makes TLS verify the certificate against
                // the bare IP ("No subject alternative names matching IP address").
                return address == null ? null
                        : InetAddress.getByAddress(host, InetAddress.getByName(address).getAddress());
            } catch (IOException | RuntimeException e) {
                // try the next server
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        dohDownUntil.set(System.currentTimeMillis() + BACKOFF_MILLIS);
        return null;
    }
}
