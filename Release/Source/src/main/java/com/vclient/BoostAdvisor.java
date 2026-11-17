package com.vclient;

import java.util.Locale;

/**
 * Picks the Boost profile that suits this PC from a measured benchmark (not a guess from the spec sheet alone).
 * The measurement and the hardware class each propose a profile and the more FPS-oriented one wins: if a "high-end"
 * PC measures like a slow one, it gets the FPS profile it actually needs, and a strong measurement never overrides
 * a weak spec.
 */
final class BoostAdvisor {
    record Recommendation(BoostProfile profile, String reason) {}

    private BoostAdvisor() {}

    /** The profile a baseline average FPS calls for. */
    static BoostProfile profileForFps(double averageFps) {
        if (averageFps < 60) return BoostProfile.POTATO;
        if (averageFps < 120) return BoostProfile.COMPETITIVE;
        if (averageFps < 240) return BoostProfile.BALANCED;
        return BoostProfile.ULTRA;
    }

    static Recommendation recommend(BenchmarkStore.Result baseline, PcAnalysis.Hardware hardware) {
        BoostProfile measured = profileForFps(baseline.avgFps());
        BoostProfile byHardware = PcAnalysis.advise(hardware).profile();
        // Enum order runs from most quality to most FPS.
        BoostProfile chosen = measured.ordinal() >= byHardware.ordinal() ? measured : byHardware;

        StringBuilder reason = new StringBuilder(String.format(Locale.ROOT,
                "Measured %.0f FPS average and %.0f FPS 1%% low over %d seconds. ", baseline.avgFps(), baseline.low1(),
                baseline.seconds()));
        if (chosen == measured && chosen != byHardware) {
            reason.append("This PC is slower in practice than its specs suggest, so ").append(chosen.label())
                    .append(" is recommended.");
        } else if (chosen == byHardware && chosen != measured) {
            reason.append("The speed is fine, but this is ").append(PcAnalysis.tier(hardware).label().toLowerCase(Locale.ROOT))
                    .append(" hardware, so ").append(chosen.label()).append(" keeps it smooth in busy scenes.");
        } else {
            reason.append("Both the measurement and the hardware point to ").append(chosen.label()).append('.');
        }
        if (baseline.avg1LowRatio() < 0.45) {
            reason.append(" 1% lows are far below the average, so stutters matter more than raw FPS.");
        }
        if (baseline.cpuBoundPct() > baseline.gpuBoundPct() + 15) {
            reason.append(" The CPU is the limit: shorter simulation distance and Lithium help most.");
        } else if (baseline.gpuBoundPct() > baseline.cpuBoundPct() + 15) {
            reason.append(" The graphics card is the limit: lower render distance and effects help most.");
        }
        return new Recommendation(chosen, reason.toString());
    }
}
