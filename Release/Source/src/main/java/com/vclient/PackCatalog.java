package com.vclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The one-click mod packs. Each is a list of Modrinth projects installed for the selected Fabric profile. */
final class PackCatalog {
    record PackMod(String slug, String name) {}

    record Pack(String id, String title, String tagline, List<PackMod> mods) {}

    /** Per-pack outcome: what was installed, what was already there, and what had no compatible release. */
    record Outcome(List<String> installed, List<String> already, List<String> unavailable, List<String> failed) {
        int total() {
            return installed.size() + already.size() + unavailable.size() + failed.size();
        }
    }

    static final List<Pack> PACKS = List.of(
            new Pack("pvp", "PvP Pack", "Smooth frames and clear visibility for fights.", List.of(
                    new PackMod("sodium", "Sodium"), new PackMod("lithium", "Lithium"),
                    new PackMod("immediatelyfast", "ImmediatelyFast"), new PackMod("entityculling", "EntityCulling"),
                    new PackMod("ferrite-core", "FerriteCore"))),
            new Pack("fps", "FPS Pack", "Every proven optimisation mod for the most FPS.", List.of(
                    new PackMod("sodium", "Sodium"), new PackMod("lithium", "Lithium"),
                    new PackMod("ferrite-core", "FerriteCore"), new PackMod("immediatelyfast", "ImmediatelyFast"),
                    new PackMod("entityculling", "EntityCulling"), new PackMod("moreculling", "MoreCulling"),
                    new PackMod("modernfix", "ModernFix"), new PackMod("noisium", "Noisium"),
                    new PackMod("memoryleakfix", "MemoryLeakFix"))),
            new Pack("smp", "SMP Pack", "Everything for long-term survival: maps, food info, performance.", List.of(
                    new PackMod("fabric-api", "Fabric API"), new PackMod("sodium", "Sodium"),
                    new PackMod("lithium", "Lithium"), new PackMod("ferrite-core", "FerriteCore"),
                    new PackMod("xaeros-minimap", "Xaero's Minimap"), new PackMod("xaeros-world-map", "Xaero's World Map"),
                    new PackMod("appleskin", "AppleSkin"), new PackMod("modmenu", "Mod Menu"))),
            new Pack("builder", "Builder Pack", "Blueprints, placement helpers and fast inventories.", List.of(
                    new PackMod("fabric-api", "Fabric API"), new PackMod("sodium", "Sodium"),
                    new PackMod("lithium", "Lithium"), new PackMod("malilib", "MaLiLib"),
                    new PackMod("litematica", "Litematica"), new PackMod("tweakeroo", "Tweakeroo"),
                    new PackMod("mouse-tweaks", "Mouse Tweaks"))),
            new Pack("shaders", "Shaders Pack", "Iris shaders on Sodium. Then pick a shader in Options > Video Settings > Shader Packs.", List.of(
                    new PackMod("sodium", "Sodium"), new PackMod("iris", "Iris Shaders"))),
            new Pack("visuals", "Visuals Pack", "Dynamic lights (torches light up in your hand), connected textures and smoother visuals.", List.of(
                    new PackMod("sodium", "Sodium"), new PackMod("lambdynamiclights", "LambDynamicLights"),
                    new PackMod("continuity", "Continuity"), new PackMod("entityculling", "EntityCulling"))));

    private PackCatalog() {}

    static Outcome install(Pack pack, ModrinthModService service, ModrinthModService.Target target,
                           Path minecraftDirectory, Consumer<String> progress) throws InterruptedException {
        List<String> installed = new ArrayList<>();
        List<String> already = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Path mods = minecraftDirectory.resolve("mods");
        for (PackMod mod : pack.mods()) {
            progress.accept(pack.title() + ": " + mod.name() + "...");
            try {
                if (alreadyInstalled(mods, mod.slug())) {
                    already.add(mod.name());
                    continue;
                }
                ModrinthModService.ModProject project = service.findBySlug(mod.slug(), mod.name(), target, progress);
                if (project == null) {
                    unavailable.add(mod.name());
                    continue;
                }
                service.install(project, minecraftDirectory, progress);
                installed.add(mod.name());
            } catch (IOException e) {
                failed.add(mod.name() + " (" + e.getMessage() + ")");
            }
        }
        // Auto dependency installation: whatever the new mods need and is still missing comes from Modrinth too.
        try {
            List<ModHealth.Issue> missing = ModHealth.check(ModHealth.scan(mods), target.gameVersion()).stream()
                    .filter(issue -> issue.kind() == ModHealth.Kind.MISSING_DEPENDENCY).toList();
            for (ModHealth.Issue issue : missing) {
                String slug = ModHealth.slugFor(issue.install());
                progress.accept(pack.title() + ": dependency " + slug + "...");
                try {
                    ModrinthModService.ModProject project = service.findBySlug(slug, issue.install(), target, progress);
                    if (project == null) {
                        unavailable.add(slug + " (dependency)");
                    } else {
                        service.install(project, minecraftDirectory, progress);
                        installed.add(slug + " (dependency)");
                    }
                } catch (IOException e) {
                    failed.add(slug + " (" + e.getMessage() + ")");
                }
            }
        } catch (RuntimeException e) {
            failed.add("dependency check (" + e.getMessage() + ")");
        }
        return new Outcome(installed, already, unavailable, failed);
    }

    /** True when a jar in the mods folder already looks like this project (file names usually start with the slug). */
    static boolean alreadyInstalled(Path modsDirectory, String slug) {
        if (!Files.isDirectory(modsDirectory)) return false;
        String needle = slug.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        try (var files = Files.list(modsDirectory)) {
            return files.map(file -> file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).replace("-", "")
                            .replace("_", ""))
                    .anyMatch(name -> name.endsWith(".jar") && name.startsWith(needle));
        } catch (IOException e) {
            return false;
        }
    }
}
