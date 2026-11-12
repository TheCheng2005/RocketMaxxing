package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.openrocketmcp.mcp.ToolException;

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

	/** What the page may hand over to be saved: a plot's picture or its numbers. */
	static final long EXPORT_LIMIT = 25L * 1024 * 1024;

	/**
	 * Saves a file the page made (a plot as a picture, or its data as a table) in {@code folder} under a name that
	 * replaces nothing. Exactly one of {@code text} and {@code base64} carries the content.
	 */
	static Path export(Path folder, String name, String text, String base64) throws IOException {
		if (name == null || !name.toLowerCase(Locale.ROOT).matches(".*\\.(png|csv|svg)$")) {
			throw new ToolException("A plot is saved as .png, .csv or .svg; got: " + name);
		}
		if ((text == null) == (base64 == null)) {
			throw new ToolException("Give the file's content as text or as base64, not both.");
		}
		byte[] bytes;
		try {
			bytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : java.util.Base64.getDecoder().decode(base64);
		} catch (IllegalArgumentException e) {
			throw new ToolException("The picture did not arrive whole (not valid base64).");
		}
		if (bytes.length == 0 || bytes.length > EXPORT_LIMIT) {
			throw new ToolException(bytes.length == 0 ? "Nothing to save: the file is empty." : "Too large to save (over 25 MB).");
		}
		Path p = freePath(folder, name);
		Files.write(p, bytes);
		return p;
	}

	/** Files the app may open in their own program: what it writes, never programs or scripts. */
	static boolean openable(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return n.matches(".*\\.(mp4|gif|png|jpg|jpeg|svg|pdf|csv|txt|md|kml|stl|ork|html)$");
	}
}
