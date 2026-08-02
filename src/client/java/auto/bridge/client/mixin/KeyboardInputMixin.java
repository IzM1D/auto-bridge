package auto.bridge.client.mixin;

import auto.bridge.client.AutoBridgeClient;
import auto.bridge.client.NoobBridgeController;
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
		if (NoobBridgeController.isActive()) {
			AutoBridgeClient.cancelCentering();
			applyAutomaticInput(inputAccessor, keyPresses, NoobBridgeController.getMovementInput(keyPresses.forward()),
				NoobBridgeController.shouldSneak());
			return;
		}

		if (keyPresses.forward() || keyPresses.backward() || keyPresses.left() || keyPresses.right()) {
			AutoBridgeClient.cancelCentering();
			return;
		}

		AutoBridgeClient.AutomaticInput automaticInput = AutoBridgeClient.getAutomaticMovementInput();
		if (automaticInput == AutoBridgeClient.AutomaticInput.NONE) {
			return;
		}

		applyAutomaticInput(inputAccessor, keyPresses, automaticInput, false);
	}

	private static void applyAutomaticInput(ClientInputAccessor inputAccessor, Input originalInput,
			AutoBridgeClient.AutomaticInput automaticInput, boolean sneak) {
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
