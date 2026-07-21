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
import java.nio.FloatBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Captures Minecraft's depth buffer alongside BBS mod's own color recording
 * and pipes a linearized grayscale version into its own ffmpeg process.
 *
 * This deliberately mirrors mchorse.bbs_mod.utils.VideoRecorder's approach
 * (double-buffered PBOs -> raw pipe -> ffmpeg) so the two stay frame-locked
 * with each other. The one real difference: depth values read straight off
 * the GPU are non-linear (almost everything past a few blocks away crushes
 * up near 1.0), so we linearize on the CPU before writing each frame out --
 * otherwise you just get a white blob instead of a usable depth pass.
 */
public class MinemaRecorder
{
    private Process process;
    private WritableByteChannel channel;
    private boolean recording;

    /** Raw GL_FLOAT depth readback target (4 bytes/pixel). */
    private ByteBuffer rawBuffer;
    /** Linearized RGB8 buffer we actually stream to ffmpeg (3 bytes/pixel). */
    private ByteBuffer outBuffer;

    private int width;
    private int height;
    private int counter;

    private int[] pbos;
    private int pboIndex;

    private float near = 0.05F;
    private float far = 512F;

    public boolean isRecording()
    {
        return this.recording;
    }

    public int getCounter()
    {
        return this.counter;
    }

    /**
     * Optionally override the far plane used for linearization/normalization.
     * Defaults to a guess based on render distance if you don't call this.
     */
    public void setPlanes(float near, float far)
    {
        this.near = near;
        this.far = far;
    }

    /**
     * Call this right after BBS mod's own VideoRecorder.startRecording(), with
     * the same width/height, so both outputs line up frame-for-frame.
     */
    public void startRecording(int width, int height)
    {
        if (this.recording)
        {
            return;
        }

        this.counter = 0;
        this.width = width;
        this.height = height;

        int rawSize = width * height * 4;
        int outSize = width * height * 3;

        this.rawBuffer = MemoryUtil.memAlloc(rawSize);
        this.outBuffer = MemoryUtil.memAlloc(outSize);

        try
        {
            File movies = BBSRendering.getVideoFolder();

            movies.mkdirs();

            Path path = Paths.get(movies.toString());
            String movieName = StringUtils.createTimestampFilename() + "_depth";
            float frameRate = (float) BBSRendering.getVideoFrameRate();

            // Raw grayscale-as-RGB pipe. Swap qp/preset to taste -- depth passes
            // compress trivially so there's no reason to crank quality here.
            String params = "-f rawvideo -pix_fmt rgb24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - "
                + "-vf vflip -an -c:v libx264 -preset medium -qp 10 -pix_fmt yuv420p %NAME%.mp4";

            params = params.replace("%WIDTH%", String.valueOf(width));
            params = params.replace("%HEIGHT%", String.valueOf(height));
            params = params.replace("%FPS%", String.valueOf(frameRate));
            params = params.replace("%NAME%", movieName);

            List<String> args = new ArrayList<>();
            String encoder = FFMpegUtils.getFFMPEG();

            args.add(encoder);
            args.addAll(Arrays.asList(params.split(" ")));

            System.out.println("[bbs-minema] Recording depth pass with: " + args);

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

            // Same "unwrap the JDK's tiny BufferedOutputStream" trick VideoRecorder
            // uses -- without it the default buffer throttles throughput badly.
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

        if (this.rawBuffer != null)
        {
            MemoryUtil.memFree(this.rawBuffer);
            this.rawBuffer = null;
        }

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
     * Record one depth frame. Pass in the current depth attachment texture id,
     * e.g. MinecraftClient.getInstance().getFramebuffer().getDepthAttachment().
     */
    public void recordFrame(int depthTextureId)
    {
        if (!this.recording || depthTextureId <= 0)
        {
            return;
        }

        try
        {
            int pbo = this.pboIndex;
            int nextPbo = (this.pboIndex + 1) % this.pbos.length;

            GL30.glPixelStorei(GL30.GL_PACK_ALIGNMENT, 1);
            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.pbos[pbo]);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, depthTextureId);
            GL30.glGetTexImage(GL30.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT, GL30.GL_FLOAT, 0);

            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.pbos[nextPbo]);

            ByteBuffer mappedBuffer = GL30.glMapBuffer(GL30.GL_PIXEL_PACK_BUFFER, GL30.GL_READ_ONLY);

            // Same off-by-one as VideoRecorder: the buffer we just mapped belongs
            // to the *previous* PBO swap, so skip writing until we've swapped once.
            if (mappedBuffer != null && this.counter != 0)
            {
                this.linearizeAndWrite(mappedBuffer);
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

    /**
     * Converts non-linear depth samples to linear view-space distance,
     * normalizes against `far`, and packs as grayscale RGB8 for ffmpeg.
     *
     * Standard perspective depth linearization:
     *   ndc    = depth * 2 - 1
     *   linear = (2 * near * far) / (far + near - ndc * (far - near))
     *
     * This assumes the vanilla Minecraft perspective projection. If you're
     * running with a modded projection (e.g. some shader packs replace it),
     * you'll want to pull the real near/far from GameRenderer instead of
     * relying on the guessed `far` set in startRecording()/setPlanes().
     */
    private void linearizeAndWrite(ByteBuffer mapped) throws IOException
    {
        FloatBuffer depths = mapped.asFloatBuffer();
        int pixelCount = this.width * this.height;

        this.outBuffer.clear();

        for (int i = 0; i < pixelCount; i++)
        {
            float depth = depths.get(i);
            float ndc = depth * 2F - 1F;
            float linear = (2F * this.near * this.far) / (this.far + this.near - ndc * (this.far - this.near));

            float normalized = linear / this.far;

            if (normalized < 0F) normalized = 0F;
            if (normalized > 1F) normalized = 1F;

            byte gray = (byte) (int) (normalized * 255F);

            this.outBuffer.put(gray);
            this.outBuffer.put(gray);
            this.outBuffer.put(gray);
        }

        this.outBuffer.flip();
        this.channel.write(this.outBuffer);
    }
}
