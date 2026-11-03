package com.vclient.agent;

import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;

/** Registered through {@code META-INF/services} in the agent jar. */
public final class GameResolverProvider extends InetAddressResolverProvider {
    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return Boolean.getBoolean("vclient.nodoh") ? builtin : new GameResolver(builtin);
    }

    @Override
    public String name() {
        return "V-Client game secure DNS";
    }
}
