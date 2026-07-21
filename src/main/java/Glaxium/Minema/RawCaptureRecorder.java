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
import java.util.concurrent.TimeUnit;

/**
 * Captures Minecraft's real default framebuffer -- whatever is actually
 * being displayed that frame, GUI and all -- instead of BBS mod's
 * deliberately restricted world-only render pass. This is what makes
 * inventory, vanilla settings screens, chat, the F3 debug overlay, and
 * other mods' UIs show up in the recording, matching how Minema 1.12.2
 * behaved (it captured the real screen, not an isolated offscreen pass).
 *
 * Structurally almost identical to MinemaRecorder (double-buffered PBOs,
 * raw pipe into ffmpeg) -- the differences are: reads GL_BGR color instead
 * of GL_DEPTH_COMPONENT (so no linearization step, straight passthrough),
 * and reads from whatever texture id it's given each frame rather than one
 * fixed at startRecording() time, since the real framebuffer's color
 * attachment can be recreated by the game (e.g. on window resize).
 */
public class RawCaptureRecorder
{
    private Process process;
    private WritableByteChannel channel;
    private boolean recording;

    private ByteBuffer outBuffer;

    private int width;
    private int height;
    private int counter;

    private int[] pbos;
    private int pboIndex;

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

        this.outBuffer = MemoryUtil.memAlloc(rawSize);

        try
        {
            File movies = BBSRendering.getVideoFolder();

            movies.mkdirs();

            Path path = Paths.get(movies.toString());
            String movieName = StringUtils.createTimestampFilename() + "_raw";
            float frameRate = (float) BBSRendering.getVideoFrameRate();

            String params = "-f rawvideo -pix_fmt bgr24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - "
                    + "-vf vflip -an -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p %NAME%.mp4";

            params = params.replace("%WIDTH%", String.valueOf(width));
            params = params.replace("%HEIGHT%", String.valueOf(height));
            params = params.replace("%FPS%", String.valueOf(frameRate));
            params = params.replace("%NAME%", movieName);

            List<String> args = new ArrayList<>();
            String encoder = FFMpegUtils.getFFMPEG();

            args.add(encoder);
            args.addAll(Arrays.asList(params.split(" ")));

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
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void stopRecording()
    {
        if (!this.recording)
        {
            return;
        }

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

        if (this.outBuffer != null)
        {
            MemoryUtil.memFree(this.outBuffer);
            this.outBuffer = null;
        }

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

        this.recording = false;
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
                this.outBuffer.clear();
                this.outBuffer.put(mappedBuffer);
                this.outBuffer.flip();
                this.channel.write(this.outBuffer);
            }

            GL30.glUnmapBuffer(GL30.GL_PIXEL_PACK_BUFFER);
            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);

            this.pboIndex = nextPbo;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        this.counter += 1;
    }
}