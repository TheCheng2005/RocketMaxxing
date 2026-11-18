package io.github.openrocketmcp.app;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;


import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.cef.handler.CefFocusHandler.FocusSource;
import org.cef.handler.CefFocusHandlerAdapter;
import org.cef.handler.CefJSDialogHandler.JSDialogType;
import org.cef.handler.CefKeyboardHandler.CefKeyEvent;
import org.cef.handler.CefKeyboardHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefRequestHandler.TerminationStatus;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.EventFlags;
import org.cef.network.CefRequest;

import io.github.openrocketmcp.mcp.Log;

/**
 * RocketMaxxing's own window: the interface in Chromium (JCEF, through jcefmaven) inside a Swing frame, talking to the
 * local engine on 127.0.0.1 with the launch's token, like a browser window did. It only ever shows the app's own
 * pages: links to anything else open in the system's browser, and nothing dropped on it replaces the app.
 */
final class AppWindow {
	private static final boolean MAC = MacDesktop.isMac();

	private CefApp cef;
	private final Supplier<List<String>> unsaved;
	private JFrame frame;
	/** The window, for the system's file dialogs to belong to (null before it opens, or without one). */
	private static volatile Frame shown;
	private CefBrowser browser;
	private String home;
	private String origin;
	private Path workspace;
	private volatile long reloaded;
	/** Downloads the page has been told about (by Chromium's id). */
	private final java.util.Set<Integer> told = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private AppWindow(Supplier<List<String>> unsaved) {
		this.unsaved = unsaved;
	}

	/**
	 * Starts Chromium from the unpacked {@code bundle} (about a second: start it while the engine starts). {@code cache}
	 * is Chromium's own folder for this designs folder, since two Chromiums cannot share one.
	 * {@code -Drocketmaxxing.chromium.args} adds Chromium switches, for development and support only:
	 * {@code --remote-debugging-port=9222} (DevTools at chrome://inspect; any local program could then control the
	 * window) or {@code --disable-gpu} (a graphics driver that draws nothing).
	 */
	static AppWindow start(Path bundle, Path cache, Supplier<List<String>> unsaved) throws Exception {
		Files.createDirectories(cache);
		AppWindow w = new AppWindow(unsaved);
		CefAppBuilder b = new CefAppBuilder();
		b.setInstallDir(bundle.toFile());
		b.setSkipInstallation(true); // Chromium.find checked it; never download or unpack here
		b.setMirrors(List.of());
		b.setProgressHandler((step, done) -> {
		});
		CefSettings s = b.getCefSettings();
		s.windowless_rendering_enabled = false; // a real window, drawn by the GPU (the 3-D view)
		s.root_cache_path = cache.toString(); // cache_path stays empty: the page's own storage is kept in memory
		s.log_file = cache.resolve("chromium.log").toString();
		s.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
		// Chromium's own services reach Google's servers by themselves (component and list updates, the network time,
		// account checks, search warm-up) and fill the cache folder with downloads. The window shows only the engine's
		// pages, on 127.0.0.1, and RocketMaxxing works offline: no name resolves, so none of that leaves the computer
		// (links open in the system's browser).
		b.addJcefArgs("--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1", "--disable-background-networking",
				"--disable-component-update", "--disable-domain-reliability", "--disable-sync", "--no-pings",
				"--no-first-run", "--disable-breakpad");
		if (MAC) {
			b.addJcefArgs("--use-mock-keychain"); // no Keychain prompt for a password store the window does not use
		}
		for (String arg : System.getProperty("rocketmaxxing.chromium.args", "").trim().split("\\s+")) {
			if (!arg.isEmpty()) {
				b.addJcefArgs(arg);
			}
		}
		b.setAppHandler(new MavenCefAppHandlerAdapter() {
			@Override
			public boolean onBeforeTerminate() {
				// macOS Quit (JCEF takes it over from AWT): true keeps the app running. This runs on the Mac's main
				// thread, which the window and every dialog need: asking here (a modal dialog waiting on that thread)
				// froze the app. With unsaved changes the quit is held, the question is asked on Swing's thread, and
				// the app quits from there on Yes.
				if (w.unsaved.get().isEmpty()) {
					return false;
				}
				SwingUtilities.invokeLater(() -> {
					if (w.mayQuit()) {
						w.quit();
					}
				});
				return true;
			}

			@Override
			public void stateHasChanged(CefApp.CefAppState state) {
				if (state == CefApp.CefAppState.TERMINATED) {
					System.exit(0);
				}
			}
		});
		w.cef = b.build();
		return w;
	}

