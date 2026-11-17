package com.vclient.mod.cosmetics;

import com.vclient.mod.VClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Makes the local player's launcher-supplied skin (Ely.by accounts) show up even when vanilla's own lookup
 * failed, and layers the equipped cosmetic cape on top.
 *
 * Vanilla fetches the local profile once at start-up and never retries, so a single failed request leaves
 * Steve/Alex for the whole session. The launcher resolves the skin itself and passes it as
 * {@code -Dvclient.skin.url / .model / .uuid} (and {@code -Dvclient.cape.url}); this class downloads it once,
 * caches it on disk, registers it as a texture and swaps it into the local player's {@link SkinTextures}
 * whenever vanilla produced the default skin. A skin vanilla did load is never replaced.
 */
public final class SkinFix {
    private static final String SKIN_URL = System.getProperty("vclient.skin.url", "").strip();
    private static final String CAPE_URL = System.getProperty("vclient.cape.url", "").strip();
    private static final String MODEL = System.getProperty("vclient.skin.model", "default").strip();
    private static final String LAUNCHER_UUID = System.getProperty("vclient.skin.uuid", "").strip();

    private static volatile Identifier skinTexture;
    private static volatile Identifier capeTexture;
    private static volatile int loadedVersion;
    private static boolean loggedVanillaSkin;
    private static boolean loggedApplied;

    private static SkinTextures lastInput;
    private static SkinTextures lastOutput;
    private static int lastStateVersion = -1;
    private static int lastFrame = -3;
    private static int lastSkinVersion = -1;
    private static int lastCatalogRevision = -1;

    private SkinFix() {}

    private static volatile String skinStatus = "not supplied by the launcher";
    private static volatile String capeStatus = "not supplied by the launcher";

    public static void init() {
        MinecraftClient client = MinecraftClient.getInstance();
        String user = client != null && client.getSession() != null ? client.getSession().getUsername() : "?";
        VClientMod.LOGGER.info("[Skin] Signed in as {} (launcher skin uuid: {}).", user,
                LAUNCHER_UUID.isEmpty() ? "none - not an Ely.by launch" : LAUNCHER_UUID);
        if (SKIN_URL.isEmpty() && CAPE_URL.isEmpty()) {
            VClientMod.LOGGER.info("[Skin] The launcher did not supply a skin, so the game's own lookup is used.");
            return;
        }
        VClientMod.LOGGER.info("[Skin] Launcher supplied a skin for {} (model {}): {}", LAUNCHER_UUID, MODEL, SKIN_URL);
        if (!SKIN_URL.isEmpty()) load(SKIN_URL, true);
        if (!CAPE_URL.isEmpty()) load(CAPE_URL, false);
    }

    /** One line for the menu and the diagnostics: where the launcher-supplied skin and cape stand. */
    /** The Ely.by skin address the launcher supplied, or an empty string. */
    public static String launcherSkinUrl() {
        return SKIN_URL;
    }

    public static String launcherSkinModel() {
        return MODEL;
    }

    public static String status() {
        return "skin: " + skinStatus + "; cape: " + capeStatus;
    }

    public static boolean skinFailed() {
        return skinStatus.startsWith("failed");
    }

    private static void setStatus(boolean skin, String value) {
        if (skin) skinStatus = value;
        else capeStatus = value;
    }

