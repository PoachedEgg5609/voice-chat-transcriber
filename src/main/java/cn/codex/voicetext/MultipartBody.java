package cn.codex.voicetext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MultipartBody {
	private final String boundary = "CodexVoice" + UUID.randomUUID().toString().replace("-", "");
	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

	public MultipartBody text(String name, String value) {
		write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + safe(name) + "\"\r\n\r\n");
		write(value == null ? "" : value); write("\r\n");
		return this;
	}

	public MultipartBody file(String name, String filename, String contentType, byte[] data) {
		write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + safe(name)
				+ "\"; filename=\"" + safe(filename) + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
		try { bytes.write(data); } catch (IOException impossible) { throw new IllegalStateException(impossible); }
		write("\r\n");
		return this;
	}

	public String contentType() { return "multipart/form-data; boundary=" + boundary; }
	public byte[] finish() { write("--" + boundary + "--\r\n"); return bytes.toByteArray(); }

	private static String safe(String value) { return value.replace("\"", "").replace("\r", "").replace("\n", ""); }
	private void write(String value) {
		try { bytes.write(value.getBytes(StandardCharsets.UTF_8)); }
		catch (IOException impossible) { throw new IllegalStateException(impossible); }
	}
}
