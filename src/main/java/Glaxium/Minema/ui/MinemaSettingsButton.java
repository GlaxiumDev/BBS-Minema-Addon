package Glaxium.Minema.ui;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.ui.UIValueMap;
import mchorse.bbs_mod.settings.ui.UIVideoSettingsOverlayPanel;
import mchorse.bbs_mod.settings.values.ui.ValueVideoSettings;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;

import java.util.Arrays;

/**
 * BBS mod's settings UI is data-driven: every {@code ValueVideoSettings}
 * field anywhere in its settings tree renders through one shared factory
 * ({@code UIValueMap.register(ValueVideoSettings.class, ...)}), which
 * normally just returns a single "Edit settings..." button. Calling
 * {@code UIValueMap.register()} again with our own factory -- from this
 * addon's own init, well after BBS mod's own static registration has
 * already run -- overwrites that entry, so both buttons show up together
 * wherever that field appears, no mixin needed.
 *
 * "Minema Settings" opens {@link MinemaSettingsOverlayPanel} as a nested
 * overlay on top of whatever's currently open -- same panel, same size,
 * that pressing J anywhere in the dashboard opens (see
 * {@link Glaxium.Minema.MinemaAddonEvents#onRegisterDashboardPanels}).
 */
public final class MinemaSettingsButton
{
    private MinemaSettingsButton()
    {
    }

    public static void register()
    {
        UIValueMap.register(ValueVideoSettings.class, (value, ui) ->
        {
            UIButton editSettings = new UIButton(UIKeys.VIDEO_SETTINGS_EDIT, (b) ->
                    UIOverlay.addOverlay(ui.getContext(), new UIVideoSettingsOverlayPanel(value)));

            UIButton minemaSettings = new UIButton(IKey.raw("Minema Settings"), (b) ->
                    UIOverlay.addOverlay(ui.getContext(), new MinemaSettingsOverlayPanel(),
                            MinemaSettingsOverlayPanel.WIDTH, MinemaSettingsOverlayPanel.HEIGHT));

            minemaSettings.color(0xB24DFF);
            minemaSettings.marginTop(4);

            return Arrays.asList(editSettings, minemaSettings);
        });
    }
}