package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.mcp.Log;

/**
 * What happened to the team's designs and standards, kept in the designs folder ({@code history/journal.jsonl}): each
 * edit, undo, redo, save and open, and each change to the team standards with what it was before (so it can be put
 * back). Who did it: the person in the app, or the assistant. Only the desktop app keeps a journal (a plain MCP server
 * writes nothing into the folder it was started in). The file is trimmed to its newest {@link #KEEP} events.
 */
public final class Journal {
	private Journal() {
	}

	static final int KEEP = 5000;
	private static final AtomicLong SEQ = new AtomicLong();
	/** Who the tool call on this thread is for: null for the person using the app. */
	private static final ThreadLocal<String> ACTOR = new ThreadLocal<>();

	/** Runs {@code work} as {@code actor} (the assistant): what it changes is put down to it. */
	public static <T> T as(String actor, Callable<T> work) throws Exception {
		String was = ACTOR.get();
		ACTOR.set(actor);
		try {
			return work.call();
		} finally {
			ACTOR.set(was);
		}
	}

	static String actor() {
		String a = ACTOR.get();
		return a == null ? "you" : a;
	}

	static Path file(Context ctx) {
		return ctx.workspace().resolve("history").resolve("journal.jsonl");
	}

	/** Adds an event; never fails the call it belongs to. Returns the event, or null when no journal is kept. */
	public static synchronized JsonObject add(Context ctx, String kind, String design, String file, String what, JsonObject extra) {
		if (!ctx.journal()) {
			return null;
		}
		JsonObject e = new JsonObject();
		e.addProperty("id", "e" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet());
		e.addProperty("time", Instant.now().toString());
		e.addProperty("kind", kind);
		e.addProperty("by", actor());
		if (design != null) {
			e.addProperty("design", design);
		}
		if (file != null) {
			e.addProperty("file", file);
		}
		e.addProperty("what", what);
		if (extra != null) {
			for (Map.Entry<String, JsonElement> x : extra.entrySet()) {
				e.add(x.getKey(), x.getValue());
			}
		}
		Path f = file(ctx);
		try {
			Files.createDirectories(f.getParent());
			Files.writeString(f, e + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			if (Files.size(f) > 4L * 1024 * 1024) {
				trim(f);
			}
		} catch (IOException ex) {
			Log.info("The history could not be written (" + ex.getMessage() + ")");
		}
		return e;
	}

	private static void trim(Path f) throws IOException {
		List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
		if (lines.size() > KEEP) {
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.write(tmp, lines.subList(lines.size() - KEEP, lines.size()), StandardCharsets.UTF_8);
			Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		}
	}

	/** The newest events first, at most {@code limit}; only one design's (and the team's) when {@code design} is given. */
	public static synchronized List<JsonObject> read(Context ctx, int limit, String design) {
		Path f = file(ctx);
		Deque<JsonObject> out = new ArrayDeque<>();
		if (!ctx.journal() || !Files.isRegularFile(f)) {
			return new ArrayList<>();
		}
		try {
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				if (line.isBlank()) {
					continue;
				}
				try {
					JsonObject e = JsonParser.parseString(line).getAsJsonObject();
					if (design != null && e.has("design") && !design.equals(e.get("design").getAsString())) {
						continue;
					}
					out.addFirst(e);
					if (out.size() > limit) {
						out.removeLast();
					}
				} catch (RuntimeException bad) {
					// a line cut short by a crash: the rest still reads
				}
			}
		} catch (IOException e) {
			Log.info("The history could not be read (" + e.getMessage() + ")");
		}
		return new ArrayList<>(out);
	}

	/** One event by its id, or null. */
	public static synchronized JsonObject find(Context ctx, String id) {
		for (JsonObject e : read(ctx, KEEP, null)) {
			if (e.get("id").getAsString().equals(id)) {
				return e;
			}
		}
		return null;
	}
}
