package cn.codex.voicetext.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VoiceChatTranscriberClient implements ClientModInitializer {
	static final Logger LOGGER = LoggerFactory.getLogger("voice_chat_transcriber");
	/**
	 * Shared with {@link FunasrLauncher} so that a freshly started service can seed the
	 * cached health state instead of the record key having to probe for it.
	 */
	static final SpeechController SPEECH = new SpeechController();
	private VoiceTextConfig config;
	private KeyMapping recordKey;
	private KeyMapping settingsKey;
	private boolean recordWasDown;

	@Override
	public void onInitializeClient() {
		config = VoiceTextConfig.load();
		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath("voice_chat_transcriber", "controls"));
		recordKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.voice_chat_transcriber.record", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
		settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.voice_chat_transcriber.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		// Tidy up on exit: the FunASR service is a detached Python process, so without this
		// it would keep running (and holding ~1 GB of model memory) after the game closes.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FunasrLauncher.stopIfWeStartedIt());
		LOGGER.info("Voice Chat Transcriber initialized for client-side use");
	}

	private void tick(Minecraft client) {
		while (settingsKey.consumeClick()) {
			if (client.screen == null) client.setScreen(new VoiceTextSettingsScreen(this, config.copy()));
		}
		// Keeps the FunASR health cache warm in the background (never blocks this thread),
		// so the record key can answer immediately.
		SPEECH.pollServiceState(config);
		boolean down = recordKey.isDown();
		if (client.player == null) {
			if (SPEECH.isRecording()) SPEECH.cancel();
			recordWasDown = down;
			return;
		}
		if (down && !recordWasDown && client.screen == null) SPEECH.begin(client, config.copy());
		if (!down && recordWasDown) SPEECH.finish(client, config.copy());
		recordWasDown = down;
	}

	void applyConfig(VoiceTextConfig replacement) throws Exception {
		replacement.save();
		config = replacement.copy();
	}
}
