package Glaxium.Minema;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.FFMpegUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.UnsafeUtils;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import sun.misc.Unsafe;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Captures Minecraft's real default framebuffer -- whatever is actually
 * being displayed that frame, GUI and all -- instead of BBS mod's
 * deliberately restricted world-only render pass. This is what makes
 * inventory, vanilla settings screens, chat, the F3 debug overlay, and
 * other mods' UIs show up in the recording, matching how Minema 1.12.2
 * behaved (it captured the real screen, not an isolated offscreen pass).
 *
 * <p>Structurally: double-buffered PBOs for the GL readback (hides GPU
 * readback latency), then a small bounded pool of reusable host-memory
 * buffers handed off to a dedicated writer thread that owns the actual
 * blocking pipe write into ffmpeg's stdin. The render thread only ever
 * does GL calls plus a fast host-memory copy -- it never blocks on I/O
 * directly. This matters a lot for achievable framerate: writing straight
 * to ffmpeg's stdin from the render thread (the previous approach) means
 * the moment ffmpeg's encoder can't keep up, the OS pipe fills and that
 * write call blocks the ENTIRE render loop until ffmpeg drains it -- which
 * silently caps captured fps at whatever the encoder preset can sustain
 * (e.g. ~90-120fps for libx264 "medium" at 1080p), even though the game
 * itself might be capable of 1000+ fps. Decoupling the two means bursts of
 * fast frames get absorbed by the buffer pool instead of stalling capture,
 * and pairing this with a fast encoder (see {@link MinemaConfig#encoderMode}/
 * {@link MinemaConfig#getEncoderArgs()} -- Default is still libx264/ultrafast/crf18, unchanged
 * from before EncoderMode existed) raises the encoder's own sustained ceiling well past what
 * "medium" could ever hit.
 *
 * <p>Reads GL_BGR color (no linearization step, straight passthrough), and
 * reads from whatever texture id it's given each frame rather than one
 * fixed at startRecording() time, since the real framebuffer's color
 * attachment can be recreated by the game (e.g. on window resize) -- see
 * {@link #updateReadTexture(int)}.
 */
public class RawCaptureRecorder
{
    /**
     * How many host-memory frame buffers exist in the pool -- effectively how many frames of
     * capture/encode desync this recorder can absorb before the render thread has to wait for
     * the writer thread to catch up. Higher tolerates bigger encoder stalls (e.g. a keyframe
     * taking longer to encode) at the cost of more resident memory: at 1080p, rawSize is
     * ~6.2MB/frame, so 8 buffers is ~50MB, trivial next to typical VRAM/RAM budgets for a
     * recording session.
     */
    private static final int BUFFER_POOL_SIZE = 8;

    /**
     * Zero-capacity marker buffer used to tell {@link #writeLoop()} to stop, queued by
     * {@link #stopRecording()}. NOT a real frame -- never written to the channel, only ever
     * compared by reference (see {@link #writeLoop()}). Deliberately not {@code null}: every
     * {@code java.util.concurrent.BlockingQueue} implementation (including
     * {@link ArrayBlockingQueue}) explicitly forbids null elements -- {@code put(null)}/
     * {@code offer(null)} unconditionally throw {@code NullPointerException} via their own
     * internal {@code Objects.requireNonNull} check, regardless of queue state. A dedicated
     * sentinel instance is the queue-safe way to signal "no more real items."
     */
    private static final ByteBuffer POISON_PILL = ByteBuffer.allocate(0);

    private Process process;
    private WritableByteChannel channel;
    private volatile boolean recording;

    private int width;
    private int height;
    private int counter;

    private int[] pbos;
    private int pboIndex;

    /** Fixed-size pool of reusable direct buffers -- frames the render thread has finished copying into but the writer thread hasn't written yet. */
    private BlockingQueue<ByteBuffer> freeBuffers;
    private BlockingQueue<ByteBuffer> pendingWrites;
    private Thread writerThread;
    private volatile Exception writerError;

    /**
     * The off-screen capture texture id to read from instead of FBO 0, or 0
     * to mean "read the real screen backbuffer" (original behaviour). Set
     * once at {@link #startRecording(int, int, int)} time by
     * RawCaptureModule.
     */
    private int readTextureId;

    /** Small dedicated FBO used only to attach {@link #readTextureId} for glReadPixels -- we don't have direct access to the off-screen Framebuffer's own internal FBO id from here. */
    private int readFbo;

    public boolean isRecording()
    {
        return this.recording;
    }

    /** The width this recording's ffmpeg process/PBOs were sized for at startRecording() time -- glReadPixels must never be asked to read more than this from a since-resized real backbuffer. */
    public int getWidth()
    {
        return this.width;
    }

    /** @see #getWidth() */
    public int getHeight()
    {
        return this.height;
    }

    /** True only while reading from the real screen backbuffer (FBO 0) -- i.e. the plain, non-custom-resolution capture path, the one that's vulnerable to the physical window being resized out from under it mid-recording. */
    public boolean isReadingRealBackbuffer()
    {
        return this.recording && this.readTextureId == 0;
    }

    /** Backwards-compatible overload -- always reads FBO 0 (the real screen), same as the original behaviour. */
    public void startRecording(int width, int height)
    {
        this.startRecording(width, height, 0);
    }

    /**
     * width/height are the resolution actually being captured this run --
     * either the live window size, or (when {@code colorTextureId != 0})
     * the custom off-screen capture resolution. colorTextureId is the
     * off-screen capture framebuffer's color attachment to read from
     * instead of the real screen; pass 0 to read FBO 0 as before.
     *
     * <p>This attachment is only a starting point -- if the caller's off-screen framebuffer gets
     * resized (and therefore its color texture recreated) after this returns, call
     * {@link #updateReadTexture(int)} with the new id or every frame captured afterwards will read
     * a deleted texture and come out solid black.
     */
    public void startRecording(int width, int height, int colorTextureId)
    {
        if (this.recording)
        {
            return;
        }

        this.counter = 0;
        this.width = width;
        this.height = height;
        this.readTextureId = colorTextureId;
        this.writerError = null;

        if (colorTextureId != 0)
        {
            this.readFbo = GL30.glGenFramebuffers();

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_TEXTURE_2D, colorTextureId, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        }
        else
        {
            this.readFbo = 0;
        }

        int rawSize = width * height * 3;

        this.freeBuffers = new ArrayBlockingQueue<>(BUFFER_POOL_SIZE);
        this.pendingWrites = new ArrayBlockingQueue<>(BUFFER_POOL_SIZE);

        for (int i = 0; i < BUFFER_POOL_SIZE; i++)
        {
            this.freeBuffers.add(MemoryUtil.memAlloc(rawSize));
        }

        try
        {
            File movies = BBSRendering.getVideoFolder();

            movies.mkdirs();

            Path path = Paths.get(movies.toString());
            String movieName = StringUtils.createTimestampFilename() + "_raw";
            float frameRate = (float) BBSRendering.getVideoFrameRate();
            // The actual codec/preset/quality args for whichever MinemaConfig#encoderMode is
            // currently selected (Balanced/High/Ultra/NVENC/Custom/Potato) -- see
            // MinemaConfig#getEncoderArgs(). RawCaptureModule#start() already refused to get
            // this far if CUSTOM is selected with empty/invalid args, so this always returns
            // something usable.
            String[] encoderArgs = MinemaConfig.INSTANCE.getEncoderArgs();

            String params = "-f rawvideo -pix_fmt bgr24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf vflip -an";

            params = params.replace("%WIDTH%", String.valueOf(width));
            params = params.replace("%HEIGHT%", String.valueOf(height));
            params = params.replace("%FPS%", String.valueOf(frameRate));

            List<String> args = new ArrayList<>();
            String encoder = FFMpegUtils.getFFMPEG();

            args.add(encoder);
            args.addAll(Arrays.asList(params.split(" ")));
            args.addAll(Arrays.asList(encoderArgs));

            // glReadPixels hands us full-range (0-255) RGB straight off the screen. Without
            // telling ffmpeg that explicitly, libswscale's default RGB->YUV conversion assumes
            // limited (16-235) "TV" range and a BT.601 matrix -- neither matches what was
            // actually on screen, which is exactly the "recording looks duller/different colors
            // than the game" symptom: blacks get lifted, whites get crushed, and reds in
            // particular shift noticeably under BT.601 vs BT.709. Tagging the actual
            // range/matrix here makes the conversion (and the file's metadata) match reality
            // instead of guessing.
            args.add("-color_range");
            args.add("pc");
            args.add("-colorspace");
            args.add("bt709");
            args.add("-color_primaries");
            args.add("bt709");
            args.add("-color_trc");
            args.add("bt709");

            args.add("-pix_fmt");
            args.add("yuv420p");
            args.add(movieName + ".mp4");

            System.out.println("[bbs-minema] Recording raw (full screen) capture with: " + args);

            this.pbos = new int[2];
            this.pboIndex = 0;

            for (int i = 0; i < 2; i++)
            {
                this.pbos[i] = GL30.glGenBuffers();

                GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.pbos[i]);
                GL30.glBufferData(GL30.GL_PIXEL_PACK_BUFFER, rawSize, GL30.GL_STREAM_READ);
            }

            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);

            ProcessBuilder builder = new ProcessBuilder(args);
            File log = path.resolve(movieName.concat(".log")).toFile();

            builder.directory(path.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log);

            this.process = builder.start();

            OutputStream os = this.process.getOutputStream();
            Unsafe unsafe = UnsafeUtils.getUnsafe();

            if (os instanceof FilterOutputStream)
            {
                try
                {
                    Field outField = FilterOutputStream.class.getDeclaredField("out");

                    os = (OutputStream) unsafe.getObject(os, unsafe.objectFieldOffset(outField));
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            this.channel = Channels.newChannel(os);
            this.recording = true;

            this.writerThread = new Thread(this::writeLoop, "bbs-minema-raw-writer");
            this.writerThread.setDaemon(true);
            this.writerThread.start();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Runs on {@link #writerThread} for the entire recording -- the only place that ever touches
     * {@link #channel}. Pulls completed frames off {@link #pendingWrites}, writes them (the
     * actually-slow, potentially-blocking part), then returns the buffer to {@link #freeBuffers}
     * so the render thread can reuse it. {@link #POISON_PILL} on {@link #pendingWrites} (queued by
     * {@link #stopRecording()}) is how this loop is told to exit once every real frame ahead of
     * it has been drained and written -- ensures the tail of the recording isn't silently
     * dropped.
     */
    private void writeLoop()
    {
        try
        {
            while (true)
            {
                ByteBuffer buffer = this.pendingWrites.take();

                if (buffer == POISON_PILL)
                {
                    break;
                }

                this.channel.write(buffer);
                buffer.clear();
                this.freeBuffers.put(buffer);
            }
        }
        catch (Exception e)
        {
            this.writerError = e;
            e.printStackTrace();
        }
    }

    /**
     * Re-points {@link #readFbo}'s color attachment at whatever texture id is currently live,
     * instead of the one that was current back at {@link #startRecording(int, int, int)} time.
     * Cheap (a single glFramebufferTexture2D state update, no allocation) -- safe and intended to
     * be called every captured frame while a custom-resolution recording is active, as insurance
     * against the off-screen framebuffer being resized (and its color texture silently recreated)
     * at any point during the recording, not just once right at the start.
     *
     * <p>No-op if not currently recording from an off-screen texture (colorTextureId == 0 case),
     * or if the id hasn't actually changed since last time.
     */
    public void updateReadTexture(int colorTextureId)
    {
        if (!this.recording || this.readFbo == 0 || colorTextureId == 0 || colorTextureId == this.readTextureId)
        {
            return;
        }

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_TEXTURE_2D, colorTextureId, 0);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);

        this.readTextureId = colorTextureId;
    }

    public void stopRecording()
    {
        if (!this.recording)
        {
            return;
        }

        // Flip this first so recordFrame() (if somehow still being called concurrently) bails
        // out immediately instead of racing the teardown below.
        this.recording = false;

        // Tell the writer thread to finish everything already queued, then stop -- not an
        // abrupt kill, so the tail of the recording isn't lost.
        try
        {
            if (this.pendingWrites != null)
            {
                this.pendingWrites.put(POISON_PILL);
            }

            if (this.writerThread != null)
            {
                this.writerThread.join(TimeUnit.MINUTES.toMillis(1));
            }
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }

        this.writerThread = null;

        if (this.pbos != null)
        {
            for (int pbo : this.pbos)
            {
                GL30.glDeleteBuffers(pbo);
            }
        }

        this.pbos = null;

        if (this.readFbo != 0)
        {
            GL30.glDeleteFramebuffers(this.readFbo);
            this.readFbo = 0;
        }

        this.readTextureId = 0;

        // Free every buffer in the pool regardless of which queue it currently sits in --
        // between freeBuffers and pendingWrites (which should be empty after the join above,
        // barring the writer thread dying on an exception) every allocated buffer is accounted
        // for exactly once. POISON_PILL is explicitly skipped -- it's a plain heap buffer, not
        // one of the MemoryUtil.memAlloc'd pool buffers, and would corrupt native memory (likely
        // crashing the JVM) if handed to memFree. It should normally already be consumed by
        // writeLoop() by this point, but if the writer thread died from some other exception
        // before reaching it, it can still be sitting unconsumed in pendingWrites here.
        if (this.freeBuffers != null)
        {
            ByteBuffer buffer;

            while ((buffer = this.freeBuffers.poll()) != null)
            {
                if (buffer != POISON_PILL)
                {
                    MemoryUtil.memFree(buffer);
                }
            }
        }

        if (this.pendingWrites != null)
        {
            ByteBuffer buffer;

            while ((buffer = this.pendingWrites.poll()) != null)
            {
                if (buffer != POISON_PILL)
                {
                    MemoryUtil.memFree(buffer);
                }
            }
        }

        this.freeBuffers = null;
        this.pendingWrites = null;

        try
        {
            if (this.channel != null && this.channel.isOpen())
            {
                this.channel.close();
            }

            this.channel = null;
        }
        catch (IOException ex)
        {
            ex.printStackTrace();
        }

        try
        {
            if (this.process != null)
            {
                this.process.waitFor(1, TimeUnit.MINUTES);
                this.process.destroy();
            }

            this.process = null;
        }
        catch (InterruptedException ex)
        {
            ex.printStackTrace();
        }
    }

    /**
     * Record one frame by reading directly from the default framebuffer
     * (FBO 0) -- the literal thing that's about to be presented on screen
     * -- instead of going through MinecraftClient#getFramebuffer(). That
     * Framebuffer object gets swapped out to BBS mod's own private,
     * world-only offscreen buffer for part of every frame (see
     * BBSRendering#toggleFramebuffer) and swapped back before GUI renders;
     * reading whichever object it happens to reference at capture time
     * means trusting that swap-back has already happened, which isn't
     * guaranteed. FBO 0 has no such ambiguity -- by the time this runs (see
     * MinecraftClientRawCaptureMixin, TAIL of render()), Minecraft's own
     * final blit-to-screen has already happened regardless of what any mod
     * did with intermediate buffers earlier in the frame.
     *
     * <p>When a custom-resolution capture is active ({@link #readTextureId}
     * != 0), reads from {@link #readFbo} (a small FBO with that off-screen
     * texture attached) instead of FBO 0 -- the real screen backbuffer is
     * the wrong size/content entirely in that mode, since WindowMixin has
     * redirected what actually gets presented on-screen to a scaled
     * preview blit, not the full-resolution capture itself.
     *
     * <p>The only blocking call left on this (the render) thread is
     * {@link BlockingQueue#take()} on {@link #freeBuffers} -- which only
     * actually waits if the writer thread has fallen more than
     * {@link #BUFFER_POOL_SIZE} frames behind. In the common case a buffer
     * is already sitting there ready and this returns instantly; the slow
     * part (the actual pipe write to ffmpeg) happens entirely on
     * {@link #writerThread} instead, off this thread.
     */
    public void recordFrame()
    {
        if (!this.recording)
        {
            return;
        }

        try
        {
            int pbo = this.pboIndex;
            int nextPbo = (this.pboIndex + 1) % this.pbos.length;
            int source = this.readTextureId != 0 ? this.readFbo : 0;

            GL30.glPixelStorei(GL30.GL_PACK_ALIGNMENT, 1);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.pbos[pbo]);
            GL30.glReadPixels(0, 0, this.width, this.height, GL30.GL_BGR, GL30.GL_UNSIGNED_BYTE, 0);

            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.pbos[nextPbo]);

            ByteBuffer mappedBuffer = GL30.glMapBuffer(GL30.GL_PIXEL_PACK_BUFFER, GL30.GL_READ_ONLY);

            // Same off-by-one as MinemaRecorder/VideoRecorder: the buffer we
            // just mapped belongs to the *previous* PBO swap.
            if (mappedBuffer != null && this.counter != 0)
            {
                ByteBuffer target = this.freeBuffers.take();

                target.clear();
                target.put(mappedBuffer);
                target.flip();

                this.pendingWrites.put(target);
            }

            GL30.glUnmapBuffer(GL30.GL_PIXEL_PACK_BUFFER);
            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);

            this.pboIndex = nextPbo;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        this.counter += 1;
    }
}