	/** Opens the window on the page at {@code url} (its address with the token, and any designs to open). */
	void open(String url, Path workspace) throws Exception {
		this.home = url;
		URI u = URI.create(url);
		this.origin = u.getScheme() + "://" + u.getRawAuthority();
		this.workspace = workspace;
		CountDownLatch created = new CountDownLatch(1);
		SwingUtilities.invokeAndWait(() -> {
			CefClient client = cef.createClient();
			Signals.giveBackToJava();
			client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
				@Override
				public boolean onBeforePopup(CefBrowser b, CefFrame f, String target, String name) {
					external(target); // "Report on GitHub": the system's browser, never a second window
					return true;
				}

				@Override
				public void onAfterCreated(CefBrowser b) {
					if (created.getCount() == 0) {
						Log.info("Chromium created a second page for the window (" + b.getIdentifier() + ")");
					}
					created.countDown();
				}
			});
			client.addRequestHandler(new CefRequestHandlerAdapter() {
				@Override
				public boolean onBeforeBrowse(CefBrowser b, CefFrame f, CefRequest r, boolean userGesture, boolean redirect) {
					if (ours(r.getURL())) {
						return false;
					}
					if (userGesture) {
						external(r.getURL());
					}
					return true;
				}

				@Override
				public void onRenderProcessTerminated(CefBrowser b, TerminationStatus status, int code, String message) {
					long now = System.currentTimeMillis();
					if (now - reloaded > 60_000) { // once a minute at most: not in a loop if it keeps failing
						reloaded = now;
						Log.info("The window's page stopped (" + status + "); loading it again.");
						b.loadURL(home);
					}
				}
			});
			client.addDownloadHandler(new CefDownloadHandlerAdapter() {
				@Override
				public boolean onBeforeDownload(CefBrowser b, CefDownloadItem item, String name, CefBeforeDownloadCallback cb) {
					// The page's saves (a problem report, a plot's PNG or CSV) go straight to Downloads, with no dialog:
					// Chromium's save dialog is modal on the Mac's main thread, the kind that froze the window. The
					// page says where the file went, with a button to show it.
					cb.Continue(downloadTo(fileName(name)).toString(), false);
					return true;
				}

				@Override
				public void onDownloadUpdated(CefBrowser b, CefDownloadItem item, org.cef.callback.CefDownloadItemCallback cb) {
					// Chromium reports a finished download more than once: the page hears of each once
					if (item.isComplete() && item.getFullPath() != null && told.add(item.getId())) {
						b.executeJavaScript("dispatchEvent(new CustomEvent('rocketmaxxing:saved', {detail: "
								+ new com.google.gson.Gson().toJson(item.getFullPath()) + "}))", home, 0);
					}
				}
			});
			client.addDisplayHandler(new CefDisplayHandlerAdapter() {
				@Override
				public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level, String message, String source, int line) {
					// The page's errors, in the app's log too (the window has no developer tools to see them in).
					if (level == CefSettings.LogSeverity.LOGSEVERITY_ERROR || level == CefSettings.LogSeverity.LOGSEVERITY_FATAL) {
						Log.info("Window page: " + message + (source == null || source.isEmpty() ? "" : " (" + source + ":" + line + ")"));
					}
					return false;
				}
			});
			client.addContextMenuHandler(new CefContextMenuHandlerAdapter() {
				@Override
				public void onBeforeContextMenu(CefBrowser b, CefFrame f, CefContextMenuParams p, CefMenuModel m) {
					// No native menu, even for text: on the Mac it runs a modal loop on the main thread, like the
					// dropdown lists that froze the window. The page shows its own (cut, copy, paste in text boxes).
					m.clear();
				}
			});
			// A page's file picker (<input type=file>): the app's own dialog, which knows how to stay in front, asked
			// off Chromium's thread; Chromium waits for the answer instead of running its own modal panel.
			client.addDialogHandler((b, mode, title, defaultPath, filters, extensions, descriptions, cb) -> {
				if (MAC) {
					// On a Mac no system panel is safe beside this window, not even the app's own (it froze the app):
					// the page uses its own file browser instead of a file picker, so this is not reached there.
					Log.info("A page file picker was refused on the Mac (the page should use the app's file browser).");
					cb.Cancel();
					return true;
				}
				String types = String.join(",", extensions == null || extensions.isEmpty() ? filters : extensions)
						.replace(".", "").toLowerCase(Locale.ROOT);
				Thread t = new Thread(() -> {
					try {
						Object chosen = switch (mode) {
							case FILE_DIALOG_OPEN_FOLDER -> FileDialogs.choose("folder", null, workspace, "").get("path");
							case FILE_DIALOG_SAVE -> FileDialogs.choose("save", fileName(defaultPath), workspace, types).get("path");
							default -> FileDialogs.choose("open", null, workspace, types).get("path");
						};
						if (chosen == null) {
							cb.Cancel();
						} else {
							cb.Continue(new java.util.Vector<>(List.of(chosen.toString())));
						}
					} catch (Exception e) {
						cb.Cancel();
					}
				}, "rocketmaxxing-file-dialog");
				t.setDaemon(true);
				t.start();
				return true;
			});
			// alert(), confirm() and prompt() would be native modal dialogs too: the app uses its own; any left are
			// answered "no" at once rather than shown. Leaving the page asks nothing.
			client.addJSDialogHandler(new org.cef.handler.CefJSDialogHandlerAdapter() {
				@Override
				public boolean onJSDialog(CefBrowser b, String origin, JSDialogType type, String message, String prompt,
						org.cef.callback.CefJSDialogCallback cb, BoolRef suppress) {
					Log.info("Window page asked a " + type + " dialog, not shown: " + message);
					suppress.set(true);
					return false;
				}

				@Override
				public boolean onBeforeUnloadDialog(CefBrowser b, String message, boolean reload,
						org.cef.callback.CefJSDialogCallback cb) {
					cb.Continue(true, "");
					return true;
				}
			});
			// A file dragged onto the window would replace the app with the file: refuse it (the parts tree's own
			// dragging is not a file and goes on).
			client.addDragHandler((b, data, mask) -> data.isFile());
			client.addFocusHandler(new CefFocusHandlerAdapter() {
				@Override
				public boolean onSetFocus(CefBrowser b, FocusSource source) {
					// JCEF's CefClient.onGotFocus focuses the browser again, and this Chromium reports that focus again
					// straight away: an endless loop that overflows the stack (java-cef issue 489). The browser has just
					// got the focus then, so that request is refused.
					return StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getMethodName().equals("onGotFocus")
							&& f.getClassName().equals(CefClient.class.getName())));
				}
			});
			browser = client.createBrowser(url, false, false);
			// Left to itself, JCEF creates the page from a timer once the window shows, every 100 ms until Chromium
			// has started on it: on a slow computer that makes several pages, each opening the designs again. Created
			// now, it goes into the window below once it exists.
			browser.createImmediately();
			// On the Mac, Chromium leaves ⌘C, ⌘V, ⌘Z and the rest to the app's menu: they are done here, before the page.
			client.addKeyboardHandler(new CefKeyboardHandlerAdapter() {
				@Override
				public boolean onPreKeyEvent(CefBrowser b, CefKeyEvent e, BoolRef shortcut) {
					if (!macCommand(e)) {
						return false;
					}
					int key = e.windows_key_code;
					if (key == KeyEvent.VK_W || key == KeyEvent.VK_M || key == KeyEvent.VK_Q
							|| e.focus_on_editable_field && EDIT_KEYS.contains(key)) {
						return command(key, shift(e)); // the window's own keys; editing in a text box
					}
					if (key == KeyEvent.VK_C) {
						command(key, false); // text selected in the page; the page still gets ⌘C (it copies a part)
					}
					return false;
				}
			});
		});
		if (!created.await(1, TimeUnit.MINUTES)) {
			throw new IllegalStateException("Chromium did not create the window's page");
		}
		SwingUtilities.invokeAndWait(() -> {
			frame = new JFrame(App.NAME);
			frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			frame.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(WindowEvent e) {
					close();
				}
			});
			// No Swing menu bar: on a Mac it becomes the system menu bar, and opening one of its menus waits on Swing's
			// thread while that thread can be waiting on the main thread for Chromium, which froze the whole app
			// (1.6.0). The ⌘ shortcuts are the window's own (onPreKeyEvent below); the system's app menu (About,
			// Hide, Quit) stays.
			frame.getContentPane().add(browser.getUIComponent(), BorderLayout.CENTER);
			Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
			frame.setMinimumSize(new Dimension(960, 600));
			frame.setSize(Math.min(1600, screen.width * 9 / 10), Math.min(1000, screen.height * 9 / 10));
			frame.setLocationRelativeTo(null);
			frame.setVisible(true);
			shown = frame;
		});
	}

	/**
	 * Brings the window to the front. With {@code url} (the page's address with designs to open), loads it: the page
	 * notices the new address and opens them.
	 */
	void show(String url) {
		SwingUtilities.invokeLater(() -> {
			if (url != null) {
				Log.info("Opening handed-over designs in the window.");
				browser.loadURL(url);
			}
			frame.setVisible(true);
			if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
				frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
			}
			if (MAC && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
				Desktop.getDesktop().requestForeground(true);
			} else {
				// Windows lets a program in the background bring its window forward only this way.
				frame.setAlwaysOnTop(true);
				frame.toFront();
				frame.setAlwaysOnTop(false);
			}
			frame.toFront();
			browser.setFocus(true);
		});
	}

	/** The app's window while it is on screen, else null. */
	static Frame owner() {
		Frame f = shown;
		return f != null && f.isDisplayable() && f.isVisible() ? f : null;
	}

	/** The window's close button, or ⌘W: on the Mac the app stays in the Dock (as Mac apps do); elsewhere it quits. */
	private void close() {
		if (MAC) {
			frame.setVisible(false);
		} else if (mayQuit()) {
			quit();
		}
	}

	/** The keys of Edit's commands (with ⌘): undo and redo, cut, copy, paste, select all. */
	private static final List<Integer> EDIT_KEYS = List.of(KeyEvent.VK_Z, KeyEvent.VK_X, KeyEvent.VK_C, KeyEvent.VK_V,
			KeyEvent.VK_A);

	private static boolean macCommand(CefKeyEvent e) {
		return MAC && e.type == CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN && (e.modifiers & EventFlags.EVENTFLAG_COMMAND_DOWN) != 0;
	}

	private static boolean shift(CefKeyEvent e) {
		return (e.modifiers & EventFlags.EVENTFLAG_SHIFT_DOWN) != 0;
	}

	/** cut, copy, paste, selectAll, undo or redo in the focused text box: Chromium's own commands, which can paste. */
	boolean edit(String action) {
		return switch (action) {
			case "cut" -> command(KeyEvent.VK_X, false);
			case "copy" -> command(KeyEvent.VK_C, false);
			case "paste" -> command(KeyEvent.VK_V, false);
			case "selectAll" -> command(KeyEvent.VK_A, false);
			case "undo" -> command(KeyEvent.VK_Z, false);
			case "redo" -> command(KeyEvent.VK_Z, true);
			default -> false;
		};
	}

	/** A menu command, from the menu or from ⌘ and its key. */
	private boolean command(int key, boolean shift) {
		CefFrame f = browser.getFocusedFrame() != null ? browser.getFocusedFrame() : browser.getMainFrame();
		switch (key) {
			case KeyEvent.VK_O, KeyEvent.VK_S -> page(key == KeyEvent.VK_O ? "o" : "s", shift);
			case KeyEvent.VK_W -> SwingUtilities.invokeLater(this::close);
			case KeyEvent.VK_M -> SwingUtilities.invokeLater(() -> frame.setExtendedState(Frame.ICONIFIED));
			case KeyEvent.VK_Q -> SwingUtilities.invokeLater(() -> {
				if (mayQuit()) {
					quit();
				}
			});
			case KeyEvent.VK_Z -> {
				if (shift) {
					f.redo();
				} else {
					f.undo();
				}
			}
			case KeyEvent.VK_X -> f.cut();
			case KeyEvent.VK_C -> f.copy();
			case KeyEvent.VK_V -> f.paste();
			case KeyEvent.VK_A -> f.selectAll();
			default -> {
				return false;
			}
		}
		return true;
	}

	/** The page's Open (its file browser on a Mac). */
	void openDialog() {
		page("o", false);
	}

	/** Does what the page does for ⌘ and {@code key} (open, save): the page's own shortcut, so it stays one thing. */
	private void page(String key, boolean shift) {
		browser.executeJavaScript("dispatchEvent(new KeyboardEvent('keydown', {key: '" + key + "', metaKey: true, shiftKey: "
				+ shift + ", bubbles: true, cancelable: true}))", home, 0);
	}

	/** Asks first when designs have unsaved changes; true to go on and quit. On the event thread. */
	boolean mayQuit() {
		return App.mayQuit(frame != null && frame.isVisible() ? frame : null, unsaved.get());
	}

	/** Closes Chromium (the page, then its processes); the app exits once it has. */
	void quit() {
		frame.setVisible(false);
		cef.dispose();
		Thread late = new Thread(() -> {
			try {
				Thread.sleep(5_000);
			} catch (InterruptedException ignored) {
				// exiting anyway
			}
			System.exit(0);
		}, "rocketmaxxing-quit");
		late.setDaemon(true);
		late.start();
	}

	private boolean ours(String url) {
		return url.equals(origin) || url.startsWith(origin + "/") || url.startsWith("blob:" + origin + "/");
	}

	/** A web link: in the system's browser. Nothing else leaves the app (no files, no other programs' links). */
	private static void external(String url) {
		String scheme = url.contains(":") ? url.substring(0, url.indexOf(':')).toLowerCase(Locale.ROOT) : "";
		if (!scheme.equals("https") && !scheme.equals("http")) {
			return;
		}
		new Thread(() -> {
			try {
				Desktop.getDesktop().browse(URI.create(url));
			} catch (Exception | LinkageError e) {
				Log.info("Could not open the link in a browser: " + e.getMessage());
			}
		}, "rocketmaxxing-link").start();
	}

	/** The name the page gave a download, without any folders in it. */
	/** Where a page's save goes: the Downloads folder (else the designs folder), under a name not yet taken. */
	Path downloadTo(String name) {
		Path dir = Path.of(System.getProperty("user.home"), "Downloads");
		if (!Files.isDirectory(dir)) {
			dir = workspace;
		}
		Path p = dir.resolve(name);
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
		for (int n = 2; Files.exists(p) && n < 1000; n++) {
			p = dir.resolve(stem + " (" + n + ")" + ext);
		}
		return p;
	}

	static String fileName(String name) {
		try {
			Path p = name == null || name.isBlank() ? null : Path.of(name).getFileName();
			return p == null ? "download.txt" : p.toString();
		} catch (RuntimeException e) {
			return "download.txt";
		}
	}

}
