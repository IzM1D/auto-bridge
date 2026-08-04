package auto.bridge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Controls the straight-line, crouched "Noob bridge" building mode. */
public final class NoobBridgeController {
	private static final float PITCH_FOR_PLACEMENT = 82.0F;
	private static final float DIAGONAL_PITCH_FOR_PLACEMENT = 76.0F;
	private static final float REVERSE_BUILD_PITCH_TOLERANCE = 12.0F;
	private static final double CENTER_TOLERANCE = 0.02D;
	private static final double IMPRECISE_CENTER_TOLERANCE = 0.27D;
	private static final double EDGE_DISTANCE_FROM_BLOCK_EDGE = 0.20D;
	// The diagonal stance: standing on the corner of the support block, 0.7 along both
	// axes toward the travel direction.
	private static final double DIAGONAL_CORNER_OFFSET = 0.70D;
	private static final int PLACE_CONFIRMATION_TICKS = 10;
	private static final int SUPPORT_SEARCH_DISTANCE = 1;
	private static final double SUPPORT_SEARCH_EPSILON = 0.0001D;
	// A dead zone for the camera-relative movement transform.  An exact diagonal cancels
	// one axis out to a value that is only zero up to rounding, and the input mixin turns
	// any non-zero component into a full key press.
	private static final double MOVEMENT_EPSILON = 1.0E-6D;
	// Vanilla's sneak edge back-off has to already be pressed during the tick that would
	// carry the player off the support, so the walk-out crouches by braking distance
	// rather than by position: one full walking step, and more if the player is somehow
	// travelling faster than that.
	private static final double EDGE_BRAKE_DISTANCE = 0.30D;
	private static final double EDGE_BRAKE_SPEED_MARGIN = 0.05D;

	private static boolean active;
	private static boolean forwardHeld;
	private static boolean useBackwardKey;
	private static boolean diagonalMode;
	private static boolean edgeBrakeEngaged;
	private static Direction buildDirection;
	private static Direction diagonalRightDirection;
	private static Direction diagonalLeftDirection;
	private static int diagonalTravelYaw;
	private static float walkingBaseYaw;
	private static float walkingYaw;
	private static float placementBaseYaw;
	private static float placementBasePitch;
	private static float placementYaw;
	private static float placementPitch;
	private static Phase phase = Phase.READY;
	private static BlockPos pendingTarget;
	private static int pendingPlacementTicks;

	private NoobBridgeController() {
	}

	public static boolean isActive() {
		return active;
	}

	public static boolean shouldLockMouse() {
		return active && forwardHeld;
	}

