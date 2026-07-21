package Glaxium.Minema.mixin;

import Glaxium.Minema.RawCaptureModule;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures one raw (full screen, GUI included) frame at TAIL of
 * MinecraftClient#render -- after every WorldRenderEvents.LAST listener AND
 * all GUI/screen drawing has already happened for this frame, so whatever
 * we read back really is the complete frame a person watching would have
 * seen, inventory/settings/chat/debug and all.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientRawCaptureMixin
{
    @Inject(method = "render", at = @At("TAIL"))
    private void bbsMinema$captureRawFrame(boolean tick, CallbackInfo ci)
    {
        RawCaptureModule.INSTANCE.captureFrame();
    }
}
