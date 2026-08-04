package auto.bridge.client.mixin;

import auto.bridge.client.build.BuildModeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftInputLockMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void autoBridge$blockAttackWhileBuilding(CallbackInfoReturnable<Boolean> info) {
		if (BuildModeManager.shouldLockMouse()) {
			info.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void autoBridge$blockContinuousAttackWhileBuilding(boolean shouldContinue, CallbackInfo info) {
		if (BuildModeManager.shouldLockMouse()) {
			info.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void autoBridge$blockUseWhileBuilding(CallbackInfo info) {
		if (BuildModeManager.shouldLockMouse()) {
			info.cancel();
		}
	}

	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void autoBridge$blockHotbarKeys(CallbackInfo info) {
		if (BuildModeManager.isActive()) {
			Minecraft minecraft = (Minecraft) (Object) this;
			for (KeyMapping hotbarKey : minecraft.options.keyHotbarSlots) {
				while (hotbarKey.consumeClick()) {
					// Consume number-key hotbar changes before the vanilla handler sees them.
				}
			}
			while (minecraft.options.keyPickItem.consumeClick()) {
				// Block pick-block, which can also change the selected hotbar slot.
			}
			while (minecraft.options.keyDrop.consumeClick()) {
				// Do not let item drops interrupt construction.
			}
			while (minecraft.options.keySwapOffhand.consumeClick()) {
				// Keep the selected building item in the main hand.
			}
		}
	}
}