	public static void toggle(Minecraft client) {
		if (active) {
			stop();
			notify(client.player, "Noob bridge: mode disabled", ChatFormatting.RED);
			return;
		}

		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		diagonalMode = false;
		diagonalRightDirection = null;
		diagonalLeftDirection = null;

		int diagonalYaw = diagonalYawFor(player.getYRot());
		if (diagonalYaw != -1) {
			diagonalMode = true;
			// Same two-step decision the straight branch makes: tentatively assume the
			// noob-bridge stance (bridge grows away from the view) so that the edge test
			// has a travel direction to work with, then commit to what it found.
			applyDiagonalTravel(diagonalYaw, true);
			boolean buildAwayFromView = isLookingAtSupportAtDiagonalCorner(player);
			applyDiagonalTravel(diagonalYaw, buildAwayFromView);
			useBackwardKey = buildAwayFromView;
			diagonalTravelYaw = buildAwayFromView ? (diagonalYaw + 180) % 360 : diagonalYaw;
		} else {
			Direction viewDirection = cardinalDirectionForYaw(player.getYRot());
			if (viewDirection == null) {
				notify(player, "Noob bridge: cannot enable - face a cardinal or diagonal direction", ChatFormatting.RED);
				return;
			}

			// At an edge, looking down at the supporting block is the usual noob-bridge
			// posture: build away from the view. Otherwise, build in the view direction.
			buildDirection = viewDirection.getOpposite();
			boolean buildAwayFromView = isLookingAtSupportAtUnbridgedEdge(player);
			buildDirection = buildAwayFromView ? viewDirection.getOpposite() : viewDirection;
			useBackwardKey = buildAwayFromView;
		}
		if (!diagonalMode && !isCenteredForDirection(player)) {
			notify(player, "Noob bridge: cannot enable - center the sideways coordinate first", ChatFormatting.RED);
			return;
		}
		if (!selectBuildBlock(player)) {
			notify(player, "Noob bridge: cannot enable - no blocks in the hotbar", ChatFormatting.RED);
			return;
		}
		AutoBridgeSettings settings = AutoBridgeSettings.get();
		// Both modes walk facing the travel direction and place facing back along it.  In
		// the diagonal reverse stance the placing yaw coincides with the view the player
		// already had, so the camera never turns at all.  The base yaw is kept apart from
		// the camera yaw: the human-like error belongs to the view only, never to the
		// geometry the movement and the placement faces are derived from.
		walkingBaseYaw = diagonalMode ? diagonalTravelYaw : yawForDirection(buildDirection);
		walkingYaw = diagonalMode ? walkingBaseYaw : walkingBaseYaw + settings.nextYawOffset();
		// The placement pose is fixed for the whole run in both geometries, and so is the
		// aiming error that goes with it: one precision action per run, re-rolled only when
		// the player nudges the camera off the pose themselves.
		placementBaseYaw = diagonalMode
			? (walkingBaseYaw + 180.0F) % 360.0F
			: yawForDirection(buildDirection.getOpposite());
		placementBasePitch = diagonalMode ? DIAGONAL_PITCH_FOR_PLACEMENT : PITCH_FOR_PLACEMENT;
		CameraMotion.release();
		choosePlacementViewOffset();

		active = true;
		forwardHeld = false;
		edgeBrakeEngaged = false;
		// Identical rule for both geometries: starting already at an unbridged edge means
		// no walk-out step is needed and the first trigger press can place immediately.
		phase = isAtUnbridgedEdge(player) ? Phase.PLACE_BLOCK : Phase.READY;
		notify(player, "Noob bridge: mode enabled", ChatFormatting.GREEN);
	}

	public static void tick(Minecraft client) {
		if (!active || client.player == null || client.level == null || client.gameMode == null) {
			if (active && client.player != null) {
				stopWithError(client.player, "Noob bridge: stopped - game state is unavailable");
				return;
			}
			stop();
			return;
		}

		LocalPlayer player = client.player;
		if (!selectBuildBlock(player)) {
			stopWithError(player, "Noob bridge: stopped - no blocks in the hotbar");
			return;
		}
		if (!forwardHeld) {
			lockYawForCurrentPhase(player);
		}

		if (pendingTarget != null) {
			if (!client.level.getBlockState(pendingTarget).isAir()) {
				BlockPos placedBlock = pendingTarget;
				pendingTarget = null;
				pendingPlacementTicks = 0;
				if (diagonalMode) {
					// Nothing to re-aim and nowhere to turn: keep the same stance and let
					// the continuing backward walk expose the next corner face.
					phase = Phase.PLACE_BLOCK;
				} else {
					phase = Phase.BACK_UP;
				}
			} else if (--pendingPlacementTicks <= 0) {
				stopWithError(player, "Noob bridge: stopped - block placement was rejected");
				return;
			}
		}

		if (phase == Phase.WALK_TO_EDGE && isAtUnbridgedEdge(player)) {
			phase = Phase.PLACE_BLOCK;
		}
		if (phase == Phase.WALK_TO_EDGE && forwardHeld) {
			lockWalkingView(player);
		}

		if (phase == Phase.BACK_UP && isAtUnbridgedEdge(player)) {
			phase = Phase.PLACE_BLOCK;
		}

		if (phase == Phase.PLACE_BLOCK) {
			if (forwardHeld) {
				if (advancePlacementView(player) && pendingTarget == null) {
					placeBlockAhead(client, player);
				}
			}
		}

		if (phase == Phase.BACK_UP && forwardHeld) {
			advancePlacementView(player);
		}
	}

