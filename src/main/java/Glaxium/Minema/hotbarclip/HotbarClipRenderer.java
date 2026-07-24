package Glaxium.Minema.hotbarclip;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.controller.ICameraController;
import mchorse.bbs_mod.camera.controller.PlayCameraController;
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
 * whenever a Hotbar clip is actually being evaluated -- either a Film really driving the camera
 * in-world, or just scrubbing/previewing the timeline inside the Film editor itself. This is
 * BBS-Minema-Addon's own addition, kept entirely in this addon's own package -- vanilla bbs-mod
 * has no idea this clip type exists, so nothing in vanilla renders it. Registered independently
 * via the normal, public HudRenderCallback (same event vanilla's own HUD rendering and
 * RecordingOverlay already use -- Fabric allows multiple listeners), so this needed no mixins.
 *
 * <p>Mirrors the two cases vanilla's own {@code BBSRendering#onWorldRenderEnd()} handles for
 * rendering subtitles: (1) {@link PlayCameraController} -- a Film actually driving the camera in
 * the world right now, and (2) the Film editor's own live preview -- the currently open menu is
 * a {@code UIDashboard} showing a {@code UIFilmPanel}, whose {@link RunnerCameraController} is
 * what actually evaluates clips while you scrub/preview the timeline. Both controller types
 * share a public {@code getContext()} (inherited from their common {@code CameraWorkCameraController}
 * base), which is where {@code HotbarClip#applyClip()} will have already populated the "hotbars"
 * list if a Hotbar clip is active in that context -- we just read it back out here, for
 * whichever of the two is actually active.
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
        ICameraController current = BBSModClient.getCameraController().getCurrent();

        if (current instanceof PlayCameraController playController)
        {
            return playController.getContext();
        }

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