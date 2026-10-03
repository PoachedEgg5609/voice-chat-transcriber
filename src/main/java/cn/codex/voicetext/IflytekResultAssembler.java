package cn.codex.voicetext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Applies iFLYTEK wpgs append/replace responses and exposes the current transcript. */
public final class IflytekResultAssembler {
	private final NavigableMap<Integer, String> segments = new TreeMap<>();

	public synchronized boolean accept(String response) {
		JsonObject root = JsonParser.parseString(response).getAsJsonObject();
		int code = integer(root, "code", -1);
		if (code != 0) {
			String message = text(root, "message", "未知错误");
			throw new IllegalStateException("讯飞错误 " + code + "：" + message);
		}
		JsonObject data = object(root, "data");
		if (data == null) return false;
		JsonObject result = object(data, "result");
		if (result != null) apply(result);
		return integer(data, "status", -1) == 2;
	}

	public synchronized String text() {
		StringBuilder combined = new StringBuilder();
		segments.values().forEach(combined::append);
		return TranscriptNormalizer.chinese(combined.toString());
	}

	private void apply(JsonObject result) {
		int sequence = integer(result, "sn", segments.isEmpty() ? 0 : segments.lastKey() + 1);
		String mode = text(result, "pgs", "apd");
		if ("rpl".equals(mode)) {
			JsonArray range = array(result, "rg");
			if (range != null && range.size() == 2) {
				int first = range.get(0).getAsInt();
				int last = range.get(1).getAsInt();
				segments.subMap(Math.min(first, last), true, Math.max(first, last), true).clear();
			}
		}
		segments.put(sequence, words(result));
	}

	private static String words(JsonObject result) {
		JsonArray groups = array(result, "ws");
		if (groups == null) return "";
		StringBuilder text = new StringBuilder();
		for (JsonElement groupElement : groups) {
			if (!groupElement.isJsonObject()) continue;
			JsonArray candidates = array(groupElement.getAsJsonObject(), "cw");
			if (candidates == null || candidates.isEmpty() || !candidates.get(0).isJsonObject()) continue;
			text.append(text(candidates.get(0).getAsJsonObject(), "w", ""));
		}
		return text.toString();
	}

	private static JsonObject object(JsonObject parent, String name) {
		JsonElement value = parent.get(name);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}
	private static JsonArray array(JsonObject parent, String name) {
		JsonElement value = parent.get(name);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
	}
	private static int integer(JsonObject parent, String name, int fallback) {
		JsonElement value = parent.get(name);
		try { return value == null || value.isJsonNull() ? fallback : value.getAsInt(); }
		catch (RuntimeException ignored) { return fallback; }
	}
	private static String text(JsonObject parent, String name, String fallback) {
		JsonElement value = parent.get(name);
		try { return value == null || value.isJsonNull() ? fallback : value.getAsString(); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
