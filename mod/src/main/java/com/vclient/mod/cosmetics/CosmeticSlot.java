package com.vclient.mod.cosmetics;

/** Where a cosmetic is worn. A player can equip one cosmetic per slot. */
public enum CosmeticSlot {
    CAPE("Cape"), WINGS("Wings"), HAT("Hat"), TRAIL("Trail"), EMOTE("Emote"), PET("Pet"), AURA("Aura"),
    KILL_EFFECT("Kill effect"), DEATH_EFFECT("Death effect"), VICTORY_EFFECT("Victory effect");

    private final String label;

    CosmeticSlot(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    static CosmeticSlot parse(String value) {
        if (value == null) return null;
        for (CosmeticSlot slot : values()) if (slot.name().equalsIgnoreCase(value)) return slot;
        return null;
    }
}
