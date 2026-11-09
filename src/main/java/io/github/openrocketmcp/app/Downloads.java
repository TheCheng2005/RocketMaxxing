package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the app saves what the user takes away (the flight video): the Downloads folder, else the Desktop, else the
 * designs folder, under a name that replaces nothing.
 */
final class Downloads {
	private Downloads() {
	}

	/** The user's Downloads folder (on Linux the one ~/.config/user-dirs.dirs names), else Desktop, else {@code fallback}. */
	static Path folder(Path home, Path fallback) {
		Path xdg = xdg(home, "XDG_DOWNLOAD_DIR");
		for (Path p : new Path[] { xdg, home.resolve("Downloads"), xdg(home, "XDG_DESKTOP_DIR"), home.resolve("Desktop") }) {
			if (p != null && Files.isDirectory(p) && Files.isWritable(p)) {
				return p;
			}
		}
		return fallback;
	}

	private static Path xdg(Path home, String key) {
		Path dirs = home.resolve(".config").resolve("user-dirs.dirs");
		if (!Files.isRegularFile(dirs)) {
			return null;
		}
		try {
			Matcher m = Pattern.compile("^" + key + "=\"(.*)\"\\s*$", Pattern.MULTILINE)
					.matcher(Files.readString(dirs, StandardCharsets.UTF_8));
			if (m.find()) {
				String v = m.group(1).replace("$HOME", home.toString());
				return v.equals(home.toString()) ? null : Path.of(v); // "$HOME" means the folder is switched off
			}
		} catch (IOException | RuntimeException e) {
			// unreadable: the usual names
		}
		return null;
	}

	/** {@code name} made safe on every system (no folders, nothing Windows refuses), and numbered when taken. */
	static Path freePath(Path folder, String name) {
		String clean = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").strip();
		clean = clean.replaceAll("^[. ]+|[. ]+$", "");
		if (clean.isEmpty()) {
			clean = "download";
		}
		int dot = clean.lastIndexOf('.');
		String stem = dot > 0 ? clean.substring(0, dot) : clean, ext = dot > 0 ? clean.substring(dot) : "";
		String s = stem;
		// Taken when the file or its companion (the video's key-moment stills, written beside it) is there.
		for (int i = 2; (Files.exists(folder.resolve(s + ext)) || Files.exists(folder.resolve(s + "-keyframes.png"))) && i < 1000; i++) {
			s = stem + " (" + i + ")";
		}
		return folder.resolve(s + ext);
	}

	/** Files the app may open in their own program: what it writes, never programs or scripts. */
	static boolean openable(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return n.matches(".*\\.(mp4|gif|png|jpg|jpeg|svg|pdf|csv|txt|md|kml|stl|ork|html)$");
	}
}