	public static AutoBridgeClient.AutomaticInput getMovementInput(boolean isForwardKeyHeld, boolean isBackwardKeyHeld) {
		if (!active) {
			return AutoBridgeClient.AutomaticInput.NONE;
		}

		forwardHeld = useBackwardKey ? isBackwardKeyHeld : isForwardKeyHeld;
		if (!forwardHeld) {
			return AutoBridgeClient.AutomaticInput.NONE;
		}

		return switch (phase) {
			case READY -> {
				LocalPlayer player = Minecraft.getInstance().player;
				phase = Phase.WALK_TO_EDGE;
				lockWalkingView(player);
				yield walkInput(player);
			}
			case WALK_TO_EDGE -> {
				LocalPlayer player = Minecraft.getInstance().player;
				if (player != null && isAtUnbridgedEdge(player)) {
					phase = Phase.PLACE_BLOCK;
					yield AutoBridgeClient.AutomaticInput.NONE;
				}
				updateEdgeBrake(player);
				lockWalkingView(player);
				yield walkInput(player);
			}
			case PLACE_BLOCK -> {
				// Straight bridging holds still while it turns around and places.  The
				// diagonal stance is already correct, so it keeps travelling and lets
				// placement happen opportunistically.
				LocalPlayer player = Minecraft.getInstance().player;
				yield diagonalMode && CameraMotion.isSettled()
					? travelInput(player)
					: AutoBridgeClient.AutomaticInput.NONE;
			}
			case BACK_UP -> {
				LocalPlayer player = Minecraft.getInstance().player;
				yield CameraMotion.isSettled()
					? travelInput(player)
					: AutoBridgeClient.AutomaticInput.NONE;
			}
		};
	}

	/**
	 * The walk-out only starts once the camera has finished turning to face the travel
	 * direction.  Movement is resolved against the camera, so walking during the turn would
	 * curve the path - which on a one-block bridge means walking off the side of it.
	 */
	private static AutoBridgeClient.AutomaticInput walkInput(LocalPlayer player) {
		return CameraMotion.isSettled() ? travelInput(player) : AutoBridgeClient.AutomaticInput.NONE;
	}

	public static boolean shouldSneak() {
		// Once engaged the brake is never released: the player is hanging over the edge by
		// then, so un-crouching - including by letting go of the trigger key - drops them.
		return active && (phase == Phase.PLACE_BLOCK || phase == Phase.BACK_UP || edgeBrakeEngaged);
	}

	/**
	 * Latches the sneak brake once the player is within a step of running out of support.
	 * This is what turns the walk-out into an automatic stop: vanilla's edge back-off
	 * clamps a crouched player at the largest overhang that is still supported, which is
	 * the stance {@link #isAtUnbridgedEdge} then recognises.
	 */
	private static void updateEdgeBrake(LocalPlayer player) {
		if (edgeBrakeEngaged || player == null) {
			return;
		}

		BlockPos support = findBridgeSupport(player);
		if (support == null) {
			// Nothing underneath any more: crouching is already overdue.
			edgeBrakeEngaged = true;
			return;
		}

		int stepX;
		int stepZ;
		if (diagonalMode) {
			stepX = diagonalRightDirection.getStepX() + diagonalLeftDirection.getStepX();
			stepZ = diagonalRightDirection.getStepZ() + diagonalLeftDirection.getStepZ();
		} else {
			stepX = buildDirection.getStepX();
			stepZ = buildDirection.getStepZ();
		}

		Vec3 velocity = player.getDeltaMovement();
		double brakingDistance = Math.max(EDGE_BRAKE_DISTANCE,
			Math.hypot(velocity.x, velocity.z) + EDGE_BRAKE_SPEED_MARGIN);
		double halfWidth = player.getBbWidth() / 2.0D;
		edgeBrakeEngaged =
			distanceToUnsupported(player.getX(), support.getX(), stepX, halfWidth) <= brakingDistance
			|| distanceToUnsupported(player.getZ(), support.getZ(), stepZ, halfWidth) <= brakingDistance;
	}

