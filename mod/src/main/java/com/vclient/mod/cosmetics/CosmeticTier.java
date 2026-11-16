package com.vclient.mod.cosmetics;

/** FREE is always usable, PREMIUM needs to be owned, EVENT is usable while its event window is open. */
public enum CosmeticTier {
    FREE("Free", 0xFF7CF29A), PREMIUM("Premium", 0xFF00CFFF), EVENT("Event", 0xFFB8F4FF);

    private final String label;
    private final int color;

    CosmeticTier(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public int color() {
        return color;
    }

    /** Unknown tiers from a newer server are treated as premium, i.e. locked, never as free. */
    static CosmeticTier parse(String value) {
        if (value != null) {
            for (CosmeticTier tier : values()) if (tier.name().equalsIgnoreCase(value)) return tier;
        }
        return PREMIUM;
    }
}
