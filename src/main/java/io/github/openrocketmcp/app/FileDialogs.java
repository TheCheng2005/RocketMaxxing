package io.github.openrocketmcp.app;

import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

import io.github.openrocketmcp.mcp.ToolException;

/** The system's own open and save dialogs (the app's page cannot see real file paths). */
final class FileDialogs {
	private FileDialogs() {
	}

	/**
	 * What a dialog belongs to: the app's window when it is on screen, brought forward first. A dialog owned by an
	 * invisible frame could open behind the window or on another Space on a Mac, where, being modal, it froze the whole
	 * app with nothing to click (1.6.0). {@code made} is set when a stand-in frame was made, to dispose of after.
	 */
	static Frame owner(boolean[] made) {
		if (MacDesktop.isMac() && java.awt.Desktop.isDesktopSupported()
				&& java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
			java.awt.Desktop.getDesktop().requestForeground(true);
		}
		Frame w = AppWindow.owner();
		if (w != null) {
			if ((w.getExtendedState() & Frame.ICONIFIED) != 0) {
				w.setExtendedState(w.getExtendedState() & ~Frame.ICONIFIED);
			}
			w.toFront();
			made[0] = false;
			return w;
		}
		made[0] = true;
		Frame f = new Frame();
		f.setAlwaysOnTop(true);
		return f;
	}

	static Map<String, Object> choose(String mode, String name, java.nio.file.Path folder, String types) throws Exception {
		if (GraphicsEnvironment.isHeadless()) {
			throw new ToolException("No file dialog here: type the file's path instead.");
		}
		boolean save = "save".equals(mode);
		AtomicReference<String> chosen = new AtomicReference<>();
		try {
			if ("folder".equals(mode)) {
				folder(folder, chosen);
			} else {
				show(save, name, folder, types.toLowerCase().split("[,\\s]+"), chosen);
			}
		} catch (Exception | LinkageError | java.awt.AWTError e) { // no screen, or no toolkit for it
			throw new ToolException("No file dialog here: type the file's path instead.");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("path", chosen.get());
		return out;
	}

	/** A folder: the native dialog in folder mode on macOS, Swing's chooser elsewhere (AWT has no folder mode there). */
	private static void folder(java.nio.file.Path start, AtomicReference<String> chosen) throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
				System.setProperty("apple.awt.fileDialogForDirectories", "true");
				boolean[] made = new boolean[1];
				Frame owner = owner(made);
				try {
					FileDialog fd = new FileDialog(owner, "Choose a folder", FileDialog.LOAD);
					fd.setDirectory(start.toAbsolutePath().toString());
					fd.setVisible(true);
					if (fd.getFile() != null) {
						chosen.set(new File(fd.getDirectory(), fd.getFile()).getAbsolutePath());
					}
				} finally {
					System.setProperty("apple.awt.fileDialogForDirectories", "false");
					if (made[0]) {
						owner.dispose();
					}
				}
				return;
			}
			JFileChooser fc = new JFileChooser(start.toFile());
			fc.setDialogTitle("Choose a folder");
			fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			if (fc.showOpenDialog(AppWindow.owner()) == JFileChooser.APPROVE_OPTION) {
				chosen.set(fc.getSelectedFile().getAbsolutePath());
			}
		});
	}

	private static void show(boolean save, String name, java.nio.file.Path folder, String[] types, AtomicReference<String> chosen) throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			boolean[] made = new boolean[1];
			Frame owner = owner(made);
			FileDialog fd = new FileDialog(owner, save ? "Save" : "Open", save ? FileDialog.SAVE : FileDialog.LOAD);
			fd.setDirectory(folder.toAbsolutePath().toString()); // the designs folder, like OpenRocket's last-used folder
			boolean any = types.length == 0 || types[0].isEmpty() || java.util.Arrays.asList(types).contains("*");
			if (!any) {
				fd.setFilenameFilter((dir, n) -> java.util.Arrays.stream(types).anyMatch(t -> n.toLowerCase().endsWith("." + t)));
			}
			if (!save && !any) {
				fd.setFile("*." + types[0]);
			} else if (name != null) {
				fd.setFile(name);
			}
			fd.setVisible(true);
			if (fd.getFile() != null) {
				chosen.set(new File(fd.getDirectory(), fd.getFile()).getAbsolutePath());
			}
			if (made[0]) {
				owner.dispose();
			}
		});
	}
}
