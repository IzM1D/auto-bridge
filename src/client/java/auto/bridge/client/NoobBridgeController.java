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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Controls the straight-line, crouched "Noob bridge" building mode. */
public final class NoobBridgeController {
	private static final float PITCH_FOR_PLACEMENT = 77.35F;
	private static final double CENTER_TOLERANCE = 0.02D;
	private static final double EDGE_DISTANCE_FROM_BLOCK_EDGE = 0.285D;
	private static final int PLACE_CONFIRMATION_TICKS = 10;
	private static final int SUPPORT_SEARCH_DISTANCE = 1;

	private static boolean active;
	private static boolean forwardHeld;
	private static Direction buildDirection;
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

		buildDirection = cardinalDirectionForYaw(player.getYRot());
		if (buildDirection == null) {
			notify(player, "Noob bridge: cannot enable - face a cardinal direction", ChatFormatting.RED);
			return;
		}
		if (!isCenteredForDirection(player)) {
			notify(player, "Noob bridge: cannot enable - center the sideways coordinate first", ChatFormatting.RED);
			return;
		}
		if (!selectBuildBlock(player)) {
			notify(player, "Noob bridge: cannot enable - no blocks in the hotbar", ChatFormatting.RED);
			return;
		}

		active = true;
		forwardHeld = false;
		phase = Phase.READY;
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

		if (pendingTarget != null) {
			if (!client.level.getBlockState(pendingTarget).isAir()) {
				pendingTarget = null;
				pendingPlacementTicks = 0;
				phase = Phase.BACK_UP;
			} else if (--pendingPlacementTicks <= 0) {
				stopWithError(player, "Noob bridge: stopped - block placement was rejected");
				return;
			}
		}

		if (phase == Phase.WALK_TO_EDGE && isAtUnbridgedEdge(player)) {
			phase = Phase.PLACE_BLOCK;
		}
		if (phase == Phase.WALK_TO_EDGE && forwardHeld) {
			player.setYRot(yawForDirection(buildDirection));
			player.setXRot(0.0F);
		}

		if (phase == Phase.BACK_UP && isAtUnbridgedEdge(player)) {
			phase = Phase.PLACE_BLOCK;
		}

		if (phase == Phase.PLACE_BLOCK) {
			if (forwardHeld) {
				lockPlacementView(player);
				if (pendingTarget == null) {
					placeBlockAhead(client, player);
				}
			}
		}

		if (phase == Phase.BACK_UP && forwardHeld) {
			lockPlacementView(player);
		}
	}

	public static AutoBridgeClient.AutomaticInput getMovementInput(boolean isForwardHeld) {
		if (!active) {
			return AutoBridgeClient.AutomaticInput.NONE;
		}

		forwardHeld = isForwardHeld;
		if (!forwardHeld) {
			return AutoBridgeClient.AutomaticInput.NONE;
		}

		return switch (phase) {
			case READY -> {
				phase = Phase.WALK_TO_EDGE;
				yield new AutoBridgeClient.AutomaticInput(true, false, false, false);
			}
			case WALK_TO_EDGE -> new AutoBridgeClient.AutomaticInput(true, false, false, false);
			case PLACE_BLOCK -> AutoBridgeClient.AutomaticInput.NONE;
			case BACK_UP -> new AutoBridgeClient.AutomaticInput(false, true, false, false);
		};
	}

	public static boolean shouldSneak() {
		return active && (phase == Phase.PLACE_BLOCK || phase == Phase.BACK_UP);
	}

	private static void placeBlockAhead(Minecraft client, LocalPlayer player) {
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
		BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(support), buildDirection, support, false);
		client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
		player.swing(InteractionHand.MAIN_HAND);
		pendingTarget = target;
		pendingPlacementTicks = PLACE_CONFIRMATION_TICKS;
	}

	private static boolean isAtUnbridgedEdge(LocalPlayer player) {
		BlockPos support = findBridgeSupport(player);
		return support != null
			&& isAtForwardEdge(player, support)
			&& player.level().getBlockState(support.relative(buildDirection)).isAir();
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

	private static BlockPos feetBlock(LocalPlayer player) {
		return BlockPos.containing(player.getX(), player.getY() - 0.1D, player.getZ());
	}

	private static void lockPlacementView(LocalPlayer player) {
		player.setYRot(yawForDirection(buildDirection.getOpposite()));
		player.setXRot(PITCH_FOR_PLACEMENT);
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
		return Math.abs(coordinate - (Math.floor(coordinate) + 0.5D)) <= CENTER_TOLERANCE;
	}

	private static Direction cardinalDirectionForYaw(float yaw) {
		float normalizedYaw = (yaw % 360.0F + 360.0F) % 360.0F;
		int roundedYaw = Math.round(normalizedYaw);
		if (Math.abs(normalizedYaw - roundedYaw) > 1.0F) {
			return null;
		}

		return switch (roundedYaw % 360) {
			case 0 -> Direction.SOUTH;
			case 90 -> Direction.WEST;
			case 180 -> Direction.NORTH;
			case 270 -> Direction.EAST;
			default -> null;
		};
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
		buildDirection = null;
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
