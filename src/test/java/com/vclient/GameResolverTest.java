package com.vclient;

import com.vclient.agent.GameResolverProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameResolverTest {
    @Test
    void agentJarContainsTheResolverAndItsServiceFile() throws Exception {
        byte[] jar = GameDnsAgent.build();
        assertTrue(jar.length > 1000);
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            var names = new java.util.ArrayList<String>();
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) names.add(entry.getName());
            assertTrue(names.contains("com/vclient/agent/GameResolver.class"));
            assertTrue(names.contains("META-INF/services/java.net.spi.InetAddressResolverProvider"));
            assertTrue(names.contains("META-INF/MANIFEST.MF"));
        }
        assertEquals(java.util.Arrays.hashCode(jar), java.util.Arrays.hashCode(GameDnsAgent.build()));
    }

    @Test
    void onlyJava21AndNewerGetTheAgent() {
        assertTrue(GameDnsAgent.supports(21));
        assertTrue(GameDnsAgent.supports(25));
        assertEquals(false, GameDnsAgent.supports(17));
    }

    @Test
    void providerIsNamed() {
        assertNotNull(new GameResolverProvider().name());
    }
}
