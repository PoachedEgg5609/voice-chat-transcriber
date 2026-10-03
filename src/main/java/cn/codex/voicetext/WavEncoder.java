package cn.codex.voicetext;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class WavEncoder {
	private WavEncoder() {}

	public static byte[] pcm16Mono(int sampleRate, byte[] pcm) {
		if (sampleRate <= 0 || pcm == null || (pcm.length & 1) != 0) {
			throw new IllegalArgumentException("PCM must be 16-bit mono data with a positive sample rate");
		}
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(44 + pcm.length);
			DataOutputStream out = new DataOutputStream(bytes);
			ascii(out, "RIFF"); little32(out, 36 + pcm.length); ascii(out, "WAVE");
			ascii(out, "fmt "); little32(out, 16); little16(out, 1); little16(out, 1);
			little32(out, sampleRate); little32(out, sampleRate * 2); little16(out, 2); little16(out, 16);
			ascii(out, "data"); little32(out, pcm.length); out.write(pcm);
			return bytes.toByteArray();
		} catch (IOException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static void ascii(DataOutputStream out, String value) throws IOException { out.writeBytes(value); }
	private static void little16(DataOutputStream out, int value) throws IOException {
		out.writeByte(value & 0xFF); out.writeByte((value >>> 8) & 0xFF);
	}
	private static void little32(DataOutputStream out, int value) throws IOException {
		out.writeByte(value & 0xFF); out.writeByte((value >>> 8) & 0xFF);
		out.writeByte((value >>> 16) & 0xFF); out.writeByte((value >>> 24) & 0xFF);
	}
}
