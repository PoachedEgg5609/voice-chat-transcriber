package cn.codex.voicetext.client;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

final class MicrophoneRecorder {
	static final int SAMPLE_RATE = 16_000;
	private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
	private TargetDataLine line;
	private volatile boolean recording;
	private CompletableFuture<byte[]> result;

	static List<String> availableDevices() {
		List<String> names = new ArrayList<>();
		DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, FORMAT);
		for (Mixer.Info info : AudioSystem.getMixerInfo()) {
			try {
				if (AudioSystem.getMixer(info).isLineSupported(wanted)) names.add(info.getName());
			} catch (Exception ignored) { }
		}
		return names;
	}

	synchronized void start(String requestedDevice, int maximumSeconds) throws LineUnavailableException {
		if (recording) throw new IllegalStateException("Microphone is already recording");
		DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, FORMAT);
		TargetDataLine opened = openLine(requestedDevice, wanted);
		if (opened == null) {
			throw new LineUnavailableException("没有可用的麦克风采集设备（需要 16 kHz / 16 位 / 单声道）。可选设备："
					+ String.join("、", availableDevices()));
		}
		line = opened;
		line.open(FORMAT, SAMPLE_RATE * 2);
		line.start();
		recording = true;
		result = new CompletableFuture<>();
		int maximumBytes = SAMPLE_RATE * 2 * maximumSeconds;
		Thread thread = new Thread(() -> capture(maximumBytes), "voice-text-microphone");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Finds a capture line for {@link #FORMAT}.
	 *
	 * <p>Asking the "default mixer" is not enough: on this machine the default is a
	 * DirectSound <em>playback</em> device, so {@code getLine} threw
	 * {@code Line unsupported: interface TargetDataLine}. The requested device is tried
	 * first, then the default, then every capture-capable mixer.
	 */
	private static TargetDataLine openLine(String requestedDevice, DataLine.Info wanted) {
		if (requestedDevice != null && !requestedDevice.isBlank()) {
			TargetDataLine fromRequest = targetLine(findMixer(requestedDevice), wanted);
			if (fromRequest != null) return fromRequest;
		}
		Mixer systemDefault = null;
		try { systemDefault = AudioSystem.getMixer(null); }
		catch (Exception ignored) { }
		TargetDataLine defaultLine = targetLine(systemDefault, wanted);
		if (defaultLine != null) return defaultLine;
		TargetDataLine capture = targetLine(firstCaptureMixer(wanted), wanted);
		if (capture != null) return capture;
		try { return (TargetDataLine) AudioSystem.getLine(wanted); }
		catch (Exception noLine) { return null; }
	}

	/** First mixer that advertises our 16 kHz mono capture format, or null. */
	private static Mixer firstCaptureMixer(DataLine.Info wanted) {
		for (Mixer.Info info : AudioSystem.getMixerInfo()) {
			try {
				Mixer mixer = AudioSystem.getMixer(info);
				if (mixer.isLineSupported(wanted)) return mixer;
			} catch (Exception ignored) { }
		}
		return null;
	}

	private static TargetDataLine targetLine(Mixer mixer, DataLine.Info wanted) {
		if (mixer == null) return null;
		try {
			if (!mixer.isLineSupported(wanted)) return null;
			return (TargetDataLine) mixer.getLine(wanted);
		} catch (Exception unsupported) {
			return null;
		}
	}

	synchronized CompletableFuture<byte[]> stop() {
		if (!recording) return result == null ? CompletableFuture.completedFuture(new byte[0]) : result;
		recording = false;
		TargetDataLine current = line;
		if (current != null) {
			current.stop();
			current.close();
		}
		return result;
	}

	synchronized void cancel() {
		CompletableFuture<byte[]> pending = stop();
		pending.cancel(false);
	}

	private void capture(int maximumBytes) {
		ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, SAMPLE_RATE * 10));
		byte[] buffer = new byte[4096];
		try {
			while (recording && output.size() < maximumBytes) {
				int count = line.read(buffer, 0, Math.min(buffer.length, maximumBytes - output.size()));
				if (count > 0) output.write(buffer, 0, count);
			}
		} catch (RuntimeException error) {
			if (recording) result.completeExceptionally(error);
		} finally {
			recording = false;
			TargetDataLine current = line;
			if (current != null && current.isOpen()) current.close();
			result.complete(output.toByteArray());
		}
	}

	private static Mixer findMixer(String requestedDevice) {
		if (requestedDevice != null && !requestedDevice.isBlank()) {
			for (Mixer.Info info : AudioSystem.getMixerInfo()) {
				if (info.getName().equals(requestedDevice)) return AudioSystem.getMixer(info);
			}
		}
		return AudioSystem.getMixer(null);
	}
}
