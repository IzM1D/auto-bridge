package auto.bridge.client;

import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Pause-style settings screen opened with the Auto-bridge settings key. */
public final class AutoBridgeSettingsScreen extends Screen {
	private final AutoBridgeSettings settings = AutoBridgeSettings.get();
	private EditBox durationInput;
	private Button smoothButton;
	private String lastValidDuration;

	public AutoBridgeSettingsScreen() {
		super(Component.translatable("screen.auto-bridge.settings.title"));
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		int top = height / 2 - 88;
		StringWidget titleWidget = new StringWidget(280, 20, title, font);
		titleWidget.setX(centerX - 140);
		titleWidget.setY(top - 30);
		addRenderableWidget(titleWidget);

		// The mode button steps through BridgeModeType, so a new kind of building appears here
		// as soon as it is added to that enum.
		Button modeButton = addRenderableWidget(Button.builder(modeMessage(), button -> {
			settings.setBridgeMode(settings.getBridgeMode().next());
			settings.save();
			button.setMessage(modeMessage());
		}).bounds(centerX - 140, top, 280, 20).build());
		modeButton.setTooltip(Tooltip.create(Component.translatable("tooltip.auto-bridge.bridge_mode")));

		smoothButton = addRenderableWidget(Button.builder(smoothMessage(), button -> {
			settings.setSmoothCamera(!settings.isSmoothCamera());
			settings.save();
			refreshSmoothControls();
		}).bounds(centerX - 140, top + 28, 190, 20).build());
		smoothButton.setTooltip(Tooltip.create(Component.translatable("tooltip.auto-bridge.smooth_camera")));

		lastValidDuration = durationText(settings.getSmoothCameraSeconds());
		durationInput = new EditBox(font, centerX + 58, top + 28, 82, 20,
			Component.translatable("screen.auto-bridge.smooth_duration"));
		durationInput.setMaxLength(4);
		durationInput.setValue(lastValidDuration);
		durationInput.setTooltip(Tooltip.create(Component.translatable("tooltip.auto-bridge.smooth_duration")));
		durationInput.setResponder(this::acceptDurationText);
		addRenderableWidget(durationInput);
		refreshSmoothControls();

		AccuracySlider accuracySlider = addRenderableWidget(new AccuracySlider(centerX - 140, top + 62));
		accuracySlider.setTooltip(Tooltip.create(Component.translatable("tooltip.auto-bridge.imprecision")));
		ChanceSlider chanceSlider = addRenderableWidget(new ChanceSlider(centerX - 140, top + 90));
		chanceSlider.setTooltip(Tooltip.create(Component.translatable("tooltip.auto-bridge.imprecision_chance")));

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
			.bounds(centerX - 100, top + 130, 200, 20).build());
	}

	@Override
	public void onClose() {
		settings.save();
		minecraft.gui.setScreen(null);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractTransparentBackground(graphics);
	}

	private Component modeMessage() {
		return Component.translatable("screen.auto-bridge.bridge_mode", settings.getBridgeMode().displayName());
	}

	private Component smoothMessage() {
		return Component.translatable("screen.auto-bridge.smooth_camera", settings.isSmoothCamera()
			? Component.translatable("options.on")
			: Component.translatable("options.off"));
	}

	private void refreshSmoothControls() {
		smoothButton.setMessage(smoothMessage());
		durationInput.setEditable(settings.isSmoothCamera());
		durationInput.active = settings.isSmoothCamera();
	}

	private void acceptDurationText(String value) {
		if (!value.matches("\\d{0,1}(?:\\.\\d{0,2})?")) {
			durationInput.setValue(lastValidDuration);
			return;
		}
		if (value.isEmpty()) {
			lastValidDuration = value;
			return;
		}

		try {
			double seconds = Double.parseDouble(value);
			if (seconds > 5.0D) {
				durationInput.setValue(lastValidDuration);
				return;
			}
			lastValidDuration = value;
			settings.setSmoothCameraSeconds(seconds);
			settings.save();
		} catch (NumberFormatException ignored) {
			durationInput.setValue(lastValidDuration);
		}
	}

	private static String durationText(double seconds) {
		return String.format(Locale.ROOT, "%.2f", seconds).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	private final class AccuracySlider extends AbstractSliderButton {
		private static final int RANDOM_STEP = 51;

		private AccuracySlider(int x, int y) {
			super(x, y, 280, 20, Component.empty(), initialValue());
			updateMessage();
		}

		private static double initialValue() {
			AutoBridgeSettings settings = AutoBridgeSettings.get();
			return settings.isRandomImprecision()
				? 1.0D
				: settings.getImprecisionPercent() / (double) RANDOM_STEP;
		}

		@Override
		protected void updateMessage() {
			int step = (int) Math.round(value * RANDOM_STEP);
			Component valueText = step == 0
				? Component.translatable("screen.auto-bridge.freakin_robot")
				: step == RANDOM_STEP
					? Component.translatable("screen.auto-bridge.random")
					: Component.literal(step + "%");
			setMessage(Component.translatable("screen.auto-bridge.imprecision", valueText));
		}

		@Override
		protected void applyValue() {
			int step = Math.clamp((int) Math.round(value * RANDOM_STEP), 0, RANDOM_STEP);
			value = step / (double) RANDOM_STEP;
			settings.setRandomImprecision(step == RANDOM_STEP);
			settings.setImprecisionPercent(step == RANDOM_STEP ? 0 : step);
			settings.save();
			updateMessage();
		}
	}

	private final class ChanceSlider extends AbstractSliderButton {
		private ChanceSlider(int x, int y) {
			super(x, y, 280, 20, Component.empty(), settings.getImprecisionChancePercent() / 100.0D);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("screen.auto-bridge.imprecision_chance",
				Math.round(value * 100.0D) + "%"));
		}

		@Override
		protected void applyValue() {
			int percentage = Math.clamp((int) Math.round(value * 100.0D), 0, 100);
			value = percentage / 100.0D;
			settings.setImprecisionChancePercent(percentage);
			settings.save();
			updateMessage();
		}
	}
}
