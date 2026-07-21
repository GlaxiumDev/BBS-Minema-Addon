package Glaxium.Minema.mixin;

import Glaxium.Minema.SyncModule;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Server-thread half of the addon's SyncModule handshake -- see
 * {@link SyncModule} for the full explanation.
 *
 * <p>BBS mod's own IntegratedServerMixin already gates *how many* times
 * {@code tick()} gets called per rendered frame (matching the fixed-timestep
 * count RenderTickCounterMixin computes), but it does so by wrapping the
 * *call site* inside {@code IntegratedServer#tick()} with a free-running
 * catch-up loop -- nothing there stops the server thread from racing ahead
 * of the render thread while a frame is still being captured.
 *
 * <p>This mixin instead targets {@code tick()} itself, on {@code
 * MinecraftServer} rather than {@code IntegratedServer}, so it fires for
 * every individual tick regardless of which loop invoked it (including BBS
 * mod's own catch-up loop) -- no conflict with BBS mod's mixin, since they
 * target different methods.
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerTickSyncMixin
{
    @Inject(method = "tick", at = @At("HEAD"))
    private void bbsMinema$awaitPermission(BooleanSupplier shouldKeepTicking, CallbackInfo ci)
    {
        SyncModule.awaitPermissionToTick();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void bbsMinema$signalDone(BooleanSupplier shouldKeepTicking, CallbackInfo ci)
    {
        SyncModule.signalTickDone();
    }
}
