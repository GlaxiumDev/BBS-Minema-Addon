package Glaxium.Minema.mixin;

import mchorse.bbs_mod.BBSModClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.stream.Stream;

/**
 * DisableBBSVideoKeyMixin already stops BBS mod's own F4 keybinding from
 * doing anything, but it was still showing up as a rebindable, functionless
 * "Record Video" entry under Controls -- confusing next to BBS-Minema's own
 * real F4/"Minema Settings" entries. This strips it out of
 * GameOptions#allKeys entirely, right after Fabric API has finished
 * appending every mod's registered keybindings (including this addon's) to
 * it, so it never shows up in the Controls list or gets saved/loaded from
 * options.txt at all.
 *
 * This only touches the display/persistence array -- BBS mod's own low-level
 * key-press bookkeeping (which doesn't come from this array) is untouched,
 * DisableBBSVideoKeyMixin is what actually makes the key inert.
 */
@Mixin(GameOptions.class)
public class HideBBSVideoKeybindMixin
{
    @Mutable
    @Shadow
    public KeyBinding[] allKeys;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsMinema$hideDisabledKeybind(CallbackInfo ci)
    {
        KeyBinding bbsRecordKey = BBSModClient.getKeyRecordVideo();

        if (bbsRecordKey == null || this.allKeys == null)
        {
            return;
        }

        this.allKeys = Stream.of(this.allKeys)
                .filter((key) -> key != bbsRecordKey)
                .toArray(KeyBinding[]::new);
    }
}
