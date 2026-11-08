package io.github.openrocketmcp.app;

import java.awt.Desktop;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.desktop.AppReopenedListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.Log;

/**
 * The app's place in macOS: the window is a browser's, so RocketMaxxing itself lives in the Dock. Clicking the Dock
 * icon brings a window back, a design double-clicked in Finder opens in it, Quit warns about unsaved designs, and the
 * Dock icon's menu and the menu bar (shown while RocketMaxxing is the active app) have Show window and Open design.
 */
final class MacDesktop {
	private MacDesktop() {
	}

	static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	}

	/** What is set up, for app_session (and the CI check on the Mac runners). */
	static final List<String> ACTIVE = new ArrayList<>();

	/**
	 * Installs the handlers; true when the Dock can bring the window back (then the app stays running with no window,
	 * as Mac apps do, until it is quit). Call early: files double-clicked to start the app arrive straight away.
	 */
	static boolean install(Runnable showWindow, Consumer<List<String>> openFiles, Supplier<List<String>> unsaved) {
		if (!isMac() || GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
			return false;
		}
		try {
			Desktop d = Desktop.getDesktop();
			if (d.isSupported(Desktop.Action.APP_OPEN_FILE)) {
				d.setOpenFileHandler(e -> openFiles.accept(e.getFiles().stream().map(File::getAbsolutePath).toList()));
				ACTIVE.add("openFile");
			}
			boolean reopen = false;
			if (d.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
				d.addAppEventListener((AppReopenedListener) e -> showWindow.run());
				reopen = true;
				ACTIVE.add("reopen");
			}
			if (d.isSupported(Desktop.Action.APP_ABOUT)) {
				d.setAboutHandler(e -> JOptionPane.showMessageDialog(null,
						App.NAME + " " + App.VERSION + "\nEngine " + Main.VERSION + " on OpenRocket\n\nDesign, simulate and check "
								+ "competition rockets, offline.",
						"About " + App.NAME, JOptionPane.INFORMATION_MESSAGE));
				ACTIVE.add("about");
			}
			if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
				// With the app's own window, Chromium takes Quit over from here (AppWindow asks the same question).
				d.setQuitHandler((e, response) -> {
					if (App.mayQuit(null, unsaved.get())) {
						response.performQuit();
					} else {
						response.cancelQuit();
					}
				});
				ACTIVE.add("quit");
			}
			Runnable choose = () -> SwingUtilities.invokeLater(() -> {
				FileDialog fd = new FileDialog((Frame) null, "Open a design", FileDialog.LOAD);
				fd.setFilenameFilter((dir, n) -> n.toLowerCase(Locale.ROOT).matches(".*\\.(ork|rkt)"));
				fd.setVisible(true);
				if (fd.getFile() != null) {
					openFiles.accept(List.of(new File(fd.getDirectory(), fd.getFile()).getAbsolutePath()));
				}
			});
			if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.MENU)) {
				PopupMenu dock = new PopupMenu();
				MenuItem show = new MenuItem("Show " + App.NAME + " Window");
				show.addActionListener(e -> showWindow.run());
				MenuItem open = new MenuItem("Open Design…");
				open.addActionListener(e -> choose.run());
				dock.add(show);
				dock.add(open);
				Taskbar.getTaskbar().setMenu(dock);
				ACTIVE.add("dockMenu");
			}
			if (d.isSupported(Desktop.Action.APP_MENU_BAR)) {
				int cmd = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
				JMenuBar bar = new JMenuBar();
				JMenu file = new JMenu("File");
				JMenuItem show = new JMenuItem("Show " + App.NAME + " Window");
				show.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, cmd));
				show.addActionListener(e -> showWindow.run());
				JMenuItem open = new JMenuItem("Open Design…");
				open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, cmd));
				open.addActionListener(e -> choose.run());
				file.add(show);
				file.add(open);
				bar.add(file);
				d.setDefaultMenuBar(bar);
				ACTIVE.add("menuBar");
			}
			return reopen;
		} catch (RuntimeException | LinkageError e) {
			Log.info("macOS integration unavailable: " + e);
			return false;
		}
	}
}
