package Glaxium.Minema;

import Glaxium.Minema.ui.MinemaQuickCaptureScreen;
import Glaxium.Minema.ui.MinemaSettingsButton;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.StringUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * F4 is now owned entirely by BBS-Minema (see {@link #recordKey}), not BBS
 * mod's own VideoRecorder -- BBS mod's own F4 keybinding is neutralized by
 * DisableBBSVideoKeyMixin (and hidden from Controls entirely by
 * HideBBSVideoKeybindMixin) so it can never start BBS mod's own world-only
 * recording pipeline again. Pressing F4 here drives RawCaptureModule
 * directly, which reads the real, final, already-composited framebuffer
 * (world + HUD + inventory + settings screens + other mods' UIs -- exactly
 * what Minema 1.12.2 recorded), with its own independent fixed-timestep
 * pacing (MinemaRenderTickCounterMixin) so output speed doesn't depend on
 * BBS mod's recorder being active at all.
 *
 * The depth pass, in-game audio, tick sync, custom resolution, and engine
 * speed settings are all reachable from two places that both read/write
 * the exact same {@link MinemaConfig#INSTANCE}, so they're always in sync
 * with each other: BBS's own "Minema Settings" button inside its video
 * settings panel (opens {@link Glaxium.Minema.ui.MinemaSettingsOverlayPanel},
 * built on BBS's UI framework), and Shift+F4 out in the world (opens
 * {@link Glaxium.Minema.ui.MinemaQuickCaptureScreen}, a standalone vanilla
 * {@code Screen} outside BBS's UI entirely, matching how the old standalone
 * BBS-Minema mod's own UI worked). The old J keybind has been removed
 * completely -- Shift+F4 is its replacement out in the world.
 * These settings still key off {@link #isAnyRecording()} -- true
 * either while BBS-Minema's own F4 recording is running, or while BBS
 * mod's own VideoRecorder happens to be active some other way (e.g. the
 * film editor's "export video" button, which doesn't go through F4 at all
 * and is left completely intact) -- so they still work no matter which
 * pipeline is actually rolling.
 */
public class BBSMinema implements ClientModInitializer
{
    private final MinemaRecorder depthRecorder = new MinemaRecorder();
    private final GameAudioRecorder audioRecorder = new GameAudioRecorder();
    // Shared with MinemaSettingsOverlayPanel, which has no reference to
    // this class -- both read/write the same static MinemaConfig.INSTANCE.
    private final MinemaConfig config = MinemaConfig.INSTANCE;
    private boolean wasRecording = false;
    private boolean wasSyncing = false;
    private boolean wasRecordingAudio = false;

    /** Set the moment we open the WAV file, used to find BBS mod's own output video for muxing afterwards -- see GameAudioRecorder#muxIntoVideo. */
    private long audioRecordingStartedAt;

    /**
     * BBS-Minema's own F4 -- kept registered mainly so it still shows up as
     * "Toggle Recording" in the Controls menu, rebindable like any other
     * key. IMPORTANT: actual press detection does NOT use this
     * KeyBinding's own wasPressed() -- see #onClientTick for why.
     */
    private KeyBinding recordKey;

    /** Edge-detection state for the raw F4 polling in #onClientTick -- see there for why this doesn't use KeyBinding#wasPressed(). */
    private boolean f4WasDown;

    @Override
    public void onInitializeClient()
    {
        this.config.load();

        this.recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bbs_minema.toggle_raw_capture",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F4, // matches Minema 1.12.2's default -- BBS mod's own F4 is disabled, see DisableBBSVideoKeyMixin
                "key.categories.bbs_minema"
        ));

        MinemaSettingsButton.register();

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        WorldRenderEvents.LAST.register(this::onWorldRenderLast);

        // Don't leave an ffmpeg process hanging (and the window resized) if
        // the world unloads out from under an active recording.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
        {
            if (RawCaptureModule.INSTANCE.isRecording())
            {
                RawCaptureModule.INSTANCE.stop();
            }
        });
    }

    /** True while either BBS-Minema's own F4 capture or BBS mod's own VideoRecorder (triggered some other way) is recording. */
    private boolean isAnyRecording()
    {
        return RawCaptureModule.INSTANCE.isRecording() || BBSModClient.getVideoRecorder().isRecording();
    }

    private static boolean isKeyDown(long handle, int glfwKey)
    {
        return InputUtil.isKeyPressed(handle, glfwKey);
    }

    /** Checked at the moment F4 is pressed to decide between toggling recording (F4) and opening quick-capture settings (Shift+F4). */
    private static boolean isShiftDown(long handle)
    {
        return isKeyDown(handle, GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /**
     * Deliberately does NOT use {@code this.recordKey.wasPressed()}.
     *
     * BBS mod registers its OWN "Record Video" keybinding defaulted to F4
     * too (see BBSModClient#keyRecordVideo -- createKey("record_video",
     * GLFW.GLFW_KEY_F4)). DisableBBSVideoKeyMixin stops that keybinding's
     * own wasPressed() from doing anything, and HideBBSVideoKeybindMixin
     * hides it from the Controls menu -- but neither of those change the
     * fact that vanilla's KeyBinding class tracks "which KeyBinding
     * currently owns physical key F4" in a single shared static map
     * (KEY_TO_BINDINGS), and only ONE KeyBinding object can own a given
     * physical key in that map at a time. Whichever of the two F4
     * KeyBinding objects (ours or BBS mod's) gets constructed/registered
     * LAST during mod init ends up owning F4 in that map -- and that
     * ordering isn't guaranteed to be the same between runs (Fabric Loader
     * doesn't promise a fixed initialization order across mods), which is
     * exactly the "worked once, then stopped after restart" bug: on some
     * launches our own recordKey loses the race and BBS's dead (but still
     * key-mapped) KeyBinding silently eats every F4 press instead.
     *
     * Polling the physical key directly via GLFW every tick sidesteps that
     * shared map entirely -- this doesn't care which KeyBinding object
     * "owns" F4, it just asks the window "is F4 physically down right
     * now", which is unaffected by BBS mod's colliding keybinding.
     */
    private void onClientTick(MinecraftClient client)
    {
        long handle = client.getWindow().getHandle();
        boolean f4Down = isKeyDown(handle, GLFW.GLFW_KEY_F4);
        boolean f4Pressed = f4Down && !this.f4WasDown;

        this.f4WasDown = f4Down;

        if (f4Pressed)
        {
            if (isShiftDown(handle))
            {
                // Shift+F4 -- open the quick-capture screen instead of
                // toggling recording. Consumes the same keypress;
                // recording itself is untouched by this branch.
                client.setScreen(new MinemaQuickCaptureScreen(client.currentScreen));
            }
            else if (RawCaptureModule.INSTANCE.isRecording())
            {
                RawCaptureModule.INSTANCE.stop();

                if (client.player != null)
                {
                    client.player.sendMessage(Text.literal("BBS Minema: recording stopped"), true);
                }
            }
            else
            {
                RawCaptureModule.INSTANCE.start();

                if (client.player != null)
                {
                    client.player.sendMessage(Text.literal("BBS Minema: recording started"), true);
                }
            }
        }

        // Either toggle alone should still capture -- "Generate .wav" by
        // itself needs audio captured just as much as "Record in-game
        // audio" does, it just skips the mux step below. Independent of
        // BBSRendering.canRender (unlike the depth pass) -- this only needs
        // isAnyRecording(), so it reacts identically whether recording was
        // started via F4 (this addon) or the film editor, and starts as
        // early as possible to give the loopback device time to come up
        // before the first captured frame.
        boolean wantsAudio = this.config.recordGameAudio || this.config.generateWavFile;
        boolean recordingAudioNow = wantsAudio && this.isAnyRecording();

        if (recordingAudioNow && !this.wasRecordingAudio)
        {
            this.audioRecordingStartedAt = System.currentTimeMillis();

            this.audioRecorder.startRecording(
                    BBSRendering.getVideoFolder().toPath(),
                    StringUtils.createTimestampFilename(),
                    (int) Math.max(1, BBSRendering.getVideoFrameRate())
            );
        }
        else if (!recordingAudioNow && this.wasRecordingAudio)
        {
            File wav = this.audioRecorder.stopRecording();

            if (wav != null && this.config.recordGameAudio)
            {
                long startedAt = this.audioRecordingStartedAt;
                boolean keepWav = this.config.generateWavFile;

                // ffmpeg mux can take a while on longer recordings -- run it
                // off the client tick thread so it doesn't freeze the game.
                // muxIntoVideo/findColorVideoOutput/File I/O below don't
                // touch anything client-thread-only.
                Thread muxThread = new Thread(() -> this.audioRecorder.muxIntoVideo(
                        wav,
                        BBSRendering.getVideoFolder().toPath(),
                        startedAt,
                        keepWav
                ), "bbs-minema-audio-mux");

                muxThread.setDaemon(true);
                muxThread.start();
            }

            // "Generate .wav" was the only thing on (recordGameAudio is
            // off) -- the WAV GameAudioRecorder already wrote is the
            // finished output, nothing further to do. If NEITHER toggle
            // was on, wav is null and this whole branch is a no-op.
        }

        this.wasRecordingAudio = recordingAudioNow;
        boolean syncingNow = this.config.syncEngine
                && this.isAnyRecording()
                && client.isIntegratedServerRunning();

        if (syncingNow && !this.wasSyncing)
        {
            // Rising edge only -- re-arms SyncModule's bookkeeping against
            // whatever VideoRecorder#serverTicks currently is, so turning
            // sync on mid-recording doesn't try to catch up on ticks that
            // already ran unsynced.
            SyncModule.reset();
        }

        SyncModule.enabled = syncingNow;
        this.wasSyncing = syncingNow;
    }

    private void onWorldRenderLast(WorldRenderContext context)
    {
        // canRender is driven by whichever pacing mixin is actually active
        // for the current recording -- MinemaRenderTickCounterMixin for
        // BBS-Minema's own F4 capture, or bbs-mod's own RenderTickCounterMixin
        // if VideoRecorder is running some other way. Either way it means
        // "a fixed-timestep capture frame is ready right now."
        boolean recordingNow = this.isAnyRecording()
                && BBSRendering.canRender
                && this.config.captureDepth;

        if (recordingNow && !this.wasRecording)
        {
            // Match whatever RawCaptureModule is actually capturing at --
            // now that F4 no longer resizes the window to a target
            // resolution, the depth pass has to read the window's real
            // current size too, or it'd end up a different resolution than
            // the color output.
            net.minecraft.client.util.Window window = MinecraftClient.getInstance().getWindow();

            this.depthRecorder.setPlanes(0.05F, (float) this.config.captureDepthDistance);
            this.depthRecorder.startRecording(
                    window.getFramebufferWidth(),
                    window.getFramebufferHeight()
            );
        }
        else if (!recordingNow && this.wasRecording)
        {
            this.depthRecorder.stopRecording();
        }

        if (recordingNow)
        {
            Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();
            int depthTextureId = framebuffer.getDepthAttachment();

            this.depthRecorder.recordFrame(depthTextureId);
        }

        this.wasRecording = recordingNow;

        // Same canRender gate as the depth pass -- one audio frame per
        // color frame actually captured, not per render call. Either audio
        // toggle being on should keep frames flowing in -- see the
        // wantsAudio comment in onClientTick for why.
        boolean capturingAudioNow = this.isAnyRecording()
                && BBSRendering.canRender
                && (this.config.recordGameAudio || this.config.generateWavFile);

        if (capturingAudioNow)
        {
            this.audioRecorder.captureFrame();
        }

        // Raw (full screen, GUI included) capture's own start/stop is now
        // driven directly by the F4 keypress in onClientTick, not an edge
        // -- see below.
    }
}