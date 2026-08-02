package auto.bridge.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class AutoBridgeClient implements ClientModInitializer {
	private static final double FINAL_SNAP_DISTANCE = 0.08D;
	private static final double PROGRESS_EPSILON = 0.0005D;
	private static final int MAX_CENTERING_TICKS = 40;
	private static final int MAX_STALLED_TICKS = 8;
	private static CenteringState centeringState;

	private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath("auto-bridge", "general")
	);
	private static final KeyMapping ALIGN_YAW_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.auto-bridge.align_yaw",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_UNKNOWN,
		KEY_CATEGORY
	));
	private static final KeyMapping BUILD_MODE_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.auto-bridge.toggle_build_mode",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_UNKNOWN,
		KEY_CATEGORY
	));

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (BUILD_MODE_KEY.consumeClick()) {
				NoobBridgeController.toggle(client);
			}

			if (NoobBridgeController.isActive()) {
				while (ALIGN_YAW_KEY.consumeClick()) {
					// Discard alignment hotkey presses while build mode owns the controls.
				}
				NoobBridgeController.tick(client);
				return;
			}

			while (ALIGN_YAW_KEY.consumeClick()) {
				if (client.player != null) {
					float alignedYaw = alignYawToCardinalDirection(client.player);
					startCentering(client.player, alignedYaw);
				}
			}
		});
	}

	private static float alignYawToCardinalDirection(LocalPlayer player) {
		float normalizedYaw = (player.getYRot() % 360.0F + 360.0F) % 360.0F;
		float alignedYaw = Math.round(normalizedYaw / 45.0F) * 45.0F;

		if (alignedYaw == 360.0F) {
			alignedYaw = 0.0F;
		}

		// Align to the nearest cardinal or intercardinal direction (multiples of 45 degrees).
		player.setYRot(alignedYaw);
		return alignedYaw;
	}

	private static void startCentering(LocalPlayer player, float yaw) {
		int direction = Math.round(yaw) % 360;
		double targetX = Double.NaN;
		double targetZ = Double.NaN;

		switch (direction) {
			case 0, 180 -> targetX = centerOfNearestBlock(player.getX());
			case 90, 270 -> targetZ = centerOfNearestBlock(player.getZ());
			case 45, 135, 225, 315 -> {
				targetX = centerOfNearestBlock(player.getX());
				targetZ = centerOfNearestBlock(player.getZ());
			}
			default -> {
				centeringState = null;
				return;
			}
		}

		centeringState = new CenteringState(targetX, targetZ, MAX_CENTERING_TICKS, Double.POSITIVE_INFINITY, 0);
	}

	/**
	 * Returns the automatic movement input for the current tick.
	 * Called by {@code KeyboardInputMixin} after Minecraft has read the player's physical controls.
	 */
	public static AutomaticInput getAutomaticMovementInput() {
		if (centeringState == null) {
			return AutomaticInput.NONE;
		}

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || centeringState.remainingTicks() <= 0) {
			centeringState = null;
			return AutomaticInput.NONE;
		}

		double xDifference = hasTargetX() ? centeringState.targetX() - player.getX() : 0.0D;
		double zDifference = hasTargetZ() ? centeringState.targetZ() - player.getZ() : 0.0D;
		boolean xCentered = !hasTargetX() || Math.abs(xDifference) <= FINAL_SNAP_DISTANCE;
		boolean zCentered = !hasTargetZ() || Math.abs(zDifference) <= FINAL_SNAP_DISTANCE;
		if (xCentered && zCentered) {
			finishCentering(player, centeringState);
			centeringState = null;
			return AutomaticInput.NONE;
		}

		double distance = Math.hypot(xDifference, zDifference);
		int stalledTicks = distance + PROGRESS_EPSILON >= centeringState.previousDistance()
			? centeringState.stalledTicks() + 1
			: 0;
		if (stalledTicks >= MAX_STALLED_TICKS) {
			centeringState = null;
			return AutomaticInput.NONE;
		}

		centeringState = centeringState.withProgress(centeringState.remainingTicks() - 1, distance, stalledTicks);
		if (xCentered) {
			xDifference = 0.0D;
		}
		if (zCentered) {
			zDifference = 0.0D;
		}

		double yawRadians = Math.toRadians(player.getYRot());
		double sideways = xDifference * Math.cos(yawRadians) + zDifference * Math.sin(yawRadians);
		double forward = -xDifference * Math.sin(yawRadians) + zDifference * Math.cos(yawRadians);
		return new AutomaticInput(forward > 0.0D, forward < 0.0D, sideways > 0.0D, sideways < 0.0D);
	}

	public static void cancelCentering() {
		centeringState = null;
	}

	private static boolean hasTargetX() {
		return centeringState != null && !Double.isNaN(centeringState.targetX());
	}

	private static boolean hasTargetZ() {
		return centeringState != null && !Double.isNaN(centeringState.targetZ());
	}

	private static double centerOfNearestBlock(double coordinate) {
		return Math.floor(coordinate) + 0.5D;
	}

	private static void finishCentering(LocalPlayer player, CenteringState state) {
		double centeredX = Double.isNaN(state.targetX()) ? player.getX() : state.targetX();
		double centeredZ = Double.isNaN(state.targetZ()) ? player.getZ() : state.targetZ();
		player.setPos(centeredX, player.getY(), centeredZ);

		Vec3 velocity = player.getDeltaMovement();
		player.setDeltaMovement(new Vec3(
			Double.isNaN(state.targetX()) ? velocity.x : 0.0D,
			velocity.y,
			Double.isNaN(state.targetZ()) ? velocity.z : 0.0D
		));
	}

	private record CenteringState(double targetX, double targetZ, int remainingTicks, double previousDistance,
			int stalledTicks) {
		private CenteringState withProgress(int remainingTicks, double previousDistance, int stalledTicks) {
			return new CenteringState(targetX, targetZ, remainingTicks, previousDistance, stalledTicks);
		}
	}

	public record AutomaticInput(boolean forward, boolean backward, boolean left, boolean right) {
		public static final AutomaticInput NONE = new AutomaticInput(false, false, false, false);
	}
}
