package Glaxium.Minema.mixin;

import Glaxium.Minema.hotbarclip.ReplayKeyframesHotbarAccess;
import mchorse.bbs_mod.film.Recorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Recorder.class, remap = false)
public class RecorderHotbarMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private void bbsMinema$recordHud(CallbackInfo info) {
        Recorder recorder = (Recorder) (Object) this;
        if (recorder.hasNotStarted() || recorder.tick < 0) return;
        if (!(recorder.keyframes instanceof ReplayKeyframesHotbarAccess access)) return;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null && access.bbsMinema$getHotbar() != null) {
            access.bbsMinema$getHotbar().record(recorder.tick, player);
        }
    }
}
