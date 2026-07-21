package Glaxium.Minema;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Deliberately NOT hooked into BBS mod's own settings/values system --
 * that's built around BBS mod's internal panels and isn't really meant for
 * third-party addons to register into. A flat properties file next to every
 * other mod's config is simpler and won't break if BBS mod's settings
 * internals change under us.
 *
 * Mirrors the two knobs Minema 1.12.2 actually exposed for this
 * (MinemaConfig#captureDepth, MinemaConfig#captureDepthDistance) instead of
 * the single hardcoded `far = 512F` the first version of this addon used.
 */
public class MinemaConfig
{
    /**
     * The mixin that adds the toggle to BBS mod's own settings panel has no
     * reference to BBSMinema's instance, so this needs to be reachable
     * statically. Loaded once in BBSMinema#onInitializeClient.
     */
    public static final MinemaConfig INSTANCE = new MinemaConfig();

    private static final Path PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("bbs-minema.properties");

    /** Off by default -- same as Minema's captureDepth, opt-in not automatic. */
    public boolean captureDepth = false;

    /** Far plane in blocks used to normalize the depth pass. Minema's default was tied to render distance; 128 is a reasonable flat default for typical builds. */
    public double captureDepthDistance = 128.0;

    /**
     * Off by default -- mirrors Minema's own syncEngine toggle. Only takes
     * effect in singleplayer; see {@link SyncModule}. Adds a
     * small amount of real-world recording overhead (each captured frame now
     * waits on a tick round-trip instead of racing the server thread), so
     * it's opt-in rather than automatic.
     */
    public boolean syncEngine = false;

    /**
     * Off by default. Captures BBS mod's actual mixed game audio (not the
     * microphone) and burns it into the output video once recording stops.
     * Independent of {@link #generateWavFile} -- this can be on by itself
     * (audio in the video, no standalone file), or both can be on at once
     * (audio in the video AND a standalone .wav).
     */
    public boolean recordGameAudio = false;

    /**
     * Off by default. Keeps the recorded game audio as its own .wav file.
     * Independent of {@link #recordGameAudio} -- this can be on by itself
     * (just the .wav, video's own audio untouched), or both can be on at
     * once (.wav AND audio burned into the video).
     */
    public boolean generateWavFile = false;

    /**
     * Legacy/unused. F4 always does the raw (full screen) capture now --
     * see RawCaptureModule and BBSMinema#recordKey -- BBS mod's own F4 is
     * permanently disabled (DisableBBSVideoKeyMixin), so there's no longer
     * a separate pipeline for this to opt into. Kept only so existing
     * bbs-minema.properties files with this key still parse without error.
     */
    public boolean rawCaptureMode = false;

    /**
     * Off by default. When on (and only while the window is actually in
     * true fullscreen -- see RawCaptureModule/WindowMixin), F4 capture
     * renders into an off-screen buffer sized to BBS mod's own configured
     * {@code BBSSettings.videoSettings.width}/{@code height} instead of
     * reading the physical window's live framebuffer size. This is what
     * makes 4K (or any other resolution that doesn't match your monitor)
     * capture possible without stretching -- world, HUD, inventory, and
     * every BBS/other-mod overlay all render at the target size, they're
     * not just upscaled afterwards. Ignored in windowed mode, since a
     * windowed game can be resized by the OS/user mid-recording, which
     * would desync the spoofed size from the real window and corrupt the
     * screen.
     *
     * <p>Deliberately NOT a separate "enable custom resolution" on/off
     * switch with its own width/height fields -- it's a choice between two
     * capture modes: true (custom resolution, off-screen render at BBS's
     * configured width/height, fullscreen-only), or false (native, capture
     * the screen exactly as it already is, works windowed or fullscreen,
     * original behaviour). Width/height themselves live in BBS's own
     * settings, not here, so editing them anywhere (BBS's own "Edit
     * settings" panel, or the Width/Height fields in Minema's own UI) is
     * genuinely the same value everywhere, not a copy that needs syncing.
     */
    public boolean customResolution = false;

    /**
     * Multiplier applied to how fast the world simulation advances relative
     * to each captured frame (same idea as Minema 1.12.2's "Engine Speed").
     * 1.0 is normal speed. Values above 1 make the world tick faster than
     * the recorded video plays back (timelapse); values below 1 slow the
     * world down relative to the video (slow motion). Applied in
     * MinemaRenderTickCounterMixin, alongside the existing fixed-timestep
     * frame pacing -- doesn't affect windowed/live gameplay at all, only
     * how many ticks a captured frame accounts for.
     */
    public double engineSpeed = 1.0;

    public void load()
    {
        if (!Files.exists(PATH))
        {
            this.save();

            return;
        }

        Properties props = new Properties();

        try (var in = Files.newInputStream(PATH))
        {
            props.load(in);

            this.captureDepth = Boolean.parseBoolean(
                    props.getProperty("captureDepth", String.valueOf(this.captureDepth))
            );
            this.captureDepthDistance = Double.parseDouble(
                    props.getProperty("captureDepthDistance", String.valueOf(this.captureDepthDistance))
            );
            this.syncEngine = Boolean.parseBoolean(
                    props.getProperty("syncEngine", String.valueOf(this.syncEngine))
            );
            this.recordGameAudio = Boolean.parseBoolean(
                    props.getProperty("recordGameAudio", String.valueOf(this.recordGameAudio))
            );
            this.generateWavFile = Boolean.parseBoolean(
                    props.getProperty("generateWavFile", String.valueOf(this.generateWavFile))
            );
            this.rawCaptureMode = Boolean.parseBoolean(
                    props.getProperty("rawCaptureMode", String.valueOf(this.rawCaptureMode))
            );
            this.customResolution = Boolean.parseBoolean(
                    props.getProperty("customResolution", String.valueOf(this.customResolution))
            );
            this.engineSpeed = Double.parseDouble(
                    props.getProperty("engineSpeed", String.valueOf(this.engineSpeed))
            );
        }
        catch (IOException | NumberFormatException e)
        {
            e.printStackTrace();
        }
    }

    public void save()
    {
        Properties props = new Properties();

        props.setProperty("captureDepth", String.valueOf(this.captureDepth));
        props.setProperty("captureDepthDistance", String.valueOf(this.captureDepthDistance));
        props.setProperty("syncEngine", String.valueOf(this.syncEngine));
        props.setProperty("recordGameAudio", String.valueOf(this.recordGameAudio));
        props.setProperty("generateWavFile", String.valueOf(this.generateWavFile));
        props.setProperty("rawCaptureMode", String.valueOf(this.rawCaptureMode));
        props.setProperty("customResolution", String.valueOf(this.customResolution));
        props.setProperty("engineSpeed", String.valueOf(this.engineSpeed));

        try
        {
            Files.createDirectories(PATH.getParent());

            try (var out = Files.newOutputStream(PATH))
            {
                props.store(out, "BBS Minema -- depth pass recording settings");
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public void toggleCaptureDepth()
    {
        this.captureDepth = !this.captureDepth;
        this.save();
    }

    public void toggleSyncEngine()
    {
        this.syncEngine = !this.syncEngine;
        this.save();
    }

    public void toggleRecordGameAudio()
    {
        this.recordGameAudio = !this.recordGameAudio;
        this.save();
    }

    public void toggleGenerateWavFile()
    {
        this.generateWavFile = !this.generateWavFile;
        this.save();
    }

    public void toggleRawCaptureMode()
    {
        this.rawCaptureMode = !this.rawCaptureMode;
        this.save();
    }

    public void toggleCustomResolution()
    {
        this.customResolution = !this.customResolution;
        this.save();
    }

    public void setEngineSpeed(double speed)
    {
        if (!Double.isFinite(speed) || speed <= 0)
        {
            speed = 1.0;
        }

        this.engineSpeed = Math.max(0.01, Math.min(100.0, speed));
        this.save();
    }
}