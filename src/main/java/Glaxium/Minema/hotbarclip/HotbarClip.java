package Glaxium.Minema.hotbarclip;

import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import net.minecraft.item.ItemStack;

import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Ported from BBS-CML-EDITION (which doesn't exist in vanilla bbs-mod) by BBS-Minema-Addon, kept
 * entirely in this addon's own package -- registered into vanilla bbs-mod's own Film editor from
 * BBSMinema#onInitializeClient via BBSMod's own public {@code getFactoryCameraClips()}, an
 * extension point already used internally the same way (see BBSMod's own clip registrations) --
 * no mixins, and no code of ours living inside bbs-mod's own package.
 *
 * <p>Fakes the whole HUD (hotbar items in all 9 slots + offhand, health, hunger, armor, XP, air,
 * hardcore/frozen heart state, and the hotbar's screen position/scale) with its own per-clip
 * keyframe timeline, independent of what the player is actually holding/has -- e.g. "make it look
 * like the player has full health and is holding a diamond sword" for a shot, regardless of
 * their real state.
 *
 * <p>One real difference from CML's original: CML added an {@code isPositionClip()} method to
 * the base {@code CameraClip} class that vanilla bbs-mod doesn't have, so that override is
 * dropped here -- harmless, vanilla's {@code CameraClip#apply()} just lerps a {@code Position}
 * this clip type doesn't use anyway (same as vanilla's own screen-effect clips already do).
 */
public class HotbarClip extends CameraClip
{
    private static final float MAX_HEALTH_CONTAINER = 1200F; /* 60 rows * 10 hearts * 2 HP */

    public final KeyframeChannel<Integer> selectedSlot = new KeyframeChannel<>("selected_slot", KeyframeFactories.INTEGER);
    public final KeyframeChannel<ItemStack> slot0 = new KeyframeChannel<>("slot_0", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<ItemStack> slot1 = new KeyframeChannel<>("slot_1", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<ItemStack> slot2 = new KeyframeChannel<>("slot_2", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<ItemStack> slot3 = new KeyframeChannel<>("slot_3", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<ItemStack> slot4 = new KeyframeChannel<>("slot_4", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<ItemStack> slot5 = new KeyframeChannel<>("slot_5", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<ItemStack> slot6 = new KeyframeChannel<>("slot_6", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<ItemStack> slot7 = new KeyframeChannel<>("slot_7", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<ItemStack> slot8 = new KeyframeChannel<>("slot_8", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<ItemStack> offhandSlot = new KeyframeChannel<>("offhand_slot", KeyframeFactories.ITEM_STACK);
    public final KeyframeChannel<Double> health = new KeyframeChannel<>("health", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> healthContainer = new KeyframeChannel<>("health_container", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> absorption = new KeyframeChannel<>("absorption", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> absorptionContainer = new KeyframeChannel<>("absorption_container", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Integer> heartType = new KeyframeChannel<>("heart_type", KeyframeFactories.INTEGER);
    public final KeyframeChannel<Boolean> hardcore = new KeyframeChannel<>("hardcore", KeyframeFactories.BOOLEAN);
    public final KeyframeChannel<Boolean> heartRegeneration = new KeyframeChannel<>("heart_regeneration", KeyframeFactories.BOOLEAN);
    public final KeyframeChannel<Double> armor = new KeyframeChannel<>("armor", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> hunger = new KeyframeChannel<>("hunger", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Boolean> hungerEffect = new KeyframeChannel<>("hunger_effect", KeyframeFactories.BOOLEAN);
    public final KeyframeChannel<Double> air = new KeyframeChannel<>("air", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Double> experience = new KeyframeChannel<>("experience", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Integer> experienceLevel = new KeyframeChannel<>("experience_level", KeyframeFactories.INTEGER);
    public final KeyframeChannel<Double> heartFlash = new KeyframeChannel<>("heart_flash", KeyframeFactories.DOUBLE);
    public final KeyframeChannel<Vector4f> layout = new KeyframeChannel<>("layout", KeyframeFactories.VECTOR4F);

    public final KeyframeChannel[] channels;

    /**
     * Index into the film's replays list (same convention as vanilla's own
     * {@code Anchor#replay} field / {@code UIAnchorKeyframeFactory.displayActors}) of the actor
     * this clip's "Pick Source" button currently has selected, or {@code -1} if none has been
     * picked yet. Purely a UI-facing pick right now -- it doesn't drive the keyframes above
     * automatically, it just remembers the choice and gates whether {@code UIHotbarClip}'s
     * "Edit Keyframes" button is shown, so the panel doesn't dump raw keyframe controls on
     * someone before they've told it which actor this hotbar is for.
     */
    public final ValueInt source = new ValueInt("source", -1);

    public HotbarClip()
    {
        this.channels = new KeyframeChannel[] {
                this.layout,
                this.selectedSlot,
                this.slot0, this.slot1, this.slot2, this.slot3, this.slot4, this.slot5, this.slot6, this.slot7, this.slot8, this.offhandSlot,
                this.health, this.healthContainer, this.absorption, this.absorptionContainer, this.heartType, this.hardcore, this.heartRegeneration, this.armor, this.hunger, this.hungerEffect, this.air, this.experience, this.experienceLevel, this.heartFlash,
        };

        for (KeyframeChannel channel : this.channels)
        {
            this.add(channel);
        }

        this.add(this.source);

        this.selectedSlot.insert(0, 0);
        this.health.insert(0, 20D);
        this.healthContainer.insert(0, 20D);
        this.absorption.insert(0, 0D);
        this.absorptionContainer.insert(0, 0D);
        this.heartType.insert(0, HotbarState.HEART_NORMAL);
        this.hardcore.insert(0, false);
        this.heartRegeneration.insert(0, false);
        this.armor.insert(0, 0D);
        this.hunger.insert(0, 20D);
        this.hungerEffect.insert(0, false);
        this.air.insert(0, 300D);
        this.experience.insert(0, 0D);
        this.experienceLevel.insert(0, 0);
        this.heartFlash.insert(0, 0D);
        this.layout.insert(0, new Vector4f(0F, 0F, 1F, 0F));
    }

    public static List<HotbarState> getHotbars(ClipContext context)
    {
        return context.clipData.get("hotbars", ArrayList::new);
    }

    /**
     * Fills in this clip's keyframes from a recorded Replay's own data -- see UIHotbarClip's
     * "Bake Keyframes" button. Only copies what BBS's replay/actor recording actually captures:
     * {@code selectedSlot} and {@code offHand} copy over directly (same types, so a straight
     * {@code copyKeyframes} 1:1), and the currently-held item ({@code mainHand}) gets written
     * into whichever of the 9 slot channels was selected at each recorded tick.
     *
     * <p>This deliberately does NOT touch health/hunger/armor/absorption/XP/air/hardcore --
     * BBS's replay recording has no data for any of those (see {@code IEntity}, which only
     * exposes position/rotation/equipment/selected-slot, nothing about vitals), so there's
     * nothing real to bake into those channels. They're left exactly as they were; keyframe them
     * by hand, or bake them once BBS's own recording is extended to capture that data.
     *
     * <p>The other 8 hotbar slots (whichever ISN'T selected at a given recorded tick) also can't
     * be baked -- BBS's replay only ever tracks the currently-held item, never the other 8 slots
     * simultaneously, so their prior contents are simply unknown. This clears and only writes
     * the slot that was actually selected at each recorded tick; every other slot is left empty
     * for that whole stretch rather than guessing.
     */
    public void bakeFromReplay(ReplayKeyframes source)
    {
        this.selectedSlot.copyKeyframes(source.selectedSlot);
        this.offhandSlot.copyKeyframes(source.offHand);

        if (source instanceof Glaxium.Minema.hotbarclip.ReplayKeyframesHotbarAccess access)
        {
            Glaxium.Minema.hotbarclip.RecordedHotbarData hud = access.bbsMinema$getHotbar();

            if (hud != null && hud.hasData())
            {
                this.slot0.copyKeyframes(hud.slots[0]);
                this.slot1.copyKeyframes(hud.slots[1]);
                this.slot2.copyKeyframes(hud.slots[2]);
                this.slot3.copyKeyframes(hud.slots[3]);
                this.slot4.copyKeyframes(hud.slots[4]);
                this.slot5.copyKeyframes(hud.slots[5]);
                this.slot6.copyKeyframes(hud.slots[6]);
                this.slot7.copyKeyframes(hud.slots[7]);
                this.slot8.copyKeyframes(hud.slots[8]);
                this.health.copyKeyframes(hud.health);
                this.healthContainer.copyKeyframes(hud.healthContainer);
                this.absorption.copyKeyframes(hud.absorption);
                this.absorptionContainer.copyKeyframes(hud.absorptionContainer);
                this.heartType.copyKeyframes(hud.heartType);
                this.hardcore.copyKeyframes(hud.hardcore);
                this.heartRegeneration.copyKeyframes(hud.regeneration);
                this.armor.copyKeyframes(hud.armor);
                this.hunger.copyKeyframes(hud.hunger);
                this.hungerEffect.copyKeyframes(hud.hungerEffect);
                this.air.copyKeyframes(hud.air);
                this.experience.copyKeyframes(hud.experience);
                this.experienceLevel.copyKeyframes(hud.experienceLevel);
                this.heartFlash.copyKeyframes(hud.heartFlash);
                return;
            }
        }

        KeyframeChannel[] slots = {
                this.slot0, this.slot1, this.slot2, this.slot3, this.slot4,
                this.slot5, this.slot6, this.slot7, this.slot8
        };

        for (KeyframeChannel slot : slots) slot.removeAll();

        TreeSet<Float> ticks = new TreeSet<>();
        for (Keyframe keyframe : source.selectedSlot.getKeyframes()) ticks.add(keyframe.getTick());
        for (Keyframe keyframe : source.mainHand.getKeyframes()) ticks.add(keyframe.getTick());

        for (float tick : ticks)
        {
            int index = Math.max(0, Math.min(8, source.selectedSlot.interpolate(tick, 0)));
            ItemStack item = source.mainHand.interpolate(tick, ItemStack.EMPTY);
            slots[index].insert(tick, item == null ? ItemStack.EMPTY : item.copy());
        }

        // Every baked channel needs an actual keyframe at tick 0, not just whichever tick the
        // recording happened to start real changes at -- otherwise a channel whose only
        // keyframe is, say, "picked up a block at tick 50" would hold that single keyframe's
        // value across the ENTIRE timeline (keyframe interpolation clamps to the nearest
        // keyframe outside its range), making the block appear to have been held since tick 0
        // instead of only from tick 50 onward. Extending the first real value backward to tick 0
        // (rather than resetting to some arbitrary default) keeps everything before the first
        // real change exactly as flat/empty as it actually was.
        //
        // Same reasoning at the other end: without an explicit keyframe at the clip's last tick,
        // whatever the LAST recorded change was would clamp forward and appear to hold all the
        // way to the end of the clip too, even past however long that item was actually held for
        // in the source recording. Holding the last real value through to the clip's actual last
        // tick keeps the tail end honest instead of silently extending it.
        float lastTick = Math.max(0F, this.duration.get() - 1);

        this.ensureFirstKeyframeAtZero(this.selectedSlot);
        this.ensureLastKeyframeAtEnd(this.selectedSlot, lastTick);
        this.ensureFirstKeyframeAtZero(this.offhandSlot);
        this.ensureLastKeyframeAtEnd(this.offhandSlot, lastTick);

        for (KeyframeChannel<ItemStack> slot : slots)
        {
            this.ensureFirstKeyframeAtZero(slot);
            this.ensureLastKeyframeAtEnd(slot, lastTick);
        }
    }

    private <T> void ensureFirstKeyframeAtZero(KeyframeChannel<T> channel)
    {
        List<Keyframe<T>> keyframes = channel.getKeyframes();

        if (keyframes.isEmpty())
        {
            return;
        }

        Keyframe<T> first = keyframes.get(0);

        if (first.getTick() > 0F)
        {
            channel.insert(0F, first.getValue());
        }
    }

    private <T> void ensureLastKeyframeAtEnd(KeyframeChannel<T> channel, float lastTick)
    {
        List<Keyframe<T>> keyframes = channel.getKeyframes();

        if (keyframes.isEmpty())
        {
            return;
        }

        Keyframe<T> last = keyframes.get(keyframes.size() - 1);

        if (last.getTick() < lastTick)
        {
            channel.insert(lastTick, last.getValue());
        }
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        if (this.source.get() < 0)
        {
            return;
        }

        float t = context.relativeTick + context.transition;
        float alpha = this.envelope.factorEnabled(this.duration.get(), t);

        if (alpha <= 0F)
        {
            return;
        }

        HotbarState state = new HotbarState();

        state.selectedSlot = Math.max(0, Math.min(8, this.selectedSlot.interpolate(t)));
        state.items[0] = this.copyItem(this.slot0.interpolate(t));
        state.items[1] = this.copyItem(this.slot1.interpolate(t));
        state.items[2] = this.copyItem(this.slot2.interpolate(t));
        state.items[3] = this.copyItem(this.slot3.interpolate(t));
        state.items[4] = this.copyItem(this.slot4.interpolate(t));
        state.items[5] = this.copyItem(this.slot5.interpolate(t));
        state.items[6] = this.copyItem(this.slot6.interpolate(t));
        state.items[7] = this.copyItem(this.slot7.interpolate(t));
        state.items[8] = this.copyItem(this.slot8.interpolate(t));
        state.offhandItem = this.copyItem(this.offhandSlot.interpolate(t));
        state.healthContainer = this.clampHealthContainer(this.healthContainer.interpolate(t));
        state.health = this.clampHealth(this.health.interpolate(t), state.healthContainer);
        state.lastHealth = this.resolveLastHealth(t, state.health);
        state.absorptionContainer = this.clampHealthContainer(this.absorptionContainer.interpolate(t));
        state.absorption = this.clampHealth(this.absorption.interpolate(t), state.absorptionContainer);
        state.heartType = this.clampHeartType(this.heartType.interpolate(t));
        state.hardcore = this.interpolateHardcore(t);
        state.heartRegeneration = this.heartRegeneration.interpolate(t, false);
        state.armor = this.clampStat(this.armor.interpolate(t));
        state.hunger = this.clampStat(this.hunger.interpolate(t));
        state.hungerEffect = this.hungerEffect.interpolate(t, false);
        state.air = this.clampAir(this.air.interpolate(t));
        state.experience = this.clampExperience(this.experience.interpolate(t));
        state.experienceLevel = this.clampExperienceLevel(this.experienceLevel.interpolate(t));
        state.heartFlash = Math.max(0F, this.heartFlash.interpolate(t, 0D).floatValue());
        Vector4f layout = this.layout.interpolate(t, new Vector4f(0F, 0F, 1F, 0F));
        state.x = layout.x;
        state.y = layout.y;
        state.scale = Math.max(0.05F, layout.z);
        state.alpha = alpha;
        // Vanilla bbs-mod's ClipContext has no "applied" counter (CML-only addition for
        // ordering multiple overlay-clip types against each other) -- left at the default 0,
        // harmless since this addon renders hotbars in their own pass, never interleaved with
        // vanilla's subtitle rendering. See HotbarState#renderOrder.

        getHotbars(context).add(state);
    }

    /**
     * Health one tick before {@code t} on this clip's own keyframe curve -- deliberately re-derived
     * from the curve itself rather than cached from the previous render call, so it stays correct
     * no matter how the timeline is scrubbed (jumping around, playing backwards, etc.), unlike a
     * mutable "last seen value" field would. This is what lets the hotbar flash hearts during a
     * health keyframe transition (e.g. 5 -> 20), the same way vanilla flashes hearts when the
     * player's real health changes.
     *
     * <p>Falls back to {@code currentHealth} (i.e. no flash) if anything about this comes back
     * non-finite -- safer than risking a flash that's stuck on or a NaN propagating into rendering.
     */
    private float resolveLastHealth(float t, float currentHealth)
    {
        try
        {
            float previousHealthContainer = this.clampHealthContainer(this.healthContainer.interpolate(t - 1F));
            float previousHealth = this.clampHealth(this.health.interpolate(t - 1F), previousHealthContainer);

            return Float.isFinite(previousHealth) ? previousHealth : currentHealth;
        }
        catch (Exception exception)
        {
            return currentHealth;
        }
    }

    private ItemStack copyItem(ItemStack stack)
    {
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    private float clampStat(Double value)
    {
        return Math.max(0F, Math.min(20F, value.floatValue()));
    }

    private float clampHealth(Double value, float healthContainer)
    {
        return Math.max(0F, Math.min(healthContainer, value.floatValue()));
    }

    private int clampHeartType(Integer value)
    {
        return Math.max(HotbarState.HEART_NORMAL, Math.min(HotbarState.HEART_FROZEN, value));
    }

    private float clampHealthContainer(Double value)
    {
        return Math.max(0F, Math.min(MAX_HEALTH_CONTAINER, value.floatValue()));
    }

    private float clampExperience(Double value)
    {
        return Math.max(0F, Math.min(1F, value.floatValue()));
    }

    private float clampAir(Double value)
    {
        return Math.max(0F, Math.min(300F, value.floatValue()));
    }

    private int clampExperienceLevel(Integer value)
    {
        return Math.max(0, Math.min(9999, value));
    }

    @Override
    public void fromData(BaseType data)
    {
        if (data != null && data.isMap())
        {
            MapType map = data.asMap();
            MapType hardcoreData = map.getMap("hardcore", null);

            if (hardcoreData != null && !"boolean".equals(hardcoreData.getString("type")))
            {
                hardcoreData.putString("type", "boolean");
            }

            this.migrateLegacyLayout(map);
        }

        super.fromData(data);
    }

    private void migrateLegacyLayout(MapType map)
    {
        if (map.has("layout") || (!map.has("x") && !map.has("y") && !map.has("scale")))
        {
            return;
        }

        KeyframeChannel<Double> legacyX = this.readLegacyDoubleChannel(map.getMap("x", null));
        KeyframeChannel<Double> legacyY = this.readLegacyDoubleChannel(map.getMap("y", null));
        KeyframeChannel<Double> legacyScale = this.readLegacyDoubleChannel(map.getMap("scale", null));

        TreeSet<Float> ticks = new TreeSet<>();
        Map<Float, Keyframe<Double>> xByTick = this.collectByTick(legacyX, ticks);
        Map<Float, Keyframe<Double>> yByTick = this.collectByTick(legacyY, ticks);
        Map<Float, Keyframe<Double>> scaleByTick = this.collectByTick(legacyScale, ticks);

        if (ticks.isEmpty())
        {
            ticks.add(0F);
        }

        MapType layoutData = new MapType();
        ListType keyframes = new ListType();

        layoutData.putString("type", "vector4f");
        layoutData.put("keyframes", keyframes);

        for (float tick : ticks)
        {
            float x = legacyX.interpolate(tick, 0D).floatValue();
            float y = legacyY.interpolate(tick, 0D).floatValue();
            float scale = legacyScale.interpolate(tick, 1D).floatValue();
            Keyframe<Double> source = xByTick.get(tick);

            if (source == null)
            {
                source = yByTick.get(tick);
            }

            if (source == null)
            {
                source = scaleByTick.get(tick);
            }

            MapType keyframeData = source == null ? new MapType() : source.toData().asMap();
            ListType value = new ListType();

            value.addFloat(x);
            value.addFloat(y);
            value.addFloat(scale);
            value.addFloat(0F);

            keyframeData.putFloat("tick", tick);
            keyframeData.put("value", value);
            keyframes.add(keyframeData);
        }

        map.put("layout", layoutData);
    }

    private KeyframeChannel<Double> readLegacyDoubleChannel(MapType data)
    {
        KeyframeChannel<Double> channel = new KeyframeChannel<>("legacy", KeyframeFactories.DOUBLE);

        if (data != null)
        {
            channel.fromData(data);
        }

        return channel;
    }

    private Map<Float, Keyframe<Double>> collectByTick(KeyframeChannel<Double> channel, TreeSet<Float> ticks)
    {
        Map<Float, Keyframe<Double>> byTick = new HashMap<>();

        for (Keyframe<Double> keyframe : channel.getKeyframes())
        {
            ticks.add(keyframe.getTick());
            byTick.put(keyframe.getTick(), keyframe);
        }

        return byTick;
    }

    @SuppressWarnings("rawtypes")
    private boolean interpolateHardcore(float tick)
    {
        if (this.hardcore.getFactory() == KeyframeFactories.BOOLEAN)
        {
            return this.hardcore.interpolate(tick, false);
        }

        Object value = ((KeyframeChannel) this.hardcore).interpolate(tick, 0);

        if (value instanceof Number number)
        {
            return number.intValue() > 0;
        }

        if (value instanceof Boolean bool)
        {
            return bool;
        }

        return false;
    }

    @Override
    protected Clip create()
    {
        return new HotbarClip();
    }
}