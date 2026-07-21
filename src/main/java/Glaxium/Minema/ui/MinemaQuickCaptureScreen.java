package Glaxium.Minema.ui;

import Glaxium.Minema.MinemaConfig;
import Glaxium.Minema.RawCaptureModule;
import Glaxium.Minema.util.FolderOpener;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Shift+F4 -- a compact quick-settings screen, matching the standalone
 * BBS-Minema mod's own layout: Width/Height/FPS/Engine speed as plain,
 * always-editable fields (not hidden behind an enable/disable toggle),
 * followed by two mode buttons, then Start Recording / More Settings, then
 * Done.
 *
 * <p>Width, Height, and FPS are NOT this addon's own copy of anything --
 * they read and write {@code BBSSettings.videoSettings.width/height/frameRate}
 * directly, the exact same values BBS mod's own "Edit settings" panel
 * edits. That's the two-way sync: there is only one width/height/frameRate
 * value, this screen and BBS's own settings panel are just two different
 * places to edit it.
 *
 * <p>The capture-mode button is NOT a literal "turn my monitor fullscreen
 * on/off" switch -- it's a choice between two different things F4 does:
 * <b>Custom Resolution</b> (renders off-screen at the width/height above,
 * only actually takes effect while the game happens to already be
 * fullscreen) versus <b>Native Fullscreen</b> (captures the screen exactly
 * as it already looks, the original/simple behaviour, works windowed or
 * fullscreen). It never touches your actual window mode.
 */
public final class MinemaQuickCaptureScreen extends Screen
{
    private final Screen parent;
    private final MinemaConfig config = MinemaConfig.INSTANCE;

    private TextFieldWidget widthField;
    private TextFieldWidget heightField;
    private TextFieldWidget fpsField;
    private TextFieldWidget engineSpeedField;

    private Text status = Text.empty();

    public MinemaQuickCaptureScreen(Screen parent)
    {
        super(Text.literal("Quick Capture"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        int colWidth = 275;
        int leftX = this.width / 2 - colWidth - 5;
        int rightX = this.width / 2 + 5;
        int y = this.height / 2 - 160;

        this.widthField = addLabeledField(leftX, y, colWidth, "Width",
                Integer.toString(BBSSettings.videoSettings.width.get()));
        this.heightField = addLabeledField(rightX, y, colWidth, "Height",
                Integer.toString(BBSSettings.videoSettings.height.get()));

        this.fpsField = addLabeledField(leftX, y + 46, colWidth, "FPS",
                Integer.toString(BBSSettings.videoSettings.frameRate.get()));
        this.engineSpeedField = addLabeledField(rightX, y + 46, colWidth, "Engine speed",
                String.valueOf(this.config.engineSpeed));

        this.addDrawableChild(ButtonWidget.builder(captureModeText(), b ->
        {
            this.config.toggleCustomResolution();
            b.setMessage(captureModeText());
        }).dimensions(leftX, y + 92, colWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Open recordings folder"), b -> openVideoFolder())
                .dimensions(rightX, y + 92, colWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(recordText(), b -> toggleRecording())
                .dimensions(leftX, y + 122, colWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("More settings..."), b ->
        {
            if (applyFields() && this.client != null)
            {
                this.client.setScreen(new MinemaSettingsScreen(this.parent));
            }
        }).dimensions(rightX, y + 122, colWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b ->
        {
            if (applyFields())
            {
                this.close();
            }
        }).dimensions(this.width / 2 - colWidth / 2, y + 160, colWidth, 20).build());
    }

    private TextFieldWidget addLabeledField(int x, int y, int width, String label, String value)
    {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y + 12, width, 20, Text.literal(label));

        field.setText(value);
        field.setMaxLength(10);

        return this.addDrawableChild(field);
    }

    /** Parses and applies whatever's currently in the fields; returns false (and sets a status message) on invalid input, without discarding anything already saved elsewhere. */
    private boolean applyFields()
    {
        try
        {
            int width = Integer.parseInt(this.widthField.getText().trim());
            int height = Integer.parseInt(this.heightField.getText().trim());
            int fps = Integer.parseInt(this.fpsField.getText().trim());
            double engineSpeed = Double.parseDouble(this.engineSpeedField.getText().trim());

            // .set() clamps to each ValueInt's own declared min/max (2..8096
            // for width/height, 10..1000 for frameRate -- see
            // ValueVideoSettings), same as BBS's own settings panel would.
            BBSSettings.videoSettings.width.set(width);
            BBSSettings.videoSettings.height.set(height);
            BBSSettings.videoSettings.frameRate.set(fps);
            this.config.setEngineSpeed(engineSpeed);

            this.status = Text.empty();

            return true;
        }
        catch (NumberFormatException e)
        {
            this.status = Text.literal("Please enter valid numbers");

            return false;
        }
    }

    private void toggleRecording()
    {
        if (RawCaptureModule.INSTANCE.isRecording())
        {
            RawCaptureModule.INSTANCE.stop();
            this.status = Text.literal("Recording stopped");
            this.clearAndInit();
        }
        else if (applyFields())
        {
            RawCaptureModule.INSTANCE.start();
            this.status = Text.literal("Recording started");
            this.clearAndInit();
        }
    }

    private void openVideoFolder()
    {
        this.status = FolderOpener.open(BBSRendering.getVideoFolder().toPath())
                ? Text.empty()
                : Text.literal("Opening folders isn't supported on this system");
    }

    private Text captureModeText()
    {
        return Text.literal("Capture Mode: " + (this.config.customResolution ? "Custom Resolution" : "Native Fullscreen"));
    }

    private Text recordText()
    {
        return Text.literal(RawCaptureModule.INSTANCE.isRecording() ? "Stop Recording" : "Start Recording");
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
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 180, 0xFFFFFF);

        drawFieldLabel(context, this.widthField, "Width");
        drawFieldLabel(context, this.heightField, "Height");
        drawFieldLabel(context, this.fpsField, "FPS");
        drawFieldLabel(context, this.engineSpeedField, "Engine speed");

        if (this.config.customResolution)
        {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Custom Resolution only actually applies while the game is already fullscreen"),
                    this.width / 2, this.height / 2 + 66, 0x808080);
        }

        if (!this.status.getString().isEmpty())
        {
            context.drawCenteredTextWithShadow(this.textRenderer, this.status, this.width / 2, this.height / 2 + 80, 0x55FF55);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawFieldLabel(DrawContext context, TextFieldWidget field, String label)
    {
        context.drawTextWithShadow(this.textRenderer, label, field.getX(), field.getY() - 10, 0xA0A0A0);
    }
}
