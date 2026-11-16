package com.vclient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads this PC's hardware and turns it into settings advice: render distance, memory, profile and mods. */
final class PcAnalysis {
    enum Tier {
        LOW("Low-end"), MID("Mid-range"), HIGH("High-end");

        private final String label;

        Tier(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** How capable a graphics card is for Minecraft, from its name alone. */
    enum GpuClass { UNKNOWN, WEAK, MID, STRONG }

    record Hardware(long ramBytes, int cpuThreads, String gpuName, String cpuName) {
        Hardware(long ramBytes, int cpuThreads, String gpuName) {
            this(ramBytes, cpuThreads, gpuName, "");
        }

        long ramGb() {
            return Math.max(1, Math.round(ramBytes / (1024.0 * 1024 * 1024)));
        }

        /** The same PC with the CPU and graphics names read from Windows ({cpu, gpu}). */
        Hardware withDevices(String[] devices) {
            return new Hardware(ramBytes, cpuThreads, devices[1], devices[0]);
        }

        /** Every graphics adapter Windows reports (a laptop usually has an integrated and a discrete one). */
        List<String> gpuNames() {
            List<String> names = new ArrayList<>();
            for (String part : gpuName.split(";")) if (!part.isBlank()) names.add(part.strip());
            return names;
        }

        /** The strongest adapter: that is the one the game should be using. */
        String bestGpu() {
            String best = "";
            GpuClass bestClass = null;
            for (String name : gpuNames()) {
                GpuClass found = gpuClass(name);
                if (bestClass == null || found.ordinal() > bestClass.ordinal()) {
                    best = name;
                    bestClass = found;
                }
            }
            return best;
        }
    }

    record Advice(Tier tier, int renderDistance, int simulationDistance, int memoryGb, BoostProfile profile,
                  List<BoostProfile.BoostMod> mods, List<String> notes) {}

    private static volatile Hardware cached;

    private PcAnalysis() {}

    static Hardware detect() {
        return new Hardware(BoostProfile.physicalRamBytes(), Runtime.getRuntime().availableProcessors(), "", "")
                .withDevices(detectDevices());
    }

    /** {@link #detect()} done once per launcher run: reading the graphics card starts PowerShell, which is slow. */
    static Hardware cached() {
        Hardware value = cached;
        if (value == null) {
            synchronized (PcAnalysis.class) {
                if (cached == null) cached = detect();
                value = cached;
            }
        }
        return value;
    }

    /** The cached hardware if it has been read already, otherwise RAM and threads only (never blocks). */
    static Hardware cachedOrBasic() {
        Hardware value = cached;
        return value != null ? value
                : new Hardware(BoostProfile.physicalRamBytes(), Runtime.getRuntime().availableProcessors(), "", "");
    }

    /** CPU name and graphics adapter names from Windows; empty strings when they cannot be read. */
    static String[] detectDevices() {
        if (!WinInput.available()) return new String[] {"", ""};
        try {
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "'CPU=' + ((Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)); "
                            + "'GPU=' + ((Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name) -join '; ')")
                    .redirectErrorStream(true).start();
            String cpu = "";
            String gpu = "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("CPU=")) cpu = line.substring(4).strip();
                    else if (line.startsWith("GPU=")) gpu = line.substring(4).strip();
                }
                if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            return new String[] {cpu, gpu};
        } catch (Exception e) {
            return new String[] {"", ""};
        }
    }

    /** Asks Windows for the graphics adapter names; empty when they cannot be read. */
    static String detectGpu() {
        return detectDevices()[1];
    }

    static boolean integratedGraphics(String gpuName) {
        String name = gpuName.toLowerCase(Locale.ROOT);
        if (name.isBlank()) return false;
        boolean discreteName = name.contains("geforce") || name.contains("rtx") || name.contains("gtx")
                || name.contains("radeon rx") || name.contains("radeon pro") || name.contains("arc a")
                || name.contains("quadro");
        if (discreteName) return false;
        return name.contains("intel") || name.contains("uhd") || name.contains("iris") || name.contains("vega")
                || name.contains("radeon(tm) graphics") || name.contains("radeon graphics") || name.contains("basic");
    }

    private static final Pattern NVIDIA = Pattern.compile("\\b(rtx|gtx|gt)\\s*(\\d{3,4})");
    private static final Pattern RADEON = Pattern.compile("\\brx\\s*(\\d{3,4})");
    private static final Pattern ARC = Pattern.compile("\\barc\\s*a(\\d{3})");

