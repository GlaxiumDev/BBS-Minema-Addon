package Glaxium.Minema.mixin;

import Glaxium.Minema.RawCaptureModule;
import Glaxium.Minema.SyncModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Singleplayer's "Save and quit to title" doesn't call the no-arg {@code
 * MinecraftClient#disconnect()} -- it calls the {@code disconnect(Screen)}
 * overload, passing the "Saving world" progress screen, and THAT call
 * blocks the calling (client/render) thread until the integrated server
 * thread has fully stopped.
 *
 * <p>That's a problem if {@link SyncModule#enabled} is still true at that
 * moment: the server thread can be parked in {@code
 * SyncModule#awaitPermissionToTick()} waiting for a release that would
 * normally arrive from the next render frame's {@code beginFrame()}/{@code
 * endFrame()} -- but no further frames happen while this very call is
 * still blocking. Genuine cross-thread deadlock, not just a slow spin --
 * the freeze at "Saving world".
 *
 * <p>{@link SyncModule#disableAndRelease()} at HEAD, before any of that
 * blocking begins, breaks it: it's a synchronous, immediate release (not
 * a wait-for-next-timeout flag flip), called on the same thread that's
 * about to do the blocking, before it starts blocking.
 *
 * <p>Also targets the no-arg {@code disconnect()} overload too, in case
 * some other disconnect path (multiplayer, being kicked, etc.) goes
 * through that one instead -- both do the same thing.
 */
@Mixin(MinecraftClient.class)
public class StopRecordingOnQuitWorldMixin
{
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("HEAD"))
    private void bbsMinema$stopRecordingOnQuitWithScreen(Screen screen, CallbackInfo ci)
    {
        bbsMinema$stopRecordingOnQuit();
    }

    @Inject(method = "disconnect()V", at = @At("HEAD"))
    private void bbsMinema$stopRecordingOnQuitNoScreen(CallbackInfo ci)
    {
        bbsMinema$stopRecordingOnQuit();
    }

    private void bbsMinema$stopRecordingOnQuit()
    {
        // Always safe and cheap to call, even if nothing was recording/syncing --
        // must run BEFORE the blocking server-stop sequence below has any
        // chance to start, so this can't be deferred via client.execute().
        SyncModule.disableAndRelease();

        if (!RawCaptureModule.INSTANCE.isRecording())
        {
            return;
        }

        // We're on the client thread here (disconnect(Screen)/disconnect()
        // are always called from it, never from Netty's thread), so this
        // runs its GL cleanup inline rather than hopping via execute().
        RawCaptureModule.INSTANCE.stop();

        MinecraftClient client = (MinecraftClient) (Object) this;

        if (client.player != null)
        {
            client.player.sendMessage(Text.literal("BBS Minema: recording stopped"), true);
        }
    }
}