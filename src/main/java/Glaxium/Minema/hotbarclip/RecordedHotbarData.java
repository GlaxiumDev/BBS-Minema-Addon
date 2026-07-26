package Glaxium.Minema.hotbarclip;

import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;

public final class RecordedHotbarData {
    public final KeyframeChannel[] slots = new KeyframeChannel[9];
    public final KeyframeChannel health = channel("health", KeyframeFactories.DOUBLE);
    public final KeyframeChannel healthContainer = channel("health_container", KeyframeFactories.DOUBLE);
    public final KeyframeChannel absorption = channel("absorption", KeyframeFactories.DOUBLE);
    public final KeyframeChannel absorptionContainer = channel("absorption_container", KeyframeFactories.DOUBLE);
    public final KeyframeChannel heartType = channel("heart_type", KeyframeFactories.INTEGER);
    public final KeyframeChannel hardcore = channel("hardcore", KeyframeFactories.BOOLEAN);
    public final KeyframeChannel regeneration = channel("regeneration", KeyframeFactories.BOOLEAN);
    public final KeyframeChannel armor = channel("armor", KeyframeFactories.DOUBLE);
    public final KeyframeChannel hunger = channel("hunger", KeyframeFactories.DOUBLE);
    public final KeyframeChannel hungerEffect = channel("hunger_effect", KeyframeFactories.BOOLEAN);
    public final KeyframeChannel air = channel("air", KeyframeFactories.DOUBLE);
    public final KeyframeChannel experience = channel("experience", KeyframeFactories.DOUBLE);
    public final KeyframeChannel experienceLevel = channel("experience_level", KeyframeFactories.INTEGER);

    private static KeyframeChannel channel(String id, IKeyframeFactory factory) {
        return new KeyframeChannel("hotbar_" + id, factory);
    }

    public RecordedHotbarData() {
        for (int i = 0; i < 9; i++) slots[i] = channel("slot_" + i, KeyframeFactories.ITEM_STACK);
    }

    public void addTo(ValueGroup group) {
        for (KeyframeChannel channel : slots) group.add(channel);
        group.add(health); group.add(healthContainer); group.add(absorption); group.add(absorptionContainer);
        group.add(heartType); group.add(hardcore); group.add(regeneration); group.add(armor); group.add(hunger);
        group.add(hungerEffect); group.add(air); group.add(experience); group.add(experienceLevel);
    }

    public boolean hasData() {
        return !slots[0].isEmpty() || !health.isEmpty();
    }

    public void record(int tick, PlayerEntity player) {
        for (int i = 0; i < 9; i++) slots[i].insert(tick, player.getInventory().getStack(i).copy());
        double absorptionValue = Math.max(0D, player.getAbsorptionAmount());
        health.insert(tick, (double) player.getHealth());
        healthContainer.insert(tick, (double) player.getMaxHealth());
        absorption.insert(tick, absorptionValue);
        absorptionContainer.insert(tick, absorptionValue);
        int type = player.hasStatusEffect(StatusEffects.POISON) ? HotbarState.HEART_POISONED
            : player.hasStatusEffect(StatusEffects.WITHER) ? HotbarState.HEART_WITHERED
            : player.isFrozen() ? HotbarState.HEART_FROZEN : HotbarState.HEART_NORMAL;
        heartType.insert(tick, type);
        hardcore.insert(tick, player.getWorld().getLevelProperties().isHardcore());
        regeneration.insert(tick, player.hasStatusEffect(StatusEffects.REGENERATION));
        armor.insert(tick, (double) player.getArmor());
        hunger.insert(tick, (double) player.getHungerManager().getFoodLevel());
        hungerEffect.insert(tick, player.hasStatusEffect(StatusEffects.HUNGER));
        air.insert(tick, (double) Math.max(0, Math.min(300, player.getAir())));
        experience.insert(tick, (double) player.experienceProgress);
        experienceLevel.insert(tick, player.experienceLevel);
    }
}
