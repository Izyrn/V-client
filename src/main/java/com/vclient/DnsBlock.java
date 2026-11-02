package com.vclient;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects the case where a network's DNS filter answers for a real website with the address of its own block
 * page. The connection then reaches the filter instead of the site, which refuses the TLS handshake
 * ("handshake_failure"), so every HTTPS request to that host fails even though Java, the certificates and the
 * launcher are fine. This is how api.modrinth.com and Mojang's skin host fail on networks that use
 * OpenDNS / Cisco Umbrella style filtering.
 */
final class DnsBlock {
    /** A host whose DNS answer looks like a filter's block page. */
    record Finding(String host, List<String> addresses, String realAddresses) {}

    private DnsBlock() {}

    /** True for the addresses block pages and "null routes" use. Private LAN addresses are not flagged. */
    static boolean isSinkhole(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean ciscoUmbrella = bytes.length == 4 && (bytes[0] & 0xFF) == 146 && (bytes[1] & 0xFF) == 112;
        return ciscoUmbrella || address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress();
    }

    /** Resolves {@code host} the way Java does and returns a finding when the answer is a block page; else null. */
    static Finding inspect(String host) {
        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            List<String> addresses = new ArrayList<>();
            boolean blocked = false;
            for (InetAddress address : resolved) {
                addresses.add(address.getHostAddress());
                if (isSinkhole(address)) blocked = true;
            }
            return blocked ? new Finding(host, List.copyOf(addresses), realAddresses(host)) : null;
        } catch (java.net.UnknownHostException e) {
            // A filter that answers NXDOMAIN instead of a block page: still try the real address.
            String real = realAddresses(host);
            return real.isEmpty() ? null : new Finding(host, List.of(), real);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private record Bypass(String address, long checkedAt) {}

    private static final java.util.Map<String, Bypass> BYPASS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The address to connect to for {@code host}: the host itself normally, but the real address (looked up through
     * Cloudflare over HTTPS) when this network's DNS filter answers with a block page, so game servers the filter
     * wrongly blocks stay reachable. Cached for five minutes.
     */
    static String bypass(String host) {
        Bypass cached = BYPASS_CACHE.get(host);
        if (cached != null && System.currentTimeMillis() - cached.checkedAt() < 5 * 60_000L) return cached.address();
        Finding finding = inspect(host);
        String address = host;
        if (finding != null && !finding.realAddresses().isEmpty()) {
            address = finding.realAddresses().split(",")[0].trim();
        }
        BYPASS_CACHE.put(host, new Bypass(address, System.currentTimeMillis()));
        return address;
    }

    /** What an independent resolver (Cloudflare over HTTPS) says, as proof the name is not really there. */
    private static String realAddresses(String host) {
        // Tries several DoH servers (many networks block 1.1.1.1 but allow 8.8.8.8) and skips sinkhole answers.
        InetAddress real = DohProxy.lookup(host);
        return real == null || isSinkhole(real) ? "" : real.getHostAddress();
    }

    static String explain(Finding finding) {
        return finding.host() + " resolves to " + String.join(", ", finding.addresses())
                + ", which is a DNS filter's block page (OpenDNS / Cisco Umbrella style), not the real server"
                + (finding.realAddresses().isEmpty() ? "" : " (its real address is " + finding.realAddresses() + ")")
                + ". The connection reaches the filter, which refuses the TLS handshake - this is a network setting, "
                + "not a certificate or Java problem. Fix: set this PC's DNS (or your router's) to 1.1.1.1 and 8.8.8.8, "
                + "or ask whoever manages the network to allow " + finding.host() + ".";
    }
}
