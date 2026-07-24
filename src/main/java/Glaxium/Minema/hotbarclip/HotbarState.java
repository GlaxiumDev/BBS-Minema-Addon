package Glaxium.Minema.hotbarclip;

import net.minecraft.item.ItemStack;

/**
 * Ported from BBS-CML-EDITION (which doesn't exist in vanilla bbs-mod) by BBS-Minema-Addon, kept
 * entirely in this addon's own package -- it only USES vanilla bbs-mod's public API, it isn't
 * part of bbs-mod. See HotbarClip for the clip that produces this per-frame snapshot, and
 * UIHotbarRenderer for what draws it.
 */
public class HotbarState
{
    public static final int HEART_NORMAL = 0;
    public static final int HEART_POISONED = 1;
    public static final int HEART_WITHERED = 2;
    public static final int HEART_ABSORBING = 3;
    public static final int HEART_FROZEN = 4;

    public final ItemStack[] items = new ItemStack[9];
    public ItemStack offhandItem = ItemStack.EMPTY;
    public int selectedSlot;
    public int heartType;
    public boolean hardcore;
    public boolean heartRegeneration;
    public boolean hungerEffect;
    public float health;
    public float healthContainer;
    public float absorption;
    public float absorptionContainer;
    public float armor;
    public float hunger;
    public float air;
    public float experience;
    public int experienceLevel;
    public float x;
    public float y;
    public float scale;
    public float alpha;

    /**
     * Draw-order among multiple simultaneous HUD-overlay clip types (subtitle/image/boss
     * bar/hotbar). CML edition's ClipContext has a matching "applied" counter to fill this in
     * meaningfully; vanilla bbs-mod's ClipContext doesn't, so this addon always leaves it at 0 --
     * harmless here since we draw hotbars in our own pass, never interleaved with vanilla's
     * subtitle rendering.
     */
    public int renderOrder;
}
