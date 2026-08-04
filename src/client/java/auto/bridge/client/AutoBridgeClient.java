package auto.bridge.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class AutoBridgeClient implements ClientModInitializer {
	private static final double FINAL_SNAP_DISTANCE = 0.08D;
	private static final double PROGRESS_EPSILON = 0.0005D;
	private static final double SUPPORT_SEARCH_EPSILON = 0.0001D;
	// A dead zone for the camera-relative movement transform.  Only one axis is being
	// centered at a time, so the other component cancels out to a value that is zero only
	// up to rounding, and the input mixin turns any non-zero component into a key press.
	private static final double MOVEMENT_EPSILON = 1.0E-6D;
	private static final int MAX_CENTERING_TICKS = 40;
	private static final int MAX_STALLED_TICKS = 8;
	private static CenteringState centeringState;

	private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath("auto-bridge", "general")
	);
	private static final KeyMapping BUILD_MODE_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.auto-bridge.toggle_build_mode",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_UNKNOWN,
		KEY_CATEGORY
	));
	private static final KeyMapping SETTINGS_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.auto-bridge.open_settings",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_UNKNOWN,
		KEY_CATEGORY
	));

	// Set when the build-mode key is pressed: align and center first, then enable build mode
	// once the player has settled onto the block center.
	private static boolean pendingBuildModeEnable;
	private static boolean pendingYawAlignment;
	private static boolean pendingCenteringStart;
	private static float pendingCenteringYaw;

	@Override
	public void onInitializeClient() {
		AutoBridgeSettings.get();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (SETTINGS_KEY.consumeClick()) {
				if (!NoobBridgeController.isActive() && client.gui.screen() == null) {
					client.gui.setScreen(new AutoBridgeSettingsScreen());
				}
			}

			while (BUILD_MODE_KEY.consumeClick()) {
				if (client.gui.screen() != null) {
					continue;
				}
				if (NoobBridgeController.isActive()) {
					NoobBridgeController.toggle(client);
					cancelBuildModePreparation();
				} else if (pendingBuildModeEnable) {
					cancelBuildModePreparation();
				} else if (client.player != null) {
					// Pressing the key aligns and centers the player, then flips build mode on.
					startPreparation(client.player);
					pendingBuildModeEnable = true;
				}
			}

			if (pendingYawAlignment && client.player != null && CameraMotion.isSettled()) {
				pendingYawAlignment = false;
				if (pendingCenteringStart) {
					pendingCenteringStart = false;
					startCentering(client.player, pendingCenteringYaw);
				}
			}

			if (NoobBridgeController.isActive()) {
				NoobBridgeController.tick(client);
				return;
			}

			// Once centering has finished (or failed to start), enable build mode.
			if (pendingBuildModeEnable && !pendingYawAlignment && !pendingCenteringStart && centeringState == null) {
				pendingBuildModeEnable = false;
				NoobBridgeController.toggle(client);
			}
		});
	}

	private static void startPreparation(LocalPlayer player) {
		AutoBridgeSettings settings = AutoBridgeSettings.get();
		float alignedYaw = alignedYawFor(player.getYRot()) + settings.nextYawOffset();
		boolean diagonalBuild = isDiagonalYaw(alignedYaw);
		pendingCenteringYaw = alignedYaw;
		if (!settings.isSmoothCamera() || settings.getSmoothCameraSeconds() <= 0.0D) {
			CameraMotion.snapYaw(player, alignedYaw);
			if (!diagonalBuild) {
				startCentering(player, alignedYaw);
			}
			return;
		}

		CameraMotion.aimYaw(player, alignedYaw);
		pendingYawAlignment = true;
		pendingCenteringStart = !diagonalBuild;
	}

	private static float alignedYawFor(float yaw) {
		float normalizedYaw = (yaw % 360.0F + 360.0F) % 360.0F;
		float alignedYaw = Math.round(normalizedYaw / 45.0F) * 45.0F;

		if (alignedYaw == 360.0F) {
			alignedYaw = 0.0F;
		}

		return alignedYaw;
	}

	private static boolean isDiagonalYaw(float yaw) {
		int roundedYaw = Math.round(yaw / 45.0F) * 45;
		return Math.floorMod(roundedYaw, 90) != 0;
	}

	private static void startCentering(LocalPlayer player, float yaw) {
		int direction = Math.round(yaw / 45.0F) * 45;
		direction = (direction % 360 + 360) % 360;
		double targetX = Double.NaN;
		double targetZ = Double.NaN;
		BlockPos support = findSupportingFullBlock(player);
		if (support == null) {
			centeringState = null;
			return;
		}

		switch (direction) {
			case 0, 180 -> targetX = centerOfBlock(support.getX());
			case 90, 270 -> targetZ = centerOfBlock(support.getZ());
			case 45, 135, 225, 315 -> {
				targetX = centerOfBlock(support.getX());
				targetZ = centerOfBlock(support.getZ());
			}
			default -> {
				centeringState = null;
				return;
			}
		}

		double offset = AutoBridgeSettings.get().nextCenteringOffset();
		if (!Double.isNaN(targetX)) {
			targetX += offset;
		}
		if (!Double.isNaN(targetZ)) {
			targetZ += offset;
		}
		centeringState = new CenteringState(targetX, targetZ, direction, MAX_CENTERING_TICKS,
			Double.POSITIVE_INFINITY, 0);
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

		double yawRadians = Math.toRadians(centeringState.basisYaw());
		double sideways = xDifference * Math.cos(yawRadians) + zDifference * Math.sin(yawRadians);
		double forward = -xDifference * Math.sin(yawRadians) + zDifference * Math.cos(yawRadians);
		return new AutomaticInput(
			forward > MOVEMENT_EPSILON,
			forward < -MOVEMENT_EPSILON,
			sideways > MOVEMENT_EPSILON,
			sideways < -MOVEMENT_EPSILON
		);
	}

	/** True while a build-mode key press is still aligning and centering the player. */
	public static boolean isPreparingBuildMode() {
		return pendingBuildModeEnable;
	}

	public static void cancelCentering() {
		centeringState = null;
		cancelBuildModePreparation();
	}

	private static void cancelBuildModePreparation() {
		if (pendingBuildModeEnable) {
			// Only the preparation owns the camera; while build mode itself is running the
			// controller is the one steering it and must not be interrupted here.
			CameraMotion.release();
		}
		pendingBuildModeEnable = false;
		pendingYawAlignment = false;
		pendingCenteringStart = false;
	}

	private static boolean hasTargetX() {
		return centeringState != null && !Double.isNaN(centeringState.targetX());
	}

	private static boolean hasTargetZ() {
		return centeringState != null && !Double.isNaN(centeringState.targetZ());
	}

	/**
	 * Finds the full block that is actually supporting the player's footprint.
	 * This intentionally does not derive a block position from the player's
	 * center: on a one-block-wide bridge the center can already be above air.
	 */
	private static BlockPos findSupportingFullBlock(LocalPlayer player) {
		AABB bounds = player.getBoundingBox();
		int supportY = BlockPos.containing(player.getX(), player.getY() - SUPPORT_SEARCH_EPSILON, player.getZ()).getY();
		int minX = (int) Math.floor(bounds.minX + SUPPORT_SEARCH_EPSILON);
		int maxX = (int) Math.floor(bounds.maxX - SUPPORT_SEARCH_EPSILON);
		int minZ = (int) Math.floor(bounds.minZ + SUPPORT_SEARCH_EPSILON);
		int maxZ = (int) Math.floor(bounds.maxZ - SUPPORT_SEARCH_EPSILON);
		BlockPos closestSupport = null;
		double closestDistanceSquared = Double.POSITIVE_INFINITY;

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				BlockPos candidate = new BlockPos(x, supportY, z);
				if (!player.level().getBlockState(candidate).isCollisionShapeFullBlock(player.level(), candidate)) {
					continue;
				}

				double xDistance = centerOfBlock(x) - player.getX();
				double zDistance = centerOfBlock(z) - player.getZ();
				double distanceSquared = xDistance * xDistance + zDistance * zDistance;
				if (distanceSquared < closestDistanceSquared) {
					closestSupport = candidate;
					closestDistanceSquared = distanceSquared;
				}
			}
		}

		return closestSupport;
	}

	private static double centerOfBlock(int coordinate) {
		return coordinate + 0.5D;
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

	/**
	 * {@code basisYaw} is the exact aligned heading the target was derived from, not the
	 * camera's own yaw.  The configured human-like error is a fraction of a degree, but the
	 * movement keys are on-or-off, so measuring against the camera would raise a full
	 * strafe and drag the player diagonally instead of along the axis being centered.
	 */
	private record CenteringState(double targetX, double targetZ, float basisYaw, int remainingTicks,
			double previousDistance, int stalledTicks) {
		private CenteringState withProgress(int remainingTicks, double previousDistance, int stalledTicks) {
			return new CenteringState(targetX, targetZ, basisYaw, remainingTicks, previousDistance, stalledTicks);
		}
	}

	public record AutomaticInput(boolean forward, boolean backward, boolean left, boolean right) {
		public static final AutomaticInput NONE = new AutomaticInput(false, false, false, false);
	}
}
