package io.github.openrocketmcp.app;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.or.OrRuntime;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.tools.DesignLocks;

/**
 * RocketMaxxing, the desktop app: the same engine and tools as the MCP server, with its own interface, offline. Starts
 * the local server and opens the interface in the app's window (Chromium, {@link AppWindow}), which quits the app.
 */
public final class App {
	public static final String NAME = "RocketMaxxing";
	public static final String VERSION = "0.1.0";

	static final String USAGE = """
			Usage: rocketmaxxing [options]
			  --workspace DIR   folder for designs (default: ~/RocketMaxxing)
			  --port N          local port (default: any free one)
			  --ui DIR          folder of the built interface (default: next to the program)
			  --no-browser      do not open the window; print the address instead (to open it in a browser)
			  --dev URL         also accept calls from a development server, e.g. http://localhost:5173
			  FILE.ork ...      designs to open (a running RocketMaxxing for the same folder opens them instead)
			""";

	/** The page's address once the window is open; files that arrive before that wait in {@code pending}. */
	private static String baseUrl;
	private static final List<String> pending = new ArrayList<>();
	/** RocketMaxxing's window (Chromium), once open; null before, and with no window (--no-browser, --dev, no screen). */
	private static AppWindow window;
	/** Chromium starting up for the window while the engine starts. */
	private static CompletableFuture<AppWindow> chromium;

	private App() {
	}

