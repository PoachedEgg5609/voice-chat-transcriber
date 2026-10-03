package cn.codex.voicetext;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class IflytekHandshakeSmokeTest {
	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("Pass the config file path");
		JsonObject config = JsonParser.parseString(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8))
				.getAsJsonObject();
		String apiKey = required(config, "iflytekApiKey");
		String apiSecret = required(config, "iflytekApiSecret");
		required(config, "iflytekAppId");
		URI signed = IflytekAuth.signedEndpoint(URI.create("wss://iat-api.xfyun.cn/v2/iat"),
				apiKey, apiSecret, Instant.now());
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
		try {
			WebSocket socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
					.buildAsync(signed, new WebSocket.Listener() {}).get(20, TimeUnit.SECONDS);
			socket.abort();
			System.out.println("iFLYTEK WebSocket handshake passed");
		} catch (ExecutionException error) {
			WebSocketHandshakeException handshake = findHandshake(error);
			if (handshake != null) {
				throw new IllegalStateException("iFLYTEK handshake rejected with HTTP "
						+ handshake.getResponse().statusCode());
			}
			throw new IllegalStateException("iFLYTEK handshake failed: " + root(error).getClass().getSimpleName());
		}
	}

	private static String required(JsonObject config, String name) {
		if (!config.has(name) || config.get(name).isJsonNull() || config.get(name).getAsString().isBlank()) {
			throw new IllegalArgumentException(name + " is missing");
		}
		return config.get(name).getAsString().trim();
	}

	private static WebSocketHandshakeException findHandshake(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof WebSocketHandshakeException handshake) return handshake;
			current = current.getCause();
		}
		return null;
	}

	private static Throwable root(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) current = current.getCause();
		return current;
	}
}
