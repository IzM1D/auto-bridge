package auto.bridge.client;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The mod's hand on the mouse: every automatic camera movement goes through here.
 *
 * <p>Two things make it read as a mouse rather than as an animation.  It advances once per
 * rendered frame instead of once per tick - a tick-based ease can only ever produce twenty
 * corners a second, and those corners are the stepping.  And it is a follower rather than a
 * timeline: the aim may move or be disturbed at any moment and the motion continues from the
 * speed it already carries, so nothing ever restarts from a standstill.
 *
 * <p>The follower is a damped second-order filter, integrated semi-implicitly so that it stays
 * stable however long a frame took.  Left alone it eases in, peaks in the middle and settles
 * softly, which is the shape of a real aimed mouse movement.  The imprecision setting then
 * makes that shape uneven: the speed wanders while the turn is under way, no two turns take
 * quite the same time, and the filter is allowed to carry a little past the mark and come back.
 */
public final class CameraMotion {
	/** Solves {@code (1 + x)e^-x = 0.01}: the filter covers 99% of a turn in the set time. */
	private static final double SETTLE_RATE = 6.64D;
	/** A turn this wide gets the whole configured time; shorter ones are quicker, as a hand is. */
	private static final double REFERENCE_TURN_DEGREES = 90.0D;
	private static final double MIN_TIME_FRACTION = 0.45D;
	private static final double MIN_TURN_SECONDS = 0.05D;
	private static final double MAX_FRAME_SECONDS = 0.1D;
	private static final double MAX_TIME_SPREAD = 0.30D;
	private static final double MAX_OVERSHOOT = 0.28D;
	private static final double MAX_WOBBLE = 0.55D;
	/** A turn is over long before this; the cap only exists so nothing can wait on it forever. */
	private static final double TURN_TIMEOUT_FACTOR = 4.0D;
	/** The pitch reads the speed noise slightly later than the yaw, so the path bends. */
	private static final double PITCH_WOBBLE_LAG = 0.11D;
	private static final float SETTLE_DEGREES = 0.5F;
	private static final float SETTLE_DEGREES_PER_SECOND = 20.0F;
	private static final float RETARGET_EPSILON = 0.01F;
	/** Any rotation the mod did not write itself came from the player's own mouse. */
	private static final float PLAYER_INPUT_EPSILON = 0.001F;
	/** How far the player has to drag the camera before it counts as taking aim themselves. */
	private static final float PLAYER_NUDGE_DEGREES = 2.0F;

	private static boolean driving;
	private static boolean pitchDriven;
	private static float targetYaw;
	private static float targetPitch;
	private static double yawSpeed;
	private static double pitchSpeed;
	private static double baseOmega;
	private static double timeoutSeconds;
	private static double damping = 1.0D;
	private static double wobbleAmplitude;
	private static double wobbleFrequency;
	private static double tremorFrequency;
	private static double wobblePhase;
	private static double tremorPhase;
	private static double elapsedSeconds;
	private static long lastFrameNanos;
	private static boolean tracking;
	private static float writtenYaw;
	private static float writtenPitch;
	private static float playerDrift;
	private static boolean playerNudged;

	private CameraMotion() {
	}

	/** Aims at a whole pose, driving the pitch as well as the yaw. */
	public static void aim(LocalPlayer player, float yaw, float pitch) {
		retarget(player, yaw, pitch, true);
	}

	/** Aims the yaw only and leaves the pitch to the player. */
	public static void aimYaw(LocalPlayer player, float yaw) {
		retarget(player, yaw, 0.0F, false);
	}

	/** Puts the camera straight onto the pose, the way the mod behaves with smoothing off. */
	public static void snap(LocalPlayer player, float yaw, float pitch) {
		aim(player, yaw, pitch);
		finish(player);
	}

	public static void snapYaw(LocalPlayer player, float yaw) {
		aimYaw(player, yaw);
		finish(player);
	}

	/** Stops driving the camera and leaves it wherever it currently is. */
	public static void release() {
		driving = false;
		tracking = false;
		yawSpeed = 0.0D;
		pitchSpeed = 0.0D;
		playerDrift = 0.0F;
		playerNudged = false;
	}

	/** True when no turn is under way - the camera is on the aim and holding still. */
	public static boolean isSettled() {
		return !driving;
	}

	/**
	 * Reports, once per occurrence, that the player has dragged the camera off the mod's aim
	 * by a deliberate amount.  This is measured against what the mod itself last wrote, so a
	 * single mouse count is not mistaken for a new aim and the mod's own turns never count.
	 */
	public static boolean consumePlayerNudge() {
		boolean nudged = playerNudged;
		playerNudged = false;
		return nudged;
	}

