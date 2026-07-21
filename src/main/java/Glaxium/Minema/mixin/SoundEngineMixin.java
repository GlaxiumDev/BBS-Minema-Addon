package Glaxium.Minema.mixin;

import Glaxium.Minema.GameAudioController;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.sound.SoundEngine;

import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/**
 * Targets vanilla {@code net.minecraft.client.sound.SoundEngine} directly --
 * deliberately NOT anything under mchorse.bbs_mod, so this works no matter
 * which bbs-mod build this addon is compiled against (see build.gradle).
 *
 * <p>The technique: OpenAL's SOFT_loopback extension lets you open a device
 * that isn't backed by any real audio hardware, and pull however many
 * samples you want out of it on demand with alcRenderSamplesSOFT, instead of
 * a real device which only gives you samples as fast as they physically
 * play. That's what makes this safe with BBS-Minema's tick-sync/slow-motion
 * feature: {@link Glaxium.Minema.GameAudioRecorder#captureFrame()} asks for
 * exactly (sampleRate / fps) samples once per *captured video frame*, not
 * once per real-world second, so the audio track's length is locked to the
 * frame count regardless of how fast or slow the simulation actually ran.
 *
 * <p>SoundEngine only picks its device once, in init() -- so flipping
 * {@link GameAudioController#requestCapture} alone does nothing until
 * something forces SoundEngine to close+reinit, which is why
 * {@link Glaxium.Minema.GameAudioRecorder#startRecording} calls
 * {@code SoundManager#reloadSounds()} right after requesting capture.
 *
 * <p>NOTE ON PORTING: this is a straight port of the same trick BBS CML
 * EDITION uses internally (their SoundEngineMixin), rewritten here as
 * addon-owned code that only touches this vanilla class. The exact
 * mixin targets below (field/method names) were confirmed against Yarn
 * 1.21.1; this project targets Yarn 1.20.4+build.1 per gradle.properties.
 * SoundEngine hasn't materially changed between those versions in past
 * BBS mod ports, but if this addon fails to build with "unable to locate
 * field/method" errors, run `./gradlew genSources` and check the actual
 * field/method names in your decompiled SoundEngine class and adjust the
 * @Shadow/@At targets below to match.
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin
{
    @Shadow
    private long devicePointer;

    @Unique
    private boolean bbsMinema$usingLoopbackDevice;

    @Inject(method = "init", at = @At("HEAD"))
    private void bbsMinema$init(String deviceSpecifier, boolean directionalAudio, CallbackInfo ci)
    {
        this.bbsMinema$usingLoopbackDevice = GameAudioController.isCaptureRequested();

        if (!this.bbsMinema$usingLoopbackDevice)
        {
            GameAudioController.setLoopbackDevice(0L);
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void bbsMinema$afterInit(String deviceSpecifier, boolean directionalAudio, CallbackInfo ci)
    {
        if (this.bbsMinema$usingLoopbackDevice)
        {
            GameAudioController.setLoopbackDevice(this.devicePointer);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void bbsMinema$close(CallbackInfo ci)
    {
        if (this.bbsMinema$usingLoopbackDevice)
        {
            GameAudioController.setLoopbackDevice(0L);
        }
    }

    @WrapOperation(
        method = "init",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundEngine;openDeviceOrFallback(Ljava/lang/String;)J")
    )
    private long bbsMinema$openLoopbackDevice(String deviceSpecifier, Operation<Long> original)
    {
        if (!this.bbsMinema$usingLoopbackDevice)
        {
            return original.call(deviceSpecifier);
        }

        return SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
    }

    @WrapOperation(
        method = "init",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J", remap = false)
    )
    private long bbsMinema$createLoopbackContext(long deviceHandle, IntBuffer attrList, Operation<Long> original)
    {
        if (!this.bbsMinema$usingLoopbackDevice)
        {
            return original.call(deviceHandle, attrList);
        }

        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer format = stack.callocInt(7)
                .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_FLOAT_SOFT)
                .put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT)
                .put(ALC10.ALC_FREQUENCY).put(GameAudioController.SAMPLE_RATE)
                .put(0)
                .flip();

            return ALC10.alcCreateContext(deviceHandle, format);
        }
    }
}
