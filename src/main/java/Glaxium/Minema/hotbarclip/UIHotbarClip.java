package Glaxium.Minema.hotbarclip;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIAnchorKeyframeFactory;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

/**
 * Ported from BBS-CML-EDITION by BBS-Minema-Addon, kept in this addon's own package -- it only
 * extends bbs-mod's public {@code UIClip<T>} base class, registered from
 * BBSMinema#onInitializeClient via {@code UIClip.register(...)}, an extension point already used
 * internally the same way -- no mixins, and no code of ours living inside bbs-mod's own package.
 *
 * <p>Two real changes from the original: (1) CML's own {@code UIKeys.CAMERA_PANELS_HOTBAR} label
 * doesn't exist in vanilla, so the panel header below uses a plain constant label instead --
 * purely cosmetic (no lang-file entry, just "Hotbar"). (2) CML's {@code UIClip} base class has a
 * {@code resolveClipEmbeddableView} hook (for restoring which embedded view was open after an
 * undo/redo) that vanilla's {@code UIClip} doesn't have, so that override is dropped -- the
 * "Edit Keyframes" button below still opens the embedded editor fine via
 * {@code editor.embedView(...)}; the only thing lost is that editor not auto-reopening after an
 * undo, which is a minor nicety, not core functionality.
 */
public class UIHotbarClip extends UIClip<HotbarClip>
{
    public UIButton pickSource;
    public UIButton edit;
    public UIKeyframeEditor keyframes;

    public UIHotbarClip(HotbarClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.keyframes = new UIKeyframeEditor((consumer) -> new UIFilmKeyframes(this.editor, consumer));
        this.keyframes.view.duration(() -> this.clip.duration.get());
        this.keyframes.setUndoId("hotbar_keyframes");

        this.pickSource = new UIButton(IKey.constant("Pick Source"), (b) -> this.displaySourcePicker());

        this.edit = new UIButton(UIKeys.CAMERA_PANELS_EDIT_KEYFRAMES, (b) ->
        {
            this.editor.embedView(this.keyframes);
            this.keyframes.view.resetView();
            this.keyframes.view.getGraph().clearSelection();
        });
        this.edit.keys().register(Keys.FORMS_EDIT, () -> this.edit.clickItself());
    }

    /**
     * Opens the same actor/replay picker context menu vanilla's own anchor keyframes use
     * ({@code UIAnchorKeyframeFactory.displayActors}), listing every actor currently in the
     * film by index (with a "None" option at the top). Picking one stores its index on
     * {@code clip.source} and refreshes the panel, which is what makes the "Edit Keyframes"
     * button appear -- see {@link #fillData()}.
     */
    private void displaySourcePicker()
    {
        UIFilmPanel panel = this.getParent(UIFilmPanel.class);

        if (panel == null)
        {
            return;
        }

        UIAnchorKeyframeFactory.displayActors(this.getContext(), panel.getController().getEntities(), this.clip.source.get(), this::setSource);
    }

    private void setSource(int source)
    {
        this.clip.source.set(source);
        this.fillData();
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(UI.column(UIClip.label(IKey.constant("Hotbar")), this.pickSource, this.edit).marginTop(12));
    }

    @Override
    public void fillData()
    {
        super.fillData();
        this.ensureHardcoreIsBoolean();

        int source = this.clip.source.get();

        this.edit.setVisible(source >= 0);

        // Vanilla bbs-mod's UIKeyframeEditor has no setChannels(KeyframeChannel[]) helper (a
        // CML-only addition) and UIKeyframeSheet#title is final, so it has to be supplied at
        // construction time rather than assigned afterward -- build the sheets by hand instead,
        // using the same public removeAllSheets()/addSheet() vanilla's own setClip() equivalent
        // uses internally.
        this.keyframes.view.removeAllSheets();

        for (int i = 0; i < this.clip.channels.length; i++)
        {
            KeyframeChannel channel = this.clip.channels[i];
            int color = UIKeyframeEditor.COLORS[i % UIKeyframeEditor.COLORS.length];

            this.keyframes.view.addSheet(new UIKeyframeSheet(
                    channel.getId(), this.getTrackTitle(channel.getId()), color, false, channel, null));
        }
    }

    private IKey getTrackTitle(String id)
    {
        // Plain constant labels, not UIKeys.C_CLIP.get("bbs:...") -- that lookup falls back to
        // the full untranslated lang key itself (e.g. "bbs.ui.camera.clips.bbs:slot_0") when
        // there's no matching entry, which there isn't in vanilla (that key format assumes
        // CML edition's own lang file). Those raw keys are too long for the track-label column
        // and overflow into the UI next to it -- short constants sidestep the lookup entirely.
        return switch (id)
        {
            case "selected_slot" -> IKey.constant("Selected Slot");
            case "slot_0" -> IKey.constant("Slot 1");
            case "slot_1" -> IKey.constant("Slot 2");
            case "slot_2" -> IKey.constant("Slot 3");
            case "slot_3" -> IKey.constant("Slot 4");
            case "slot_4" -> IKey.constant("Slot 5");
            case "slot_5" -> IKey.constant("Slot 6");
            case "slot_6" -> IKey.constant("Slot 7");
            case "slot_7" -> IKey.constant("Slot 8");
            case "slot_8" -> IKey.constant("Slot 9");
            case "offhand_slot" -> IKey.constant("Offhand");
            case "health" -> IKey.constant("Health");
            case "health_container" -> IKey.constant("Health Container");
            case "absorption" -> IKey.constant("Absorption");
            case "absorption_container" -> IKey.constant("Absorption Container");
            case "heart_type" -> IKey.constant("Heart Type");
            case "hardcore" -> IKey.constant("Hardcore");
            case "heart_regeneration" -> IKey.constant("Heart Regeneration");
            case "hunger_effect" -> IKey.constant("Hunger Effect");
            case "armor" -> IKey.constant("Armor");
            case "hunger" -> IKey.constant("Hunger");
            case "air" -> IKey.constant("Air");
            case "experience" -> IKey.constant("Experience");
            case "experience_level" -> IKey.constant("Experience Level");
            case "layout" -> IKey.constant("Layout");
            default -> IKey.constant(id);
        };
    }

    private void ensureHardcoreIsBoolean()
    {
        if (this.clip.hardcore.getFactory() == KeyframeFactories.BOOLEAN)
        {
            return;
        }

        MapType data = this.clip.hardcore.toData().asMap();

        data.putString("type", "boolean");
        this.clip.hardcore.fromData(data);
    }
}