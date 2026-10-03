package cn.codex.voicetext;

/** Configures JNA before Vosk registers its native UTF-8 string boundary. */
public final class VoskRuntime {
	private VoskRuntime() {}

	public static void prepareNativeStrings() {
		System.setProperty("jna.encoding", "UTF-8");
	}
}
