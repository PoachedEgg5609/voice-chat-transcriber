package cn.codex.voicetext.client;

import cn.codex.voicetext.MultipartBody;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

final class TranscriptionClient {
	private static final HttpClient PROBE_HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5)).build();
	/**
	 * How long a first run may need: measured 151 s to download ~1.1 GB of models and
	 * build them on CPU, so anything shorter produces bogus "timed out" reports.
	 */
	static final Duration FIRST_RUN_BUDGET = Duration.ofMinutes(10);
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	String transcribe(VoiceTextConfig config, byte[] wav) throws Exception {
		URI endpoint = URI.create(config.transcriptionEndpoint());
		validateEndpoint(endpoint);
		MultipartBody form = new MultipartBody().text("model", config.transcriptionModel())
				.text("language", config.language)
				.text("response_format", "json").file("file", "minecraft-voice.wav", "audio/wav", wav);
		byte[] body = form.finish();
		HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90))
				.header("Content-Type", form.contentType())
				// The service answers with "application/json" and no charset, and java.net.http
				// then falls back to a default charset; asking for UTF-8 explicitly is what
				// keeps Chinese transcripts from arriving as mojibake.
				.header("Accept", "application/json; charset=utf-8")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (!config.apiKey.isBlank()) request.header("Authorization", "Bearer " + config.apiKey);
		HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		// Decode the body as UTF-8 ourselves rather than letting HttpResponse.BodyHandlers
		// pick a charset: the service sends "application/json" without one, and a wrong
		// default turns Chinese into mojibake. Logging the codepoints makes that visible
		// in the game log instead of only on screen.
		String payload = new String(response.body(), StandardCharsets.UTF_8);
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("HTTP " + response.statusCode() + ": " + shorten(payload, 240));
		}
		String contentType = response.headers().firstValue("content-type").orElse("");
		String text;
		if (contentType.startsWith("text/plain")) {
			text = normalize(payload);
		} else {
			JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
			if (!json.has("text")) throw new IllegalStateException("接口响应中没有 text 字段");
			text = normalize(json.get("text").getAsString());
		}
		VoiceChatTranscriberClient.LOGGER.info("FunASR returned {} bytes; text='{}' codepoints={}",
				response.body().length, text, codepoints(text));
		return text;
	}

	/** First few codepoints as U+XXXX, so an encoding problem is identifiable from a log. */
	private static String codepoints(String text) {
		StringBuilder builder = new StringBuilder();
		text.codePoints().limit(12).forEach(code -> builder.append(String.format("U+%04X ", code)));
		return builder.toString().trim();
	}

	/** Pings the FunASR service health endpoint derived from the transcription URL. */
	static CompletableFuture<Integer> probeHealth(String endpoint) {
		URI transcription;
		try { transcription = URI.create(endpoint); }
		catch (RuntimeException invalid) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("接口地址无效"));
		}
		String scheme = transcription.getScheme();
		String authority = transcription.getRawAuthority();
		if (scheme == null || authority == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("接口地址无效"));
		}
		URI health = URI.create(scheme + "://" + authority + "/health");
		try { validateEndpoint(health); }
		catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
		HttpRequest request = HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(10)).GET().build();
		return PROBE_HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
				.thenApply(HttpResponse::statusCode);
	}

	/** What the FunASR service looks like right now. */
	enum ServiceState {
		/** Nothing is listening (or the address is unusable). */
		UNREACHABLE,
		/** The process is up but its models are still being downloaded/built. */
		STARTING,
		/** Answering and ready to transcribe. */
		READY
	}

	/**
	 * Reads {@code GET /health} and reports whether the service is merely up or actually
	 * ready to transcribe.
	 *
	 * <p>The distinction matters on a first run: the service accepts requests immediately
	 * but may spend minutes downloading and loading ~1.1 GB of models, and the user should
	 * be told that instead of being shown "not running" or waiting blind.
	 */
	static ServiceState healthState(VoiceTextConfig config, Duration timeout) {
		try {
			JsonObject health = probeHealthJson(config.transcriptionEndpoint())
					.get(timeout.toMillis() + 500L, java.util.concurrent.TimeUnit.MILLISECONDS);
			return health.has("ready") && health.get("ready").getAsBoolean()
					? ServiceState.READY : ServiceState.STARTING;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return ServiceState.UNREACHABLE;
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
				| RuntimeException unavailable) {
			return ServiceState.UNREACHABLE;
		}
	}

	/** True when the service is up and its models are loaded. */
	static boolean healthOk(VoiceTextConfig config, Duration timeout) {
		return healthState(config, timeout) == ServiceState.READY;
	}

	/** Parsed {@code /health} response, for callers that need the details. */
	static CompletableFuture<JsonObject> probeHealthJson(String endpoint) {
		URI transcription;
		try { transcription = URI.create(endpoint); }
		catch (RuntimeException invalid) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("接口地址无效"));
		}
		String scheme = transcription.getScheme();
		String authority = transcription.getRawAuthority();
		if (scheme == null || authority == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("接口地址无效"));
		}
		URI health = URI.create(scheme + "://" + authority + "/health");
		try { validateEndpoint(health); }
		catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
		HttpRequest request = HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(10)).GET().build();
		return PROBE_HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> JsonParser.parseString(response.body()).getAsJsonObject());
	}

	/**
	 * Warms the service by transcribing a short silent clip.
	 *
	 * <p>Only worth doing when the service answers but its models are not built yet (the
	 * lazy path). With the bundled launcher the service preloads them, so this normally
	 * finds the service ready and returns immediately. The generous timeout is the point:
	 * a first run can spend ~2.5 minutes downloading before the clip is even read, and the
	 * earlier 90 s limit is exactly what made the warm-up report a bogus timeout.
	 */
	static void prewarmAsync(VoiceTextConfig config) {
		Thread thread = new Thread(() -> {
			try {
				long deadline = System.currentTimeMillis() + FIRST_RUN_BUDGET.toMillis();
				while (System.currentTimeMillis() < deadline
						&& healthState(config, Duration.ofSeconds(3)) != ServiceState.READY) {
					Thread.sleep(2000L);
				}
				if (healthState(config, Duration.ofSeconds(3)) == ServiceState.READY) {
					VoiceChatTranscriberClient.LOGGER.info("FunASR service is ready; no warm-up needed");
					return;
				}
				byte[] silence = cn.codex.voicetext.WavEncoder.pcm16Mono(
						MicrophoneRecorder.SAMPLE_RATE, new byte[MicrophoneRecorder.SAMPLE_RATE]);
				new TranscriptionClient().transcribe(config, silence);
				VoiceChatTranscriberClient.LOGGER.info("FunASR warm-up finished");
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			} catch (Exception error) {
				VoiceChatTranscriberClient.LOGGER.warn("FunASR warm-up failed: {}", error.getMessage());
			}
		}, "voice-text-funasr-warmup");
		thread.setDaemon(true);
		thread.start();
	}

	private static void validateEndpoint(URI endpoint) {
		String scheme = endpoint.getScheme();
		if (endpoint.getHost() == null) throw new IllegalArgumentException("接口地址无效");
		boolean local = endpoint.getHost().equalsIgnoreCase("localhost") || endpoint.getHost().equals("127.0.0.1")
				|| endpoint.getHost().equals("::1");
		if (!"https".equalsIgnoreCase(scheme) && !(local && "http".equalsIgnoreCase(scheme))) {
			throw new IllegalArgumentException("远程接口必须使用 HTTPS；本机 localhost 可使用 HTTP");
		}
	}

	private static String normalize(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
	private static String shorten(String value, int maximum) {
		String clean = normalize(value); return clean.length() <= maximum ? clean : clean.substring(0, maximum) + "…";
	}
}
