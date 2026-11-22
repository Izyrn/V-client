package com.vclient.mod.cosmetics;

/**
 * The tabs of the Cosmetics menu. {@link #ANIMATED} is a pure grouping: its items declare which
 * {@link CosmeticSlot} they occupy, the others have a fixed slot.
 */
public enum CosmeticCategory {
    CAPE("Capes", CosmeticSlot.CAPE), WINGS("Wings", CosmeticSlot.WINGS), HAT("Hats", CosmeticSlot.HAT),
    EMOTE("Emotes", CosmeticSlot.EMOTE), ANIMATED("Animated", null), TRAIL("Trails", CosmeticSlot.TRAIL),
    PET("Pets", CosmeticSlot.PET), AURA("Auras", CosmeticSlot.AURA), KILL_EFFECT("Kill FX", CosmeticSlot.KILL_EFFECT),
    DEATH_EFFECT("Death FX", CosmeticSlot.DEATH_EFFECT), VICTORY_EFFECT("Win FX", CosmeticSlot.VICTORY_EFFECT);

    private final String label;
    private final CosmeticSlot slot;

    CosmeticCategory(String label, CosmeticSlot slot) {
        this.label = label;
        this.slot = slot;
    }

    public String label() {
        return label;
    }

    /** The slot items of this category use, or null when each item names its own. */
    public CosmeticSlot fixedSlot() {
        return slot;
    }

    static CosmeticCategory parse(String value) {
        if (value == null) return null;
        for (CosmeticCategory category : values()) if (category.name().equalsIgnoreCase(value)) return category;
        return null;
    }
}
