package Glaxium.Minema.ui;

import Glaxium.Minema.MinemaConfig;
import Glaxium.Minema.RawCaptureModule;
import mchorse.bbs_mod.BBSSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The full Minema settings UI, as a standalone vanilla {@link Screen} --
 * same pattern as the discontinued standalone BBS-Minema mod's own
 * {@code MinemaSettingsScreen}, deliberately NOT built on top of BBS mod's
 * UI framework (no {@code UIOverlayPanel}, no dashboard dependency), so it
 * opens and closes exactly like the standalone mod's UI did, outside of
 * BBS's own UI entirely.
 *
 * <p>It reads and writes the exact same {@link MinemaConfig#INSTANCE} that
 * {@link MinemaSettingsOverlayPanel} (the BBS-side panel, still reachable
 * from BBS's own "Minema Settings" button in its video settings) does --
 * that's the two-way sync: change a toggle here, it's changed there too,
 * and vice versa, with no extra glue code needed, since both are just
 * views onto the same singleton.
 *
 * <p>Only exposes settings this addon actually has a feature for -- no
 * motion blur, shutter profiles, encoding presets, alpha capture, After
 * Effects camera export, chunk preloading, gamma, or shaderpack switching,
 * since (unlike the standalone mod) none of those exist in this addon. BBS
 * mod's own settings panel (frame resolution/FPS/output path/ffmpeg args)
 * is intentionally left alone and not duplicated here -- the "Minema
 * Settings" and "Edit settings..." buttons sit side by side in BBS's video
 * panel for exactly that split.
 */
public final class MinemaSettingsScreen extends Screen
{
    private enum Tab
    {
        RESOLUTION, CAPTURING, ENGINE, MISC
    }

    private final Screen parent;
    private final MinemaConfig config = MinemaConfig.INSTANCE;
    private final List<TextFieldWidget> fields = new ArrayList<>();
    private final List<String> fieldLabels = new ArrayList<>();
    private Tab tab = Tab.RESOLUTION;
    private Text error = Text.empty();

    public MinemaSettingsScreen(Screen parent)
    {
        super(Text.literal("Minema Settings"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        this.fields.clear();
        this.fieldLabels.clear();

        int tabWidth = 78;
        int start = this.width / 2 - tabWidth * 2;

        for (Tab value : Tab.values())
        {
            this.addDrawableChild(ButtonWidget.builder(Text.literal(title(value)), b -> switchTab(value))
                    .dimensions(start + value.ordinal() * tabWidth, 28, tabWidth - 2, 20).build())
                    .active = value != this.tab;
        }

        switch (this.tab)
        {
            case RESOLUTION -> resolutionTab();
            case CAPTURING -> capturingTab();
            case ENGINE -> engineTab();
            case MISC -> miscTab();
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b ->
        {
            if (applyFields())
            {
                this.close();
            }
        }).dimensions(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    private String title(Tab value)
    {
        return switch (value)
        {
            case RESOLUTION -> "Resolution";
            case CAPTURING -> "Capturing";
            case ENGINE -> "Engine";
            case MISC -> "Misc";
        };
    }

    private void switchTab(Tab value)
    {
        // Persist whatever's currently in text fields before leaving this tab, so
        // hopping between tabs doesn't silently discard an in-progress edit.
        applyFields();
        this.tab = value;
        this.clearAndInit();
    }

    // ---- RESOLUTION tab: custom resolution for F4 capture -- the actual feature this was built for ----
    private void resolutionTab()
    {
        int x = this.width / 2 - 155;
        int y = 60;

        // These are BBS mod's own values (BBSSettings.videoSettings.width/height/frameRate)
        // -- editing them here is editing the exact same thing BBS's own
        // "Edit settings" panel edits, not a separate copy.
        addField(x, y, "Width", Integer.toString(BBSSettings.videoSettings.width.get()));
        addField(x + 160, y, "Height", Integer.toString(BBSSettings.videoSettings.height.get()));
        addField(x, y + 32, "FPS", Integer.toString(BBSSettings.videoSettings.frameRate.get()));

        this.addDrawableChild(ButtonWidget.builder(Text.literal("3840 x 2160 (4K)"), b ->
        {
            this.fields.get(0).setText("3840");
            this.fields.get(1).setText("2160");
        }).dimensions(x, y + 64, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("1920 x 1080"), b ->
        {
            this.fields.get(0).setText("1920");
            this.fields.get(1).setText("1080");
        }).dimensions(x + 160, y + 64, 150, 20).build());

        // A choice between two capture modes, NOT an enable/disable switch,
        // and NOT a literal "make my window fullscreen" toggle -- see the
        // class doc comment.
        this.addDrawableChild(ButtonWidget.builder(captureModeText(), b ->
        {
            this.config.toggleCustomResolution();
            b.setMessage(captureModeText());
        }).dimensions(x, y + 94, 310, 20).build());
    }

    private Text captureModeText()
    {
        return Text.literal("Capture Mode: " + (this.config.customResolution ? "Custom Resolution" : "Native Fullscreen"));
    }

    // ---- CAPTURING tab: everything RawCaptureModule/MinemaRecorder/GameAudioRecorder actually support ----
    private void capturingTab()
    {
        int x = this.width / 2 - 155;
        int y = 60;

        this.addDrawableChild(UiToggle.of(x, y, 310, this.config.recordGameAudio,
                "Record in-game audio", v -> { this.config.recordGameAudio = v; this.config.save(); }));

        this.addDrawableChild(UiToggle.of(x, y + 26, 310, this.config.generateWavFile,
                "Generate .wav audio file", v -> { this.config.generateWavFile = v; this.config.save(); }));

        this.addDrawableChild(UiToggle.of(x, y + 52, 310, this.config.captureDepth,
                "Capture depth pass", v -> { this.config.captureDepth = v; this.config.save(); }));

        addField(x, y + 84, "Depth capture distance", Double.toString(this.config.captureDepthDistance));
    }

    // ---- ENGINE tab: sync + engine speed ----
    private void engineTab()
    {
        int x = this.width / 2 - 155;
        int y = 60;

        this.addDrawableChild(UiToggle.of(x, y, 310, this.config.syncEngine,
                "Sync engine to capture", v -> { this.config.syncEngine = v; this.config.save(); }));

        addField(x, y + 32, "Engine speed", String.valueOf(this.config.engineSpeed));
    }

    // ---- MISC tab: nothing addon-specific to configure yet besides output access -- BBS mod's own Frame Resolution/FPS/output path panel covers the rest ----
    private void miscTab()
    {
        int x = this.width / 2 - 155;
        int y = 60;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Open recordings folder"), b -> openVideoFolder())
                .dimensions(x, y, 310, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal(RawCaptureModule.INSTANCE.isRecording() ? "Stop Recording" : "Start Recording"),
                b -> toggleRecording()
        ).dimensions(x, y + 30, 310, 20).build());
    }

    private void openVideoFolder()
    {
        try
        {
            boolean opened = Glaxium.Minema.util.FolderOpener.open(
                    mchorse.bbs_mod.client.BBSRendering.getVideoFolder().toPath());

            this.error = opened ? Text.empty() : Text.literal("Opening folders isn't supported on this system");
        }
        catch (Exception e)
        {
            this.error = Text.literal("Could not open the recordings folder");
        }
    }

    private void toggleRecording()
    {
        if (RawCaptureModule.INSTANCE.isRecording())
        {
            RawCaptureModule.INSTANCE.stop();
        }
        else
        {
            if (applyFields())
            {
                RawCaptureModule.INSTANCE.start();
            }
        }

        this.clearAndInit();
    }

    private void addField(int x, int y, String label, String value)
    {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y + 12, 150, 20, Text.literal(label));

        field.setText(value);
        field.setMaxLength(16);
        this.fields.add(this.addDrawableChild(field));
        this.fieldLabels.add(label);
    }

    /** Parses and saves whatever number fields are currently visible on this tab; returns false (and sets an error) on invalid input, without discarding anything already saved. */
    private boolean applyFields()
    {
        try
        {
            switch (this.tab)
            {
                case RESOLUTION ->
                {
                    if (this.fields.size() >= 3)
                    {
                        // Same ValueInt objects BBS mod's own settings panel
                        // edits -- .set() clamps to each one's declared
                        // min/max (see ValueVideoSettings).
                        BBSSettings.videoSettings.width.set(
                                Integer.parseInt(this.fields.get(0).getText().trim()));
                        BBSSettings.videoSettings.height.set(
                                Integer.parseInt(this.fields.get(1).getText().trim()));
                        BBSSettings.videoSettings.frameRate.set(
                                Integer.parseInt(this.fields.get(2).getText().trim()));
                    }
                }
                case CAPTURING ->
                {
                    if (!this.fields.isEmpty())
                    {
                        this.config.captureDepthDistance = Double.parseDouble(this.fields.get(0).getText().trim());
                        this.config.save();
                    }
                }
                case ENGINE ->
                {
                    if (!this.fields.isEmpty())
                    {
                        this.config.setEngineSpeed(Double.parseDouble(this.fields.get(0).getText().trim()));
                    }
                }
                default ->
                {
                }
            }

            this.error = Text.empty();

            return true;
        }
        catch (NumberFormatException e)
        {
            this.error = Text.literal("Please enter valid numbers");

            return false;
        }
    }

    @Override
    public void close()
    {
        if (this.client != null)
        {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);

        for (int i = 0; i < this.fields.size(); i++)
        {
            TextFieldWidget field = this.fields.get(i);

            context.drawTextWithShadow(this.textRenderer, this.fieldLabels.get(i), field.getX(), field.getY() - 10, 0xA0A0A0);
        }

        if (!this.error.getString().isEmpty())
        {
            context.drawCenteredTextWithShadow(this.textRenderer, this.error, this.width / 2, this.height - 42, 0xFF5555);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    /** Simple label-with-current-value toggle button, since this addon doesn't pull in BBS mod's UI framework here (deliberately outside it). */
    private static final class UiToggle
    {
        static ButtonWidget of(int x, int y, int width, boolean value, String label, java.util.function.Consumer<Boolean> onChange)
        {
            boolean[] state = { value };

            return ButtonWidget.builder(text(label, state[0]), b ->
            {
                state[0] = !state[0];
                b.setMessage(text(label, state[0]));
                onChange.accept(state[0]);
            }).dimensions(x, y, width, 20).build();
        }

        private static Text text(String label, boolean value)
        {
            return Text.literal(label + ": " + (value ? "ON" : "OFF"));
        }
    }
}
