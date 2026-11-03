package com.vclient.agent;

import java.lang.instrument.Instrumentation;

/**
 * Entry point of the DNS helper the launcher attaches to the game ({@code -javaagent}). Loading the jar is all that is
 * needed: it puts {@link GameResolverProvider} on the class path, and the JVM picks that up for every name lookup.
 * Uses only the JDK, because it runs inside the game's own Java, not the launcher's.
 */
public final class DohAgent {
    private DohAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        // nothing to do: the service file in the agent jar registers the resolver
    }
}
