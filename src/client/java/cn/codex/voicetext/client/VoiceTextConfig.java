package cn.codex.voicetext.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

final class VoiceTextConfig {
	static final String ENGINE_OFFLINE = "offline";
	static final String ENGINE_IFLYTEK = "iflytek";
	static final String ENGINE_OPENAI = "openai";
	static final String ENGINE_FUNASR = "funasr";
	static final String DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions";
	/**
	 * The bundled lean FunASR service (funasr_server.py) listens on this port. It is
	 * the measured lean stack -- vanilla Paraformer + fsmn-vad + ct-punc-c -- which
	 * needs about 1.13 GB of models and produced byte-identical transcripts to the
	 * 2.1 GB pipeline that the official funasr-server hardcodes.
	 */
	static final String DEFAULT_FUNASR_ENDPOINT = "http://127.0.0.1:8321/v1/audio/transcriptions";
	static final int DEFAULT_FUNASR_PORT = 8321;	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("voice-chat-transcriber.json");

	String endpoint = DEFAULT_OPENAI_ENDPOINT;
	String apiKey = "";
	String model = "gpt-4o-mini-transcribe";
	String language = "zh";
	String microphone = "";
	String engine = ENGINE_OFFLINE;
	String iflytekAppId = "";
	String iflytekApiKey = "";
	String iflytekApiSecret = "";
	String funasrEndpoint = DEFAULT_FUNASR_ENDPOINT;
	String funasrModel = "paraformer";
	boolean offline = true;
	boolean autoSend = false;
	/** Warm the FunASR model on the background thread that starts the service. */
	boolean prewarm = true;
	int maxRecordingSeconds = 30;

	static VoiceTextConfig load() {
		if (!Files.isRegularFile(PATH)) return new VoiceTextConfig().sanitized();
		try {
			String json = Files.readString(PATH, StandardCharsets.UTF_8);
			VoiceTextConfig loaded = GSON.fromJson(json, VoiceTextConfig.class);
			if (loaded != null && !GSON.fromJson(json, com.google.gson.JsonObject.class).has("engine")) {
				loaded.engine = loaded.offline ? ENGINE_OFFLINE : ENGINE_OPENAI;
			}
			return loaded == null ? new VoiceTextConfig() : loaded.sanitized();
		} catch (Exception error) {
			VoiceChatTranscriberClient.LOGGER.warn("Could not read {}", PATH, error);
			return new VoiceTextConfig();
		}
	}

	/**
	 * Points the FunASR endpoint at the port the bundled service actually listens on.
	 *
	 * <p>Configs written by an earlier build still name port 8000 (which the official
	 * funasr-server used), while the bundled launcher now starts the lean service on
	 * {@link #DEFAULT_FUNASR_PORT}. Without this the settings screen probes one port and
	 * the transcription request goes to another, so nothing ever connects. A blank or
	 * non-matching endpoint counts as "never configured"; an intentional change is
	 * refused and reported instead of being overwritten silently.
	 */
	boolean alignFunasrEndpoint(int port) {
		String host = "127.0.0.1";
		String desired = "http://" + host + ":" + port + "/v1/audio/transcriptions";
		String current = funasrEndpoint == null ? "" : funasrEndpoint.trim();
		if (current.isEmpty()) { funasrEndpoint = desired; return true; }
		if (current.equals(desired)) return false;
		if (!isLoopbackAddress(current)) return false;
		VoiceChatTranscriberClient.LOGGER.warn(
				"FunASR endpoint {} does not match the bundled service on port {}; using {}",
				current, port, desired);
		funasrEndpoint = desired;
		return true;
	}

	/** True when the URL's host is a loopback address (where the local service lives). */
	private static boolean isLoopbackAddress(String url) {
		try {
			String authority = java.net.URI.create(url).getAuthority();
			if (authority == null) return false;
			String host = authority;
			int at = host.indexOf('@');
			if (at >= 0) host = host.substring(at + 1);
			if (host.startsWith("[")) host = host.substring(1, host.indexOf(']'));
			else if (host.contains(":")) host = host.substring(0, host.indexOf(':'));
			return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
		} catch (RuntimeException invalid) {
			return false;
		}
	}

	void save() throws IOException {
		sanitized();
		Files.createDirectories(PATH.getParent());
		Path temporary = PATH.resolveSibling(PATH.getFileName() + ".tmp");
		Files.writeString(temporary, GSON.toJson(this), StandardCharsets.UTF_8);
		try {
			Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException unsupportedAtomicMove) {
			Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	VoiceTextConfig copy() {
		VoiceTextConfig copy = new VoiceTextConfig();
		copy.endpoint = endpoint; copy.apiKey = apiKey; copy.model = model; copy.language = language;
		copy.engine = engine; copy.iflytekAppId = iflytekAppId; copy.iflytekApiKey = iflytekApiKey;
		copy.iflytekApiSecret = iflytekApiSecret;
		copy.funasrEndpoint = funasrEndpoint; copy.funasrModel = funasrModel;
		copy.microphone = microphone; copy.offline = offline; copy.autoSend = autoSend;
		copy.prewarm = prewarm;
		copy.maxRecordingSeconds = maxRecordingSeconds;
		return copy;
	}

	private VoiceTextConfig sanitized() {
		if (engine == null || (!ENGINE_OFFLINE.equals(engine) && !ENGINE_IFLYTEK.equals(engine)
				&& !ENGINE_OPENAI.equals(engine) && !ENGINE_FUNASR.equals(engine))) {
			engine = offline ? ENGINE_OFFLINE : ENGINE_OPENAI;
		}
		offline = ENGINE_OFFLINE.equals(engine);
		endpoint = clean(endpoint, DEFAULT_OPENAI_ENDPOINT, 2048);
		apiKey = clean(apiKey, "", 4096);
		model = clean(model, "gpt-4o-mini-transcribe", 128);
		language = clean(language, "zh", 32);
		microphone = clean(microphone, "", 512);
		iflytekAppId = clean(iflytekAppId, "", 128);
		iflytekApiKey = clean(iflytekApiKey, "", 512);
		iflytekApiSecret = clean(iflytekApiSecret, "", 512);
		funasrEndpoint = clean(funasrEndpoint, DEFAULT_FUNASR_ENDPOINT, 2048);
		funasrModel = clean(funasrModel, "paraformer", 128);
		maxRecordingSeconds = Math.max(3, Math.min(120, maxRecordingSeconds));
		return this;
	}

	boolean isOffline() { return ENGINE_OFFLINE.equals(engine); }
	boolean isIflytek() { return ENGINE_IFLYTEK.equals(engine); }
	boolean isOpenAi() { return ENGINE_OPENAI.equals(engine); }
	boolean isFunasr() { return ENGINE_FUNASR.equals(engine); }

	/** The FunASR engine reuses the OpenAI-compatible multipart transport. */
	String transcriptionEndpoint() { return isFunasr() ? funasrEndpoint : endpoint; }
	String transcriptionModel() { return isFunasr() ? funasrModel : model; }

	void cycleEngine() {
		engine = isOffline() ? ENGINE_IFLYTEK : isIflytek() ? ENGINE_OPENAI
				: isOpenAi() ? ENGINE_FUNASR : ENGINE_OFFLINE;
		offline = isOffline();
	}

	private static String clean(String value, String fallback, int maximum) {
		if (value == null) return fallback;
		String trimmed = value.trim();
		if (trimmed.isEmpty() && !fallback.isEmpty()) return fallback;
		return trimmed.length() > maximum ? trimmed.substring(0, maximum) : trimmed;
	}
}
