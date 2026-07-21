package Glaxium.Minema.mixin;

import Glaxium.Minema.CaptureFramebuffer;
import Glaxium.Minema.MinemaConfig;
import Glaxium.Minema.RawCaptureModule;
import mchorse.bbs_mod.BBSSettings;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ported from the standalone BBS-Minema mod's {@code mixin.WindowMixin},
 * adapted to this addon's config/module classes.
 *
 * <p>Reports the capture resolution instead of the physical window size
 * while a custom-resolution F4 recording is active, so GameRenderer/
 * WorldRenderer/BBS mod's own overlays -- everything that sizes itself off
 * the {@link Window}, not the framebuffer object -- rebuild at the capture
 * size instead of the on-screen window size. Overriding just
 * getFramebufferWidth/Height isn't enough on its own: HUD/inventory/GUI
 * layout uses getScaledWidth/getScaledHeight, separate cached values that
 * don't recompute just because the framebuffer getters are spoofed --
 * leaving those alone is exactly what would put the HUD/inventory in the
 * wrong place (the classic "stretched" symptom) in a 4K/unusual-resolution
 * recording.
 *
 * <p>Only active in true fullscreen -- see RawCaptureModule for why.
 */
@Mixin(Window.class)
public abstract class WindowMixin
{
    @Inject(method = "getFramebufferWidth", at = @At("HEAD"), cancellable = true)
    private void bbsMinema$captureWidth(CallbackInfoReturnable<Integer> cir)
    {
        if (bbsMinema$active())
        {
            cir.setReturnValue(BBSSettings.videoSettings.width.get());
        }
    }

    @Inject(method = "getFramebufferHeight", at = @At("HEAD"), cancellable = true)
    private void bbsMinema$captureHeight(CallbackInfoReturnable<Integer> cir)
    {
        if (bbsMinema$active())
        {
            cir.setReturnValue(BBSSettings.videoSettings.height.get());
        }
    }

    @Inject(method = "getScaledWidth", at = @At("HEAD"), cancellable = true)
    private void bbsMinema$captureScaledWidth(CallbackInfoReturnable<Integer> cir)
    {
        if (bbsMinema$active())
        {
            double scaleFactor = Math.max(1.0, ((Window) (Object) this).getScaleFactor());

            cir.setReturnValue((int) Math.max(1, Math.ceil(BBSSettings.videoSettings.width.get() / scaleFactor)));
        }
    }

    @Inject(method = "getScaledHeight", at = @At("HEAD"), cancellable = true)
    private void bbsMinema$captureScaledHeight(CallbackInfoReturnable<Integer> cir)
    {
        if (bbsMinema$active())
        {
            double scaleFactor = Math.max(1.0, ((Window) (Object) this).getScaleFactor());

            cir.setReturnValue((int) Math.max(1, Math.ceil(BBSSettings.videoSettings.height.get() / scaleFactor)));
        }
    }

    /**
     * Minecraft's own end-of-frame blit uses this same spoofed
     * getFramebufferWidth/Height to decide how much of the real screen to
     * draw into -- in fullscreen, where the physical display can't
     * actually be resized to match, that blit would corrupt the visible
     * window. Right before the frame is presented, redraw the capture
     * framebuffer's contents scaled (letterboxed, never stretched) to the
     * TRUE physical window size instead, overwriting Minecraft's bad blit
     * with a correct one. What's actually written to disk is read back
     * from the full-resolution capture framebuffer separately by
     * RawCaptureRecorder, before this runs -- this only ever affects what
     * you see on your own monitor while recording.
     */
    @Inject(method = "swapBuffers", at = @At("HEAD"))
    private void bbsMinema$correctOnScreenPreview(CallbackInfo ci)
    {
        if (!bbsMinema$active())
        {
            return;
        }

        CaptureFramebuffer framebuffer = RawCaptureModule.INSTANCE.getCaptureFramebuffer();

        if (framebuffer != null)
        {
            framebuffer.drawPreview();
        }
    }

    private boolean bbsMinema$active()
    {
        return RawCaptureModule.INSTANCE.isRecording()
                && MinemaConfig.INSTANCE.customResolution
                && ((Window) (Object) this).isFullscreen();
    }
}
