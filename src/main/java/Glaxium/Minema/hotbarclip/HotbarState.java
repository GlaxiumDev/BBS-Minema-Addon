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
    /**
     * Health one tick earlier on the same keyframe curve (see HotbarClip#applyClip). Used purely
     * to drive the vanilla-style "hurt/heal flash" on the hearts that changed -- NOT a general
     * "previous frame" value, so it stays correct even when scrubbing the timeline backwards/
     * jumping around, unlike a mutable field that only makes sense during forward playback.
     */
    public float lastHealth;

    /**
     * Lowest and highest {@code health} value seen over the last {@link HotbarClip#HEAL_FLASH_WINDOW_TICKS}
     * ticks (including now) -- a sustained rolling window, not a single-tick comparison against
     * {@link #lastHealth}. Used to decide BOTH whether a flash should be showing at all right now, and
     * which specific hearts (whose HP band overlaps [recentHealthLow, recentHealthHigh]) it applies to
     * -- real vanilla only ever flashes the hearts actually within the range that changed, for damage
     * AND healing alike; it does not flash the whole bar in either direction. A raw "value >
     * lastHealth this exact tick" check was only ever true for the one or two ticks where the health
     * curve actually crosses, far shorter than one full on/off blink cycle and with no memory of which
     * HP range was involved -- this rolling window fixes both problems at once.
     */
    public float recentHealthLow;

    /** @see #recentHealthLow */
    public float recentHealthHigh;

    public float healthContainer;
    public float absorption;
    public float absorptionContainer;
    public float armor;
    public float hunger;
    public float air;
    public float experience;
    public int experienceLevel;
    /**
     * Baked {@code heart_flash} value for this frame (from the recorded {@code hurtTime} timer,
     * see RecordedHotbarData#record): 0 = off, 1..10 = active white hurt-flash strength/timer.
     * See UIHotbarRenderer#renderBar, which forces every rendered heart to blink for as long as
     * this is above 0.
     */
    public float heartFlash;
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