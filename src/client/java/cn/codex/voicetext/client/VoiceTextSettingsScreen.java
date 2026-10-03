package cn.codex.voicetext.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.network.chat.Component;

final class VoiceTextSettingsScreen extends Screen {
	private final VoiceChatTranscriberClient owner;
	private final VoiceTextConfig working;
	private EditBox endpoint;
	private EditBox apiKey;
	private EditBox model;
	private EditBox language;
	private EditBox iflytekAppId;
	private EditBox iflytekApiKey;
	private EditBox iflytekApiSecret;
	private EditBox funasrEndpoint;
	private EditBox funasrModel;
	private Button engineButton;
	private Button downloadButton;
	private Button microphoneButton;
	private Button modeButton;
	private Button funasrCheckButton;
	private Button funasrStartButton;
	private Checkbox prewarmCheckbox;
	private StringWidget resultWidget;
	private Component result = Component.empty();
	private List<String> devices = List.of();

	VoiceTextSettingsScreen(VoiceChatTranscriberClient owner, VoiceTextConfig working) {
		super(Component.translatable("screen.voice_chat_transcriber.title"));
		this.owner = owner;
		this.working = working;
	}

	@Override
	protected void init() {
		endpoint = null; apiKey = null; model = null; language = null;
		iflytekAppId = null; iflytekApiKey = null; iflytekApiSecret = null;
		funasrEndpoint = null; funasrModel = null; funasrCheckButton = null;
		funasrStartButton = null; prewarmCheckbox = null;
		int contentWidth = Math.min(520, width - 24);
		int left = (width - contentWidth) / 2;
		int top = Math.max(22, height / 2 - (working.isOffline() ? 82 : 132));
		int half = (contentWidth - 8) / 2;
		engineButton = addRenderableWidget(Button.builder(engineLabel(), ignored -> toggleEngine())
				.bounds(left, top + 12, half, 20).build());
		downloadButton = addRenderableWidget(Button.builder(downloadLabel(), ignored -> downloadModel())
				.bounds(left + half + 8, top + 12, half, 20).build());
		downloadButton.active = !OfflineModelManager.isInstalled();

		devices = new ArrayList<>();
		devices.add("");
		devices.addAll(MicrophoneRecorder.availableDevices());
		if (working.isOffline()) {
			int afterCommon = addCommonControls(left, top + 42, contentWidth, half, disabledHint());
			addResultLine(left, afterCommon, contentWidth);
			addRenderableWidget(new StringWidget(left, afterCommon + 26, contentWidth, 24,
					Component.translatable("screen.voice_chat_transcriber.privacy"), font).setMaxWidth(contentWidth));
			return;
		}
		if (working.isIflytek()) {
			iflytekAppId = field(left, top + 52, contentWidth,
					"screen.voice_chat_transcriber.iflytek_app_id", working.iflytekAppId, 128);
			iflytekApiKey = field(left, top + 90, half,
					"screen.voice_chat_transcriber.iflytek_api_key", "", 512);
			iflytekApiSecret = field(left + half + 8, top + 90, half,
					"screen.voice_chat_transcriber.iflytek_api_secret", "", 512);
			Button clearIflytek = addRenderableWidget(Button.builder(
					Component.translatable("screen.voice_chat_transcriber.clear_iflytek"), ignored -> {
						working.iflytekAppId = ""; working.iflytekApiKey = ""; working.iflytekApiSecret = "";
						iflytekAppId.setValue(""); iflytekApiKey.setValue(""); iflytekApiSecret.setValue("");
						setResult(Component.translatable("screen.voice_chat_transcriber.iflytek_cleared"));
					}).build());
			int afterCommon = addCommonControls(left, top + 128, contentWidth, half, clearIflytek);
			addResultLine(left, afterCommon, contentWidth);
			addRenderableWidget(new StringWidget(left, afterCommon + 26, contentWidth, 24,
					Component.translatable("screen.voice_chat_transcriber.privacy"), font).setMaxWidth(contentWidth));
			return;
		}
		if (working.isFunasr()) {
			funasrEndpoint = field(left, top + 52, contentWidth,
					"screen.voice_chat_transcriber.funasr_endpoint", working.funasrEndpoint, 2048);
			funasrModel = field(left, top + 90, half,
					"screen.voice_chat_transcriber.funasr_model", working.funasrModel, 128);
			language = field(left + half + 8, top + 90, half,
					"screen.voice_chat_transcriber.language", working.language, 32);
			prewarmCheckbox = addRenderableWidget(Checkbox.builder(
							Component.translatable("screen.voice_chat_transcriber.funasr_prewarm"), font)
					.pos(left, top + 124).maxWidth(contentWidth).selected(working.prewarm)
					.onValueChange((box, value) -> working.prewarm = value).build());
			// Start service lives in the keys row's right slot; check service goes on the
			// row below it. Both are positioned by the shared rows, never overlapping.
			funasrStartButton = addRenderableWidget(Button.builder(
							Component.translatable("screen.voice_chat_transcriber.funasr_start"), ignored -> startFunasrServer())
					.build());
			funasrCheckButton = addRenderableWidget(Button.builder(
							Component.translatable("screen.voice_chat_transcriber.funasr_check"), ignored -> checkFunasrServer())
					.build());
			int afterCommon = addCommonControls(left, top + 150, contentWidth, half, funasrStartButton);
			funasrCheckButton.setRectangle(half, 20, left, afterCommon);
			addResultLine(left, afterCommon + 22, contentWidth);
			addRenderableWidget(new StringWidget(left, afterCommon + 48, contentWidth, 24,
					Component.translatable("screen.voice_chat_transcriber.funasr_hint"), font).setMaxWidth(contentWidth));
			return;
		}

		endpoint = field(left, top + 52, contentWidth, "screen.voice_chat_transcriber.endpoint", working.endpoint, 2048);
		apiKey = field(left, top + 90, contentWidth, "screen.voice_chat_transcriber.api_key", "", 4096);
		model = field(left, top + 128, half, "screen.voice_chat_transcriber.model", working.model, 128);
		language = field(left + half + 8, top + 128, half, "screen.voice_chat_transcriber.language", working.language, 32);
		Button clearKey = addRenderableWidget(
				Button.builder(Component.translatable("screen.voice_chat_transcriber.clear_key"), ignored -> {
					working.apiKey = ""; apiKey.setValue(""); setResult(Component.translatable("screen.voice_chat_transcriber.key_cleared"));
				}).build());
		int afterCommon = addCommonControls(left, top + 156, contentWidth, half, clearKey);
		addResultLine(left, afterCommon, contentWidth);
		addRenderableWidget(new StringWidget(left, afterCommon + 26, contentWidth, 24,
				Component.translatable("screen.voice_chat_transcriber.privacy"), font).setMaxWidth(contentWidth));
	}

