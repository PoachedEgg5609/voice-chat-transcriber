package cn.codex.voicetext.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Launches the bundled lean FunASR service on demand.
 *
 * <p>The scripts ship inside the mod jar and are extracted next to the config file
 * on first use, so a fresh machine needs no manual file placement. The process is
 * started detached (the PowerShell bootstrap uses {@code Start-Process} with a
 * hidden window), which means it survives Minecraft exiting instead of becoming an
 * orphan child that keeps port 8321 busy.
 */
final class FunasrLauncher {
	enum Status { NOT_RUNNING, STARTING, READY, FAILED }

	/** Location inside the jar. */
	private static final String SCRIPT_RESOURCE = "/funasr/start-funasr.ps1";
	private static final String SERVER_RESOURCE = "/funasr/funasr_server.py";
	/** Location once extracted. */
	static final String SCRIPT_NAME = "start-funasr.ps1";
	static final String SERVER_NAME = "funasr_server.py";
	static final String LOG_NAME = "logs/funasr-server.log";
	/** Records which process asked for the service, so only that one stops it again. */
	private static final String OWNER_FILE = "funasr-server.owner";
	/**
	 * Written by the launcher script as its very first action.
	 *
	 * <p>An empty service log means the bootstrap died before it could log anything, which
	 * is indistinguishable from "the script never ran". This marker tells the two apart
	 * and also records which Windows PowerShell ran it.
	 */
	static final String TRACE_NAME = "launcher-trace.txt";

	private static final Object LOCK = new Object();
	private static volatile Status status = Status.NOT_RUNNING;
	private static volatile String lastFailure = "";
	private static volatile Path root;

	private FunasrLauncher() {}

	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/** Directory that holds the extracted scripts, the runtime venv and the models. */
	static synchronized Path root() {
		if (root == null) {
			root = FabricLoader.getInstance().getConfigDir()
					.resolve("voice-chat-transcriber").toAbsolutePath().normalize();
		}
		return root;
	}

	static Status status() { return status; }
	static String lastFailure() { return lastFailure; }

	/** Extracts the bundled scripts when they are missing or outdated. */
	static void ensureScripts() throws IOException {
		Path target = root();
		Files.createDirectories(target);
		extract(SCRIPT_RESOURCE, target.resolve(SCRIPT_NAME), true);
		extract(SERVER_RESOURCE, target.resolve(SERVER_NAME), false);
	}

