package Glaxium.Minema.ui;

import Glaxium.Minema.MinemaConfig;
import Glaxium.Minema.RawCaptureModule;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * A small red "REC  &lt;frame count&gt;" indicator in the top-left corner, in the same spirit as
 * the overlay BBS mod's own F4 recording used to draw (via {@code BBSRendering#renderHud}, tied
 * to {@code VideoRecorder#isRecording()}) -- except tied to {@link RawCaptureModule}'s own
 * recording state instead, since BBS mod's own {@code VideoRecorder} is never actually started
 * anymore (F4 is fully owned by this addon -- see DisableBBSVideoKeyMixin). Controlled by its own
 * {@link MinemaConfig#showOverlay} toggle ("Show Overlay" in both
 * {@link MinemaSettingsScreen} and {@link MinemaSettingsOverlayPanel}) rather than BBS mod's own
 * {@code BBSSettings.recordingOverlays} -- a dedicated switch here, not a shared one that could
 * also be flipped from somewhere unrelated to this addon.
 *
 * <p>Registered via the normal, public {@link HudRenderCallback} -- meaning this draws during
 * the same HUD pass as chat/F3/vanilla HUD, well before {@code RawCaptureModule#captureFrame()}
 * reads the finished frame back at the TAIL of {@code MinecraftClient#render}. That also means
 * this indicator, like the rest of the HUD, IS captured into the recorded video, same as it
 * always was for BBS mod's own F4 recording. Turn "Show Overlay" off if you don't want that --
 * recording itself is completely unaffected either way.
 */
public final class RecordingOverlay
{
    private RecordingOverlay()
    {
    }

    public static void register()
    {
        HudRenderCallback.EVENT.register(RecordingOverlay::render);
    }

    private static void render(DrawContext context, float tickDelta)
    {
        if (!RawCaptureModule.INSTANCE.isRecording())
        {
            return;
        }

        if (!MinemaConfig.INSTANCE.showOverlay)
        {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        String text = "\u25CF REC  " + RawCaptureModule.INSTANCE.getFramesCaptured();

        context.drawTextWithShadow(client.textRenderer, Text.literal(text), 6, 6, 0xFF5555);
    }
}