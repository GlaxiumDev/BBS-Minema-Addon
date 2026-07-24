package Glaxium.Minema.hotbarclip;

import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

import java.util.List;

/**
 * Draws the ported Hotbar camera clip's fake HUD (see HotbarClip/HotbarState/UIHotbarRenderer)
 * whenever a Hotbar clip is actually being evaluated by the Film editor's own live preview
 * (scrubbing/previewing the timeline). This is BBS-Minema-Addon's own addition, kept entirely in
 * this addon's own package -- vanilla bbs-mod has no idea this clip type exists, so nothing in
 * vanilla renders it. Registered independently via the normal, public HudRenderCallback (same
 * event vanilla's own HUD rendering and RecordingOverlay already use -- Fabric allows multiple
 * listeners), so this needed no mixins.
 *
 * <p>Deliberately does NOT also render during actual in-world film playback
 * ({@code PlayCameraController}) -- that was tried and removed. That controller reference doesn't
 * get cleared just because a different dashboard panel (or an entirely different screen, like the
 * Morph menu) gets opened afterwards, so keying rendering off "is a PlayCameraController current"
 * meant the overlay kept getting drawn on top of whatever screen happened to be open later,
 * regardless of the Film panel no longer being active -- producing visible corruption over other
 * screens. Gating purely on "is the Film panel the active dashboard panel right now" (see
 * {@link #resolveActiveContext()}) has no equivalent stale-reference failure mode: the moment you
 * leave the Film panel, nothing renders, full stop -- at the cost of the overlay intentionally
 * not appearing during genuine in-world playback, only while actively editing/previewing in the
 * Film panel itself.
 */
public final class HotbarClipRenderer
{
    private HotbarClipRenderer()
    {
    }

    public static void register()
    {
        HudRenderCallback.EVENT.register(HotbarClipRenderer::render);
    }

    private static void render(DrawContext context, float tickDelta)
    {
        CameraClipContext clipContext = resolveActiveContext();

        if (clipContext == null)
        {
            return;
        }

        List<HotbarState> hotbars = HotbarClip.getHotbars(clipContext);

        if (hotbars.isEmpty())
        {
            return;
        }

        Batcher2D batcher = new Batcher2D(context);
        MatrixStack matrices = context.getMatrices();

        UIHotbarRenderer.renderHotbars(matrices, batcher, hotbars);
    }

    private static CameraClipContext resolveActiveContext()
    {
        // Only the Film editor's own live preview -- the currently open menu is a UIDashboard
        // showing a UIFilmPanel specifically. The separate "actually playing in the world"
        // (PlayCameraController) case has been removed entirely: that controller reference
        // doesn't get cleared just because a different panel/screen gets opened afterwards, so
        // keying rendering off it meant the hotbar overlay kept getting drawn on top of whatever
        // screen happened to be open later (e.g. the Morph menu), regardless of the Film panel
        // no longer being active. Gating purely on "is the Film panel the active panel right
        // now" has no equivalent stale-reference failure mode -- the moment you leave the Film
        // panel, this returns null and nothing renders, full stop.
        UIBaseMenu currentMenu = UIScreen.getCurrentMenu();

        if (currentMenu instanceof UIDashboard dashboard)
        {
            UIDashboardPanel panel = dashboard.getPanels().panel;

            if (panel instanceof UIFilmPanel filmPanel)
            {
                RunnerCameraController runner = filmPanel.getRunner();

                if (runner != null)
                {
                    return runner.getContext();
                }
            }
        }

        return null;
    }
}