	public static void main(String[] args) throws Exception {
		List<String> a = List.of(args);
		if (a.contains("--help") || a.contains("-h")) {
			System.out.print(USAGE);
			return;
		}
		if (System.getProperty("java.awt.headless") == null) {
			// The system's file dialogs need a screen; a Linux machine without one (a server, a container) has none.
			boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
			boolean screen = !linux || notBlank(System.getenv("DISPLAY")) || notBlank(System.getenv("WAYLAND_DISPLAY"));
			System.setProperty("java.awt.headless", screen ? "false" : "true");
		}
		if (MacDesktop.isMac()) {
			System.setProperty("apple.awt.application.name", NAME); // the menu bar's name, also when run from a terminal
			System.setProperty("apple.laf.useScreenMenuBar", "true"); // the window's menus in the Mac menu bar
			System.setProperty("apple.awt.application.appearance", "system"); // the title bar follows dark mode
		}
		Path ws = Path.of(opt(a, "--workspace", System.getProperty("user.home") + File.separator + NAME)).toAbsolutePath().normalize();
		Files.createDirectories(ws);
		List<String> files = designFiles(a);
		boolean noBrowser = a.contains("--no-browser");
		String devUrl = opt(a, "--dev", null);

		// One app per designs folder: a later launch hands its files to the running one and exits.
		Instance instance = devUrl == null ? new Instance(ws) : null;
		if (instance != null && !instance.claim()) {
			String running = instance.handOver(files, !noBrowser, 90_000);
			if (running != null) {
				if (noBrowser) {
					System.out.println("Open " + running);
				}
				Log.info(NAME + " is already running for " + ws + "; handed over to it.");
				System.exit(0);
			}
			Log.info("Another " + NAME + " holds " + ws + " but does not answer; starting anyway.");
			instance = null;
		}

		// The app's window, unless asked not to or there is no screen (a server, a container): then it prints the address.
		boolean windowed = !noBrowser && devUrl == null && !GraphicsEnvironment.isHeadless();
		Path bundle = windowed ? chromiumBundle() : null;

		// macOS: the Dock and Finder talk to the app from the start (a design double-clicked to launch it arrives now).
		AtomicReference<Context> ctxRef = new AtomicReference<>();
		if (devUrl == null) {
			MacDesktop.install(() -> show(List.of()), App::show, () -> unsaved(ctxRef.get()));
		}
		if (bundle != null) {
			// Two Chromiums cannot share a folder: one per designs folder, as there is one app per designs folder.
			String key = Instance.key(ws) + (instance == null ? "-" + ProcessHandle.current().pid() : "");
			chromium = startChromium(bundle, Instance.dir().resolve("chromium").resolve(key), () -> unsaved(ctxRef.get()));
		}

		OrRuntime.init(); // before any request: OpenRocket's databases fail for good if touched before it starts
		Context ctx = new Context(Standards.discover(ws), ws, false);
		ctxRef.set(ctx);
		McpServer server = Main.build(ctx, s -> AppTools.register(s, ctx), false);
		server.guard(new DesignLocks(ctx));
		server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
				+ "\"capabilities\":{},\"clientInfo\":{\"name\":\"" + NAME + "\",\"version\":\"" + VERSION + "\"}}}");
		Main.warmUp();
		List<String> dev = new ArrayList<>();
		if (devUrl != null) {
			dev.add(devUrl.replaceAll("/+$", ""));
		}
		AppServer app = new AppServer(server, ui(opt(a, "--ui", System.getProperty("rocketmaxxing.ui"))), dev);
		app.files(ws);
		server.tool(new ToolDef("app_window", "App: window", "Opens a window of the running app (another launch asks for it), "
				+ "with designs to open; returns its address.",
				Schema.object().array("files", "Design files to open.", Schema.type("string", null), false)
						.bool("window", "Open a window (default true).", false).build(),
				false, x -> {
					List<String> given = x.has("files") ? x.strList("files") : List.of();
					if (x.bool("window", true)) {
						show(given);
					}
					return Map.of("url", withFiles(app.url(), given));
				}));
		InetSocketAddress bound = app.start(Integer.parseInt(opt(a, "--port", "0")));
		Instance held = instance;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			app.stop();
			if (held != null) {
				held.release();
			}
		}));
		String url = app.url();
		if (instance != null) {
			instance.publish(bound.getPort(), app.token());
		}
		Log.info(NAME + " " + VERSION + " (engine " + Main.VERSION + ") on http://127.0.0.1:" + bound.getPort() + " ; workspace " + ws
				+ (MacDesktop.ACTIVE.isEmpty() ? "" : " ; macOS: " + String.join(", ", MacDesktop.ACTIVE)));
		if (windowed) {
			ready(url, files, ws);
		} else {
			System.out.println("Open " + withFiles(url, files));
			if (devUrl != null) {
				System.out.println("Development: open " + devUrl + "/#token=" + app.token() + "&port=" + bound.getPort());
			}
		}
		// The window quits the app (on the Mac, Quit does: a closed window waits in the Dock, as Mac apps do).
		new CountDownLatch(1).await();
	}

	/** The Chromium for the app's window, its library loaded (before AWT starts). Without one, says so and stops. */
	private static Path chromiumBundle() {
		try {
			Path bundle = Chromium.find();
			Chromium.preload(bundle);
			return bundle;
		} catch (IllegalStateException | LinkageError e) { // none, or the JCEF classes or library are missing or broken
			noWindow(e);
			return null;
		}
	}

	/**
	 * The window cannot open: says why, in a dialog too (an installed app has no terminal to read), and stops. With
	 * --no-browser the app still works from a browser.
	 */
	private static void noWindow(Throwable e) {
		String why = e.getMessage() != null ? e.getMessage() : e.toString();
		Log.info(NAME + "'s window cannot open: " + why);
		try {
			SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null, NAME + " cannot open its window.\n" + why
					+ "\n\nInstall " + NAME + " again, or start it with --no-browser and open the address it prints in a browser.",
					NAME, JOptionPane.ERROR_MESSAGE));
		} catch (Exception | LinkageError ignored) {
			// no dialog either: the log says why
		}
		System.exit(1);
	}

	/** Starts Chromium for the app's window in the background. */
	private static CompletableFuture<AppWindow> startChromium(Path bundle, Path cache, Supplier<List<String>> unsaved) {
		CompletableFuture<AppWindow> started = new CompletableFuture<>();
		Thread t = new Thread(() -> {
			try {
				started.complete(AppWindow.start(bundle, cache, unsaved));
			} catch (Throwable e) {
				started.completeExceptionally(e);
			}
		}, "chromium-start");
		t.setDaemon(true);
		t.start();
		return started;
	}

	/**
	 * Opens the window, with the files given at launch and any that arrived while starting. Waits for Chromium without
	 * holding the lock, so a design double-clicked meanwhile (handled on the event thread, which Chromium needs too)
	 * only queues up.
	 */
	private static void ready(String url, List<String> files, Path ws) {
		AppWindow w;
		try {
			w = chromium.get(2, TimeUnit.MINUTES);
			w.open(withFiles(url, files), ws);
		} catch (ExecutionException e) {
			noWindow(e.getCause());
			return;
		} catch (Exception | LinkageError e) {
			noWindow(e);
			return;
		}
		synchronized (App.class) {
			baseUrl = url;
			window = w;
			if (!pending.isEmpty()) {
				w.show(withFiles(url, pending));
				pending.clear();
			}
		}
	}

	/** The window in front, with these designs open: the Dock, a second launch and Finder all land there. */
	static synchronized void show(List<String> files) {
		if (window == null) {
			pending.addAll(files); // the window is still opening
			return;
		}
		window.show(files.isEmpty() ? null : withFiles(baseUrl, files));
	}

	/** The interface's window: "app" once it is open, else "none" (not yet, or no window: --no-browser, no screen). */
	static synchronized String windowKind() {
		return window != null ? "app" : "none";
	}

	/** Asks first when designs have unsaved changes; true to go on and quit. */
	static boolean mayQuit(Component parent, List<String> open) {
		return open.isEmpty() || JOptionPane.showConfirmDialog(parent,
				"Unsaved changes in " + String.join(", ", open) + ".\nQuit anyway and lose them?",
				"Quit " + NAME, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
	}

	static String withFiles(String url, List<String> files) {
		StringBuilder b = new StringBuilder(url);
		for (String f : files) {
			b.append("&open=").append(java.net.URLEncoder.encode(f, java.nio.charset.StandardCharsets.UTF_8));
		}
		return b.toString();
	}

	/** The design files among the arguments (what Windows and Linux pass for a double-clicked file). */
	static List<String> designFiles(List<String> a) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < a.size(); i++) {
			String s = a.get(i);
			if (s.startsWith("--")) {
				if (List.of("--workspace", "--port", "--ui", "--dev").contains(s)) {
					i++;
				}
				continue;
			}
			if (s.toLowerCase(Locale.ROOT).matches(".*\\.(ork|rkt)") && Files.isRegularFile(Path.of(s))) {
				out.add(Path.of(s).toAbsolutePath().normalize().toString());
			}
		}
		return out;
	}

	/** Names of open designs with unsaved changes. */
	static List<String> unsaved(Context ctx) {
		List<String> out = new ArrayList<>();
		if (ctx != null) {
			for (var d : ctx.designs.all()) {
				if (!d.doc.isSaved()) {
					out.add(d.name());
				}
			}
		}
		return out;
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	static String opt(List<String> a, String name, String fallback) {
		int i = a.indexOf(name);
		return i >= 0 && i + 1 < a.size() ? a.get(i + 1) : fallback;
	}

	/**
	 * The interface: --ui, else the "ui" folder of the installation (next to its lib folder, or next to the jars in an
	 * installer's app folder), else the copy in the jar.
	 */
	static Path ui(String given) {
		if (given != null) {
			return Path.of(given).toAbsolutePath().normalize();
		}
		try {
			Path jar = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			for (Path ui : List.of(jar.getParent().getParent().resolve("ui"), jar.getParent().resolve("ui"))) {
				if (Files.isRegularFile(ui.resolve("index.html"))) {
					return ui.toAbsolutePath().normalize();
				}
			}
		} catch (Exception ignored) {
			// not run from an installation
		}
		return null;
	}
}
