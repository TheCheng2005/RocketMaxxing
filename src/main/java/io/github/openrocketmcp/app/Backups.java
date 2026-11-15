package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import info.openrocket.core.file.GeneralRocketSaver;
import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.tools.DesignLocks;

/**
 * Copies of the open designs with unsaved changes, every few minutes, in the designs folder's {@code backups/}: after
 * a crash or a power cut the work since the last save is there. A copy never changes the design (its file, its saved
 * state, its undo history), and the newest {@link #KEEP} copies of each design are kept.
 */
final class Backups {
	private Backups() {
	}

	static final String FOLDER = "backups";
	/** Copies kept of each design (by name); older ones are deleted as new ones are written. */
	static final int KEEP = 10;
	/** Minutes between copies when the team has not chosen. */
	static final int DEFAULT_MINUTES = 5;
	static final String PREF = "rocketmaxxing.backupMinutes";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");

	static Path folder(Context ctx) {
		return ctx.workspace().resolve(FOLDER);
	}

	/** The minutes chosen in Settings (0: off), from the interface's stored preferences. */
	static int minutes(UiPrefs prefs) {
		try {
			String v = prefs.all().get(PREF);
			return v == null ? DEFAULT_MINUTES : Math.max(0, Integer.parseInt(v.trim()));
		} catch (RuntimeException e) {
			return DEFAULT_MINUTES;
		}
	}

	/** A file name from the design's name: no characters a file system refuses. */
	static String stem(String name) {
		String s = (name == null ? "" : name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").strip()
				.replaceAll("^[. ]+|[. ]+$", "");
		return s.isEmpty() ? "Rocket" : s.length() > 80 ? s.substring(0, 80).strip() : s;
	}

	/**
	 * Writes a copy of the design now and removes the oldest beyond {@link #KEEP}. The copy is written to a temporary
	 * file first and moved into place, so a crash while writing never leaves half a backup.
	 */
	static Path write(Context ctx, Designs.Design d) throws IOException {
		Path dir = folder(ctx);
		Files.createDirectories(dir);
		String stem = stem(d.name());
		Path target = dir.resolve(stem + " " + LocalDateTime.now().format(STAMP) + ".ork");
		Path tmp = Files.createTempFile(dir, ".backup-", ".tmp");
		try {
			new GeneralRocketSaver().save(tmp.toFile(), d.doc);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Files.deleteIfExists(tmp);
			throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
		}
		prune(dir, stem);
		return target;
	}

	/** Only the newest {@link #KEEP} copies of one design. */
	static void prune(Path dir, String stem) throws IOException {
		List<Path> mine;
		try (Stream<Path> s = Files.list(dir)) {
			mine = s.filter(p -> isCopyOf(p, stem)).sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
		}
		for (int i = KEEP; i < mine.size(); i++) {
			Files.deleteIfExists(mine.get(i));
		}
	}

	private static boolean isCopyOf(Path p, String stem) {
		String n = p.getFileName().toString();
		return n.endsWith(".ork") && n.startsWith(stem + " ") && n.substring(stem.length() + 1).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}-\\d{2}\\.ork");
	}

	/** The copies in the folder, newest first: name of the design, when, the file. */
	static List<Map<String, Object>> list(Context ctx) throws IOException {
		Path dir = folder(ctx);
		List<Map<String, Object>> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		List<Path> files;
		try (Stream<Path> s = Files.list(dir)) {
			files = s.filter(p -> p.getFileName().toString().endsWith(".ork")).toList();
		}
		for (Path p : files) {
			String n = p.getFileName().toString();
			var m = java.util.regex.Pattern.compile("^(.*) (\\d{4}-\\d{2}-\\d{2}) (\\d{2})-(\\d{2})-(\\d{2})\\.ork$").matcher(n);
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("path", p.toString());
			e.put("design", m.matches() ? m.group(1) : n.replaceAll("\\.ork$", ""));
			Instant when = Files.getLastModifiedTime(p).toInstant();
			e.put("time", when.toEpochMilli());
			e.put("size", Files.size(p));
			out.add(e);
		}
		out.sort(Comparator.comparing((Map<String, Object> e) -> (Long) e.get("time")).reversed());
		return out;
	}

	/**
	 * Every half minute: each open design with changes not yet saved, and changed since its last copy, is copied when
	 * the chosen minutes have passed since that copy. The copy is made under the design's lock, between tool calls,
	 * so it never sees a design half way through an edit.
	 */
	static ScheduledExecutorService start(Context ctx, DesignLocks locks, IntSupplier minutes) {
		Map<String, Object[]> last = new HashMap<>(); // design id -> {modID at the last copy, time of the last copy}
		ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "backups");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		ex.scheduleWithFixedDelay(() -> {
			try {
				int every = minutes.getAsInt();
				if (every <= 0) {
					return;
				}
				long now = System.currentTimeMillis();
				for (Designs.Design d : ctx.designs.all()) {
					if (d.doc.isSaved()) {
						continue;
					}
					Object mod = d.doc.getRocket().getModID();
					Object[] was = last.get(d.id);
					if (was != null && (was[0].equals(mod) || now - (Long) was[1] < every * 60_000L)) {
						continue;
					}
					Lock lock = locks.lock(d.id);
					if (!lock.tryLock(5, TimeUnit.SECONDS)) {
						continue; // a long run is going on: next time
					}
					try {
						write(ctx, d);
						last.put(d.id, new Object[] { mod, now });
					} finally {
						lock.unlock();
					}
				}
				last.keySet().removeIf(id -> ctx.designs.all().stream().noneMatch(d -> d.id.equals(id)));
			} catch (Exception e) {
				Log.info("Backup failed: " + e.getMessage());
			}
		}, 30, 30, TimeUnit.SECONDS);
		return ex;
	}

	static String when(long millis) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(STAMP);
	}
}