	private static void extract(String resource, Path destination, boolean alwaysRefresh) throws IOException {
		if (!alwaysRefresh && Files.isRegularFile(destination)) return;
		try (InputStream input = FunasrLauncher.class.getResourceAsStream(resource)) {
			if (input == null) throw new IOException("模组 jar 内缺少资源：" + resource);
			Files.createDirectories(destination.getParent());
			Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
			Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Starts the service without blocking; {@link #status()} tracks the outcome. */
	static CompletableFuture<Status> startAsync(VoiceTextConfig config) {
		synchronized (LOCK) {
			if (status == Status.STARTING) return CompletableFuture.completedFuture(status);
			// A previous run may have left a python child behind that is still downloading
			// or building models (the wait window is minutes long). Starting a second
			// bootstrap then would race for the same port, so when something already holds
			// it, attach to that instead of spawning another one.
			if (portInUse()) {
				VoiceChatTranscriberClient.LOGGER.info(
						"Port {} is already in use; attaching to the running FunASR service instead of starting another",
						VoiceTextConfig.DEFAULT_FUNASR_PORT);
				status = Status.STARTING;
				lastFailure = "";
				return CompletableFuture.supplyAsync(() -> {
					Status result = awaitHealth(600, config) ? Status.READY : Status.FAILED;
					if (result == Status.FAILED) lastFailure = explainFailure();
					synchronized (LOCK) { status = result; }
					return result;
				});
			}
			status = Status.STARTING;
			lastFailure = "";
		}
		return CompletableFuture.supplyAsync(() -> {
			Status result;
			try {
				runBootstrap();
				// The spawn succeeds long before the server answers, and with --preload the
				// server only answers /health once its models exist, so this waits for the
				// honest "ready" instead of the old "the port is open" guess.
				result = awaitHealth(600, config) ? Status.READY : Status.FAILED;
				if (result == Status.READY && config != null && config.prewarm) {
					// No-op on the bundled service (already preloaded); this exists for a
					// service the player runs themselves in lazy mode.
					TranscriptionClient.prewarmAsync(config);
				}
				if (result == Status.FAILED) lastFailure = explainFailure();
			} catch (Exception error) {
				lastFailure = describe(error);
				result = Status.FAILED;
			}
			synchronized (LOCK) { status = result; }
			return result;
		});
	}

	/**
	 * Builds a failure message the player can act on.
	 *
	 * <p>A silent failure used to be the whole report ("no log, no models"), so this quotes
	 * the service log when there is one, and otherwise quotes the launcher trace — which
	 * distinguishes "the script never ran" from "the script ran but python did not start".
	 */
	private static String explainFailure() {
		String tail = logTail(6).trim();
		if (!tail.isEmpty()) {
			return "服务未就绪，日志末尾：" + tail.replaceAll("\\s+", " ");
		}
		String trace = traceTail();
		return "服务未就绪、没有服务日志。启动追踪：" + trace;
	}

	/**
	 * Polls the health endpoint until the service reports that it is ready.
	 *
	 * <p>{@code attempts} is in seconds; a first run can legitimately take minutes because
	 * the models have to be downloaded and built before the service answers.
	 */
	private static boolean awaitHealth(int attempts, VoiceTextConfig config) {
		for (int attempt = 0; attempt < attempts; attempt++) {
			if (TranscriptionClient.healthOk(config, Duration.ofSeconds(2))) return true;
			try { Thread.sleep(1000L); }
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	/** True when the FunASR service is up *and* its models are loaded. */
	static boolean isServiceUp(VoiceTextConfig config) {
		return TranscriptionClient.healthOk(config, Duration.ofSeconds(2));
	}

	/**
	 * Stops the service if this game session is the one that started it, so closing the
	 * game does not leave a Python process (and ~1 GB of models) behind.
	 *
	 * <p>A service the player started by hand beforehand is deliberately left running:
	 * the owner file only matches when our own pid recorded it.
	 */
	static void stopIfWeStartedIt() {
		long mine = ProcessHandle.current().pid();
		if (!readFile(root().resolve(OWNER_FILE)).equals(Long.toString(mine))) return;
		int port = VoiceTextConfig.DEFAULT_FUNASR_PORT;
		VoiceChatTranscriberClient.LOGGER.info("Stopping the FunASR service this session started (port {})", port);
		try {
			ProcessBuilder builder = new ProcessBuilder(
					"powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
					"-WindowStyle", "Hidden", "-File", root().resolve(SCRIPT_NAME).toString(),
					"stop", "-Port", Integer.toString(port));
			builder.directory(root().toFile());
			builder.redirectErrorStream(true);
			Process process = builder.start();
			process.getInputStream().readAllBytes();
			// Called from CLIENT_STOPPING on the render thread: an unbounded wait here would
			// stop the game from ever closing.
			if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				VoiceChatTranscriberClient.LOGGER.warn("Stopping the FunASR service timed out; killed the helper script");
			}
		} catch (Exception error) {
			VoiceChatTranscriberClient.LOGGER.warn("Could not stop the FunASR service: {}", describe(error));
		}
	}

	private static String readFile(Path path) {
		try {
			return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8).trim() : "";
		} catch (IOException error) {
			return "";
		}
	}

	private static void runBootstrap() throws IOException, InterruptedException {
		if (!isWindows()) {
			throw new IOException("自动启动目前仅支持 Windows，请手动运行 start-funasr.ps1");
		}
		ensureScripts();
		Path script = root().resolve(SCRIPT_NAME);
		Files.deleteIfExists(root().resolve(TRACE_NAME));

		Process process = null;
		IOException failure = null;
		for (String shell : new String[] {"powershell.exe", "pwsh.exe"}) {
			try {
				ProcessBuilder builder = new ProcessBuilder(
						shell, "-NoProfile", "-ExecutionPolicy", "Bypass",
						"-WindowStyle", "Hidden", "-File", script.toString(),
						"start", "-Port", Integer.toString(VoiceTextConfig.DEFAULT_FUNASR_PORT),
						// The owner lets stopAsync() tell "we started it" apart from "it was
						// already running", so a manually started service is never killed on
						// game exit.
						"-OwnerPid", Long.toString(ProcessHandle.current().pid()), "-NoWait");
				builder.directory(root().toFile());
				builder.redirectErrorStream(true);
				process = builder.start();
				break;
			} catch (IOException missing) {
				failure = missing;
				VoiceChatTranscriberClient.LOGGER.warn("Could not run {}: {}", shell, missing.getMessage());
			}
		}
		if (process == null) {
			// Both shells missing: report it rather than the vaguer "exited with code".
			throw new IOException("无法运行 PowerShell：" + describe(failure)
					+ "（自动启动需要 Windows PowerShell，或改用 pwsh）");
		}

		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		// Bounded waiting: powershell hands its stdout handle down to the python child it
		// spawns, so an unhealthy exit could leave waitFor() blocked forever.
		if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("启动脚本 120 秒内没有返回（追踪见 " + TRACE_NAME + "）：" + traceTail());
		}
		int code = process.exitValue();
		trace("exit-code=" + code + " output=" + output.replaceAll("\\s+", " "));
		if (code != 0) {
			throw new IOException("启动脚本退出码 " + code + "：" + shorten(output));
		}

		// The script exits 0 after spawning python, so "did the service really come up?" is
		// only answerable from the trace it appends once python is running. Without this an
		// early python failure looks like a silent hang.
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(45).toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (readFile(root().resolve(TRACE_NAME)).contains("python-pid=")) return;
			// A service already bound to the port is exactly what we wanted: hand over to
			// awaitHealth() to decide whether it is actually ready.
			if (portInUse()) return;
			Thread.sleep(1000L);
		}
		throw new IOException("脚本已运行但没有启动 Python 服务（追踪见 " + TRACE_NAME + "）：" + traceTail());
	}

	/** Appends a launcher-side note to the same trace file the script writes. */
	private static void trace(String text) {
		try {
			Files.writeString(root().resolve(TRACE_NAME),
					"launcher: " + text + System.lineSeparator(),
					StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) { }
	}

	private static String traceTail() {
		String trace = readFile(root().resolve(TRACE_NAME));
		if (trace.isEmpty()) return "追踪文件为空（脚本没有执行到第一行）";
		String[] lines = trace.split("\\R");
		int from = Math.max(0, lines.length - 3);
		return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
	}

	private static boolean portInUse() {
		try (java.net.ServerSocket probe = new java.net.ServerSocket(
				VoiceTextConfig.DEFAULT_FUNASR_PORT, 1, java.net.InetAddress.getLoopbackAddress())) {
			return false;
		} catch (IOException inUse) {
			return true;
		}
	}

	/** Reads up to {@code lines} lines from the end of the service log. */
	static String logTail(int lines) {
		Path log = root().resolve(LOG_NAME);
		if (!Files.isRegularFile(log)) return "";
		try {
			var all = Files.readAllLines(log, StandardCharsets.UTF_8);
			int from = Math.max(0, all.size() - lines);
			return String.join("\n", all.subList(from, all.size()));
		} catch (IOException error) {
			return "";
		}
	}

	private static String describe(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) current = current.getCause();
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}

	private static String shorten(String text) {
		String clean = text == null ? "" : text.trim();
		if (clean.isEmpty()) return "启动脚本没有输出";
		return clean.length() <= 400 ? clean : clean.substring(clean.length() - 400);
	}
}
