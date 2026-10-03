package cn.codex.voicetext.client;

import cn.codex.voicetext.WavEncoder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

final class SpeechController {
	private enum State { IDLE, RECORDING, TRANSCRIBING }
	private final MicrophoneRecorder recorder = new MicrophoneRecorder();
	private final TranscriptionClient transcription = new TranscriptionClient();
	private final OfflineTranscriptionClient offlineTranscription = new OfflineTranscriptionClient();
	private final IflytekTranscriptionClient iflytekTranscription = new IflytekTranscriptionClient();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "voice-text-transcription"); thread.setDaemon(true); return thread;
	});
	private volatile State state = State.IDLE;
	/**
	 * Bumped whenever a recording session begins, is cancelled, or completes.
	 *
	 * <p>The transcribe callback runs on the render thread some time after the recording it
	 * belongs to. Without this, cancelling a slow transcription and immediately recording
	 * again let the *old* callback reset the state to IDLE — the new recording's
	 * {@link #finish} then returned early and left the microphone open until the maximum
	 * recording length, and a cancelled request could still deliver its text.
	 */
	private int generation;

	/**
	 * Last known FunASR service state, refreshed on {@link #worker}.
	 *
	 * <p>This used to be probed synchronously from {@link #begin}, which runs on the render
	 * thread: pressing the record key blocked the game for up to 2.5 s, and longer when the
	 * local service was wedged. The key handler now only ever reads a cached value.
	 */
	private volatile TranscriptionClient.ServiceState serviceState = TranscriptionClient.ServiceState.UNREACHABLE;
	private volatile long serviceCheckedAt;
	/** Short enough that a service which died is noticed, long enough to not poll per tick. */
	private static final long SERVICE_STATE_TTL_MS = 5_000L;
	private final java.util.concurrent.atomic.AtomicBoolean serviceRefreshInFlight =
			new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * Refreshes the cached FunASR service state in the background.
	 *
	 * <p>Called from the client tick loop. It never blocks: when the cached value is still
	 * fresh, or a refresh is already running, it returns immediately.
	 */
	void pollServiceState(VoiceTextConfig config) {
		refreshServiceStateInternal(config, false);
	}

	private void refreshServiceStateInternal(VoiceTextConfig config, boolean force) {
		if (config == null || !config.isFunasr()) return;
		if (!force && System.currentTimeMillis() - serviceCheckedAt < SERVICE_STATE_TTL_MS) return;
		if (!serviceRefreshInFlight.compareAndSet(false, true)) return;
		worker.execute(() -> {
			try {
				serviceState = TranscriptionClient.healthState(config, java.time.Duration.ofSeconds(2));
				serviceCheckedAt = System.currentTimeMillis();
			} catch (RuntimeException ignored) {
				serviceState = TranscriptionClient.ServiceState.UNREACHABLE;
				serviceCheckedAt = System.currentTimeMillis();
			} finally {
				serviceRefreshInFlight.set(false);
			}
		});
	}

	private TranscriptionClient.ServiceState cachedServiceState(VoiceTextConfig config) {
		long age = System.currentTimeMillis() - serviceCheckedAt;
		if (age >= SERVICE_STATE_TTL_MS) {
			// Stale: answer from what we have and re-check behind the scenes.
			refreshServiceStateInternal(config, true);
		}
		return serviceState;
	}

	void begin(Minecraft client, VoiceTextConfig config) {
		if (state != State.IDLE || client.player == null) return;
		if (config.isOffline() && !OfflineModelManager.isInstalled()) {
			message(client, "message.voice_chat_transcriber.need_offline_model"); return;
		}
		if (config.isIflytek() && (config.iflytekAppId.isBlank() || config.iflytekApiKey.isBlank()
				|| config.iflytekApiSecret.isBlank())) {
			message(client, "message.voice_chat_transcriber.need_iflytek_credentials"); return;
		}
		if (config.isOpenAi() && config.apiKey.isBlank() && config.endpoint.contains("api.openai.com")) {
			message(client, "message.voice_chat_transcriber.need_key"); return;
		}
		if (config.isFunasr() && !isLocalEndpoint(config.funasrEndpoint)) {
			message(client, "message.voice_chat_transcriber.funasr_remote"); return;
		}
		// Refuse before recording rather than after: otherwise the clip is captured,
		// sent, and only then fails with "Connection refused". "Starting" is called out
		// separately because on a first run the service is up for minutes while it
		// downloads and builds its models, and "not running" would be a lie.
		if (config.isFunasr()) {
			TranscriptionClient.ServiceState service = cachedServiceState(config);
			if (service == TranscriptionClient.ServiceState.STARTING) {
				message(client, "message.voice_chat_transcriber.funasr_starting"); return;
			}
			if (service != TranscriptionClient.ServiceState.READY) {
				message(client, "message.voice_chat_transcriber.funasr_not_running"); return;
			}
		}
		try {
			recorder.start(config.microphone, config.isIflytek()
					? Math.min(config.maxRecordingSeconds, 60) : config.maxRecordingSeconds);
			generation++;
			state = State.RECORDING;
			message(client, "message.voice_chat_transcriber.recording");
		} catch (Exception error) { fail(client, error); }
	}

	void finish(Minecraft client, VoiceTextConfig snapshot) {
		if (state != State.RECORDING) return;
		state = State.TRANSCRIBING;
		int session = generation;
		message(client, snapshot.isOffline() ? "message.voice_chat_transcriber.transcribing_offline"
				: snapshot.isIflytek() ? "message.voice_chat_transcriber.transcribing_iflytek"
				: snapshot.isFunasr() ? "message.voice_chat_transcriber.transcribing_funasr"
				: "message.voice_chat_transcriber.transcribing");
		CompletableFuture<byte[]> captured = recorder.stop();
		captured.thenApplyAsync(pcm -> {
			if (pcm.length < MicrophoneRecorder.SAMPLE_RATE / 2) throw new IllegalStateException("录音太短");
			try {
				if (snapshot.isOffline()) return offlineTranscription.transcribe(pcm);
				if (snapshot.isIflytek()) return iflytekTranscription.transcribe(snapshot, pcm);
				// The FunASR engine speaks the same OpenAI-compatible multipart protocol.
				return transcription.transcribe(snapshot, WavEncoder.pcm16Mono(MicrophoneRecorder.SAMPLE_RATE, pcm));
			}
			catch (Exception error) { throw new RuntimeException(error); }
		}, worker).whenComplete((text, error) -> client.execute(() -> {
			// This transcription belongs to an older session: the player cancelled it, or
			// already started a new recording. Touch nothing — not the state, not the chat.
			if (session != generation) return;
			state = State.IDLE;
			if (error != null) { fail(client, root(error)); return; }
			if (text == null || text.isBlank()) { fail(client, new IllegalStateException("接口返回了空文本")); return; }
			deliver(client, snapshot, limit(text, 256));
		}));
	}

	void cancel() {
		if (state == State.RECORDING) recorder.cancel();
		// Invalidate any in-flight transcription so its callback cannot reset the state of
		// the recording that follows, nor deliver text the player has cancelled.
		generation++;
		state = State.IDLE;
	}

	boolean isRecording() { return state == State.RECORDING; }

	private static void deliver(Minecraft client, VoiceTextConfig config, String text) {
		String route;
		if (config.autoSend && client.getConnection() != null) {
			client.getConnection().sendChat(text);
			message(client, "message.voice_chat_transcriber.sent");
			route = "sent to chat";
		} else if (client.screen == null) {
			client.setScreen(new ChatScreen(text, false));
			message(client, "message.voice_chat_transcriber.ready");
			route = "prefilled the chat box";
		} else {
			client.keyboardHandler.setClipboard(text);
			message(client, "message.voice_chat_transcriber.copied");
			route = "copied to the clipboard (a screen was open)";
		}
		// Logged with codepoints so an encoding problem is provable from the game log
		// instead of only being visible as mojibake on screen.
		VoiceChatTranscriberClient.LOGGER.info("Delivered transcription ({}): '{}' codepoints={}",
				route, text, codepoints(text));
	}

	private static String codepoints(String text) {
		StringBuilder builder = new StringBuilder();
		text.codePoints().limit(12).forEach(code -> builder.append(String.format("U+%04X ", code)));
		return builder.toString().trim();
	}

	private static String limit(String text, int maximumCodePoints) {
		int count = text.codePointCount(0, text.length());
		return count <= maximumCodePoints ? text : text.substring(0, text.offsetByCodePoints(0, maximumCodePoints));
	}
	private static Throwable root(Throwable error) {
		Throwable current = error; while (current.getCause() != null) current = current.getCause(); return current;
	}
	/** The loopback-only rule keeps the recorded speech on this machine. */
	private static boolean isLocalEndpoint(String endpoint) {
		try {
			String host = java.net.URI.create(endpoint).getHost();
			return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
		} catch (RuntimeException invalid) { return false; }
	}
	private static void fail(Minecraft client, Throwable error) {
		VoiceChatTranscriberClient.LOGGER.warn("Voice transcription failed", error);
		String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
		if (client.player != null) client.player.sendOverlayMessage(Component.translatable("message.voice_chat_transcriber.error", detail));
	}
	private static void message(Minecraft client, String key) {
		if (client.player != null) client.player.sendOverlayMessage(Component.translatable(key));
	}
}
