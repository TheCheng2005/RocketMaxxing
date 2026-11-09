package io.github.openrocketmcp.app;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import sun.misc.Signal;

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
import org.cef.callback.CefMenuModel.MenuId;
import org.cef.callback.CefMenuModel.MenuItemType;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.cef.handler.CefFocusHandler.FocusSource;
import org.cef.handler.CefFocusHandlerAdapter;
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
	private CefBrowser browser;
	private String home;
	private String origin;
	private Path workspace;
	private volatile long reloaded;

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
				// macOS Quit (JCEF takes it over from AWT): true keeps the app running.
				return !w.mayQuit();
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
		SwingUtilities.invokeAndWait(() -> {
			CefClient client = cef.createClient();
			javaSignals();
			client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
				@Override
				public boolean onBeforePopup(CefBrowser b, CefFrame f, String target, String name) {
					external(target); // "Report on GitHub": the system's browser, never a second window
					return true;
				}

				@Override
				public void onAfterCreated(CefBrowser b) {
					Log.info("Window page " + b.getIdentifier() + " created" + (b == browser ? "" : " (not the window's own)"));
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
					// The page's Save (a problem report): the system's save dialog, starting in the designs folder.
					cb.Continue(AppWindow.this.workspace.resolve(fileName(name)).toString(), true);
					return true;
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
					editOnly(m);
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
			frame = new JFrame(App.NAME);
			frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			frame.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(WindowEvent e) {
					close();
				}
			});
			if (MAC) {
				frame.setJMenuBar(menus());
			}
			frame.getContentPane().add(browser.getUIComponent(), BorderLayout.CENTER);
			Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
			frame.setMinimumSize(new Dimension(960, 600));
			frame.setSize(Math.min(1600, screen.width * 9 / 10), Math.min(1000, screen.height * 9 / 10));
			frame.setLocationRelativeTo(null);
			frame.setVisible(true);
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

	/** The window's close button, or ⌘W: on the Mac the app stays in the Dock (as Mac apps do); elsewhere it quits. */
	private void close() {
		if (MAC) {
			frame.setVisible(false);
		} else if (mayQuit()) {
			quit();
		}
	}

	/**
	 * The Mac menu bar while the window is in front. File's commands are the page's own (the same as its shortcuts);
	 * Edit's work on text, as a browser's Edit menu does.
	 */
	private JMenuBar menus() {
		JMenuBar bar = new JMenuBar();
		JMenu file = new JMenu("File");
		file.add(item("Open Design…", KeyEvent.VK_O, false));
		file.add(item("Save", KeyEvent.VK_S, false));
		file.add(item("Save As…", KeyEvent.VK_S, true));
		file.addSeparator();
		file.add(item("Close Window", KeyEvent.VK_W, false));
		JMenu edit = new JMenu("Edit");
		edit.add(item("Undo", KeyEvent.VK_Z, false));
		edit.add(item("Redo", KeyEvent.VK_Z, true));
		edit.addSeparator();
		edit.add(item("Cut", KeyEvent.VK_X, false));
		edit.add(item("Copy", KeyEvent.VK_C, false));
		edit.add(item("Paste", KeyEvent.VK_V, false));
		edit.add(item("Select All", KeyEvent.VK_A, false));
		JMenu window = new JMenu("Window");
		window.add(item("Minimize", KeyEvent.VK_M, false));
		bar.add(file);
		bar.add(edit);
		bar.add(window);
		return bar;
	}

	private JMenuItem item(String label, int key, boolean shift) {
		JMenuItem it = new JMenuItem(label);
		int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | (shift ? InputEvent.SHIFT_DOWN_MASK : 0);
		it.setAccelerator(KeyStroke.getKeyStroke(key, mask));
		it.addActionListener(e -> command(key, shift));
		return it;
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

	/**
	 * Chromium, once started, handles SIGTERM, SIGINT and SIGHUP itself (as a Quit, which asks about unsaved designs, or
	 * on Linux only closes the page). Java gets them back, so stopping the app (Ctrl+C, kill) stops it as it did with a
	 * browser window: right away, and its shutdown hooks free the designs folder.
	 */
	private static void javaSignals() {
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			return; // no such handlers there
		}
		for (String name : List.of("TERM", "INT", "HUP")) {
			try {
				Signal.handle(new Signal(name), s -> System.exit(128 + s.getNumber()));
			} catch (IllegalArgumentException | UnsupportedOperationException e) {
				// the JVM keeps this signal (-Xrs)
			}
		}
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
	static String fileName(String name) {
		try {
			Path p = name == null || name.isBlank() ? null : Path.of(name).getFileName();
			return p == null ? "download.txt" : p.toString();
		} catch (RuntimeException e) {
			return "download.txt";
		}
	}

	/**
	 * The page has its own right-click menu; where it leaves Chromium's (text boxes, selected text), only the editing
	 * commands stay: no Back, Reload, Print or View Source.
	 */
	static void editOnly(CefMenuModel m) {
		for (int i = m.getCount() - 1; i >= 0; i--) {
			int id = m.getCommandIdAt(i);
			boolean edit = id >= MenuId.MENU_ID_UNDO && id <= MenuId.MENU_ID_SELECT_ALL;
			boolean spelling = id >= MenuId.MENU_ID_SPELLCHECK_SUGGESTION_0 && id <= MenuId.MENU_ID_NO_SPELLING_SUGGESTIONS;
			if (m.getTypeAt(i) != MenuItemType.MENUITEMTYPE_SEPARATOR && !edit && !spelling) {
				m.removeAt(i);
			}
		}
		for (int i = m.getCount() - 1; i >= 0; i--) {
			if (m.getTypeAt(i) == MenuItemType.MENUITEMTYPE_SEPARATOR
					&& (i == 0 || i == m.getCount() - 1 || m.getTypeAt(i - 1) == MenuItemType.MENUITEMTYPE_SEPARATOR)) {
				m.removeAt(i);
			}
		}
	}
}
