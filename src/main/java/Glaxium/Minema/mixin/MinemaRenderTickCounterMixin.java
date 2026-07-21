package Glaxium.Minema.mixin;

import Glaxium.Minema.MinemaConfig;
import Glaxium.Minema.RawCaptureModule;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Same trick BBS mod's own RenderTickCounterMixin uses for its recording,
 * and the same trick Minema 1.12.2 used before that: while capturing, pin
 * tickDelta/lastFrameDuration to a fixed increment derived from the target
 * export frame rate instead of real wall-clock time. Without this, the
 * captured video would play back at whatever your actual, fluctuating FPS
 * happened to be while recording instead of a consistent declared rate --
 * fast when your FPS is high, slow-motion when it dips, since ffmpeg is
 * simply told to encode incoming frames at a fixed rate and has no idea how
 * much real time each one actually took.
 *
 * Deliberately separate from bbs-mod's own RenderTickCounterMixin (which
 * only activates for BBS mod's own VideoRecorder, permanently disabled for
 * F4 now -- see DisableBBSVideoKeyMixin) rather than trying to reuse it, so
 * BBS-Minema's raw capture works even if BBS mod's own recorder is
 * triggered some other way (e.g. film editor export) at the same time --
 * in that edge case this defers entirely rather than fighting over
 * tickDelta.
 */
@Mixin(RenderTickCounter.class)
public class MinemaRenderTickCounterMixin
{
    @Shadow
    public float tickDelta;

    @Shadow
    public float lastFrameDuration;

    @Shadow
    private long prevTimeMillis;

    @Inject(method = "beginRenderTick", at = @At("HEAD"), cancellable = true)
    private void bbsMinema$onBeginRenderTick(long timeMillis, CallbackInfoReturnable<Integer> info)
    {
        if (!RawCaptureModule.INSTANCE.isRecording())
        {
            return;
        }

        // BBS mod's own recorder (if active via some other trigger) already
        // handles this exact injection point -- don't double-drive it.
        if (BBSModClient.getVideoRecorder().isRecording())
        {
            return;
        }

        float frameRate = (float) BBSRendering.getVideoFrameRate();
        float engineSpeed = (float) MinemaConfig.INSTANCE.engineSpeed;

        // Engine speed multiplies how many world ticks each captured frame accounts for --
        // >1 makes the world simulate faster than the video plays back (timelapse), <1 slower
        // (slow motion). See MinemaConfig#engineSpeed.
        this.lastFrameDuration = (20F / frameRate) * engineSpeed;
        this.prevTimeMillis = timeMillis;
        this.tickDelta += this.lastFrameDuration;

        int wholeTicks = (int) this.tickDelta;

        this.tickDelta -= (float) wholeTicks;

        RawCaptureModule.INSTANCE.addServerTicks(wholeTicks);

        // Reuses BBSRendering.canRender as the shared "a fixed-timestep
        // capture frame is ready" flag -- everything downstream (audio
        // cadence, depth pass cadence, the recording HUD indicator) already
        // gates on it the same way BBS mod's own recorder does.
        BBSRendering.canRender = true;

        info.setReturnValue(wholeTicks);
    }
}