	/**
	 * The result line is a real widget inside the layout instead of an overlay drawn at a
	 * fixed screen offset. That is what makes an overlap impossible: it owns its own row
	 * between the button rows and the trailing hint, and the hint is placed below it.
	 */
	private void addResultLine(int left, int y, int contentWidth) {
		resultWidget = addRenderableWidget(new StringWidget(left, y, contentWidth, 24, result, font)
				.setMaxWidth(contentWidth));
	}

	private Button disabledHint() {
		Button button = Button.builder(Component.translatable("screen.voice_chat_transcriber.offline_hint"), ignored -> {})
				.build();
		button.active = false;
		return addRenderableWidget(button);
	}

	/**
	 * Adds the rows every engine shares and returns the y just below them.
	 *
	 * <pre>
	 *   y       : microphone | delivery mode
	 *   y + 26  : keys       | extraRight          (engine buttons, if any)
	 *   y + 52  : save (centred 100 px) + cancel
	 * </pre>
	 *
	 * The rows have a fixed height and the caller places engine-specific widgets in the
	 * remaining slots instead of on top of these, which is what fixed the collision where
	 * the FunASR start button and the keys button shared one rectangle.
	 */
	private int addCommonControls(int left, int y, int contentWidth, int half, Button extraRight) {
		microphoneButton = addRenderableWidget(Button.builder(microphoneLabel(), ignored -> cycleMicrophone())
				.bounds(left, y, half, 20).build());
		modeButton = addRenderableWidget(Button.builder(modeLabel(), ignored -> toggleMode())
				.bounds(left + half + 8, y, half, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.voice_chat_transcriber.keys"), ignored -> {
			captureFields();
			Minecraft.getInstance().setScreen(new ControlsScreen(this, Minecraft.getInstance().options));
		})
				.bounds(left, y + 26, half, 20).build());
		if (extraRight != null) extraRight.setRectangle(half, 20, left + half + 8, y + 26);
		addRenderableWidget(Button.builder(Component.translatable("screen.voice_chat_transcriber.save"), ignored -> save())
				.bounds(left + contentWidth / 2 - 104, y + 52, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.voice_chat_transcriber.cancel"), ignored -> onClose())
				.bounds(left + contentWidth / 2 + 4, y + 52, 100, 20).build());
		return y + 78;
	}