    /**
     * Downloads one launcher-supplied texture without ever blocking a pool thread (chaining on the download
     * future), so the skin and the cape load side by side and a failure is reported instead of hanging.
     */
    private static void load(String address, boolean skin) {
        String label = skin ? "skin" : "cape";
        URI uri;
        try {
            uri = URI.create(address);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
                throw new IOException("only https texture addresses are accepted");
            }
        } catch (IOException | IllegalArgumentException e) {
            setStatus(skin, "failed: invalid address (" + e.getMessage() + ")");
            VClientMod.LOGGER.warn("[Skin] The launcher {} address {} is not usable: {}", label, address, e.getMessage());
            return;
        }
        Path cache = CosmeticNet.directory().getParent().resolve("skin-cache")
                .resolve(CosmeticNet.sha256(address.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".png");
        setStatus(skin, "downloading");
        VClientMod.LOGGER.info("[Skin] Fetching {} texture {} (cache {}).", label, uri, cache);
        CosmeticNet.download(uri, "", cache).whenComplete((bytes, error) -> {
            if (error != null) {
                String reason = CosmeticNet.describe(error);
                setStatus(skin, "failed: " + reason);
                VClientMod.LOGGER.warn("[Skin] Could not download the {} texture {}: {}", label, uri, reason);
                return;
            }
            VClientMod.LOGGER.info("[Skin] {} download finished ({} bytes).", label, bytes.length);
            try {
                NativeImage image = skin ? processSkin(NativeImage.read(bytes)) : NativeImage.read(bytes);
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> {
                    try {
                        Identifier id = Identifier.of("vclient", "launcher/" + label + "/" + Integer.toHexString(address.hashCode()));
                        client.getTextureManager().registerTexture(id,
                                new NativeImageBackedTexture(() -> "vclient launcher " + label, image));
                        if (skin) skinTexture = id;
                        else capeTexture = id;
                        loadedVersion++;
                        setStatus(skin, "ready");
                        VClientMod.LOGGER.info("[Skin] {} texture registered as {}.", label, id);
                    } catch (RuntimeException e) {
                        setStatus(skin, "failed: could not create the GPU texture (" + e.getMessage() + ")");
                        CosmeticNet.logError("Registering the " + label + " texture", e);
                    }
                });
            } catch (IOException | RuntimeException e) {
                setStatus(skin, "failed: the file is not a usable " + label + " image (" + e.getMessage() + ")");
                CosmeticNet.logError("Decoding the " + label + " texture from " + uri, e);
            }
        });
    }

    /**
     * Returns the textures to use for the profile {@code id}. For anyone but the local player, or when there
     * is nothing to change, this is {@code original} itself.
     */
    public static SkinTextures apply(UUID id, SkinTextures original) {
        return apply(id, null, original);
    }

    /** As {@link #apply(UUID, SkinTextures)}; {@code name} lets other players' Ely.by skins be looked up by name. */
    public static SkinTextures apply(UUID id, String name, SkinTextures original) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isLocalPlayer(client, id)) return remote(name, original);
        int state = CosmeticState.version();
        // The cape frame is part of the cache key: an animated cape needs a new SkinTextures each time its frame
        // changes (a few times a second), and a cape whose texture finished loading must replace the cached one.
        Cosmetic cape = CosmeticState.effective(CosmeticSlot.CAPE);
        int frame = cape == null ? -2 : CosmeticTextures.frameIndex(cape);
        if (original == lastInput && state == lastStateVersion && loadedVersion == lastSkinVersion
                && CosmeticCatalog.revision() == lastCatalogRevision && frame == lastFrame) {
            return lastOutput;
        }
        SkinTextures result = compute(original);
        lastInput = original;
        lastOutput = result;
        lastFrame = frame;
        lastStateVersion = state;
        lastSkinVersion = loadedVersion;
        lastCatalogRevision = CosmeticCatalog.revision();
        return result;
    }

    // ---- other players' Ely.by skins ---------------------------------------------------------------------

    /**
     * On offline-mode servers every player arrives with the default skin, so nobody sees anybody's. When another
     * player has the default skin, this looks their name up on Ely.by's skin system (once per name per session) and
     * shows that skin instead. Only players with an Ely.by skin of that name are affected. Turn it off with
     * {@code -Dvclient.skin.others=false}.
     */
    private static final boolean LOOK_UP_OTHERS = !"false".equalsIgnoreCase(System.getProperty("vclient.skin.others", "true"));
    private record RemoteSkin(Identifier skin, PlayerSkinType model) {}
    private static final java.util.concurrent.ConcurrentHashMap<String, RemoteSkin> REMOTE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final RemoteSkin NONE = new RemoteSkin(null, PlayerSkinType.byModelMetadata("default"));
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8)).build();

    private static SkinTextures remote(String name, SkinTextures original) {
        if (!LOOK_UP_OTHERS || name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return original;
        if (!original.comp_1626().comp_3627().getPath().startsWith("textures/")) return original; // a real skin loaded
        String key = name.toLowerCase(java.util.Locale.ROOT);
        RemoteSkin found = REMOTE.get(key);
        if (found == null) {
            if (REMOTE.putIfAbsent(key, NONE) == null) lookUp(name, key);
            return original;
        }
        if (found.skin() == null) return original;
        return new SkinTextures(new AssetInfo.TextureAssetInfo(found.skin(), found.skin()), original.comp_1627(),
                original.comp_1628(), found.model(), original.comp_1630());
    }

    private static void lookUp(String name, String key) {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("https://skinsystem.ely.by/textures/" + name))
                .timeout(java.time.Duration.ofSeconds(8)).header("User-Agent", "VSMP-Client").GET().build();
        HTTP.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            if (error != null || response.statusCode() != 200) return; // no Ely.by skin: keep the default
            try {
                com.google.gson.JsonObject skin = com.google.gson.JsonParser.parseString(response.body())
                        .getAsJsonObject().getAsJsonObject("SKIN");
                if (skin == null || !skin.has("url")) return;
                String address = skin.get("url").getAsString().replaceFirst("^http://", "https://");
                String model = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                        ? skin.getAsJsonObject("metadata").get("model").getAsString() : "default";
                URI uri = URI.create(address);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null) return;
                Path cache = CosmeticNet.directory().getParent().resolve("skin-cache")
                        .resolve(CosmeticNet.sha256(address.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".png");
                CosmeticNet.download(uri, "", cache).whenComplete((bytes, downloadError) -> {
                    if (downloadError != null) return;
                    try {
                        NativeImage image = processSkin(NativeImage.read(bytes));
                        MinecraftClient client = MinecraftClient.getInstance();
                        client.execute(() -> {
                            Identifier id = Identifier.of("vclient", "others/" + key);
                            client.getTextureManager().registerTexture(id,
                                    new NativeImageBackedTexture(() -> "vclient skin " + key, image));
                            REMOTE.put(key, new RemoteSkin(id, PlayerSkinType.byModelMetadata(model)));
                            VClientMod.LOGGER.info("[Skin] Showing {}'s Ely.by skin.", name);
                        });
                    } catch (IOException | RuntimeException e) {
                        VClientMod.LOGGER.warn("[Skin] {}'s Ely.by skin could not be used: {}", name, e.toString());
                    }
                });
            } catch (RuntimeException e) {
                VClientMod.LOGGER.warn("[Skin] Unreadable Ely.by answer for {}: {}", name, e.toString());
            }
        });
    }

    private static boolean loggedIdentity;

    /**
     * Whether {@code id} is the local player. On a single-player world it is the account's own UUID, but on an
     * offline-mode ("cracked") server the server gives you a different UUID derived from your name, so the
     * player entity's UUID and the signed-in name are checked as well.
     */
    private static boolean isLocalPlayer(MinecraftClient client, UUID id) {
        if (client == null || client.getSession() == null) return false;
        boolean local = id.equals(client.getSession().getUuidOrNull())
                || (client.player != null && id.equals(client.player.getUuid()));
        if (local && !loggedIdentity && !id.equals(client.getSession().getUuidOrNull())) {
            loggedIdentity = true;
            VClientMod.LOGGER.info("[Skin] The server uses a different UUID for you ({}) than your account ({}); "
                    + "applying the launcher skin to it.", id, client.getSession().getUuidOrNull());
        }
        return local;
    }

    private static SkinTextures compute(SkinTextures original) {
        AssetInfo.TextureAsset body = original.comp_1626();
        AssetInfo.TextureAsset cape = original.comp_1627();
        PlayerSkinType model = original.comp_1629();
        boolean changed = false;

        boolean vanillaIsDefault = body.comp_3627().getPath().startsWith("textures/");
        if (vanillaIsDefault && skinTexture != null) {
            body = new AssetInfo.TextureAssetInfo(skinTexture, skinTexture);
            model = PlayerSkinType.byModelMetadata(MODEL);
            changed = true;
            if (!loggedApplied) {
                loggedApplied = true;
                VClientMod.LOGGER.info("[Skin] Vanilla fell back to the default skin; applying the launcher skin {}.",
                        skinTexture);
            }
        } else if (!vanillaIsDefault && !loggedVanillaSkin) {
            loggedVanillaSkin = true;
            VClientMod.LOGGER.info("[Skin] Vanilla loaded the skin itself ({}); leaving it alone.", body.comp_3627());
        }

        Cosmetic equipped = CosmeticState.effective(CosmeticSlot.CAPE);
        Identifier cosmeticCape = equipped == null ? null : CosmeticTextures.current(equipped);
        if (cosmeticCape != null) {
            cape = new AssetInfo.TextureAssetInfo(cosmeticCape, cosmeticCape);
            changed = true;
        } else if (cape == null && capeTexture != null && equipped == null) {
            cape = new AssetInfo.TextureAssetInfo(capeTexture, capeTexture);
            changed = true;
        }
        return changed ? new SkinTextures(body, cape, original.comp_1628(), model, original.comp_1630()) : original;
    }

    // ---- skin image clean-up, same rules as vanilla's player skin loader -----------------------------

    private static NativeImage processSkin(NativeImage source) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width != 64 || (height != 32 && height != 64)) {
            source.close();
            throw new IOException("unsupported skin size " + width + "x" + height);
        }
        NativeImage image = new NativeImage(64, 64, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < 64; x++) image.setColorArgb(x, y, source.getColorArgb(x, y));
        source.close();
        if (height == 32) {
            copyMirrored(image, 4, 16, 16, 32, 4, 4);
            copyMirrored(image, 8, 16, 16, 32, 4, 4);
            copyMirrored(image, 0, 20, 24, 32, 4, 12);
            copyMirrored(image, 4, 20, 16, 32, 4, 12);
            copyMirrored(image, 8, 20, 8, 32, 4, 12);
            copyMirrored(image, 12, 20, 16, 32, 4, 12);
            copyMirrored(image, 44, 16, -8, 32, 4, 4);
            copyMirrored(image, 48, 16, -8, 32, 4, 4);
            copyMirrored(image, 40, 20, 0, 32, 4, 12);
            copyMirrored(image, 44, 20, -8, 32, 4, 12);
            copyMirrored(image, 48, 20, -16, 32, 4, 12);
            copyMirrored(image, 52, 20, -8, 32, 4, 12);
        }
        opaque(image, 0, 0, 32, 16);
        opaque(image, 0, 16, 64, 16);
        opaque(image, 16, 48, 32, 16);
        return image;
    }

    private static void copyMirrored(NativeImage image, int sx, int sy, int dx, int dy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setColorArgb(sx + dx + (w - 1 - x), sy + dy + y, image.getColorArgb(sx + x, sy + y));
            }
        }
    }

    private static void opaque(NativeImage image, int x0, int y0, int w, int h) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) image.setColorArgb(x, y, image.getColorArgb(x, y) | 0xFF000000);
        }
    }
}
