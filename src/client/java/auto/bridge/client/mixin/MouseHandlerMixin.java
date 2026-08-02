package auto.bridge.client.mixin;

import auto.bridge.client.NoobBridgeController;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void autoBridge$blockMouseButtons(long window, MouseButtonInfo button, int action, CallbackInfo info) {
		if (NoobBridgeController.shouldLockMouse()) {
			info.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void autoBridge$blockHotbarScroll(long window, double horizontal, double vertical, CallbackInfo info) {
		if (NoobBridgeController.isActive()) {
			info.cancel();
		}
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void autoBridge$lockCameraWhileBuilding(double deltaTime, CallbackInfo info) {
		if (NoobBridgeController.shouldLockMouse()) {
			info.cancel();
		}
	}
}
