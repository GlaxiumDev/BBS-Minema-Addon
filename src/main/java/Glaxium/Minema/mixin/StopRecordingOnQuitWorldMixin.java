package Glaxium.Minema.mixin;

import Glaxium.Minema.RawCaptureModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pressing Escape in-world calls {@code MinecraftClient#setScreen(new
 * GameMenuScreen(true))} directly -- unlike F4/Shift+F4 (this addon's own
 * keybind, handled entirely in {@link Glaxium.Minema.BBSMinema#onClientTick}),
 * there's no addon-owned code in that path at all, so a raw-capture
 * recording that's still active when the pause menu opens was previously
 * left to collide with whatever vanilla/BBS mod state a screen open
 * disturbs (framebuffer/window-size assumptions the custom-resolution
 * fullscreen path in {@link WindowMixin} depends on chief among them),
 * which is what was crashing the game.
 *
 * <p>Stopping the recording at HEAD of setScreen -- before GameMenuScreen
 * actually gets initialized -- sidesteps that collision entirely: it's the
 * same "stop recording" call plain F4 already makes, just triggered by
 * Escape instead. The menu still opens normally afterwards; this mixin
 * only ever touches the recording, never cancels the screen.
 *
 * <p>Deliberately scoped to {@link GameMenuScreen} specifically (the pause
 * menu Escape opens), not "any screen" -- raw capture is built to record
 * the inventory, chat, and other in-game GUIs right along with everything
 * else, so stopping on every screen open would defeat that.
 */
@Mixin(MinecraftClient.class)
public class StopRecordingOnQuitWorldMixin
{
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void bbsMinema$stopRecordingBeforeGameMenu(Screen screen, CallbackInfo ci)
    {
        if (!(screen instanceof GameMenuScreen))
        {
            return;
        }

        if (!RawCaptureModule.INSTANCE.isRecording())
        {
            return;
        }

        RawCaptureModule.INSTANCE.stop();

        MinecraftClient client = (MinecraftClient) (Object) this;

        if (client.player != null)
        {
            client.player.sendMessage(Text.literal("BBS Minema: recording stopped"), true);
        }
    }
}
