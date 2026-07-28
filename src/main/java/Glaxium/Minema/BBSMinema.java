package Glaxium.Minema;

import Glaxium.Minema.hotbarclip.HotbarClip;
import Glaxium.Minema.hotbarclip.HotbarClipRenderer;
import Glaxium.Minema.hotbarclip.UIHotbarClip;
import Glaxium.Minema.hotbarclip.UIHotbarIntegerKeyframeFactory;
import Glaxium.Minema.hotbarclip.UIHotbarItemKeyframeFactory;
import Glaxium.Minema.ui.MinemaQuickCaptureScreen;
import Glaxium.Minema.ui.MinemaSettingsButton;
import Glaxium.Minema.ui.RecordingOverlay;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
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
 * speed settings are all reachable from three places that all read/write
 * the exact same {@link MinemaConfig#INSTANCE}, so they're always in sync
 * with each other: BBS's own "Minema Settings" button inside its video
 * settings panel, the J key anywhere in the dashboard (both open
 * {@link Glaxium.Minema.ui.MinemaSettingsOverlayPanel}, built on BBS's UI
 * framework -- see {@link MinemaAddonEvents} for the J
 * registration), and Shift+F4 out in the world (opens
 * {@link MinemaQuickCaptureScreen}, a standalone vanilla
 * {@code Screen} outside BBS's UI entirely, matching how the old standalone
 * BBS-Minema mod's own UI worked). J previously did something different in
 * an earlier version of this addon and was removed; this is a new,
 * unrelated binding that happens to reuse the same key, chosen to match
 * F6's "open a settings panel from anywhere" behaviour.
 *
 * Plain F4 ONLY toggles recording (start/stop) -- it never opens any
 * screen. Shift+F4 is the only way to open the quick-capture screen: if a
 * recording is active it's stopped first (same as plain F4's stop), then
 * the screen always opens; if nothing's recording, Shift+F4 just opens the
 * screen with recording left untouched. See {@link #onClientTick} for the
 * actual branch.
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

    /** Non-null only while sync engine has raised the integrated server's tick rate -- see the syncingNow rising/falling edge handling in {@link #onClientTick}. */
    private net.minecraft.server.MinecraftServer syncedServer;

    /** The tick rate {@link #syncedServer} actually had before sync engine raised it, restored on the falling edge. */
    private float oldTickRate;

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
        RecordingOverlay.register();

        // Ported from BBS-CML-EDITION (doesn't exist in vanilla bbs-mod): registers a new
        // "Hotbar" camera-clip type into BBS's own Film editor using its own public, already-
        // used-internally extension points -- no mixins needed for this part. See HotbarClip's
        // own class doc for the full picture, and HotbarClipRenderer for what actually draws it
        // during playback (vanilla has no idea this clip type exists, so nothing in vanilla
        // would render it without that).
        BBSMod.getFactoryCameraClips().register(new Link("bbs_minema", "hotbar"), HotbarClip.class,
                new ClipFactoryData(Icons.BLOCK, 0x55aaff));
        UIClip.register(HotbarClip.class, UIHotbarClip::new);
        HotbarClipRenderer.register();

        // Replaces vanilla's own ItemStack keyframe editor widget with one that also has a
        // count field (vanilla's has no way to set stack count at all -- see
        // UIHotbarItemKeyframeFactory's own class doc). Applies to every ItemStack keyframe in
        // BBS's Film editor, not just Hotbar clips, since it's a strict addition.
        UIKeyframeFactory.register(KeyframeFactories.ITEM_STACK, UIHotbarItemKeyframeFactory::new);

        // Replaces vanilla's own Integer keyframe editor widget, which never marks its value
        // field as an integer field -- dragging or typing a value into it leaves it showing (and
        // keeping) a decimal, even though it's only ever used for Integer-typed channels. See
        // UIHotbarIntegerKeyframeFactory's own class doc for the full explanation; this is what
        // was letting health/hunger/armor/etc. keyframes look and behave like floats when edited
        // by hand, despite baking/recording always producing clean integers.
        UIKeyframeFactory.register(KeyframeFactories.INTEGER, UIHotbarIntegerKeyframeFactory::new);

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        WorldRenderEvents.LAST.register(this::onWorldRenderLast);

        // Don't leave an ffmpeg process hanging (and the window resized) if
        // the world unloads out from under an active recording.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
        {
            // Safe from any thread (this event fires on Netty's network
            // thread, not the client thread) -- see SyncModule#disableAndRelease.
            // Defense-in-depth alongside StopRecordingOnQuitWorldMixin, for
            // any disconnect path that isn't one of MinecraftClient's own
            // disconnect(...) overloads.
            SyncModule.disableAndRelease();

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

        // In a world (singleplayer OR connected to a server) AND nothing --
        // inventory, vanilla settings/pause, BBS mod's own dashboard/film
        // editor, another mod's screen, anything -- is currently covering
        // it. Both F4 and Shift+F4 are gated on this: neither one should
        // fire from the main menu / world-select / multiplayer-list screens
        // (client.world == null there), and neither should fire while any
        // GUI is already open on top of gameplay (client.currentScreen !=
        // null covers inventory, pause, BBS's UI, everything).
        boolean inWorldWithNoScreenOpen = client.world != null && client.currentScreen == null;

        if (f4Pressed && inWorldWithNoScreenOpen)
        {
            if (isShiftDown(handle))
            {
                // Shift+F4 -- if recording, stop it first (same message as
                // plain F4 below), then open the quick-capture screen
                // ("Minema settings"). If nothing's recording, this just
                // opens the screen.
                if (RawCaptureModule.INSTANCE.isRecording())
                {
                    RawCaptureModule.INSTANCE.stop();
                    UIUtils.playClick();
                }

                // inWorldWithNoScreenOpen already guarantees
                // client.currentScreen == null, so this always opens as a
                // fresh, parentless screen -- never layered on top of
                // another GUI.
                client.setScreen(new MinemaQuickCaptureScreen(null));
            }
            else if (RawCaptureModule.INSTANCE.isRecording())
            {
                // Plain F4 just stops recording -- no settings screen.
                // Shift+F4 (above) is the only way to open Minema Settings now.
                RawCaptureModule.INSTANCE.stop();
                UIUtils.playClick();
            }
            else
            {
                RawCaptureModule.INSTANCE.start();
                UIUtils.playClick();
            }
        }

        // If the world unloads (quit to title) while a recording was
        // active, stop it even though f4Pressed/inWorldWithNoScreenOpen
        // above won't fire again from the main menu to do it -- mirrors
        // the same cleanup the DISCONNECT event handler in
        // onInitializeClient already does for the network-thread
        // disconnect path; this covers any other route back to
        // client.world == null (e.g. singleplayer "Save and Quit to Title").
        if (client.world == null && RawCaptureModule.INSTANCE.isRecording())
        {
            RawCaptureModule.INSTANCE.stop();
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

            // MinecraftServer.runServer() paces itself against the real system clock -- it
            // sleeps whenever a tick finishes "early" relative to the vanilla 50ms/tick
            // schedule -- so the integrated server never ticks faster than ~20 TPS in real
            // time. SyncModule's handshake correctly holds the render thread open until a
            // fresh tick finishes, but that tick itself is still bound by this real-time
            // ceiling regardless -- which is exactly why sync was capping out at 60fps: at
            // the default 60fps export target, every 3rd rendered frame needs one new tick,
            // and 3 frames per tick at a max of 20 ticks/sec is exactly 60fps, not a
            // coincidence. Raising the tick rate ceiling here -- the same mechanism behind
            // vanilla's own "/tick rate" command -- removes that ceiling for the duration of
            // the recording instead of fighting it. Restored on the falling edge below.
            net.minecraft.server.MinecraftServer server = client.getServer();

            if (server != null)
            {
                this.syncedServer = server;
                this.oldTickRate = server.getTickManager().getTickRate();
                server.getTickManager().setTickRate(10000F);
            }
        }
        else if (!syncingNow && this.wasSyncing && this.syncedServer != null)
        {
            // Falling edge -- restore whatever the tick rate actually was before sync turned
            // this up, rather than assuming vanilla's default of 20; someone may have had a
            // custom rate set (e.g. via /tick rate) before recording ever started. Guarded --
            // this can fire right as the world is unloading (e.g. quit to title), by which
            // point the server may already be mid-shutdown; restoring the tick rate doesn't
            // matter once the server's gone anyway.
            try
            {
                this.syncedServer.getTickManager().setTickRate(this.oldTickRate);
            }
            catch (Exception ignored) {}

            this.syncedServer = null;
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