	/**
	 * Distance the player may still travel along one axis before their hitbox stops
	 * overlapping the support block on that axis - the point at which they start falling.
	 * For a player standing on the block at Z=4 and walking towards Z-, that limit is
	 * Z = 4 - 0.3 = 3.7, so 3.7 itself already falls and 3.701 still stands.
	 */
	private static double distanceToUnsupported(double coordinate, int blockCoordinate, int step, double halfWidth) {
		if (step > 0) {
			return blockCoordinate + 1.0D + halfWidth - coordinate;
		}
		if (step < 0) {
			return coordinate - (blockCoordinate - halfWidth);
		}
		return Double.POSITIVE_INFINITY;
	}

	private static void placeBlockAhead(Minecraft client, LocalPlayer player) {
		if (diagonalMode) {
			placeDiagonalBlock(client, player);
			return;
		}

		BlockPos support = findBridgeSupport(player);
		if (support == null) {
			stopWithError(player, "Noob bridge: stopped - no supporting block");
			return;
		}
		BlockPos target = support.relative(buildDirection);
		if (!client.level.getBlockState(target).isAir()) {
			phase = Phase.BACK_UP;
			return;
		}
		if (!(client.hitResult instanceof BlockHitResult crosshairHit)
			|| !isValidPlacementCrosshair(crosshairHit, support)) {
			return;
		}

		client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, crosshairHit);
		player.swing(InteractionHand.MAIN_HAND);
		pendingTarget = target;
		pendingPlacementTicks = PLACE_CONFIRMATION_TICKS;
	}

	/**
	 * Diagonal placing.  The view is fixed on the chosen diagonal and the player walks
	 * backwards along it, so the exposed corner face alternates on its own.  Whichever
	 * of the two travel faces the crosshair actually reports is the one used - no
	 * right/left order is forced and the camera is never rotated to a cardinal yaw.
	 */
	private static void placeDiagonalBlock(Minecraft client, LocalPlayer player) {
		if (!(client.hitResult instanceof BlockHitResult crosshairHit)) {
			return;
		}

		Direction face = crosshairHit.getDirection();
		if (face != diagonalRightDirection && face != diagonalLeftDirection) {
			return;
		}
		BlockPos support = crosshairHit.getBlockPos();
		if (!isAtDiagonalCorner(player, support)) {
			return;
		}
		BlockPos target = support.relative(face);
		if (!client.level.getBlockState(target).isAir()) {
			return;
		}

		buildDirection = face;
		client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, crosshairHit);
		player.swing(InteractionHand.MAIN_HAND);
		pendingTarget = target;
		pendingPlacementTicks = PLACE_CONFIRMATION_TICKS;
	}

	/**
	 * The diagonal stance: the player is at or past the corner of the support block on
	 * both axes, roughly 0.7 along each one toward the travel direction.  Sneaking lets
	 * the hitbox hang over the corner, which is what exposes the two side faces to the
	 * crosshair.
	 */
	private static boolean isAtDiagonalCorner(LocalPlayer player, BlockPos support) {
		if (diagonalRightDirection == null || diagonalLeftDirection == null) {
			return false;
		}

		int stepX = diagonalRightDirection.getStepX() + diagonalLeftDirection.getStepX();
		int stepZ = diagonalRightDirection.getStepZ() + diagonalLeftDirection.getStepZ();
		return isPastCorner(player.getX() - support.getX(), stepX)
			&& isPastCorner(player.getZ() - support.getZ(), stepZ);
	}

	private static boolean isPastCorner(double fraction, int step) {
		return step > 0
			? fraction >= DIAGONAL_CORNER_OFFSET
			: fraction <= 1.0D - DIAGONAL_CORNER_OFFSET;
	}

	private static boolean isAtUnbridgedEdge(LocalPlayer player) {
		BlockPos support = findBridgeSupport(player);
		if (support == null) {
			return false;
		}
		if (diagonalMode) {
			// The diagonal equivalent of "standing at the forward edge with air ahead":
			// hanging over the corner with at least one of the two travel faces free.
			return isAtDiagonalCorner(player, support)
				&& (player.level().getBlockState(support.relative(diagonalRightDirection)).isAir()
					|| player.level().getBlockState(support.relative(diagonalLeftDirection)).isAir());
		}

		return isAtForwardEdge(player, support)
			&& player.level().getBlockState(support.relative(buildDirection)).isAir();
	}

	private static boolean isValidPlacementCrosshair(BlockHitResult hitResult, BlockPos support) {
		return hitResult.getBlockPos().equals(support) && hitResult.getDirection() == buildDirection;
	}

	private static boolean isAtForwardEdge(LocalPlayer player, BlockPos support) {
		double coordinate = buildDirection.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
		return switch (buildDirection) {
			case SOUTH -> coordinate >= support.getZ() + 1.0D + EDGE_DISTANCE_FROM_BLOCK_EDGE;
			case WEST -> coordinate <= support.getX() - EDGE_DISTANCE_FROM_BLOCK_EDGE;
			case NORTH -> coordinate <= support.getZ() - EDGE_DISTANCE_FROM_BLOCK_EDGE;
			case EAST -> coordinate >= support.getX() + 1.0D + EDGE_DISTANCE_FROM_BLOCK_EDGE;
			default -> false;
		};
	}

	private static BlockPos findBridgeSupport(LocalPlayer player) {
		BlockPos footprintSupport = findSupportingFullBlock(player);
		if (footprintSupport != null) {
			return footprintSupport;
		}

		BlockPos position = feetBlock(player);
		Direction backwards = buildDirection.getOpposite();
		for (int distance = 0; distance <= SUPPORT_SEARCH_DISTANCE; distance++) {
			BlockPos candidate = position.relative(backwards, distance);
			if (!player.level().getBlockState(candidate).isAir()) {
				return candidate;
			}
		}
		return null;
	}

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

				double xDistance = candidate.getX() + 0.5D - player.getX();
				double zDistance = candidate.getZ() + 0.5D - player.getZ();
				double distanceSquared = xDistance * xDistance + zDistance * zDistance;
				if (distanceSquared < closestDistanceSquared) {
					closestSupport = candidate;
					closestDistanceSquared = distanceSquared;
				}
			}
		}

		return closestSupport;
	}

	private static BlockPos feetBlock(LocalPlayer player) {
		return BlockPos.containing(player.getX(), player.getY() - 0.1D, player.getZ());
	}

	private static boolean advancePlacementView(LocalPlayer player) {
		if (player == null) {
			return false;
		}
		if (!updatePlacementViewTarget(player)) {
			return false;
		}
		if (CameraMotion.consumePlayerNudge()) {
			// The camera has been dragged off the pose, which can only be the player's own
			// doing: treat coming back as a fresh aim and give it a fresh error.
			choosePlacementViewOffset();
		}
		if (!AutoBridgeSettings.get().isSmoothCamera()) {
			CameraMotion.snap(player, placementYaw, placementPitch);
			return true;
		}

		CameraMotion.aim(player, placementYaw, placementPitch);
		return CameraMotion.isSettled();
	}

	private static void lockWalkingView(LocalPlayer player) {
		if (player == null) {
			return;
		}
		if (AutoBridgeSettings.get().isSmoothCamera()) {
			CameraMotion.aimYaw(player, walkingYaw);
		} else {
			CameraMotion.snapYaw(player, walkingYaw);
		}
	}

	/**
	 * Turns the world-space travel direction into camera-relative key presses, using the
	 * same transform the centering code applies.  One movement path then serves every
	 * stance - forward or backward, cardinal or diagonal - instead of hardcoding which
	 * key happens to be correct for a particular camera yaw.
	 */
	private static AutoBridgeClient.AutomaticInput travelInput(LocalPlayer player) {
		if (player == null) {
			return AutoBridgeClient.AutomaticInput.NONE;
		}

		double travelX;
		double travelZ;
		if (diagonalMode) {
			travelX = diagonalRightDirection.getStepX() + diagonalLeftDirection.getStepX();
			travelZ = diagonalRightDirection.getStepZ() + diagonalLeftDirection.getStepZ();
		} else {
			travelX = buildDirection.getStepX();
			travelZ = buildDirection.getStepZ();
		}

		double yawRadians = Math.toRadians(travelBaseYaw());
		double sideways = travelX * Math.cos(yawRadians) + travelZ * Math.sin(yawRadians);
		double forward = -travelX * Math.sin(yawRadians) + travelZ * Math.cos(yawRadians);
		return new AutoBridgeClient.AutomaticInput(
			forward > MOVEMENT_EPSILON,
			forward < -MOVEMENT_EPSILON,
			sideways > MOVEMENT_EPSILON,
			sideways < -MOVEMENT_EPSILON
		);
	}

	/**
	 * The exact yaw the current stance is built around, with the configured human-like
	 * error left out.  Movement keys are discrete, so the transform above has to be fed
	 * the ideal pose: measured against the camera's real yaw, even a tenth of a degree of
	 * imprecision raises a full strafe key and sends the player off at 45 degrees to the
	 * direction they are supposed to travel.  The error still reaches the camera, so the
	 * walk itself drifts by exactly that angle - which is the point of the setting.
	 */
	private static float travelBaseYaw() {
		return switch (phase) {
			case READY, WALK_TO_EDGE -> walkingBaseYaw;
			case PLACE_BLOCK, BACK_UP -> placementBaseYaw;
		};
	}

	/**
	 * Holds the camera on the yaw of the current phase while the trigger key is not held.  The
	 * aim is only re-stated, never forced: letting go halfway through a turn now lets that turn
	 * finish on its own instead of teleporting the camera to the end of it.
	 */
	private static void lockYawForCurrentPhase(LocalPlayer player) {
		if (player == null) {
			return;
		}

		float targetYaw = switch (phase) {
			case READY, WALK_TO_EDGE -> walkingYaw;
			case PLACE_BLOCK, BACK_UP -> placementYaw;
		};
		if (AutoBridgeSettings.get().isSmoothCamera()) {
			CameraMotion.aimYaw(player, targetYaw);
		} else {
			CameraMotion.snapYaw(player, targetYaw);
		}
	}

	private static boolean isLookingAtSupportAtUnbridgedEdge(LocalPlayer player) {
		if (Math.abs(player.getXRot() - PITCH_FOR_PLACEMENT) > REVERSE_BUILD_PITCH_TOLERANCE
			|| !(Minecraft.getInstance().hitResult instanceof BlockHitResult hitResult)) {
			return false;
		}

		BlockPos support = findBridgeSupport(player);
		return support != null && hitResult.getBlockPos().equals(support) && isAtUnbridgedEdge(player);
	}

	/**
	 * The diagonal counterpart of {@link #isLookingAtSupportAtUnbridgedEdge}: the player
	 * already hangs over the corner of the support looking down at it, which means the
	 * bridge should grow away from the view and the backward key becomes the trigger.
	 */
	private static boolean isLookingAtSupportAtDiagonalCorner(LocalPlayer player) {
		if (Math.abs(player.getXRot() - DIAGONAL_PITCH_FOR_PLACEMENT) > REVERSE_BUILD_PITCH_TOLERANCE
			|| !(Minecraft.getInstance().hitResult instanceof BlockHitResult hitResult)) {
			return false;
		}

		BlockPos support = findBridgeSupport(player);
		return support != null && hitResult.getBlockPos().equals(support) && isAtUnbridgedEdge(player);
	}

	/**
	 * Sets the two cardinal components blocks get placed against.  They are the
	 * components of the travel direction, which is the reverse of the view in the
	 * noob-bridge stance and the view itself otherwise.
	 */
	private static void applyDiagonalTravel(int facingYaw, boolean buildAwayFromView) {
		Direction right = cardinalDirectionForExactYaw((facingYaw + 45) % 360);
		Direction left = cardinalDirectionForExactYaw((facingYaw + 315) % 360);
		diagonalRightDirection = buildAwayFromView ? right.getOpposite() : right;
		diagonalLeftDirection = buildAwayFromView ? left.getOpposite() : left;
		buildDirection = diagonalRightDirection;
	}

	private static void choosePlacementViewOffset() {
		AutoBridgeSettings settings = AutoBridgeSettings.get();
		placementYaw = placementBaseYaw + settings.nextYawOffset();
		placementPitch = placementBasePitch + settings.nextYawOffset();
	}

	/**
	 * Confirms the stance still has something to aim at.  The pose itself is settled once,
	 * when the mode is enabled: re-deriving it per placed block would also re-roll the
	 * aiming error per block, and a single error held for the whole run is what makes the
	 * camera look like one deliberate human aim rather than a twitch on every block.
	 */
	private static boolean updatePlacementViewTarget(LocalPlayer player) {
		return diagonalMode || findBridgeSupport(player) != null;
	}

	private static boolean selectBuildBlock(LocalPlayer player) {
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
				player.getInventory().setSelectedSlot(slot);
				return true;
			}
		}
		return false;
	}

	private static boolean isCenteredForDirection(LocalPlayer player) {
		Direction direction = cardinalDirectionForYaw(player.getYRot());
		if (direction == null) {
			return false;
		}

		double coordinate = direction.getAxis() == Direction.Axis.X ? player.getZ() : player.getX();
		double tolerance = AutoBridgeSettings.get().hasImprecisionEnabled()
			? IMPRECISE_CENTER_TOLERANCE
			: CENTER_TOLERANCE;
		return Math.abs(coordinate - (Math.floor(coordinate) + 0.5D)) <= tolerance;
	}

	private static Direction cardinalDirectionForYaw(float yaw) {
		float normalizedYaw = (yaw % 360.0F + 360.0F) % 360.0F;
		int roundedYaw = Math.round(normalizedYaw / 90.0F) * 90;
		float difference = Math.abs((normalizedYaw - roundedYaw + 180.0F) % 360.0F - 180.0F);
		if (difference > 8.0F) {
			return null;
		}

		return cardinalDirectionForExactYaw(roundedYaw % 360);
	}

	private static Direction cardinalDirectionForExactYaw(int yaw) {
		return switch (yaw % 360) {
			case 0 -> Direction.SOUTH;
			case 90 -> Direction.WEST;
			case 180 -> Direction.NORTH;
			case 270 -> Direction.EAST;
			default -> null;
		};
	}

	private static int diagonalYawFor(float yaw) {
		float normalizedYaw = (yaw % 360.0F + 360.0F) % 360.0F;
		int roundedYaw = Math.round(normalizedYaw / 45.0F) * 45;
		float difference = Math.abs((normalizedYaw - roundedYaw + 180.0F) % 360.0F - 180.0F);
		if (difference > 8.0F || roundedYaw % 90 == 0) {
			return -1;
		}
		return roundedYaw % 360;
	}

	private static float yawForDirection(Direction direction) {
		return switch (direction) {
			case SOUTH -> 0.0F;
			case WEST -> 90.0F;
			case NORTH -> 180.0F;
			case EAST -> 270.0F;
			default -> 0.0F;
		};
	}

	private static void stop() {
		active = false;
		forwardHeld = false;
		useBackwardKey = false;
		diagonalMode = false;
		edgeBrakeEngaged = false;
		buildDirection = null;
		diagonalRightDirection = null;
		diagonalLeftDirection = null;
		diagonalTravelYaw = 0;
		walkingBaseYaw = 0.0F;
		walkingYaw = 0.0F;
		placementBaseYaw = 0.0F;
		placementBasePitch = 0.0F;
		placementYaw = 0.0F;
		placementPitch = 0.0F;
		CameraMotion.release();
		phase = Phase.READY;
		pendingTarget = null;
		pendingPlacementTicks = 0;
	}

	private static void stopWithError(LocalPlayer player, String message) {
		stop();
		notify(player, message, ChatFormatting.RED);
	}

	private static void notify(LocalPlayer player, String message, ChatFormatting color) {
		if (player != null) {
			Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.literal(message).withStyle(color), false);
		}
	}

	private enum Phase {
		READY,
		WALK_TO_EDGE,
		PLACE_BLOCK,
		BACK_UP
	}
}
