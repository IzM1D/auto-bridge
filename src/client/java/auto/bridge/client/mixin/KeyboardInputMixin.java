package auto.bridge.client.mixin;

import auto.bridge.client.AutoBridgeClient;
import auto.bridge.client.build.BuildModeManager;
import auto.bridge.client.build.MovementInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void autoBridge$applyCenteringInput(CallbackInfo info) {
		ClientInputAccessor inputAccessor = (ClientInputAccessor) this;
		Input keyPresses = inputAccessor.autoBridge$getKeyPresses();
		if (BuildModeManager.isActive()) {
			AutoBridgeClient.cancelCentering();
			applyAutomaticInput(inputAccessor, keyPresses,
				BuildModeManager.movementInput(keyPresses.forward(), keyPresses.backward()),
				BuildModeManager.shouldSneak());
			return;
		}

		// Manual movement normally means the player has taken over.  A build-mode key press
		// made while already walking is the exception: the alignment and centering it starts
		// must not be cancelled by the very keys the player is still holding, and the strafe
		// it injects replaces them until the block is lined up.
		if (!AutoBridgeClient.isPreparingBuildMode()
			&& (keyPresses.forward() || keyPresses.backward() || keyPresses.left() || keyPresses.right())) {
			AutoBridgeClient.cancelCentering();
			return;
		}

		MovementInput automaticInput = AutoBridgeClient.getAutomaticMovementInput();
		if (automaticInput.isIdle()) {
			return;
		}

		applyAutomaticInput(inputAccessor, keyPresses, automaticInput, false);
	}

	private static void applyAutomaticInput(ClientInputAccessor inputAccessor, Input originalInput,
			MovementInput automaticInput, boolean sneak) {
		Input keyPresses = new Input(
			automaticInput.forward(),
			automaticInput.backward(),
			automaticInput.left(),
			automaticInput.right(),
			false,
			sneak,
			false
		);

		float forward = automaticInput.forward() ? 1.0F : (automaticInput.backward() ? -1.0F : 0.0F);
		float sideways = automaticInput.left() ? 1.0F : (automaticInput.right() ? -1.0F : 0.0F);
		inputAccessor.autoBridge$setKeyPresses(keyPresses);
		inputAccessor.autoBridge$setMoveVector(new Vec2(sideways, forward).normalized());
	}
}