	/**
	 * Advances the turn by one rendered frame.  Called from {@code MouseHandlerMixin} right
	 * after the game has applied the player's own mouse movement for this frame, which is both
	 * the finest sampling rate available and the exact point vanilla itself turns the player.
	 */
	public static void advance() {
		long now = System.nanoTime();
		double frameSeconds = (now - lastFrameNanos) / 1.0E9D;
		lastFrameNanos = now;

		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.isPaused()) {
			return;
		}
		if (!tracking) {
			// First frame of a run: adopt the current pose as "ours" so that the rotation the
			// player was already holding is not read as a nudge.
			writtenYaw = player.getYRot();
			writtenPitch = player.getXRot();
			tracking = true;
			return;
		}

		trackPlayerInput(player);
		if (!driving || frameSeconds <= 0.0D) {
			return;
		}

		double step = Math.min(frameSeconds, MAX_FRAME_SECONDS);
		elapsedSeconds += step;
		advanceTurn(player, step);
	}

	private static void retarget(LocalPlayer player, float yaw, float pitch, boolean drivePitch) {
		if (player == null) {
			return;
		}

		boolean unchanged = driving
			&& pitchDriven == drivePitch
			&& Math.abs(shortestDelta(targetYaw, yaw)) <= RETARGET_EPSILON
			&& (!drivePitch || Math.abs(targetPitch - pitch) <= RETARGET_EPSILON);
		targetYaw = yaw;
		targetPitch = pitch;
		pitchDriven = drivePitch;
		if (unchanged) {
			// The turn already under way is heading exactly here: leave it alone.
			return;
		}
		if (!tracking) {
			writtenYaw = player.getYRot();
			writtenPitch = player.getXRot();
			tracking = true;
		}
		if (distanceToTarget(player) <= SETTLE_DEGREES) {
			driving = false;
			return;
		}

		driving = true;
		beginTurn(player);
	}

	/**
	 * Settles the character of one turn.  Only the shape is drawn here - the speed the camera
	 * already carries is deliberately left untouched, which is what lets an aim change halfway
	 * through a turn continue as one movement instead of stopping and starting again.
	 */
	private static void beginTurn(LocalPlayer player) {
		AutoBridgeSettings settings = AutoBridgeSettings.get();
		double seconds = Math.max(MIN_TURN_SECONDS, settings.getSmoothCameraSeconds());
		// A hand crosses a small gap faster than a wide one rather than spending the same time
		// creeping through both, so the configured time belongs to a wide turn.
		double reach = Math.min(1.0D, distanceToTarget(player) / REFERENCE_TURN_DEGREES);
		seconds *= MIN_TIME_FRACTION + (1.0D - MIN_TIME_FRACTION) * reach;

		double unevenness = settings.nextCameraUnevenness();
		// No two movements of a hand take the same time, so the configured duration becomes a
		// centre rather than a figure, and the speed within the turn stops being constant.
		seconds *= 1.0D + unevenness * randomBetween(-MAX_TIME_SPREAD, MAX_TIME_SPREAD);
		baseOmega = SETTLE_RATE / Math.max(MIN_TURN_SECONDS, seconds);
		timeoutSeconds = TURN_TIMEOUT_FACTOR * Math.max(MIN_TURN_SECONDS, seconds);
		// Below one the filter carries slightly past the aim and comes back - the small
		// correction a player makes when the mouse overshoots the mark.
		damping = 1.0D - MAX_OVERSHOOT * unevenness;
		wobbleAmplitude = MAX_WOBBLE * unevenness;
		wobbleFrequency = randomBetween(9.0D, 26.0D);
		tremorFrequency = randomBetween(24.0D, 58.0D);
		wobblePhase = randomBetween(0.0D, Math.PI * 2.0D);
		tremorPhase = randomBetween(0.0D, Math.PI * 2.0D);
		elapsedSeconds = 0.0D;
	}

	private static void advanceTurn(LocalPlayer player, double frameSeconds) {
		double yawOmega = baseOmega * wobble(elapsedSeconds);
		double pitchOmega = baseOmega * wobble(elapsedSeconds + PITCH_WOBBLE_LAG);
		yawSpeed = follow(yawSpeed, shortestDelta(player.getYRot(), targetYaw), yawOmega, frameSeconds);
		pitchSpeed = pitchDriven
			? follow(pitchSpeed, targetPitch - player.getXRot(), pitchOmega, frameSeconds)
			: 0.0D;
		applyRotation(player, (float) (yawSpeed * frameSeconds), (float) (pitchSpeed * frameSeconds));

		if (distanceToTarget(player) <= SETTLE_DEGREES
			&& Math.abs(yawSpeed) <= SETTLE_DEGREES_PER_SECOND
			&& Math.abs(pitchSpeed) <= SETTLE_DEGREES_PER_SECOND) {
			// Close enough that the remaining tail would only be a crawl, and slow enough that
			// this is the end of the movement rather than the middle of an overshoot.
			finish(player);
		} else if (elapsedSeconds >= timeoutSeconds) {
			// Only reachable if something outside keeps pulling the camera away, and the mod
			// waits on the turn before it walks or places: end it rather than stall the run.
			finish(player);
		}
	}

	/**
	 * One step of a damped follower.  The denominator is what makes it semi-implicit: the step
	 * can never overshoot into instability no matter how long the frame took, so a stutter in
	 * the framerate changes how finely the turn is sampled and nothing else.
	 */
	private static double follow(double speed, double error, double omega, double frameSeconds) {
		double stiffness = omega * omega;
		return (speed + stiffness * error * frameSeconds)
			/ (1.0D + 2.0D * damping * omega * frameSeconds + stiffness * frameSeconds * frameSeconds);
	}

	/**
	 * The speed multiplier for this instant of the turn.  Two slow sines with random phases
	 * make the movement surge and ease unevenly instead of tracing the same clean curve every
	 * time; the faster one is the tremor of a hand.  It only ever scales the rate of approach,
	 * so the turn still converges - it just stops being machine-even.
	 */
	private static double wobble(double seconds) {
		if (wobbleAmplitude <= 0.0D) {
			return 1.0D;
		}

		double swing = 0.65D * Math.sin(wobbleFrequency * seconds + wobblePhase)
			+ 0.35D * Math.sin(tremorFrequency * seconds + tremorPhase);
		return Math.clamp(1.0D + wobbleAmplitude * swing, 0.3D, 2.2D);
	}

	private static void finish(LocalPlayer player) {
		applyRotation(player,
			shortestDelta(player.getYRot(), targetYaw),
			pitchDriven ? targetPitch - player.getXRot() : 0.0F);
		driving = false;
		yawSpeed = 0.0D;
		pitchSpeed = 0.0D;
	}

	/**
	 * Moves the camera by a relative amount, current and previous rotation together, exactly as
	 * vanilla does for mouse input.  Two reasons: the renderer interpolates between the two, so
	 * writing only the current one would smear each frame's step across the following tick; and
	 * a relative move cannot land a whole turn away from where the camera already was, which an
	 * absolute assignment can, because the player's yaw is never wrapped back into 0..360.
	 */
	private static void applyRotation(LocalPlayer player, float yawDelta, float pitchDelta) {
		player.setYRot(player.getYRot() + yawDelta);
		player.yRotO += yawDelta;
		player.setXRot(Math.clamp(player.getXRot() + pitchDelta, -90.0F, 90.0F));
		player.xRotO = Math.clamp(player.xRotO + pitchDelta, -90.0F, 90.0F);
		writtenYaw = player.getYRot();
		writtenPitch = player.getXRot();
	}

	private static void trackPlayerInput(LocalPlayer player) {
		float yawDrift = shortestDelta(writtenYaw, player.getYRot());
		float pitchDrift = player.getXRot() - writtenPitch;
		writtenYaw = player.getYRot();
		writtenPitch = player.getXRot();
		if (Math.abs(yawDrift) <= PLAYER_INPUT_EPSILON && Math.abs(pitchDrift) <= PLAYER_INPUT_EPSILON) {
			return;
		}

		playerDrift += Math.abs(yawDrift) + Math.abs(pitchDrift);
		if (playerDrift >= PLAYER_NUDGE_DEGREES) {
			playerNudged = true;
			playerDrift = 0.0F;
		}
	}

	private static double distanceToTarget(LocalPlayer player) {
		double yawError = shortestDelta(player.getYRot(), targetYaw);
		double pitchError = pitchDriven ? targetPitch - player.getXRot() : 0.0D;
		return Math.hypot(yawError, pitchError);
	}

	private static float shortestDelta(float from, float to) {
		return (to - from + 540.0F) % 360.0F - 180.0F;
	}

	private static double randomBetween(double min, double max) {
		return ThreadLocalRandom.current().nextDouble(min, max);
	}
}
