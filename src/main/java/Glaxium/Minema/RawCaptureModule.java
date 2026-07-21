package Glaxium.Minema;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.Window;

/**
 * Drives the "raw" (full screen, GUI included) capture -- see
 * RawCaptureRecorder for the actual pixel-pushing.
 *
 * <p>Two modes, chosen every time F4 is pressed:
 * <ul>
 *   <li>{@link MinemaConfig#customResolution} off (default), or the window
 *   isn't in true fullscreen: unchanged original behaviour -- records at
 *   whatever the game window's actual current framebuffer size is, no
 *   resize, no custom resolution.</li>
 *   <li>{@link MinemaConfig#customResolution} on AND the window is in true
 *   fullscreen: renders into a dedicated off-screen {@link CaptureFramebuffer}
 *   sized to BBS mod's own {@code BBSSettings.videoSettings.width}/
 *   {@code height} instead, via {@code MinecraftClient#framebuffer} (made
 *   public+mutable by this addon's own access widener -- see
 *   bbs-minema.accesswidener), and {@link Glaxium.Minema.mixin.WindowMixin}
 *   spoofs the window's reported size so everything (world, HUD, other
 *   mods' overlays) rebuilds and renders at the target resolution instead
 *   of the monitor's. This is what makes 4K (or any unusual resolution)
 *   capture possible without stretching.</li>
 * </ul>
 *
 * <p>Custom resolution is deliberately windowed-mode-excluded: a windowed
 * game can be resized by the OS/user at any moment, and spoofing its
 * reported size while that's possible desyncs Minecraft's internal size
 * tracking from the real GLFW window, corrupting the screen until a world
 * reload. Fullscreen's dimensions are fixed by the display mode, so that
 * failure mode can't happen there.
 */
public class RawCaptureModule
{
    public static final RawCaptureModule INSTANCE = new RawCaptureModule();

    private final RawCaptureRecorder recorder = new RawCaptureRecorder();

    /**
     * Mirrors what {@code VideoRecorder#serverTicks} is for BBS mod's own
     * recorder -- a running count of how many fixed-timestep world ticks
     * this capture has asked for so far. Advanced from
     * MinemaRenderTickCounterMixin (once per frame, by however many whole
     * ticks that frame's fixed timestep covered) -- always the CLIENT
     * thread, and always the only thread that ever writes to this field.
     * SyncModule reads this from the integrated SERVER thread instead of
     * VideoRecorder#serverTicks while BBS-Minema's own F4 capture is the
     * one actually running -- {@code volatile} here is for that
     * cross-thread visibility, not atomicity: single-writer/multi-reader,
     * so the "non-atomic operation on volatile field" warning IDEs raise
     * on {@code +=} doesn't apply (there's no concurrent writer it could
     * race against).
     */
    private volatile int serverTicks;

    /**
     * How many frames this recording has captured so far -- drives
     * {@link MinemaConfig#frameLimit}'s auto-stop, same accounting Minema
     * 1.12.2's CaptureSession used ({@code time.getNumFrames()}). Reset to 0
     * in {@link #start()}, incremented once per frame at the end of
     * {@link #captureFrame()}.
     */
    private int framesCaptured;

    /** Non-null only while a custom-resolution fullscreen capture is active. */
    private CaptureFramebuffer captureFramebuffer;

    /** {@code MinecraftClient#framebuffer} as it was before we swapped it out, so it can be restored on stop. */
    private Framebuffer originalFramebuffer;

    public boolean isRecording()
    {
        return this.recorder.isRecording();
    }

    public int getServerTicks()
    {
        return this.serverTicks;
    }

    public void addServerTicks(int delta)
    {
        //noinspection NonAtomicOperationOnVolatileField -- see the field's own doc comment: single-writer (client thread) only.
        this.serverTicks += delta;
    }

    /** Non-null only while a custom-resolution fullscreen capture is active -- used by WindowMixin for the on-screen preview blit. */
    public CaptureFramebuffer getCaptureFramebuffer()
    {
        return this.captureFramebuffer;
    }

    public void start()
    {
        if (this.recorder.isRecording())
        {
            return;
        }

        this.serverTicks = 0;
        this.framesCaptured = 0;

        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();
        MinemaConfig config = MinemaConfig.INSTANCE;

        boolean useCustomResolution = config.customResolution && window.isFullscreen();

        if (useCustomResolution)
        {
            // BBSSettings.videoSettings.width/height IS BBS mod's own
            // "Edit settings" Frame Resolution values -- there's no
            // separate copy to keep in sync, this reads the exact same
            // ValueInt object BBS's own settings panel edits.
            int width = BBSSettings.videoSettings.width.get();
            int height = BBSSettings.videoSettings.height.get();

            this.originalFramebuffer = client.getFramebuffer();
            this.captureFramebuffer = new CaptureFramebuffer(client, width, height);

            // Made accessible+mutable by this addon's own access widener
            // (bbs-minema.accesswidener) -- BBS mod declares the same
            // entry in its own AW, but relying on that transitively did
            // NOT work with this project's actual Loom setup, so it's
            // declared directly here too. No accessor mixin needed either
            // way.
            client.framebuffer = this.captureFramebuffer.framebuffer();
            this.captureFramebuffer.framebuffer().beginWrite(true);

            this.recorder.startRecording(width, height, this.captureFramebuffer.colorTextureId());

            // Must happen after the framebuffer swap above (so GameRenderer/
            // WorldRenderer rebuild their render targets against the new
            // capture-sized buffer) and after WindowMixin has something to
            // key its "active" check off (this.recorder.isRecording() is
            // already true by this point).
            client.onResolutionChanged();
        }
        else
        {
            // Unchanged original behaviour -- whatever's actually being
            // displayed right now, no resize, no target resolution.
            int width = window.getFramebufferWidth();
            int height = window.getFramebufferHeight();

            this.recorder.startRecording(width, height, 0);
        }
    }

