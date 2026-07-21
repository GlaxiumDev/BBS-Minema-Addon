package Glaxium.Minema;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.utils.VideoRecorder;
import net.minecraft.client.MinecraftClient;

/**
 * Port of Minema 1.12.2's SyncModule to modern Fabric/BBS mod.
 *
 * <p>What BBS mod already has (RenderTickCounterMixin + IntegratedServerMixin):
 * stretches render interpolation to a fixed target frame rate, and gates how
 * many times {@code MinecraftServer#tick(BooleanSupplier)} gets called per
 * rendered frame to match it. That second part runs as a free-running
 * catch-up loop on the integrated server's own thread -- nothing stops that
 * loop from racing ahead of (or lagging behind) the render thread while a
 * frame is still being captured, since the two threads otherwise have no
 * happens-before relationship for a given frame.
 *
 * <p>What this adds: a real handshake between the two threads, one tick at a
 * time. The server thread is only allowed to run a tick once the render
 * thread grants permission, and after that tick finishes, the server thread
 * is parked again until the render thread has finished issuing this frame's
 * capture commands. That's the "so advanced" part of Minema's original
 * design -- not just smooth video, but video that's locked frame-for-tick to
 * the simulation.
 *
 * <p>Minema did this with real network packets, because in 1.12.2 the
 * integrated server's "connection" to the client still had to work in
 * theory over a socket. On modern Fabric, a singleplayer world's server is
 * just another thread inside the same JVM as the client -- so this is
 * simpler: a plain wait/notify handshake on a shared monitor, no networking
 * involved.
 *
 * <p>Two mixins drive this class:
 * <ul>
 *   <li>{@code MinecraftServerTickSyncMixin} (server thread) --
 *       {@link #awaitPermissionToTick()} at HEAD of tick(), {@link #signalTickDone()} at TAIL.</li>
 *   <li>{@code MinecraftClientRenderSyncMixin} (render thread) --
 *       {@link #beginFrame()} right after {@code beginRenderTick} returns,
 *       {@link #endFrame()} at TAIL of {@code MinecraftClient#render}.</li>
 * </ul>
 */
public class SyncModule
{
    private static final Object LOCK = new Object();

    /** How long a waiter blocks before re-checking {@link #enabled}, so a mid-wait toggle-off can't deadlock either thread. */
    private static final long WAIT_TIMEOUT_MS = 200L;

    /** True while the server thread has permission to run its next tick. */
    private static volatile boolean captureReady = true;

    /** True once the server thread has finished a tick and is waiting to be collected. */
    private static volatile boolean tickDone = false;

    /** True between beginFrame() parking the server after this frame's last tick, and endFrame() releasing it. */
    private static volatile boolean holding = false;

    /** Master switch. Recomputed once per client tick by BBSMinema#onClientTick from config + recording state. */
    public static volatile boolean enabled = false;

    /** VideoRecorder#serverTicks as of the last frame, so beginFrame() can work out how many *new* ticks this frame asked for. */
    private static volatile int lastObservedTarget = 0;

    private SyncModule() {}

    /**
     * Re-arms the handshake. Call this once, on the frame sync engine turns
     * on (recording start, or the setting being toggled mid-recording) --
     * never mid-stream, or you'll get a burst of held-open ticks.
     */
    public static void reset()
    {
        synchronized (LOCK)
        {
            lastObservedTarget = currentServerTicks();
            captureReady = true;
            tickDone = false;
            holding = false;
            LOCK.notifyAll();
        }
    }

    /**
     * BBS mod's own VideoRecorder#serverTicks if its recorder is the one
     * actually running (e.g. film editor export), otherwise
     * RawCaptureModule's own counter for BBS-Minema's F4 capture. Whichever
     * pipeline is active is the one whose pacing mixin has been advancing
     * ticks for this frame.
     */
    private static int currentServerTicks()
    {
        VideoRecorder recorder = BBSModClient.getVideoRecorder();

        if (recorder.isRecording())
        {
            return recorder.serverTicks;
        }

        return RawCaptureModule.INSTANCE.getServerTicks();
    }

    // ---- server thread side (MinecraftServerTickSyncMixin) ----

    /** Blocks the server thread until the render thread has granted this tick. */
    public static void awaitPermissionToTick()
    {
        if (!enabled)
        {
            return;
        }

        synchronized (LOCK)
        {
            while (enabled && !captureReady)
            {
                try
                {
                    LOCK.wait(WAIT_TIMEOUT_MS);
                }
                catch (InterruptedException ignored) {}
            }

            captureReady = false;
        }
    }

    /** Reports that the tick the server thread was just granted has finished. */
    public static void signalTickDone()
    {
        if (!enabled)
        {
            return;
        }

        synchronized (LOCK)
        {
            tickDone = true;
            LOCK.notifyAll();
        }
    }

    // ---- render thread side (MinecraftClientRenderSyncMixin) ----

    /**
     * Called right after {@code RenderTickCounter#beginRenderTick} returns,
     * i.e. as soon as this frame's target tick count is known (BBS mod's own
     * mixin has already added this frame's delta onto VideoRecorder#serverTicks
     * by this point).
     *
     * <p>Drains every tick this frame asked for: any ticks before the last one
     * (only possible when catching up several real ticks in a single held
     * frame) aren't individually captured, so they're released back-to-back
     * with no hold. The last tick is different -- its resulting world state is
     * what this frame is actually going to render and capture, so the server
     * is left parked right after it finishes, and won't be allowed to start
     * the next tick until {@link #endFrame()} runs.
     */
    public static void beginFrame()
    {
        boolean shouldSync = enabled
                && (BBSModClient.getVideoRecorder().isRecording() || RawCaptureModule.INSTANCE.isRecording())
                && MinecraftClient.getInstance().isIntegratedServerRunning();

        if (!shouldSync)
        {
            lastObservedTarget = currentServerTicks();

            return;
        }

        int target = currentServerTicks();
        int delta = target - lastObservedTarget;

        lastObservedTarget = target;

        if (delta <= 0)
        {
            return;
        }

        for (int k = 0; k < delta; k++)
        {
            awaitTick();

            if (k < delta - 1)
            {
                releaseTick();
            }
            else
            {
                holding = true;
            }
        }
    }

    /**
     * Called at the very end of {@code MinecraftClient#render} -- after every
     * WorldRenderEvents.LAST listener for this frame has run, which includes
     * BBS mod's own color capture and this addon's depth capture. Releases
     * the tick {@link #beginFrame()} was holding, letting the server advance.
     *
     * <p>No-op on frames that didn't hold anything open (recording not
     * active, sync disabled, or this was a held/skipped render-only frame
     * with no new tick).
     */
    public static void endFrame()
    {
        if (!holding)
        {
            return;
        }

        releaseTick();
        holding = false;
    }

    private static void awaitTick()
    {
        synchronized (LOCK)
        {
            while (enabled && !tickDone)
            {
                try
                {
                    LOCK.wait(WAIT_TIMEOUT_MS);
                }
                catch (InterruptedException ignored) {}
            }

            tickDone = false;
        }
    }

    private static void releaseTick()
    {
        synchronized (LOCK)
        {
            captureReady = true;
            LOCK.notifyAll();
        }
    }
}
