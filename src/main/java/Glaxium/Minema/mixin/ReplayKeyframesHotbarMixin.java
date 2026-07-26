package Glaxium.Minema.mixin;

import Glaxium.Minema.hotbarclip.RecordedHotbarData;
import Glaxium.Minema.hotbarclip.ReplayKeyframesHotbarAccess;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ReplayKeyframes.class, remap = false)
public class ReplayKeyframesHotbarMixin implements ReplayKeyframesHotbarAccess {
    @Unique private RecordedHotbarData bbsMinema$hotbar;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbsMinema$addChannels(String id, CallbackInfo info) {
        this.bbsMinema$hotbar = new RecordedHotbarData();
        this.bbsMinema$hotbar.addTo((ReplayKeyframes) (Object) this);
    }

    @Override
    public RecordedHotbarData bbsMinema$getHotbar() {
        return this.bbsMinema$hotbar;
    }
}
