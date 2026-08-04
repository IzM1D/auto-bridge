package auto.bridge.client;

import auto.bridge.client.build.BridgeModeType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.loader.api.FabricLoader;

/** Persistent user preferences and the small, human-like deviations they enable. */
public final class AutoBridgeSettings {
	private static final String CONFIG_FILE_NAME = "auto-bridge.properties";
	private static final double MAX_CENTER_OFFSET = 0.25D;
	private static final float MAX_YAW_OFFSET = 5.0F;
	private static AutoBridgeSettings instance;

	private BridgeModeType bridgeMode = BridgeModeType.defaultType();
	private boolean smoothCamera;
	private double smoothCameraSeconds = 0.35D;
	private int imprecisionPercent;
	private boolean randomImprecision;
	private int imprecisionChancePercent = 100;

	private AutoBridgeSettings() {
	}

	public static AutoBridgeSettings get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** The kind of building the build key starts. */
	public BridgeModeType getBridgeMode() {
		return bridgeMode;
	}

	public void setBridgeMode(BridgeModeType bridgeMode) {
		this.bridgeMode = bridgeMode == null ? BridgeModeType.defaultType() : bridgeMode;
	}

	public boolean isSmoothCamera() {
		return smoothCamera;
	}

	public void setSmoothCamera(boolean smoothCamera) {
		this.smoothCamera = smoothCamera;
	}

	public double getSmoothCameraSeconds() {
		return smoothCameraSeconds;
	}

	public void setSmoothCameraSeconds(double smoothCameraSeconds) {
		this.smoothCameraSeconds = clamp(smoothCameraSeconds, 0.0D, 5.0D);
	}

	public int getImprecisionPercent() {
		return imprecisionPercent;
	}

	public void setImprecisionPercent(int imprecisionPercent) {
		this.imprecisionPercent = Math.clamp(imprecisionPercent, 0, 50);
	}

	public boolean isRandomImprecision() {
		return randomImprecision;
	}

	public boolean hasImprecisionEnabled() {
		return randomImprecision || imprecisionPercent > 0;
	}

	public void setRandomImprecision(boolean randomImprecision) {
		this.randomImprecision = randomImprecision;
	}

	public int getImprecisionChancePercent() {
		return imprecisionChancePercent;
	}

	public void setImprecisionChancePercent(int imprecisionChancePercent) {
		this.imprecisionChancePercent = Math.clamp(imprecisionChancePercent, 0, 100);
	}

	/** Returns one signed positional error for a single centering action. */
	public double nextCenteringOffset() {
		return nextDeviationFactor() * MAX_CENTER_OFFSET;
	}

	/** Returns one signed camera-yaw error for a single alignment action. */
	public float nextYawOffset() {
		return (float) (nextDeviationFactor() * MAX_YAW_OFFSET);
	}

	/**
	 * Returns how uneven one camera movement should be, from 0 for a perfectly even machine
	 * turn to 1 for a fully human one.  This is the same setting the aiming error is drawn
	 * from, so a run that aims imprecisely also moves the mouse imprecisely.
	 */
	public double nextCameraUnevenness() {
		return nextImprecisionStrength();
	}

	public void save() {
		Properties properties = new Properties();
		properties.setProperty("bridgeMode", bridgeMode.id());
		properties.setProperty("smoothCamera", Boolean.toString(smoothCamera));
		properties.setProperty("smoothCameraSeconds", Double.toString(smoothCameraSeconds));
		properties.setProperty("imprecisionPercent", Integer.toString(imprecisionPercent));
		properties.setProperty("randomImprecision", Boolean.toString(randomImprecision));
		properties.setProperty("imprecisionChancePercent", Integer.toString(imprecisionChancePercent));

		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			try (OutputStream output = Files.newOutputStream(path)) {
				properties.store(output, "Auto-bridge settings");
			}
		} catch (IOException ignored) {
			// A read-only config directory must not prevent the client from playing.
		}
	}

	private static AutoBridgeSettings load() {
		AutoBridgeSettings settings = new AutoBridgeSettings();
		Path path = configPath();
		if (!Files.isRegularFile(path)) {
			return settings;
		}

		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(path)) {
			properties.load(input);
			settings.setBridgeMode(BridgeModeType.byId(
				properties.getProperty("bridgeMode", BridgeModeType.defaultType().id())));
			settings.setSmoothCamera(Boolean.parseBoolean(properties.getProperty("smoothCamera", "false")));
			settings.setSmoothCameraSeconds(parseDouble(properties, "smoothCameraSeconds", 0.35D));
			settings.setImprecisionPercent(parseInt(properties, "imprecisionPercent", 0));
			settings.setRandomImprecision(Boolean.parseBoolean(properties.getProperty("randomImprecision", "false")));
			settings.setImprecisionChancePercent(parseInt(properties, "imprecisionChancePercent", 100));
		} catch (IOException ignored) {
			// Keep safe defaults when the file is unavailable or unreadable.
		}
		return settings;
	}

	private double nextDeviationFactor() {
		return (ThreadLocalRandom.current().nextDouble() * 2.0D - 1.0D) * nextImprecisionStrength();
	}

	/**
	 * One roll of the imprecision setting, as a 0..1 fraction of its full range.  Anything that
	 * wants to be humanly uneven - a camera turn, the spacing between clicks - scales itself by
	 * this, so the whole run is imprecise together or not at all.
	 */
	public double nextImprecisionStrength() {
		if (imprecisionChancePercent == 0
			|| ThreadLocalRandom.current().nextInt(100) >= imprecisionChancePercent) {
			return 0.0D;
		}

		int percentage = randomImprecision
			? ThreadLocalRandom.current().nextInt(12, 26)
			: imprecisionPercent;
		return percentage / 50.0D;
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
	}

	private static int parseInt(Properties properties, String key, int fallback) {
		try {
			return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static double parseDouble(Properties properties, String key, double fallback) {
		try {
			return Double.parseDouble(properties.getProperty(key, Double.toString(fallback)));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
