package cn.codex.voicetext;

import java.nio.file.Files;
import java.nio.file.Path;
import org.vosk.Model;
import org.vosk.Recognizer;

public final class OfflineEngineSmokeTest {
	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("Expected one Vosk model directory");
		Path modelPath = Path.of(args[0]).toAbsolutePath().normalize();
		if (!Files.isRegularFile(modelPath.resolve("am/final.mdl"))) {
			throw new IllegalArgumentException("Not a Vosk model directory: " + modelPath);
		}
		try (Model model = new Model(modelPath.toString()); Recognizer recognizer = new Recognizer(model, 16_000)) {
			byte[] silence = new byte[32_000];
			recognizer.acceptWaveForm(silence, silence.length);
			String result = recognizer.getFinalResult();
			if (!result.contains("\"text\"")) throw new AssertionError("Unexpected Vosk result: " + result);
			System.out.println("Offline Vosk engine smoke test passed: " + result);
		}
	}
}
