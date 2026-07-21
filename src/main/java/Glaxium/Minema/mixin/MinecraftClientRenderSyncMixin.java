package Glaxium.Minema.mixin;

import Glaxium.Minema.SyncModule;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render-thread half of the addon's SyncModule handshake -- see
 * {@link SyncModule} for the full explanation.
 *
 * <p>{@code beginRenderTick()} (patched by BBS mod's own RenderTickCounterMixin)
 * is what decides how many new server ticks this frame is asking for and
 * stashes that as {@code VideoRecorder#serverTicks}; right after that call
 * returns, {@link SyncModule#beginFrame()} knows this frame's target and can
 * drain/hold ticks accordingly.
 *
 * <p>{@link SyncModule#endFrame()} runs at TAIL of {@code render()} --
 * meaning after every WorldRenderEvents.LAST listener for this frame has
 * finished, including BBS mod's own color capture and this addon's depth
 * capture -- so the server is only released to start its next tick once
 * this frame's capture commands have actually been issued.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientRenderSyncMixin
{
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/RenderTickCounter;beginRenderTick(J)I",
            shift = At.Shift.AFTER
        )
    )
    private void bbsMinema$beginFrame(boolean tick, CallbackInfo ci)
    {
        SyncModule.beginFrame();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void bbsMinema$endFrame(boolean tick, CallbackInfo ci)
    {
        SyncModule.endFrame();
    }
}
