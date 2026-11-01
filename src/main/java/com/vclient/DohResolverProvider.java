package com.vclient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Makes every host name lookup in the launcher (including the browser's WebSockets, which bypass the proxy) fall back
 * to secure DNS: the system resolver answers first, and when that fails or returns a DNS filter's block page the name
 * is looked up over DNS-over-HTTPS instead. Registered through the {@code InetAddressResolverProvider} service.
 */
public final class DohResolverProvider extends InetAddressResolverProvider {
    /** After every DoH server failed (for example while offline), skip it for this long so lookups stay quick. */
    private static final long BACKOFF_MILLIS = 30_000;
    private static final AtomicLong dohDownUntil = new AtomicLong();

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        if (Boolean.getBoolean("vclient.nodoh")) return builtin;
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                UnknownHostException failure;
                try {
                    List<InetAddress> found = builtin.lookupByName(host, policy).toList();
                    if (found.stream().noneMatch(DohResolverProvider::isBlockPage)) return found.stream();
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
        };
    }

    @Override
    public String name() {
        return "V-Client secure DNS";
    }

    /** Cisco Umbrella / OpenDNS block pages live in 146.112.0.0/16. */
    private static boolean isBlockPage(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 146 && (bytes[1] & 0xFF) == 112;
    }

    private static InetAddress secureLookup(String host) {
        if (host == null || host.isEmpty() || host.equals("localhost") || host.endsWith(".local")
                || System.currentTimeMillis() < dohDownUntil.get()) {
            return null;
        }
        InetAddress address = DohProxy.lookup(host);
        if (address == null) dohDownUntil.set(System.currentTimeMillis() + BACKOFF_MILLIS);
        return address;
    }
}
