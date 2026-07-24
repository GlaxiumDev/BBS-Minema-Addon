package Glaxium.Minema.mixin;

import Glaxium.Minema.RawCaptureModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defends against BBS mod's own {@code UIPickableFormRenderer#renderUserModel} (the Morph
 * menu / form-picker viewport's click-to-select-body-part logic) leaving the wrong framebuffer
 * bound. That method binds a small offscreen "stencil picking" framebuffer
 * ({@code this.stencil.apply()}), renders the model into it with unique per-part colors so it can
 * read back which part is under the cursor, then rebinds the real on-screen framebuffer at the
 * very end ({@code MinecraftClient#getFramebuffer()#beginWrite(true)}) -- with no try/finally
 * around any of it. If that rebind doesn't run for any reason (an exception partway through the
 * stencil pass, or a driver/GL-level failure that doesn't throw a catchable Java exception),
 * every draw call for the rest of that frame -- and every frame after, since nothing else
 * automatically rebinds the main framebuffer once something else has claimed it -- silently
 * targets that small offscreen texture instead of the real screen. Visually that looks exactly
 * like what gets reported: the screen appears frozen/"ghosted" on whatever was last actually
 * presented, with new UI (e.g. going back to the Film editor) invisibly rendering into a buffer
 * nobody ever sees.
 *
 * <p>Rather than trying to patch that one BBS mod method's internals (which would need
 * MixinExtras' {@code @WrapMethod} for proper try/finally semantics, not currently a build
 * dependency here), this takes the simpler, strictly more robust approach: unconditionally force
 * the correct on-screen framebuffer to be bound at the very start of every single frame, before
 * anything else has a chance to render into whatever was left bound by the previous frame. This
 * closes off the entire class of "some widget forgot to rebind the main framebuffer" bugs, not
 * just this one BBS mod code path -- including, symmetrically, protecting against any future
 * mistake in this addon's own code doing the same thing.
 *
 * <p>Skipped while this addon's own custom-resolution F4 recording is actually running (see
 * {@link RawCaptureModule#getCaptureFramebuffer()}) -- that feature deliberately keeps
 * {@code MinecraftClient#framebuffer} pointed at its own dedicated capture-sized framebuffer for
 * the whole recording, and this must not fight that by force-rebinding the real window
 * framebuffer out from under it every frame.
 */
@Mixin(MinecraftClient.class)
public abstract class FramebufferLeakGuardMixin
{
    @Inject(method = "render", at = @At("HEAD"))
    private void bbsMinema$guardMainFramebuffer(boolean tick, CallbackInfo ci)
    {
        if (RawCaptureModule.INSTANCE.getCaptureFramebuffer() != null)
        {
            return;
        }

        MinecraftClient client = (MinecraftClient) (Object) this;
        Framebuffer framebuffer = client.getFramebuffer();

        if (framebuffer != null)
        {
            framebuffer.beginWrite(true);
        }
    }
}
