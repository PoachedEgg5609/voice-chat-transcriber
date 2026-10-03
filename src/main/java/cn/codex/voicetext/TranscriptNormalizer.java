package cn.codex.voicetext;

public final class TranscriptNormalizer {
	private TranscriptNormalizer() {}

	public static String chinese(String value) {
		if (value == null) return "";
		return value.trim().replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})", "")
				.replaceAll("\\s+", " ");
	}
}
