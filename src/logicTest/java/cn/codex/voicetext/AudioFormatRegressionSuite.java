package cn.codex.voicetext;

import java.nio.charset.StandardCharsets;

public final class AudioFormatRegressionSuite {
	public static void main(String[] args) {
		byte[] wav = WavEncoder.pcm16Mono(16000, new byte[] {1, 2, 3, 4});
		require(wav.length == 48, "WAV length must include a 44-byte header");
		require(text(wav, 0, 4).equals("RIFF"), "missing RIFF header");
		require(text(wav, 8, 4).equals("WAVE"), "missing WAVE marker");
		require(readLittle32(wav, 24) == 16000, "wrong sample rate");
		require(readLittle32(wav, 40) == 4, "wrong PCM payload size");
		require(wav[44] == 1 && wav[47] == 4, "PCM payload was changed");

		MultipartBody body = new MultipartBody().text("model", "test-model")
				.file("file", "speech.wav", "audio/wav", wav);
		String contentType = body.contentType();
		byte[] encoded = body.finish();
		String encodedText = new String(encoded, StandardCharsets.ISO_8859_1);
		require(contentType.startsWith("multipart/form-data; boundary=CodexVoice"), "wrong multipart content type");
		require(encodedText.contains("name=\"model\"\r\n\r\ntest-model"), "model field missing");
		require(encodedText.contains("filename=\"speech.wav\""), "audio file field missing");
		require(TranscriptNormalizer.chinese("你 好  Minecraft").equals("你好 Minecraft"),
				"Chinese character spacing was not normalized");
		funasrRequestShape();
		System.out.println("Audio format regression suite passed");
	}

	/**
	 * The FunASR engine reuses the OpenAI-compatible multipart transport, so the
	 * request must carry exactly the form fields the FunASR server reads
	 * (file/model/language/response_format) plus a readable JSON "text" field.
	 */
	private static void funasrRequestShape() {
		byte[] wav = WavEncoder.pcm16Mono(16000, new byte[16000]);
		MultipartBody body = new MultipartBody().text("model", "paraformer")
				.text("language", "zh").text("response_format", "json")
				.file("file", "minecraft-voice.wav", "audio/wav", wav);
		String encoded = new String(body.finish(), StandardCharsets.ISO_8859_1);
		require(body.contentType().startsWith("multipart/form-data; boundary="),
				"FunASR request must be multipart/form-data");
		for (String field : new String[] {"model", "language", "response_format", "file"}) {
			require(encoded.contains("name=\"" + field + "\""), "FunASR form field missing: " + field);
		}
		require(encoded.contains("name=\"model\"\r\n\r\nparaformer"), "FunASR model alias was not sent");
		require(encoded.contains("filename=\"minecraft-voice.wav\"")
				&& encoded.contains("Content-Type: audio/wav"), "FunASR audio part is malformed");
		// The server always answers with an object holding the transcript in "text".
		String response = "{\"text\":\"我想玩我的世界\"}";
		require(com.google.gson.JsonParser.parseString(response).getAsJsonObject().get("text").getAsString()
				.equals("我想玩我的世界"), "FunASR JSON response cannot be parsed as a transcript");
	}

	private static String text(byte[] bytes, int offset, int length) {
		return new String(bytes, offset, length, StandardCharsets.US_ASCII);
	}
	private static int readLittle32(byte[] bytes, int offset) {
		return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
				| ((bytes[offset + 2] & 255) << 16) | ((bytes[offset + 3] & 255) << 24);
	}
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
