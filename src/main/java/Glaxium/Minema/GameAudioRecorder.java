package Glaxium.Minema;

import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.utils.FFMpegUtils;
import mchorse.bbs_mod.utils.StringUtils;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Records BBS mod's actual mixed game audio (not the microphone) to a WAV
 * file, one frame's worth of samples at a time, then muxes that WAV into
 * whatever video file BBS mod's own VideoRecorder produces once recording
 * stops (that's what turning "Record in-game audio" on means). Whether the
 * intermediate WAV is kept afterward as its own file is a separate call
 * made by {@code BBSMinema} based on the "Generate .wav Audio file" toggle
 * -- see the {@code keepWav} parameter on {@link #muxIntoVideo}.
 *
 * <p>Started/stopped from  alongside
 * everything else that watches {@code VideoRecorder#isRecording()} --
 * matters that this is *not* tied to how recording started (F4 quick-record
 * vs the film editor), it reacts to the shared recording state either way.
 *
 * <p>{@link #captureFrame()} is called from
 * on the exact same gate {@code MinemaRecorder} uses for the depth pass, so
 * this addon asks for exactly one frame's worth of audio per frame BBS mod
 * actually captures -- see {@link Glaxium.Minema.mixin.SoundEngineMixin}'s
 * class doc for why that's what keeps this in sync during slow-motion
 * (tick-synced) recording instead of drifting like a real-time capture
 * would.
 *
 * <p>The actual sample capture (OpenAL loopback via {@link GameAudioController}) has no
 * equivalent in BBS mod -- its own audio pipeline mixes down a Film's pre-placed
 * {@code AudioClip} timeline offline (see {@code AudioRenderer#renderAudio}), not a live
 * arbitrary gameplay session -- so that part stays addon-owned. What this class *does* borrow
 * from BBS mod is its {@code Wave}/{@link WaveWriter} pair: captured PCM is buffered into a
 * {@link Wave} exactly like {@code AudioRenderer#renderAudio} builds its mixdown, then written
 * out with the same {@code WaveWriter.write(File, Wave)} call that method itself uses, instead
 * of this addon hand-poking RIFF header bytes. Note this does mean the captured audio sits in
 * memory for the length of the recording (no incremental "patch the header afterward" streaming
 * write exists in the published bbs-mod API this addon compiles against) -- fine for typical
 * clip lengths, worth knowing for very long recordings.
 */
public class GameAudioRecorder
{
    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int SAMPLE_RATE = GameAudioController.SAMPLE_RATE;

    private File wavFile;
    private ByteArrayOutputStream pcmBuffer;
    private int fps;
    private double pendingSamples;
    private long writtenBytes;
    private boolean recording;

    /** True for the handful of frames after startRecording() before the loopback device has actually finished opening (SoundManager#reloadSounds() hasn't settled yet). Those frames are silently dropped instead of throwing. */
    private boolean waitingForDevice;

    public boolean isRecording()
    {
        return this.recording;
    }

    public File getWavFile()
    {
        return this.wavFile;
    }

    /**
     * Opens the WAV file and requests the loopback device. Call this once,
     * on the same rising edge {@code MinemaRecorder#startRecording} uses.
     *
     * @param folder   video output folder, e.g. BBSRendering.getVideoFolder()
     * @param baseName base filename (no extension) -- pass the SAME base
     *                 name you'll later hand to {@link #muxIntoVideo}'s
     *                 color-video lookup window so logs/files are easy to
     *                 correlate; this class itself just appends "_audio.wav"
     * @param fps      target video frame rate, used to convert "1 frame" into a sample count
     */
    public void startRecording(Path folder, String baseName, int fps)
    {
        if (this.recording)
        {
            return;
        }

        folder.toFile().mkdirs();

        this.wavFile = folder.resolve(baseName + "_audio.wav").toFile();
        this.pcmBuffer = new ByteArrayOutputStream();
        this.fps = Math.max(1, fps);
        this.pendingSamples = 0;
        this.writtenBytes = 0;
        this.waitingForDevice = true;

        // Route SoundEngine to a loopback device the next time it (re)inits.
        GameAudioController.requestCapture(true);

        MinecraftClient client = MinecraftClient.getInstance();

        client.getSoundManager().stopAll();
        client.getSoundManager().reloadSounds();
        client.getSoundManager().stopAll();

        this.recording = true;
    }

    /**
     * Pull one frame's worth of samples from the loopback device. Must be
     * called exactly once per video frame actually captured, no more, no
     * less, or the WAV will run short/long relative to the video.
     */
    public void captureFrame()
    {
        if (!this.recording)
        {
            return;
        }

        long device = GameAudioController.getLoopbackDevice();

        if (device == 0L)
        {
            // Still waiting on reloadSounds() to finish opening the loopback
            // device (or it failed to open at all) -- drop this frame's
            // audio rather than blocking the render thread on it.
            return;
        }

        this.waitingForDevice = false;
        this.pendingSamples += (double) SAMPLE_RATE / this.fps;

        int samples = (int) this.pendingSamples;

        if (samples <= 0)
        {
            return;
        }

        this.pendingSamples -= samples;

        int floats = samples * CHANNELS;
        FloatBuffer floatBuffer = MemoryUtil.memAllocFloat(floats);

        try
        {
            SOFTLoopback.alcRenderSamplesSOFT(device, floatBuffer, samples);

            byte[] pcm = new byte[floats * 2];
            int index = 0;

            for (int i = 0; i < floats; i++)
            {
                float value = Math.max(-1F, Math.min(1F, floatBuffer.get(i)));
                short pcm16 = (short) (value * Short.MAX_VALUE);

                pcm[index++] = (byte) (pcm16 & 0xFF);
                pcm[index++] = (byte) ((pcm16 >> 8) & 0xFF);
            }

            this.pcmBuffer.write(pcm, 0, pcm.length);
            this.writtenBytes += pcm.length;
        }
        finally
        {
            MemoryUtil.memFree(floatBuffer);
        }
    }

    /**
     * Finalizes the WAV header, closes the file, and switches SoundEngine
     * back to a real playback device. Returns the finished WAV file (or null
     * if recording never actually started producing data), so the caller
     * can decide whether to mux it and/or keep it as a standalone file.
     */
    public File stopRecording()
    {
        if (!this.recording)
        {
            return null;
        }

        this.recording = false;

        GameAudioController.requestCapture(false);
        GameAudioController.setLoopbackDevice(0L);

        MinecraftClient client = MinecraftClient.getInstance();

        client.getSoundManager().stopAll();
        client.getSoundManager().reloadSounds();
        client.getSoundManager().stopAll();

        this.writeWavFile();

        if (this.waitingForDevice)
        {
            // Loopback device never actually came up -- e.g. this system's
            // OpenAL runtime doesn't support SOFT_loopback. There's a valid
            // (near-silent) WAV on disk, but it's not useful.
            System.err.println("[bbs-minema] Game audio recording produced no samples -- "
                    + "does your OpenAL runtime support the SOFT_loopback extension?");
        }

        File finished = this.wavFile;

        this.wavFile = null;
        this.pcmBuffer = null;

        return finished;
    }

    /**
     * Builds a bbs-fs {@link Wave} from the buffered PCM and writes it with
     * {@code WaveWriter.write(File, Wave)} -- the same call {@code AudioRenderer#renderAudio}
     * itself uses to write its own mixdown, reused here instead of hand-poking RIFF bytes.
     */
    private void writeWavFile()
    {
        try
        {
            Wave wave = new Wave(1, CHANNELS, SAMPLE_RATE, BITS_PER_SAMPLE, this.pcmBuffer.toByteArray());

            WaveWriter.write(this.wavFile, wave);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Muxes `audio` into the newest color video BBS mod wrote into `folder`
     * at or after `recordingStartedAt` (epoch millis). This addon doesn't
     * control BBS mod's own VideoRecorder, so unlike the depth pass (which
     * this addon names/writes itself) there's no guaranteed shared filename
     * to look up directly -- scanning by "newest non-depth video file
     * written since we started recording" is the same trick BBS CML
     * EDITION's own findOutputVideo() amounts to, adapted since we don't
     * have their VideoRecorder's internal movieName field to read.
     *
     * Re-encodes nothing -- video stream is copied, audio is encoded to AAC,
     * same as BBS mod's own existing "burn a separately-rendered audio file
     * into the video" ffmpeg call.
     *
     * @param keepWav if false, `audio` is deleted once muxing succeeds --
     *                used when "Record in-game audio" is on but "Generate
     *                .wav Audio file" is off, i.e. the person only wanted
     *                audio baked into the video, not a standalone file.
     */
    public void muxIntoVideo(File audio, Path folder, long recordingStartedAt, boolean keepWav)
    {
        if (audio == null || !audio.isFile())
        {
            return;
        }

        File video = this.findColorVideoOutput(folder, recordingStartedAt);

        if (video == null)
        {
            System.err.println("[bbs-minema] Couldn't find the color video to mux game audio into "
                    + "(looked in " + folder + " for a video newer than recording start). "
                    + "The WAV is still on disk at " + audio.getAbsolutePath() + ".");

            return;
        }

        String extension = this.extensionOf(video.getName());
        File tempOutput = folder.resolve(video.getName().replace("." + extension, "") + "_muxed." + extension).toFile();

        List<String> args = new ArrayList<>();

        args.add(FFMpegUtils.getFFMPEG());
        args.add("-y");
        args.add("-i");
        args.add(video.getAbsolutePath());
        args.add("-i");
        args.add(audio.getAbsolutePath());
        args.add("-c:v");
        args.add("copy");
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add("192k");
        args.add("-shortest");
        args.add(tempOutput.getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(args);

        builder.directory(folder.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(folder.resolve(StringUtils.createTimestampFilename() + "_audiomux.log").toFile());

        try
        {
            Process process = builder.start();
            boolean finished = process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0 && tempOutput.isFile();

            if (finished)
            {
                File backup = folder.resolve(video.getName().replace("." + extension, "") + "_noaudio." + extension).toFile();

                if (backup.exists())
                {
                    backup.delete();
                }

                if (video.renameTo(backup))
                {
                    if (!tempOutput.renameTo(video))
                    {
                        // Muxed file couldn't take the original name back -- restore
                        // the un-muxed video so nothing is silently lost.
                        backup.renameTo(video);
                    }
                    else
                    {
                        backup.delete();

                        if (!keepWav)
                        {
                            audio.delete();
                        }
                    }
                }
            }
            else
            {
                System.err.println("[bbs-minema] ffmpeg mux failed, leaving video un-muxed. Check the log next to it.");
                tempOutput.delete();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private File findColorVideoOutput(Path folder, long recordingStartedAt)
    {
        File[] candidates = folder.toFile().listFiles((dir, name) -> {
            String lower = name.toLowerCase();

            return Arrays.asList("mp4", "mkv", "mov", "webm", "avi").contains(this.extensionOf(lower))
                    && !lower.contains("_depth")
                    && !lower.contains("_audio")
                    && !lower.contains("_muxed")
                    && !lower.contains("_noaudio");
        });

        if (candidates == null || candidates.length == 0)
        {
            return null;
        }

        // A few seconds of slack: ffmpeg's own file creation can predate our
        // "recording started" timestamp slightly depending on OS/filesystem
        // mtime granularity and exactly which tick each side observed the
        // rising edge on.
        long cutoff = recordingStartedAt - 5000L;

        return Arrays.stream(candidates)
                .filter(f -> f.lastModified() >= cutoff)
                .max(Comparator.comparingLong(File::lastModified))
                .orElse(null);
    }

    private String extensionOf(String name)
    {
        int dot = name.lastIndexOf('.');

        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}