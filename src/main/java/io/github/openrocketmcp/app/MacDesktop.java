package io.github.openrocketmcp.app;

import java.awt.Desktop;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Taskbar;
import java.awt.desktop.AppReopenedListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.Log;

/**
 * The app's place in macOS: RocketMaxxing lives in the Dock, also with its window closed. Clicking the Dock icon brings
 * the window back, a design double-clicked in Finder opens in it, Quit warns about unsaved designs, and the Dock icon's
 * menu and the menu bar with no window open have Show window and Open design.
 */
final class MacDesktop {
	private MacDesktop() {
	}

	static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	}

	/** What is set up, for app_session (and the CI check on the Mac runners). */
	static final List<String> ACTIVE = new ArrayList<>();

	/** Installs the handlers. Call early: files double-clicked to start the app arrive straight away. */
	static void install(Runnable showWindow, Consumer<List<String>> openFiles, Supplier<List<String>> unsaved) {
		if (!isMac() || GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
			return;
		}
		try {
			Desktop d = Desktop.getDesktop();
			if (d.isSupported(Desktop.Action.APP_OPEN_FILE)) {
				d.setOpenFileHandler(e -> openFiles.accept(e.getFiles().stream().map(File::getAbsolutePath).toList()));
				ACTIVE.add("openFile");
			}
			if (d.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
				d.addAppEventListener((AppReopenedListener) e -> showWindow.run());
				ACTIVE.add("reopen");
			}
			if (d.isSupported(Desktop.Action.APP_ABOUT)) {
				d.setAboutHandler(e -> JOptionPane.showMessageDialog(AppWindow.owner(), // in front of the window, not behind it
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
				// With the app's window: its own Open (the page's file browser); the system's panel froze the window.
				if (App.openInWindow()) {
					return;
				}
				boolean[] made = new boolean[1];
				Frame owner = FileDialogs.owner(made);
				FileDialog fd = new FileDialog(owner, "Open a design", FileDialog.LOAD);
				fd.setFilenameFilter((dir, n) -> n.toLowerCase(Locale.ROOT).matches(".*\\.(ork|rmx|rkt|cdx1)"));
				fd.setVisible(true);
				if (made[0]) {
					owner.dispose();
				}
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
			// No menu bar of our own (File: Show Window, Open Design): a Swing menu bar in the Mac's menu bar froze the
			// app when opened (see AppWindow). The Dock's menu and a click on the Dock icon do the same.
		} catch (RuntimeException | LinkageError e) {
			Log.info("macOS integration unavailable: " + e);
		}
	}
}
