package Glaxium.Minema.mixin;

import mchorse.bbs_mod.BBSModClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Neutralizes BBS mod's own F4 ("Record Video") keybinding so it can never
 * start BBS mod's own recording pipeline again -- that pipeline is what
 * swaps MinecraftClient's framebuffer out for a private world-only one for
 * part of every frame (see BBSRendering#toggleFramebuffer / canReplaceFramebuffer
 * in bbs-mod), which is both the reason a plain screenshot-style "record
 * everything" capture wasn't possible before, and the root cause behind
 * inventory/settings/BBS's own UI intermittently failing to render while
 * F4 recording was active.
 *
 * BBS-Minema now owns F4 completely (see BBSMinema#recordKey /
 * RawCaptureModule) and reads the real, final, already-composited
 * framebuffer instead, so none of that swapping ever needs to happen.
 *
 * Scoped to the *specific* KeyBinding instance (BBSModClient.getKeyRecordVideo())
 * via an identity check, rather than mixing into the lambda that handles it
 * or nuking VideoRecorder entirely -- BBS mod's own video export (e.g. from
 * the film editor's "export" button, which doesn't go through this
 * keybinding at all) is left completely intact.
 */
@Mixin(KeyBinding.class)
public class DisableBBSVideoKeyMixin
{
    @Inject(method = "wasPressed", at = @At("HEAD"), cancellable = true)
    private void bbsMinema$disableF4(CallbackInfoReturnable<Boolean> info)
    {
        KeyBinding self = (KeyBinding) (Object) this;

        // getKeyRecordVideo() is null for the brief window before BBS mod's
        // own onInitializeClient has registered it -- wasPressed() can't
        // meaningfully be called that early anyway, but guard it regardless.
        KeyBinding bbsRecordKey = BBSModClient.getKeyRecordVideo();

        if (bbsRecordKey != null && self == bbsRecordKey)
        {
            info.setReturnValue(false);
            info.cancel();
        }
    }
}
