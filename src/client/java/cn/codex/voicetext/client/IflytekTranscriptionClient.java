package cn.codex.voicetext.client;

import cn.codex.voicetext.IflytekAuth;
import cn.codex.voicetext.IflytekResultAssembler;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

final class IflytekTranscriptionClient {
	private static final URI ENDPOINT = URI.create("wss://iat-api.xfyun.cn/v2/iat");
	private static final int FRAME_BYTES = 1280;
	private static final long FRAME_INTERVAL_MILLIS = 40L;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	String transcribe(VoiceTextConfig config, byte[] pcm) throws IOException {
		if (config.iflytekAppId.isBlank() || config.iflytekApiKey.isBlank() || config.iflytekApiSecret.isBlank()) {
			throw new IllegalStateException("请先填写讯飞 AppID、APIKey 和 APISecret");
		}
		if (pcm.length == 0) throw new IllegalArgumentException("没有可识别的录音数据");
		WebSocket socket = null;
		try {
			IflytekListener listener = new IflytekListener();
			URI signed = IflytekAuth.signedEndpoint(ENDPOINT, config.iflytekApiKey,
					config.iflytekApiSecret, Instant.now());
			socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
					.buildAsync(signed, listener).get(20, TimeUnit.SECONDS);
			int offset = 0;
			int firstLength = Math.min(FRAME_BYTES, pcm.length);
			send(socket, firstFrame(config.iflytekAppId, pcm, 0, firstLength));
			offset += firstLength;
			while (offset < pcm.length && !listener.finished.isDone()) {
				Thread.sleep(FRAME_INTERVAL_MILLIS);
				int length = Math.min(FRAME_BYTES, pcm.length - offset);
				send(socket, audioFrame(1, pcm, offset, length));
				offset += length;
			}
			if (!listener.finished.isDone()) {
				Thread.sleep(FRAME_INTERVAL_MILLIS);
				send(socket, finalFrame());
			}
			String result = listener.finished.get(90, TimeUnit.SECONDS);
			if (!socket.isOutputClosed()) socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
			return result;
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("讯飞识别已取消");
		} catch (Exception error) {
			WebSocketHandshakeException handshake = findHandshake(error);
			if (handshake != null) {
				int status = handshake.getResponse().statusCode();
				throw new IOException("讯飞握手被拒绝（HTTP " + status
						+ "），请检查系统时间、应用凭据、流式听写权限和 IP 白名单");
			}
			Throwable root = root(error);
			String detail = root.getMessage();
			if (detail != null && detail.startsWith("讯飞错误 ")) throw new IOException(detail);
			throw new IOException("讯飞连接或识别失败（" + root.getClass().getSimpleName() + "）");
		} finally {
			if (socket != null && !socket.isOutputClosed()) socket.abort();
		}
	}

	private static JsonObject firstFrame(String appId, byte[] pcm, int offset, int length) {
		JsonObject root = new JsonObject();
		JsonObject common = new JsonObject();
		common.addProperty("app_id", appId);
		JsonObject business = new JsonObject();
		business.addProperty("language", "zh_cn");
		business.addProperty("domain", "iat");
		business.addProperty("accent", "mandarin");
		business.addProperty("eos", 3000);
		business.addProperty("dwa", "wpgs");
		business.addProperty("ptt", 1);
		root.add("common", common);
		root.add("business", business);
		root.add("data", audioData(0, pcm, offset, length));
		return root;
	}

	private static JsonObject audioFrame(int status, byte[] pcm, int offset, int length) {
		JsonObject root = new JsonObject();
		root.add("data", audioData(status, pcm, offset, length));
		return root;
	}

	private static JsonObject finalFrame() {
		JsonObject root = new JsonObject();
		JsonObject data = new JsonObject();
		data.addProperty("status", 2);
		root.add("data", data);
		return root;
	}

	private static JsonObject audioData(int status, byte[] pcm, int offset, int length) {
		JsonObject data = new JsonObject();
		data.addProperty("status", status);
		data.addProperty("format", "audio/L16;rate=16000");
		data.addProperty("encoding", "raw");
		data.addProperty("audio", Base64.getEncoder().encodeToString(
				Arrays.copyOfRange(pcm, offset, offset + length)));
		return data;
	}

	private static void send(WebSocket socket, JsonObject frame) throws Exception {
		socket.sendText(frame.toString(), true).get(15, TimeUnit.SECONDS);
	}

	private static Throwable root(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) current = current.getCause();
		return current;
	}

	private static WebSocketHandshakeException findHandshake(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof WebSocketHandshakeException handshake) return handshake;
			current = current.getCause();
		}
		return null;
	}

	private static final class IflytekListener implements WebSocket.Listener {
		private static final int MAX_MESSAGE_CHARS = 1_048_576;
		private final IflytekResultAssembler result = new IflytekResultAssembler();
		private final StringBuilder message = new StringBuilder();
		private final CompletableFuture<String> finished = new CompletableFuture<>();

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			try {
				if (message.length() + data.length() > MAX_MESSAGE_CHARS) {
					throw new IllegalStateException("讯飞响应超过安全大小限制");
				}
				message.append(data);
				if (last) {
					String complete = message.toString();
					message.setLength(0);
					if (result.accept(complete)) finished.complete(result.text());
				}
			} catch (Exception error) {
				finished.completeExceptionally(error);
				webSocket.abort();
			}
			webSocket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			finished.completeExceptionally(new IOException("讯飞返回了非文本响应"));
			webSocket.abort();
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			if (!finished.isDone()) finished.completeExceptionally(new IOException("讯飞连接提前关闭：" + statusCode));
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			finished.completeExceptionally(new IOException("讯飞 WebSocket 通信失败"));
		}
	}
}
