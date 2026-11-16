package io.github.openrocketmcp.or;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.util.ModID;

/**
 * Undo / redo for one open design: before each editing tool call the rocket is copied, and the copy is kept only if
 * the call changed the rocket (OpenRocket's modification counter moved). Undo loads the copy back; the state it
 * replaces goes on the redo stack. Motors, deployment settings and every component property live in the rocket, so
 * they come back too; files on disk are never touched.
 */
public final class History {
	public static final int LEVELS = 50;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	/** A rocket state and the edit that led away from it: as the tool call (for assistants) and in words (for people). */
	record Entry(Rocket state, String what, String when, String said) {
	}

	/** Taken before a call; becomes an entry if the call changed the rocket. */
	public record Pending(Rocket copy, ModID modId) {
	}

	private final Deque<Entry> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

	public synchronized Pending before(Rocket r) {
		return new Pending(r.copyWithOriginalID(), r.getModID());
	}

	/** Records the edit if the rocket changed since {@link #before}; returns whether it did. */
	public synchronized boolean after(Pending p, Rocket r, String what) {
		return after(p, r, what, what);
	}

	/** As {@link #after(Pending, Rocket, String)}, with the edit also said in words for the History page. */
	public synchronized boolean after(Pending p, Rocket r, String what, String said) {
		if (p == null || r.getModID().equals(p.modId())) {
			return false;
		}
		undo.push(new Entry(p.copy(), what, LocalTime.now().format(TIME), said == null ? what : said));
		while (undo.size() > LEVELS) {
			undo.removeLast();
		}
		redo.clear();
		return true;
	}

	public synchronized int undoable() {
		return undo.size();
	}

	public synchronized int redoable() {
		return redo.size();
	}

	/** Undoes the last {@code steps} edits; returns what was undone, most recent first. */
	public synchronized List<String> undo(Rocket r, int steps) {
		return move(r, steps, undo, redo);
	}

	public synchronized List<String> redo(Rocket r, int steps) {
		return move(r, steps, redo, undo);
	}

	private static List<String> move(Rocket r, int steps, Deque<Entry> from, Deque<Entry> to) {
		List<String> done = new ArrayList<>();
		for (int i = 0; i < steps && !from.isEmpty(); i++) {
			Entry e = from.pop();
			to.push(new Entry(r.copyWithOriginalID(), e.what(), e.when(), e.said()));
			r.loadFrom(e.state().copyWithOriginalID()); // loadFrom consumes its argument; keep the stored copy intact
			done.add(e.what());
		}
		return done;
	}

	/** The edits that can be undone, newest first: what (in words) and when. */
	public synchronized List<Map<String, Object>> entries() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Entry e : undo) {
			out.add(Map.of("what", e.said(), "when", e.when()));
		}
		return out;
	}

	/** The next {@code steps} edits an undo (or redo) would take back, in words, newest first. */
	public synchronized List<String> next(boolean undoing, int steps) {
		List<String> out = new ArrayList<>();
		for (Entry e : undoing ? undo : redo) {
			if (out.size() >= steps) {
				break;
			}
			out.add(e.said());
		}
		return out;
	}

	/** Edits that can be undone (newest first) and redone. */
	public synchronized Map<String, Object> describe() {
		Map<String, Object> m = new LinkedHashMap<>();
		List<String> u = new ArrayList<>();
		int n = 1;
		for (Iterator<Entry> it = undo.iterator(); it.hasNext(); n++) {
			Entry e = it.next();
			u.add(n + ". " + e.when() + " " + e.what());
		}
		m.put("canUndo", u.isEmpty() ? List.of("nothing to undo in this session") : u);
		if (!redo.isEmpty()) {
			List<String> r = new ArrayList<>();
			for (Entry e : redo) {
				r.add(e.when() + " " + e.what());
			}
			m.put("canRedo", r);
		}
		return m;
	}
}
