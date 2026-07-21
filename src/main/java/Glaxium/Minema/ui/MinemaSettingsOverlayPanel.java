package Glaxium.Minema.ui;

import Glaxium.Minema.MinemaConfig;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;

/**
 * Minema's settings, as a proper BBS UI overlay panel -- opened inside
 * BBS's dashboard (via {@code UIOverlay.addOverlay}) exactly like the
 * built-in Utility panel (F6) or Edit Settings panels, rather than a
 * separate vanilla Screen replacing whatever was on screen before.
 */
public class MinemaSettingsOverlayPanel extends UIOverlayPanel
{
    private final MinemaConfig config = MinemaConfig.INSTANCE;

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

        UIToggle syncEngine = new UIToggle(IKey.raw("Sync engine to capture"), this.config.syncEngine, (b) ->
        {
            this.config.syncEngine = b.getValue();
            this.config.save();
        });

        UITrackpad depthDistance = new UITrackpad((v) ->
        {
            this.config.captureDepthDistance = v.doubleValue();
            this.config.save();
        });

        depthDistance.limit(1, 1024, true);
        depthDistance.setValue(this.config.captureDepthDistance);

        UIScrollView view = UI.scrollView(5, 10,
            recordAudio,
            generateWav,
            captureDepth,
            UI.label(IKey.raw("Depth capture distance")).marginTop(6),
            depthDistance,
            syncEngine
        );

        view.full(this.content);
        this.content.add(view);
    }
}
