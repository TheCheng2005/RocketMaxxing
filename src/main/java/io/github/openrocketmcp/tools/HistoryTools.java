package io.github.openrocketmcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.History;

/** Undo, redo and the edit history of an open design. */
public final class HistoryTools {
	private HistoryTools() {
	}

	/** Tools that never go into the history themselves. */
	static final Set<String> SKIP = Set.of("undo", "redo", "history", "open_design", "close_design", "save_design");

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("undo", "Undo edits",
				"Undo the last edit(s) to a design made in this session (components, motors, deployment, applied optimizer or "
						+ "avionics results...), newest first. Each tool call that changed the rocket is one step. Files on disk "
						+ "are not touched until save_design. Use history to see what can be undone.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.integer("steps", "How many edits to undo (default 1).", false).build(),
				false, a -> move(ctx, a, true)));
		s.tool(new ToolDef("redo", "Redo edits", "Redo edits that were undone (until a new edit is made).",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.integer("steps", "How many edits to redo (default 1).", false).build(),
				false, a -> move(ctx, a, false)));
		s.tool(new ToolDef("history", "Edit history",
				"The edits made to a design in this session that can be undone (newest first, with the time and the tool "
						+ "call), and those that can be redone; plus whether there are unsaved changes.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false).build(), true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Map<String, Object> out = new LinkedHashMap<>(d.history.describe());
					out.put("unsavedChanges", !d.doc.isSaved());
					return out;
				}));
	}

	private static Object move(Context ctx, Args a, boolean undo) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		int steps = Math.max(1, Math.min(History.LEVELS, a.integer("steps", 1)));
		List<String> words = d.history.next(undo, steps);
		List<String> done = undo ? d.history.undo(d.doc.getRocket(), steps) : d.history.redo(d.doc.getRocket(), steps);
		if (done.isEmpty()) {
			throw new ToolException("Nothing to " + (undo ? "undo" : "redo") + " for " + d.name() + " in this session.");
		}
		d.doc.setSaved(false);
		Journal.add(ctx, undo ? "undo" : "redo", d.name(), d.path == null ? null : d.path.toString(),
				(undo ? "Undid: " : "Redid: ") + String.join("; ", words), null);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put(undo ? "undone" : "redone", done);
		out.put("canUndo", d.history.undoable());
		out.put("canRedo", d.history.redoable());
		out.put("stability", Analysis.stageStacks(d.doc.getRocket().getSelectedConfiguration(), 0.3));
		return out;
	}

	/**
	 * Wraps an editing tool so each call that changes a design's rocket can be undone. Read-only tools, and calls
	 * that name no design when several are open, pass through.
	 */
	public static ToolDef recording(Context ctx, ToolDef t) {
		if (t.readOnly() || SKIP.contains(t.name())) {
			return t;
		}
		return t.withHandler(args -> {
			Designs.Design d = target(ctx, args);
			if (d == null) {
				return t.handler().call(args);
			}
			History.Pending p = d.history.before(d.doc.getRocket());
			String said = described(t, args, d); // before the call: a removed part's name is gone after it
			try {
				return t.handler().call(args);
			} finally {
				if (d.history.after(p, d.doc.getRocket(), t.name() + summary(args), said)) {
					Journal.add(ctx, "edit", d.name(), d.path == null ? null : d.path.toString(), said, null);
				}
			}
		});
	}

	private static Designs.Design target(Context ctx, Args args) {
		try {
			String id = args.str("designId", null);
			if (id == null && ctx.designs.all().size() != 1) {
				return null;
			}
			return ctx.designs.get(id);
		} catch (RuntimeException e) {
			return null; // the tool reports the unknown design itself
		}
	}

	/**
	 * An edit as the team would say it: "Set Length of Nose cone to 45 cm" for a field typed in the app, else the
	 * tool's title with its arguments in short.
	 */
	static String described(ToolDef t, Args args, Designs.Design d) {
		try {
			String part = args.has("component") ? partName(d, args.str("component")) : null;
			if (t.name().equals("app_edit") && args.has("key")) {
				String key = args.str("key").replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT);
				return "Set " + key + (part == null ? "" : " of " + part) + " to " + args.get("value").toString().replace("\"", "");
			}
			String title = t.title() == null ? t.name() : t.title().replaceFirst("^App: ", "");
			title = title.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + title.substring(1);
			return title + (part == null ? "" : ": " + part) + summary(args).replaceAll("component=[^,)]*,? ?", "").replace(" ()", "");
		} catch (RuntimeException e) {
			return t.name() + summary(args);
		}
	}

	private static String partName(Designs.Design d, String id) {
		try {
			return io.github.openrocketmcp.or.Components.find(d.doc.getRocket(), id).getName();
		} catch (RuntimeException e) {
			return id;
		}
	}

	/** The call's arguments in short, e.g. "(component=Fins, properties={height: 5 in})". */
	static String summary(Args args) {
		StringBuilder b = new StringBuilder();
		for (String k : args.keys()) {
			if (k.equals("designId")) {
				continue;
			}
			String v = args.get(k).toString().replace("\"", "");
			b.append(b.length() == 0 ? "" : ", ").append(k).append('=').append(v);
		}
		String s = b.toString();
		if (s.length() > 140) {
			s = s.substring(0, 137) + "...";
		}
		return s.isEmpty() ? "" : " (" + s + ")";
	}
}
