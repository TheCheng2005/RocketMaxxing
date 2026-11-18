package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.openrocketmcp.mcp.ToolException;

/**
 * What the app's own file browser shows: one folder's contents and the places to start from. On a Mac the system's
 * open and save panels run a modal loop on the thread the app's window needs, and the app froze; the page draws its
 * own browser from this instead.
 */
final class FileBrowser {
	/** More than this many entries in one folder: the rest are left out (the browser says so). */
	static final int MAX = 2000;

	private FileBrowser() {
	}

	static Map<String, Object> list(Path asked, Path designs) {
		Path dir = asked.toAbsolutePath().normalize();
		if (!Files.isDirectory(dir)) {
			// a file, or a folder that has gone: show where it was
			Path up = dir.getParent();
			while (up != null && !Files.isDirectory(up)) {
				up = up.getParent();
			}
			if (up == null) {
				throw new ToolException("No folder " + asked + ".");
			}
			dir = up;
		}
		List<Map<String, Object>> folders = new ArrayList<>(), files = new ArrayList<>();
		boolean more = false;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				String name = p.getFileName().toString();
				if (name.startsWith(".") || name.equals("$RECYCLE.BIN") || name.equalsIgnoreCase("desktop.ini")) {
					continue;
				}
				if (folders.size() + files.size() >= MAX) {
					more = true;
					break;
				}
				try {
					BasicFileAttributes at = Files.readAttributes(p, BasicFileAttributes.class);
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("name", name);
					if (at.isDirectory() || Files.isDirectory(p)) {
						// a Mac app or package is a folder, but not one to open here
						if (name.endsWith(".app") || name.endsWith(".photoslibrary")) {
							continue;
						}
						folders.add(e);
					} else {
						e.put("size", at.size());
						e.put("modified", at.lastModifiedTime().toMillis());
						files.add(e);
					}
				} catch (IOException | SecurityException ignored) {
					// unreadable: left out
				}
			}
		} catch (IOException | SecurityException e) {
			throw new ToolException("Cannot read " + dir + " (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
					+ "). On a Mac, allow RocketMaxxing in System Settings > Privacy & Security > Files and Folders.");
		}
		Comparator<Map<String, Object>> byName = Comparator.comparing(m -> m.get("name").toString().toLowerCase(Locale.ROOT));
		folders.sort(byName);
		files.sort(byName);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("path", dir.toString());
		out.put("parent", dir.getParent() == null ? null : dir.getParent().toString());
		out.put("separator", java.io.File.separator);
		out.put("folders", folders);
		out.put("files", files);
		out.put("more", more);
		out.put("places", places(designs));
		return out;
	}

	/** Where to start: the designs folder, home and its usual folders, iCloud Drive, and the other disks. */
	static List<Map<String, Object>> places(Path designs) {
		List<Map<String, Object>> out = new ArrayList<>();
		Path home = Path.of(System.getProperty("user.home"));
		add(out, "Designs", designs);
		add(out, "Home", home);
		add(out, "Desktop", home.resolve("Desktop"));
		add(out, "Documents", home.resolve("Documents"));
		add(out, "Downloads", home.resolve("Downloads"));
		add(out, "iCloud Drive", home.resolve("Library/Mobile Documents/com~apple~CloudDocs"));
		Path volumes = Path.of("/Volumes");
		if (Files.isDirectory(volumes)) {
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(volumes)) {
				for (Path v : ds) {
					// the start-up disk is listed there too, as a link to /
					if (!Files.isSymbolicLink(v) && Files.isDirectory(v, LinkOption.NOFOLLOW_LINKS)) {
						add(out, v.getFileName().toString(), v);
					}
				}
			} catch (IOException | SecurityException ignored) {
				// no other disks to show
			}
		} else {
			for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
				add(out, root.toString(), root);
			}
		}
		return out;
	}

	private static void add(List<Map<String, Object>> out, String label, Path p) {
		if (p != null && Files.isDirectory(p)) {
			String s = p.toAbsolutePath().normalize().toString();
			if (out.stream().noneMatch(m -> m.get("path").equals(s))) {
				out.add(Map.of("label", label, "path", s));
			}
		}
	}
}
