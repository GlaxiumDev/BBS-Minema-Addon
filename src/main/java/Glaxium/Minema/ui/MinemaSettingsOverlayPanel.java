package Glaxium.Minema.ui;

import Glaxium.Minema.MinemaConfig;
import Glaxium.Minema.RawCaptureModule;
import Glaxium.Minema.util.FolderOpener;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;

/**
 * Minema's settings, as a proper BBS UI overlay panel -- opened inside
 * BBS's dashboard (via {@code UIOverlay.addOverlay}) exactly like the
 * built-in Utility panel (F6) or Edit Settings panels, rather than a
 * separate vanilla Screen replacing whatever was on screen before.
 *
 * <p>Covers every Minema-specific setting the standalone {@link MinemaSettingsScreen}
 * does (Resolution/Capturing/Engine/Misc tabs, minus BBS mod's own
 * width/height/framerate/output-path/ffmpeg-args fields, which intentionally
 * stay in BBS's own "Edit settings" panel -- same split MinemaSettingsScreen
 * documents: this panel is reachable from BBS's video panel right next to
 * "Edit settings", not a duplicate of it).
 */
public class MinemaSettingsOverlayPanel extends UIOverlayPanel
{
    /**
     * Was 240x200 (barely fit the original 4 settings before this addon
     * grew to cover everything MinemaSettingsScreen does) -- widened/
     * heightened so most of the panel's content fits without scrolling on
     * a typical GUI scale. Referenced from every call site that opens this
     * panel (see MinemaSettingsButton and MinemaAddonEvents) instead of
     * each hardcoding its own numbers, so they can't drift out of sync.
     */
    public static final int WIDTH = 340;
    public static final int HEIGHT = 420;

    private final MinemaConfig config = MinemaConfig.INSTANCE;
    private UIButton recordButton;

    public MinemaSettingsOverlayPanel()
    {
        super(IKey.raw("Minema Settings"));

        UIToggle recordAudio = new UIToggle(IKey.raw("Record in-game audio"), this.config.recordGameAudio, (b) ->
        {
            this.config.recordGameAudio = b.getValue();
            this.config.save();
        });

        UIToggle generateWav = new UIToggle(IKey.raw("Generate .wav audio file"), this.config.generateWavFile, (b) ->
        {
            this.config.generateWavFile = b.getValue();
            this.config.save();
        });

        UIToggle captureDepth = new UIToggle(IKey.raw("Capture depth pass"), this.config.captureDepth, (b) ->
        {
            this.config.captureDepth = b.getValue();
            this.config.save();
        });

        UITrackpad depthDistance = new UITrackpad((v) ->
        {
            this.config.captureDepthDistance = v.doubleValue();
            this.config.save();
        });

        depthDistance.limit(1, 1024, true);
        depthDistance.setValue(this.config.captureDepthDistance);

        UIToggle customResolution = new UIToggle(IKey.raw("Custom resolution capture (F4)"), this.config.customResolution, (b) ->
        {
            this.config.customResolution = b.getValue();
            this.config.save();
        });

        UITrackpad frameLimit = new UITrackpad((v) -> this.config.setFrameLimit(v.intValue()));

        frameLimit.limit(-1, Integer.MAX_VALUE, true);
        frameLimit.setValue(this.config.frameLimit);
        frameLimit.tooltip(IKey.raw("-1 = unlimited, otherwise auto-stops recording after this many frames"));

        UIToggle syncEngine = new UIToggle(IKey.raw("Sync engine to capture"), this.config.syncEngine, (b) ->
        {
            this.config.syncEngine = b.getValue();
            this.config.save();
        });

        UITrackpad engineSpeed = new UITrackpad((v) -> this.config.setEngineSpeed(v.doubleValue()));

        engineSpeed.limit(0.01, 100.0, false);
        engineSpeed.setValue(this.config.engineSpeed);

        UIButton openFolder = new UIButton(IKey.raw("Open recordings folder"), (b) ->
                FolderOpener.open(BBSRendering.getVideoFolder().toPath()));

//        this.recordButton = new UIButton(recordLabel(), (b) -> toggleRecording());

        UIScrollView view = UI.scrollView(5, 10,
                recordAudio,
                generateWav,
                captureDepth,
                UI.label(IKey.raw("Depth capture distance")).marginTop(6),
                depthDistance,
                customResolution,
                UI.label(IKey.raw("Frame limit")).marginTop(6),
                frameLimit,
                syncEngine,
                UI.label(IKey.raw("Engine speed")).marginTop(6),
                engineSpeed,
                openFolder.marginTop(10)
//                this.recordButton
        );

        view.full(this.content);
        this.content.add(view);
    }

    private void toggleRecording()
    {
        if (RawCaptureModule.INSTANCE.isRecording())
        {
            RawCaptureModule.INSTANCE.stop();
        }
        else
        {
            RawCaptureModule.INSTANCE.start();
        }

        this.recordButton.label = recordLabel();
    }

    private IKey recordLabel()
    {
        return IKey.raw(RawCaptureModule.INSTANCE.isRecording() ? "Stop Recording" : "Start Recording");
    }
}