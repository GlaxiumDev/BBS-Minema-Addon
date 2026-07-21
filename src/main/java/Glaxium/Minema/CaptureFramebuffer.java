package Glaxium.Minema;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Off-screen render target used by RawCaptureModule when
 * {@link MinemaConfig#customResolution} is on -- ported from the standalone
 * BBS-Minema mod's {@code capture.CaptureFramebuffer}, adapted to this
 * addon's package/config.
 *
 * <p>The whole point of this class is that it lets F4 capture at a
 * resolution that has nothing to do with the physical window/monitor --
 * world, HUD, inventory, and every other mod's overlay all get rendered
 * directly at the target size (e.g. 4K) instead of being captured at the
 * window's real size and stretched afterwards, which is what caused the
 * "doesn't work with unusual resolutions" stretching problem this was asked
 * to fix.
 */
public final class CaptureFramebuffer implements AutoCloseable
{
    private final MinecraftClient client;
    private final SimpleFramebuffer framebuffer;
    private final Framebuffer windowFramebuffer;

    public CaptureFramebuffer(MinecraftClient client, int width, int height)
    {
        this.client = client;
        this.windowFramebuffer = client.getFramebuffer();
        this.framebuffer = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
        this.framebuffer.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        this.framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
    }

    public Framebuffer framebuffer()
    {
        return this.framebuffer;
    }

    public int width()
    {
        return this.framebuffer.textureWidth;
    }

    public int height()
    {
        return this.framebuffer.textureHeight;
    }

    /** The off-screen buffer's color attachment -- what RawCaptureRecorder actually reads pixels from. */
    public int colorTextureId()
    {
        return this.framebuffer.getColorAttachment();
    }

    /**
     * Draws a preview of the capture, scaled and letterboxed/pillarboxed to
     * fit the real physical window, without touching the capture
     * attachment itself. Without this, a fullscreen 4K (or any
     * non-window-aspect) capture would either not show anything on your
     * actual monitor, or (if Minecraft's own end-of-frame blit is left to
     * run unmodified) show a warped/stretched image, since that blit
     * assumes the framebuffer size and the physical window size are the
     * same thing. Called from WindowMixin right before the frame is
     * presented.
     */
    public void drawPreview()
    {
        int windowWidth = this.client.getWindow().getWidth();
        int windowHeight = this.client.getWindow().getHeight();
        double windowAspect = (double) windowWidth / windowHeight;
        double captureAspect = (double) this.framebuffer.textureWidth / this.framebuffer.textureHeight;
        int fitWidth;
        int fitHeight;

        if (captureAspect > windowAspect)
        {
            fitWidth = windowWidth;
            fitHeight = Math.max(1, (int) Math.round(windowWidth / captureAspect));
        }
        else
        {
            fitHeight = windowHeight;
            fitWidth = Math.max(1, (int) Math.round(windowHeight * captureAspect));
        }

        int xOffset = (windowWidth - fitWidth) / 2;
        int yOffset = (windowHeight - fitHeight) / 2;

        RenderSystem.disableScissor();
        RenderSystem.viewport(0, 0, windowWidth, windowHeight);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 1.0F);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.framebuffer.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        GL30.glBlitFramebuffer(
                0, 0, this.framebuffer.viewportWidth, this.framebuffer.viewportHeight,
                xOffset, yOffset, xOffset + fitWidth, yOffset + fitHeight,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR
        );

        this.windowFramebuffer.beginWrite(false);
    }

    @Override
    public void close()
    {
        this.framebuffer.delete();
        this.windowFramebuffer.beginWrite(true);
    }
}
