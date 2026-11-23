package io.github.yannickspies.awe.mixin;

import io.github.yannickspies.awe.Awe;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A dedicated server with no players stops ticking after pause-when-empty-seconds.
 * The engine applies edits in ticks, so the server must stay awake while an agent works.
 */
@Mixin(DedicatedServer.class)
public class DedicatedServerMixin {
	@Inject(method = "pauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
	private void awe$stayAwakeForAgent(CallbackInfoReturnable<Integer> info) {
		if (Awe.isAgentActive()) {
			info.setReturnValue(0);
		}
	}
}