	private void toggleEngine() {
		captureFields();
		working.cycleEngine();
		rebuildWidgets();
	}

	private void downloadModel() {
		downloadButton.active = false;
		setResult(Component.translatable("screen.voice_chat_transcriber.model_downloading"));
		OfflineModelManager.downloadAsync().whenComplete((path, error) -> Minecraft.getInstance().execute(() -> {
			if (error == null) {
				working.engine = VoiceTextConfig.ENGINE_OFFLINE;
				working.offline = true;
				setResult(Component.translatable("screen.voice_chat_transcriber.model_installed"));
				rebuildWidgets();
			} else {
				downloadButton.active = true;
				Throwable root = error;
				while (root.getCause() != null) root = root.getCause();
				setResult(Component.translatable("screen.voice_chat_transcriber.model_download_failed",
						root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage()));
			}
		}));
	}

	private void checkFunasrServer() {
		captureFields();
		if (funasrCheckButton != null) funasrCheckButton.active = false;
		setResult(Component.translatable("screen.voice_chat_transcriber.funasr_checking"));
		VoiceTextConfig snapshot = working.copy();
		TranscriptionClient.probeHealth(snapshot.transcriptionEndpoint())
				.whenComplete((status, error) -> Minecraft.getInstance().execute(() -> {
					if (funasrCheckButton != null) funasrCheckButton.active = true;
					if (error != null) {
						setResult(Component.translatable("screen.voice_chat_transcriber.funasr_not_running"));
						return;
					}
					setResult(status >= 200 && status < 300
							? Component.translatable("screen.voice_chat_transcriber.funasr_ok")
							: Component.translatable("screen.voice_chat_transcriber.funasr_bad_status", status));
				}));
	}

	/** Extracts the bundled scripts, starts the service detached and waits for health. */
	private void startFunasrServer() {
		captureFields();
		// Point the endpoint at the port the bundled service uses, otherwise the probe and
		// the transcription request talk to a port nothing is listening on (this is what
		// made an older config, which still said 8000, look like a broken service).
		if (working.alignFunasrEndpoint(VoiceTextConfig.DEFAULT_FUNASR_PORT)) {
			funasrEndpoint.setValue(working.funasrEndpoint);
			result = Component.translatable("screen.voice_chat_transcriber.funasr_endpoint_fixed",
					working.funasrEndpoint);
		}
		final VoiceTextConfig snapshot = working.copy();
		if (funasrStartButton != null) funasrStartButton.active = false;
		if (result == Component.empty()) {
			setResult(Component.translatable("screen.voice_chat_transcriber.funasr_starting"));
		}
		FunasrLauncher.startAsync(snapshot).whenComplete((status, error) ->
				Minecraft.getInstance().execute(() -> {
					if (funasrStartButton != null) funasrStartButton.active = true;
					if (status == FunasrLauncher.Status.READY) {
						try {
							owner.applyConfig(snapshot);
						} catch (Exception saveError) {
							VoiceChatTranscriberClient.LOGGER.warn("Could not save the FunASR endpoint", saveError);
						}
						setResult(Component.translatable(snapshot.prewarm
								? "screen.voice_chat_transcriber.funasr_ready_warming"
								: "screen.voice_chat_transcriber.funasr_ready"));
						return;
					}
					String detail = error != null ? error.getMessage() : FunasrLauncher.lastFailure();
					setResult(Component.translatable("screen.voice_chat_transcriber.funasr_start_failed",
							detail == null || detail.isBlank()
									? Component.translatable("screen.voice_chat_transcriber.unknown_error").getString()
									: detail));
				}));
	}