    /** A rough class for one graphics adapter name, so the PC is not judged by its weakest or unknown part. */
    static GpuClass gpuClass(String gpuName) {
        String name = gpuName.toLowerCase(Locale.ROOT);
        if (name.isBlank()) return GpuClass.UNKNOWN;
        if (integratedGraphics(name)) return GpuClass.WEAK;
        Matcher nvidia = NVIDIA.matcher(name);
        if (nvidia.find()) {
            int model = Integer.parseInt(nvidia.group(2));
            switch (nvidia.group(1)) {
                case "rtx" -> {
                    int tier = model % 100; // 3060 -> 60, 4090 -> 90, 2080 -> 80
                    return tier >= 70 ? GpuClass.STRONG : GpuClass.MID;
                }
                case "gtx" -> {
                    if (model >= 1600) return model >= 1650 ? GpuClass.MID : GpuClass.WEAK;
                    if (model >= 1000) return model >= 1060 ? (model >= 1080 ? GpuClass.STRONG : GpuClass.MID) : GpuClass.WEAK;
                    return model >= 970 ? GpuClass.MID : GpuClass.WEAK;
                }
                default -> {
                    return GpuClass.WEAK;
                }
            }
        }
        Matcher radeon = RADEON.matcher(name);
        if (radeon.find()) {
            int model = Integer.parseInt(radeon.group(1));
            if (model < 1000) return model >= 580 ? GpuClass.MID : GpuClass.WEAK;
            int level = model / 100 % 10; // 6700 -> 7, 7600 -> 6, 5500 -> 5
            return level >= 7 ? GpuClass.STRONG : level >= 5 ? GpuClass.MID : GpuClass.WEAK;
        }
        Matcher arc = ARC.matcher(name);
        if (arc.find()) {
            int model = Integer.parseInt(arc.group(1));
            return model >= 700 ? GpuClass.STRONG : model >= 500 ? GpuClass.MID : GpuClass.WEAK;
        }
        return GpuClass.UNKNOWN;
    }

    /** Low-end, mid-range or high-end from RAM, CPU threads and the graphics card. */
    static Tier tier(Hardware hardware) {
        long ramGb = hardware.ramGb();
        GpuClass gpu = gpuClass(hardware.bestGpu());
        if (ramGb < 6 || hardware.cpuThreads() < 4 || gpu == GpuClass.WEAK) return Tier.LOW;
        if (ramGb >= 16 && hardware.cpuThreads() >= 8 && (gpu == GpuClass.STRONG || gpu == GpuClass.UNKNOWN)) return Tier.HIGH;
        return Tier.MID;
    }

    /** True when an integrated and a discrete adapter are both present: the game may be running on the wrong one. */
    static boolean hybridGraphics(Hardware hardware) {
        List<String> names = hardware.gpuNames();
        boolean integrated = false;
        boolean discrete = false;
        for (String name : names) {
            if (integratedGraphics(name)) integrated = true;
            else discrete = true;
        }
        return integrated && discrete;
    }

    static Advice advise(Hardware hardware) {
        long ramGb = hardware.ramGb();
        Tier tier = tier(hardware);
        boolean integrated = gpuClass(hardware.bestGpu()) == GpuClass.WEAK && integratedGraphics(hardware.bestGpu());

        int render = switch (tier) {
            case LOW -> ramGb < 6 ? 6 : 8;
            case MID -> 12;
            case HIGH -> 18;
        };
        int simulation = Math.max(5, Math.min(render, switch (tier) {
            case LOW -> 6;
            case MID -> 8;
            case HIGH -> 12;
        }));
        BoostProfile profile = switch (tier) {
            case LOW -> BoostProfile.POTATO;
            case MID -> BoostProfile.BALANCED;
            case HIGH -> BoostProfile.ULTRA;
        };
        int memory = switch (tier) {
            case LOW -> 3;
            case MID -> 5;
            case HIGH -> 8;
        };
        // Never give the game more than half the installed memory: Windows and other programs need the rest.
        memory = (int) Math.max(2, Math.min(memory, ramGb / 2));

        ArrayList<String> notes = new ArrayList<>();
        if (ramGb < 6) notes.add("Only " + ramGb + " GB of RAM: close other programs before playing.");
        if (integrated) notes.add("Integrated graphics detected: Sodium matters most here, and shaders will be slow.");
        if (hybridGraphics(hardware)) {
            notes.add("This PC has two graphics adapters. Make sure Minecraft uses the faster one: Windows Settings > "
                    + "System > Display > Graphics > add javaw.exe > Options > High performance.");
        }
        if (hardware.cpuThreads() < 4) notes.add("Few CPU threads: keep simulation distance low.");
        if (tier == Tier.HIGH) notes.add("Plenty of headroom: you can raise render distance further if FPS stays high.");
        return new Advice(tier, render, simulation, memory, profile, profile.mods(), notes);
    }
}