    public void stop()
    {
        MinecraftClient client = MinecraftClient.getInstance();

        // stop() makes raw GL calls (via RawCaptureRecorder#stopRecording --
        // glDeleteBuffers etc.), which are only legal on the thread that
        // owns the GL context. Every caller inside this addon (F4/Shift+F4
        // in BBSMinema#onClientTick, the window-resize guard in
        // #captureFrame below) already runs on that thread -- but BBS
        // mod's own quit-to-title path fires Fabric's
        // ClientPlayConnectionEvents.DISCONNECT from Netty's network I/O
        // thread instead, and that's exactly what was crashing with "No
        // context is current" (LWJGL aborting the JVM on an off-thread GL
        // call). Hopping onto the client thread via execute() here fixes
        // that regardless of which hook ends up calling stop() -- it's a
        // no-op hop (runs immediately) for callers already on the right
        // thread, and a one-tick-deferred hop for anything that isn't.
        if (!client.isOnThread())
        {
            client.execute(this::stop);

            return;
        }

        this.recorder.stopRecording();

        if (this.captureFramebuffer != null)
        {
            if (this.originalFramebuffer != null)
            {
                client.framebuffer = this.originalFramebuffer;
                this.originalFramebuffer.beginWrite(true);
            }

            this.captureFramebuffer.close();
            this.captureFramebuffer = null;
            this.originalFramebuffer = null;

            client.onResolutionChanged();
        }
    }

    /**
     * Call once per frame, after everything (world, GUI, screens, HUD) has
     * finished drawing for that frame -- i.e. at TAIL of
     * MinecraftClient#render, same spot SyncModule#endFrame() runs from.
     *
     * Gated on BBSRendering.canRender (same flag the depth pass and audio
     * use) so this only writes a frame on the same fixed-timestep cadence
     * as everything else -- without this check, a raw capture would write
     * one frame per real render call instead of one per target-FPS captured
     * frame, and the output video's actual framerate wouldn't match what
     * ffmpeg was told to encode it at.
     */
    public void captureFrame()
    {
        if (!this.recorder.isRecording() || !BBSRendering.canRender)
        {
            return;
        }

        // Only the plain (non-custom-resolution) path reads FBO 0 -- the
        // REAL window backbuffer -- directly, at whatever width/height it
        // happened to be when startRecording() ran. If the physical window
        // is resized while that's happening (dragging a windowed game's
        // border, or toggling Fullscreen/changing GUI Scale from the
        // vanilla Video Settings screen while F4 recording is active),
        // glReadPixels(0, 0, staleWidth, staleHeight, ...) is reading a
        // size that no longer matches FBO 0's actual, reallocated
        // dimensions -- undefined content at best, a driver-level crash at
        // worst. Stop cleanly the moment that happens instead of feeding
        // ffmpeg (and the GPU) a mismatched read every frame after.
        if (this.recorder.isReadingRealBackbuffer())
        {
            MinecraftClient client = MinecraftClient.getInstance();
            Window window = client.getWindow();

            if (window.getFramebufferWidth() != this.recorder.getWidth()
                    || window.getFramebufferHeight() != this.recorder.getHeight())
            {
                this.stop();

                if (client.player != null)
                {
                    client.player.sendMessage(net.minecraft.text.Text.literal(
                            "BBS Minema: recording stopped (window was resized)"), true);
                }

                return;
            }
        }

        this.recorder.recordFrame();
        this.framesCaptured++;

        // Same convention as Minema 1.12.2's CaptureSession: frameLimit <= 0
        // means unlimited, and the check happens right after the frame that
        // reaches the limit is captured (so a limit of 300 keeps exactly
        // frames 1..300, not 299).
        int frameLimit = MinemaConfig.INSTANCE.frameLimit;

        if (frameLimit > 0 && this.framesCaptured >= frameLimit)
        {
            this.stop();

            MinecraftClient client = MinecraftClient.getInstance();

            if (client.player != null)
            {
                client.player.sendMessage(net.minecraft.text.Text.literal(
                        "BBS Minema: recording stopped (frame limit reached)"), true);
            }
        }
    }
}