	private Component engineLabel() {
		return Component.translatable(working.isOffline() ? "screen.voice_chat_transcriber.engine.offline"
				: working.isIflytek() ? "screen.voice_chat_transcriber.engine.iflytek"
				: working.isFunasr() ? "screen.voice_chat_transcriber.engine.funasr"
				: "screen.voice_chat_transcriber.engine.online");
	}

	private Component downloadLabel() {
		return Component.translatable(OfflineModelManager.isInstalled()
				? "screen.voice_chat_transcriber.model_ready" : "screen.voice_chat_transcriber.model_download");
	}

	private EditBox field(int x, int y, int fieldWidth, String labelKey, String value, int maxLength) {
		addRenderableWidget(new StringWidget(x, y - 13, fieldWidth, 12, Component.translatable(labelKey), font).setMaxWidth(fieldWidth));
		EditBox box = new EditBox(font, x, y, fieldWidth, 20, Component.translatable(labelKey));
		box.setMaxLength(maxLength); box.setValue(value); addRenderableWidget(box); return box;
	}

	private void cycleMicrophone() {
		int current = devices.indexOf(working.microphone);
		working.microphone = devices.get((current < 0 ? 0 : current + 1) % devices.size());
		microphoneButton.setMessage(microphoneLabel());
	}

	private void toggleMode() {
		working.autoSend = !working.autoSend;
		modeButton.setMessage(modeLabel());
	}

	private Component microphoneLabel() {
		String name = working.microphone.isBlank()
				? Component.translatable("screen.voice_chat_transcriber.system_default").getString()
				: working.microphone;
		return Component.translatable("screen.voice_chat_transcriber.microphone", name);
	}
	private Component modeLabel() {
		return Component.translatable(working.autoSend ? "screen.voice_chat_transcriber.mode.auto"
				: "screen.voice_chat_transcriber.mode.review");
	}

	private void save() {
		captureFields();
		try {
			owner.applyConfig(working); setResult(Component.translatable("screen.voice_chat_transcriber.saved"));
		} catch (Exception error) {
			setResult(Component.translatable("screen.voice_chat_transcriber.save_failed",
					error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
		}
	}

	private void captureFields() {
		if (endpoint != null) working.endpoint = endpoint.getValue();
		if (apiKey != null && !apiKey.getValue().isBlank()) working.apiKey = apiKey.getValue().trim();
		if (model != null) working.model = model.getValue();
		if (language != null) working.language = language.getValue();
		if (iflytekAppId != null) working.iflytekAppId = iflytekAppId.getValue().trim();
		if (iflytekApiKey != null && !iflytekApiKey.getValue().isBlank()) {
			working.iflytekApiKey = iflytekApiKey.getValue().trim();
		}
		if (iflytekApiSecret != null && !iflytekApiSecret.getValue().isBlank()) {
			working.iflytekApiSecret = iflytekApiSecret.getValue().trim();
		}
		if (funasrEndpoint != null) working.funasrEndpoint = funasrEndpoint.getValue().trim();
		if (funasrModel != null) working.funasrModel = funasrModel.getValue().trim();
	}

	/** Sets the status line and keeps its widget in sync. */
	private void setResult(Component message) {
		result = message;
		if (resultWidget != null) resultWidget.setMessage(message);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.centeredText(font, title, width / 2, Math.max(8, height / 2 - 156), 0xFFFFFF);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override public void onClose() { Minecraft.getInstance().setScreen(null); }
	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }
}
