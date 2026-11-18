package com.vclient;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The update check must never leave an installed launcher without a way to find the newest release. */
final class UpdateSourcesTest {
    private static BackendClient.UpdateInfo offers(String version) {
        return new BackendClient.UpdateInfo(true, false,
                new BackendClient.Release(version, "notes", false, 100, "abc", "https://example.test/setup.exe"));
    }

    private static BackendClient.UpdateInfo knowsNothing() {
        return new BackendClient.UpdateInfo(false, false, null);
    }

    @Test
    void aSourceThatKnowsNoReleaseNeverHidesOneThatDoes() {
        // The backend was not redeployed (latest: null) but the website has 1.7.2.
        BackendClient.UpdateInfo chosen = BackendClient.chooseNewest(List.of(knowsNothing(), offers("1.7.2")), "1.6.28");
        assertEquals("1.7.2", chosen.latest().version());
        assertTrue(chosen.updateAvailable());
    }

    @Test
    void theNewestReleaseAnySourceReportsWins() {
        BackendClient.UpdateInfo chosen = BackendClient.chooseNewest(
                List.of(offers("1.7.0"), offers("1.7.2"), offers("1.7.1")), "1.6.28");
        assertEquals("1.7.2", chosen.latest().version());
    }

    @Test
    void availabilityIsRecomputedAgainstTheRunningVersion() {
        // A stale source claims an update for a launcher that is already newer than that release.
        BackendClient.UpdateInfo chosen = BackendClient.chooseNewest(List.of(offers("1.7.0")), "1.7.2");
        assertFalse(chosen.updateAvailable());
        assertEquals("1.7.0", chosen.latest().version());
    }

    @Test
    void sourcesThatFailedAreSkippedAndAllFailingGivesNothing() {
        assertEquals("1.7.2", BackendClient.chooseNewest(Arrays.asList(null, offers("1.7.2"), null), "1.7.1").latest().version());
        assertNull(BackendClient.chooseNewest(Arrays.asList(null, null), "1.7.1"));
        assertNull(BackendClient.chooseNewest(List.of(), "1.7.1"));
    }

    @Test
    void noKnownUpdateAddressIsEverRemoved() {
        // Installed launchers only know the addresses they were built with. Keep every address this project has used.
        assertTrue(BackendClient.SITE_URLS.contains("https://izyrn.github.io/V-client"));
        assertTrue(BackendClient.SITE_URLS.contains("https://eliteprofast.github.io/V-client"),
                "the previous address stays in the list so a revived mirror can still serve updates");
        assertEquals(BackendClient.SITE_URLS.getFirst(), BackendClient.SITE_URL);
    }
}
