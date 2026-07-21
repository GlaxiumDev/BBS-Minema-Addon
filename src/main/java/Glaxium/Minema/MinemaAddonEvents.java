package Glaxium.Minema;

import Glaxium.Minema.ui.MinemaSettingsOverlayPanel;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterDashboardPanelsEvent;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import org.lwjgl.glfw.GLFW;

/**
 * Registered via this addon's own {@code "bbs-addon"} entrypoint (see
 * fabric.mod.json) -- BBS mod scans that entrypoint at startup and calls
 * {@code BBSMod.events.register(...)} on whatever it finds there, which is
 * what actually wires up {@link #onRegisterDashboardPanels} below. Kept
 * separate from {@link BBSMinema} (the {@code "client"} entrypoint) since
 * Fabric Loader doesn't guarantee those resolve to the same instance --
 * this class doesn't need any of BBSMinema's own per-tick state, so there's
 * no reason to risk sharing one.
 */
public final class MinemaAddonEvents implements BBSAddonMod
{
    /**
     * Opens {@link MinemaSettingsOverlayPanel} from anywhere in the
     * dashboard -- same "global while the dashboard's open" behaviour as
     * BBS mod's own F6 (see {@code mchorse.bbs_mod.ui.Keys#OPEN_UTILITY_PANEL}
     * and {@code UIDashboard}'s own registration of it, which this mirrors).
     */
    public static final KeyCombo OPEN_MINEMA_PANEL =
            new KeyCombo("open_minema_panel", IKey.raw("Minema Settings"), GLFW.GLFW_KEY_J).categoryKey("dashboard");

    @Subscribe
    public void onRegisterDashboardPanels(RegisterDashboardPanelsEvent event)
    {
        // BBS mod's own Keys.CLIP_ENABLE ("Toggle enabled", camera clip
        // timeline) also defaults to J. That one's only meaningful while
        // actively editing a camera clip, whereas this addon's J is meant
        // to work globally, so whichever local panel handler is focused at
        // the time would otherwise silently eat the keypress before it
        // reaches this global one. Reassigning BBS's own default off J
        // (onto K, its only other unclaimed neighbour) avoids that fight
        // entirely -- this is a runtime rebind of BBS mod's own KeyCombo
        // object (its `keys` list is public/mutable), not an edit to BBS
        // mod's source, since that's a dependency this addon doesn't own.
        //
        // Only touches it if it's still sitting at the untouched default --
        // if the player already rebound "Toggle enabled" themselves
        // (including, harmlessly, to K), this leaves their choice alone.
        // BBS mod loads any saved keybind overrides after mod init runs,
        // so a player's own saved rebind always wins over this on the next
        // launch regardless.
        if (Keys.CLIP_ENABLE.keys.size() == 1 && Keys.CLIP_ENABLE.keys.get(0) == GLFW.GLFW_KEY_J)
        {
            Keys.CLIP_ENABLE.keys.clear();
            Keys.CLIP_ENABLE.keys.add(GLFW.GLFW_KEY_K);
        }

        event.dashboard.overlay.keys().register(OPEN_MINEMA_PANEL, () ->
        {
            // Same "don't stack a second overlay on top of one that's
            // already open" guard BBS mod's own F6 handler uses.
            if (UIOverlay.has(event.dashboard.context))
            {
                return;
            }

            UIOverlay.addOverlay(event.dashboard.context, new MinemaSettingsOverlayPanel(),
                    MinemaSettingsOverlayPanel.WIDTH, MinemaSettingsOverlayPanel.HEIGHT);
        });
    }
}
