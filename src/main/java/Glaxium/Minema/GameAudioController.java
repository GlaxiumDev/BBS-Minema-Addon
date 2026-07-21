package Glaxium.Minema;

/**
 * Shared state between {@link Glaxium.Minema.mixin.SoundEngineMixin} (which
 * runs on whatever thread {@code SoundManager#reloadSounds()} runs on) and
 * {@link GameAudioRecorder} (which pulls samples from the render thread).
 *
 * Deliberately a standalone class in this addon rather than reusing
 * anything from BBS mod itself -- this addon only depends on BBS mod's
 * *compiled* jar (see build.gradle), and the upstream mchorse/bbs-mod
 * VideoRecorder doesn't expose an equivalent hook. This mirrors the
 * approach BBS CML EDITION's fork uses internally (LoopbackAudioController +
 * a SoundEngine mixin around OpenAL's SOFT_loopback extension), ported here
 * as addon-owned code that only touches the vanilla SoundEngine class, not
 * anything BBS-mod-internal -- so it works regardless of which bbs-mod
 * build this addon is compiled against.
 */
public class GameAudioController
{
    /** Shared sample rate for the loopback context and {@link GameAudioRecorder}'s WAV output -- keep these in lockstep, the loopback context is literally configured with this value. */
    public static final int SAMPLE_RATE = 48000;

    /**
     * Set before {@code SoundManager#reloadSounds()} is called, so the mixin
     * knows to open a loopback device instead of a real playback device the
     * *next* time SoundEngine#init() runs. Toggling this alone does nothing
     * until a reload actually happens -- see
     * {@link GameAudioRecorder#startRecording}.
     */
    private static volatile boolean captureRequested;

    /**
     * The OpenAL loopback device handle, set by the mixin once SoundEngine
     * has actually finished opening it. 0 whenever no loopback device is
     * open (including in between calling requestCapture(true) and the
     * reload actually completing).
     */
    private static volatile long loopbackDevice;

    private GameAudioController() {}

    public static void requestCapture(boolean value)
    {
        captureRequested = value;
    }

    public static boolean isCaptureRequested()
    {
        return captureRequested;
    }

    public static void setLoopbackDevice(long value)
    {
        loopbackDevice = value;
    }

    public static long getLoopbackDevice()
    {
        return loopbackDevice;
    }
}
