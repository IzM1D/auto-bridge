package auto.bridge.client.mixin;

import auto.bridge.client.AutoBridgeClient;
import auto.bridge.client.CameraMotion;
import auto.bridge.client.build.BuildModeManager;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void autoBridge$blockMouseButtons(long window, MouseButtonInfo button, int action, CallbackInfo info) {
		if (BuildModeManager.shouldLockMouse()) {
			info.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void autoBridge$blockHotbarScroll(long window, double horizontal, double vertical, CallbackInfo info) {
		if (BuildModeManager.isActive()) {
			info.cancel();
		}
	}

	@ModifyArgs(method = "turnPlayer", at = @At(
		value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"
	))
	private void autoBridge$lockCameraYawWhileBuilding(Args args) {
		// The lock starts with the key press, not with the mode: the alignment that follows
		// it would otherwise fight the mouse of a player who is still walking and looking
		// around.  Vertical look stays free throughout.
		if (BuildModeManager.isActive() || AutoBridgeClient.isPreparingBuildMode()) {
			args.set(0, 0.0D);
		}
	}

	/**
	 * The mod's own camera movement is advanced here, once per rendered frame and immediately
	 * after the game has applied the player's mouse for that frame.  This is the only place
	 * with both properties: vanilla turns the player from here too, so the mod's turns are
	 * sampled exactly as finely as a real mouse instead of twenty times a second.
	 */
	@Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
	private void autoBridge$advanceCameraMotion(CallbackInfo info) {
		CameraMotion.advance();
	}
}
