package cn.codex.voicetext.client;

import cn.codex.voicetext.TranscriptNormalizer;
import cn.codex.voicetext.VoskRuntime;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Path;
import org.vosk.Model;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Recognizer;

final class OfflineTranscriptionClient {
	private Model loadedModel;
	private Path loadedPath;

	synchronized String transcribe(byte[] pcm) throws IOException {
		Path modelPath = OfflineModelManager.modelDirectory();
		if (!OfflineModelManager.isInstalled()) {
			throw new IOException("离线中文模型尚未安装，请按 K 在设置中下载");
		}
		if (loadedModel == null || !modelPath.equals(loadedPath)) {
			if (loadedModel != null) loadedModel.close();
			VoskRuntime.prepareNativeStrings();
			LibVosk.setLogLevel(LogLevel.WARNINGS);
			loadedModel = new Model(modelPath.toString());
			loadedPath = modelPath;
		}
		try (Recognizer recognizer = new Recognizer(loadedModel, MicrophoneRecorder.SAMPLE_RATE)) {
			recognizer.acceptWaveForm(pcm, pcm.length);
			JsonObject result = JsonParser.parseString(recognizer.getFinalResult()).getAsJsonObject();
			String text = result.has("text") ? result.get("text").getAsString() : "";
			return TranscriptNormalizer.chinese(text);
		}
	}
}
