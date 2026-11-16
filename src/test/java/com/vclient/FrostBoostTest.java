package com.vclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrostBoostTest {
    @TempDir
    Path directory;

    private static PcAnalysis.Hardware pc(int ramGb, int threads, String gpu) {
        return new PcAnalysis.Hardware(ramGb * 1024L * 1024 * 1024, threads, gpu, "Test CPU");
    }

    private static BenchmarkStore.Result result(String label, long at, double avg, double low) {
        return new BenchmarkStore.Result(label, "BALANCED", at, 60, avg, low, low * 0.8, 20, 400, 70, 20, 0, 3000, 6144,
                "GPU", "1.21.11");
    }

    @Test
    void improvementIsAPercentageOfBefore() {
        assertEquals(77.0, BenchmarkStore.gainPercent(180, 318.6), 0.05);
        assertEquals("+77%", BenchmarkStore.formatPercent(77.2));
        assertEquals("-5%", BenchmarkStore.formatPercent(-5.1));
        assertEquals(0, BenchmarkStore.gainPercent(0, 100));
    }

    @Test
    void comparisonPairsTheNewestAfterWithTheBeforeTakenEarlier() {
        List<BenchmarkStore.Result> results = List.of(result("before", 1, 100, 60), result("after", 2, 150, 90),
                result("before", 3, 110, 70), result("after", 4, 220, 140));
        BenchmarkStore.Comparison comparison = BenchmarkStore.compare(results);
        assertEquals(3, comparison.before().finishedAt());
        assertEquals(4, comparison.after().finishedAt());
        assertEquals(100.0, comparison.avgGainPercent(), 0.01);
        assertTrue(comparison.complete());
    }

    @Test
    void aBeforeWithoutAnAfterIsAwaitingOne() {
        assertTrue(BenchmarkStore.awaitingAfter(List.of(result("before", 1, 100, 60))));
        assertFalse(BenchmarkStore.awaitingAfter(List.of(result("before", 1, 100, 60), result("after", 2, 150, 90))));
        assertFalse(BenchmarkStore.awaitingAfter(List.of()));
    }

    @Test
    void modResultIsMovedIntoTheHistory() throws Exception {
        Path file = BenchmarkStore.resultFile(directory);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"label\":\"before\",\"profile\":\"BALANCED\",\"finishedAt\":5,\"seconds\":60,"
                + "\"avgFps\":180.5,\"low1\":120,\"low01\":90,\"minFps\":80,\"maxFps\":260,\"cpuBoundPct\":60,"
                + "\"gpuBoundPct\":10,\"cappedPct\":0,\"memoryUsedMb\":2000,\"memoryMaxMb\":4096,"
                + "\"runtime\":{\"renderer\":\"Test GPU\",\"minecraft\":\"1.21.11\"}}");
        assertTrue(BenchmarkStore.ingest(directory).isPresent());
        assertFalse(Files.exists(file));
        List<BenchmarkStore.Result> loaded = BenchmarkStore.load(directory);
        assertEquals(1, loaded.size());
        assertEquals(180.5, loaded.getFirst().avgFps(), 0.001);
        assertTrue(BenchmarkStore.ingest(directory).isEmpty());
    }

    @Test
    void gpuClassesFollowTheModelNumber() {
        assertEquals(PcAnalysis.GpuClass.STRONG, PcAnalysis.gpuClass("NVIDIA GeForce RTX 4080"));
        assertEquals(PcAnalysis.GpuClass.MID, PcAnalysis.gpuClass("NVIDIA GeForce RTX 3060"));
        assertEquals(PcAnalysis.GpuClass.WEAK, PcAnalysis.gpuClass("NVIDIA GeForce GTX 1050"));
        assertEquals(PcAnalysis.GpuClass.MID, PcAnalysis.gpuClass("AMD Radeon RX 6600"));
        assertEquals(PcAnalysis.GpuClass.STRONG, PcAnalysis.gpuClass("AMD Radeon RX 7800 XT"));
        assertEquals(PcAnalysis.GpuClass.WEAK, PcAnalysis.gpuClass("Intel(R) UHD Graphics 630"));
        assertEquals(PcAnalysis.GpuClass.UNKNOWN, PcAnalysis.gpuClass(""));
    }

    @Test
    void tiersSeparateLowMidAndHighEndPcs() {
        assertEquals(PcAnalysis.Tier.LOW, PcAnalysis.tier(pc(4, 4, "Intel UHD Graphics")));
        assertEquals(PcAnalysis.Tier.LOW, PcAnalysis.tier(pc(16, 8, "NVIDIA GeForce GTX 1050")));
        assertEquals(PcAnalysis.Tier.MID, PcAnalysis.tier(pc(16, 8, "NVIDIA GeForce RTX 3060")));
        assertEquals(PcAnalysis.Tier.HIGH, PcAnalysis.tier(pc(32, 16, "NVIDIA GeForce RTX 4080")));
        assertTrue(PcAnalysis.hybridGraphics(pc(16, 8, "Intel(R) UHD Graphics; NVIDIA GeForce RTX 3050")));
    }

    @Test
    void profilesAreTunedPerHardwareClass() {
        assertTrue(Integer.parseInt(BoostProfile.ULTRA.options(PcAnalysis.Tier.LOW).get("renderDistance")) <= 8);
        assertEquals("\"fast\"", BoostProfile.BALANCED.options(PcAnalysis.Tier.LOW).get("graphicsPreset"));
        assertEquals("\"fancy\"", BoostProfile.BALANCED.options(PcAnalysis.Tier.HIGH).get("graphicsPreset"));
        assertEquals("true", BoostProfile.ULTRA.options(PcAnalysis.Tier.HIGH).get("ao"));
        assertEquals(BoostProfile.COMPETITIVE.options(), BoostProfile.COMPETITIVE.options(PcAnalysis.Tier.MID));
        assertEquals("Glacier Mode", BoostProfile.POTATO.label());
    }

    @Test
    void recommendationFollowsTheMeasurementAndTheHardware() {
        PcAnalysis.Hardware strong = pc(32, 16, "NVIDIA GeForce RTX 4080");
        assertEquals(BoostProfile.ULTRA, BoostAdvisor.recommend(result("before", 1, 400, 300), strong).profile());
        // A strong PC that measures slowly gets the FPS profile it needs.
        assertEquals(BoostProfile.COMPETITIVE, BoostAdvisor.recommend(result("before", 1, 90, 60), strong).profile());
        // A weak PC that measures well is still held to a low-end profile.
        assertEquals(BoostProfile.POTATO, BoostAdvisor.recommend(result("before", 1, 400, 300), pc(4, 4, "Intel UHD")).profile());
    }

    @Test
    void bottleneckNeedsAClearWinner() {
        assertEquals(FpsDiagnostics.Bottleneck.CPU, FpsDiagnostics.bottleneck(70, 20, 0));
        assertEquals(FpsDiagnostics.Bottleneck.GPU, FpsDiagnostics.bottleneck(10, 80, 0));
        assertEquals(FpsDiagnostics.Bottleneck.LIMITER, FpsDiagnostics.bottleneck(10, 10, 70));
        assertEquals(FpsDiagnostics.Bottleneck.BALANCED, FpsDiagnostics.bottleneck(45, 45, 0));
        assertEquals(FpsDiagnostics.Bottleneck.UNKNOWN, FpsDiagnostics.bottleneck(0, 0, 0));
    }

    @Test
    void verifierReportsWhatTheRunningGameDoesNotMatch() throws Exception {
        BoostProfile.Tuning tuning = BoostProfile.BALANCED.tuning(pc(16, 8, "NVIDIA GeForce RTX 3060"));
        Path mods = directory.resolve("mods");
        Files.createDirectories(mods);
        Files.createFile(mods.resolve("sodium-fabric-0.6.jar"));
        FpsDiagnostics.Environment runtime = new FpsDiagnostics.Environment("1.21.11", "GPU", 8, 6144,
                List.of("-Xmx6G"), java.util.Map.of("sodium", "0.6"), java.util.Map.of("renderDistance", "10"));
        FpsDiagnostics.Snapshot live = new FpsDiagnostics.Snapshot(1, 60, 200, 120, 90, 80, 300, 40, 40, 0, 2000, 6144, runtime);

        List<BoostVerifier.Check> checks = BoostVerifier.verify(tuning, live, directory);

        assertEquals(BoostVerifier.Status.OK, status(checks, "Game memory"));
        assertEquals(BoostVerifier.Status.MISMATCH, status(checks, "Garbage-collector flags"));
        assertEquals(BoostVerifier.Status.OK, status(checks, "renderDistance"));
        assertEquals(BoostVerifier.Status.OK, status(checks, "Sodium"));
        assertEquals(BoostVerifier.Status.MISMATCH, status(checks, "Lithium"));
    }

    private static BoostVerifier.Status status(List<BoostVerifier.Check> checks, String name) {
        return checks.stream().filter(check -> check.name().equals(name)).findFirst().orElseThrow().status();
    }
}
