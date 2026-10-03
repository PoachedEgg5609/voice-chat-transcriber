package cn.codex.voicetext.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.loader.api.FabricLoader;

final class OfflineModelManager {
	static final String MODEL_NAME = "vosk-model-small-cn-0.22";
	private static final URI MODEL_URI = URI.create("https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip");
	private static final String MODEL_SHA256 = "3AF8B0E7E0F835AE9D414CE5DF580237A3CFB08D586C9FBBB0F7FF29AD5B14BA";
	private static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;
	private static final long MAX_EXTRACTED_BYTES = 512L * 1024 * 1024;
	private static final ExecutorService DOWNLOAD_WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "voice-text-model-download"); thread.setDaemon(true); return thread;
	});

	private OfflineModelManager() {}

	static Path modelDirectory() { return modelsRoot().resolve(MODEL_NAME); }

	static boolean isInstalled() {
		Path model = modelDirectory();
		return Files.isRegularFile(model.resolve("am/final.mdl"))
				&& Files.isRegularFile(model.resolve("conf/model.conf"))
				&& Files.isRegularFile(model.resolve("graph/HCLr.fst"));
	}

	static CompletableFuture<Path> downloadAsync() {
		return CompletableFuture.supplyAsync(() -> {
			try { return install(); }
			catch (Exception error) { throw new RuntimeException(error); }
		}, DOWNLOAD_WORKER);
	}

	private static Path install() throws Exception {
		if (isInstalled()) return modelDirectory();
		Path root = modelsRoot();
		Files.createDirectories(root);
		Path archive = root.resolve(MODEL_NAME + ".zip.part");
		Path staging = root.resolve(MODEL_NAME + ".installing");
		deleteTree(staging, root);
		try {
			download(archive);
			verifySha256(archive);
			Files.createDirectories(staging);
			extractSafely(archive, staging);
			Path extracted = staging.resolve(MODEL_NAME);
			if (!Files.isRegularFile(extracted.resolve("am/final.mdl"))) {
				throw new IOException("下载包中没有有效的 Vosk 中文模型");
			}
			Path destination = modelDirectory();
			deleteTree(destination, root);
			try {
				Files.move(extracted, destination, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException unsupportedAtomicMove) {
				Files.move(extracted, destination);
			}
			return destination;
		} finally {
			Files.deleteIfExists(archive);
			deleteTree(staging, root);
		}
	}

	private static void download(Path destination) throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest request = HttpRequest.newBuilder(MODEL_URI).timeout(Duration.ofMinutes(5)).GET().build();
		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) throw new IOException("模型下载失败：HTTP " + response.statusCode());
		long advertised = response.headers().firstValueAsLong("content-length").orElse(-1);
		if (advertised > MAX_DOWNLOAD_BYTES) throw new IOException("模型下载文件超过安全大小限制");
		try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(destination)) {
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			for (int count; (count = input.read(buffer)) >= 0;) {
				total += count;
				if (total > MAX_DOWNLOAD_BYTES) throw new IOException("模型下载文件超过安全大小限制");
				output.write(buffer, 0, count);
			}
		}
	}

	private static void verifySha256(Path archive) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream input = Files.newInputStream(archive)) {
			byte[] buffer = new byte[64 * 1024];
			for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
		}
		String actual = HexFormat.of().withUpperCase().formatHex(digest.digest());
		if (!MODEL_SHA256.equals(actual)) throw new IOException("模型文件校验失败，已拒绝安装");
	}

	private static void extractSafely(Path archive, Path staging) throws IOException {
		int entries = 0;
		long total = 0;
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
				if (++entries > 5000) throw new IOException("模型压缩包文件数量异常");
				Path target = staging.resolve(entry.getName()).normalize();
				if (!target.startsWith(staging)) throw new IOException("模型压缩包包含不安全路径");
				if (entry.isDirectory()) { Files.createDirectories(target); continue; }
				Files.createDirectories(target.getParent());
				try (OutputStream output = Files.newOutputStream(target)) {
					byte[] buffer = new byte[64 * 1024];
					for (int count; (count = zip.read(buffer)) >= 0;) {
						total += count;
						if (total > MAX_EXTRACTED_BYTES) throw new IOException("模型解压后超过安全大小限制");
						output.write(buffer, 0, count);
					}
				}
			}
		}
	}

	private static Path modelsRoot() {
		String localAppData = System.getenv("LOCALAPPDATA");
		Path base = localAppData == null || localAppData.isBlank()
				? FabricLoader.getInstance().getConfigDir() : Path.of(localAppData);
		return base.resolve("voice-chat-transcriber").resolve("models").toAbsolutePath().normalize();
	}

	private static void deleteTree(Path target, Path allowedRoot) throws IOException {
		Path normalized = target.toAbsolutePath().normalize();
		Path root = allowedRoot.toAbsolutePath().normalize();
		if (!normalized.startsWith(root) || normalized.equals(root) || !Files.exists(normalized)) return;
		try (var paths = Files.walk(normalized)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}
}
