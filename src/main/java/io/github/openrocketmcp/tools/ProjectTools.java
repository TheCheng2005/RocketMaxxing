package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.ClaimCheck;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.ProjectFacts;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * The project side of the chief engineer's job: check what meeting notes and messages say against the design, and
 * keep a dated record of where the design stands so the team sees what changed week to week. Reading the notes and
 * posting the summary go through the client's own connectors (Slack, Notion, Google Drive); this server supplies the
 * engineering numbers.
 */
public final class ProjectTools {
	private ProjectTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("verify_claims", "Check meeting notes / messages against the design",
				"Check what the team's notes, messages or documents say about the rocket against the design and its simulation. "
						+ "Read the notes (e.g. from Notion or Slack through their connectors), state each number or choice they "
						+ "give as a claim, and this tool compares it: apogee, speed, Mach, rail exit, stability, masses, length, "
						+ "diameter, CG / CP, descent rates, landing distance, flutter margin, the motor, or any part's property "
						+ "(component + property, e.g. the fins' thickness or the nose cone's material). Values are read as written: "
						+ "\"~30k ft\", \">= 2 cal\", \"under 25 lb\", \"1/8 in\". Each claim comes back MATCH, MISMATCH (with the "
						+ "design's value and the difference) or UNCHECKED (why). Mismatches mean the notes and the design disagree: "
						+ "one of them is out of date.",
				SimTools.simSelect(Schema.object())
						.array("claims", "What the notes say, one entry per number or choice: {quantity (e.g. \"apogee\", \"rail exit\", "
								+ "\"dry mass\", \"main descent rate\", \"motor\") or component + property, value (as written, with its "
								+ "unit), tolerance? (\"5%\" or \"200 ft\"), source? (e.g. \"Oct 12 meeting\")}.",
								Schema.object()
										.str("quantity", "What the number is, e.g. \"apogee\", \"rail exit\", \"dry mass\", \"motor\" "
												+ "(or give component + property).", false)
										.str("component", "A part by name, with property, e.g. \"Fins\".", false)
										.str("property", "The part's property, e.g. \"thickness\", \"material\".", false)
										.str("value", "As written, with its unit: \"~30k ft\", \">= 2 cal\", \"1/8 in\".", true)
										.str("tolerance", "Optional: \"5%\" or \"200 ft\".", false)
										.str("source", "Optional: where it was said, e.g. \"Oct 12 meeting\".", false)
										.item(), true)
						.build(),
				true, a -> verify(ctx, a)));

		s.tool(new ToolDef("progress_log", "Track the design over time; draft a status update",
				"Record where the design stands today (the headline numbers, the rule-check failures and warnings, what is "
						+ "not set up yet) next to the design file, and compare with the last record (or a milestone such as "
						+ "\"PDR\"): what improved, what got worse, what was fixed, what is new. Returns a digest written for a team "
						+ "channel or a Notion page, ready to post through those connectors. mode record (default) keeps the "
						+ "snapshot, preview compares without keeping it, history lists the records. Use it after a design session, "
						+ "before a meeting, or on a schedule for a weekly update.",
				SimTools.simSelect(Schema.object())
						.enumStr("mode", "record (default) | preview | history", false, "record", "preview", "history")
						.str("note", "What happened, e.g. \"after the fin review\" (kept with the record).", false)
						.str("label", "A milestone name for this record, e.g. \"PDR\" (compare with it later through since).", false)
						.str("since", "Compare with: \"last\" (default), a label (\"PDR\") or a date (\"2026-10-01\": the last record "
								+ "on or before it).", false)
						.str("path", "Also write the digest to this Markdown file.", false).build(),
				false, a -> progress(ctx, a)));
	}

	// ------------------------------------------------------------------------------------------- claims

	private static Object verify(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Simulation sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), ctx.standards(), false);
		Sims.ensure(sim);
		FlightConfiguration fc = d.doc.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		Map<String, ProjectFacts.Fact> facts = ProjectFacts.of(sim, fc, ctx.standards());
		JsonArray claims = a.array("claims");
		if (claims.isEmpty() || claims.size() > 60) {
			throw new ToolException("Give 1-60 claims.");
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		int match = 0, mismatch = 0, unchecked = 0;
		for (JsonElement e : claims) {
			if (!e.isJsonObject()) {
				throw new ToolException("Each claim is an object, e.g. {\"quantity\": \"apogee\", \"value\": \"30000 ft\"}; got " + e + ".");
			}
			JsonObject c = e.getAsJsonObject();
			Map<String, Object> r = ClaimCheck.checkOrExplain(facts, d.doc.getRocket(), str(c, "quantity"), str(c, "component"),
					str(c, "property"), str(c, "value"), str(c, "tolerance"), str(c, "source"));
			switch (String.valueOf(r.get("status"))) {
				case "MATCH" -> match++;
				case "MISMATCH" -> mismatch++;
				default -> unchecked++;
			}
			rows.add(r);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("design", d.name() + " (" + fc.getName() + ", " + sim.getName() + ")");
		out.put("summary", claims.size() + " claim(s): " + match + " match, " + mismatch + " mismatch, " + unchecked + " unchecked.");
		out.put("claims", rows);
		out.put("note", mismatch > 0
				? "Each mismatch means the notes and the design disagree: either the notes record a decision the design file does "
						+ "not have yet, or they quote an old number. Ask the owner which before changing either."
				: "Values from the design and OpenRocket's simulation, within each quantity's tolerance (give tolerance to tighten it).");
		return out;
	}

	static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? (o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : o.get(k).toString()) : null;
	}

	// ------------------------------------------------------------------------------------------- progress

	/** Where a design's records are kept: next to its file, else in the workspace's progress folder. */
	static Path logFile(Context ctx, Designs.Design d) {
		if (d.path != null) {
			String n = d.path.getFileName().toString();
			String stem = n.replaceFirst("(?i)\\.(ork|rmx)$", "");
			return d.path.resolveSibling(stem + ".progress.jsonl");
		}
		return ctx.path("progress/" + d.name().replaceAll("[^A-Za-z0-9._-]+", "-") + ".progress.jsonl");
	}

	static List<JsonObject> read(Path f) throws IOException {
		List<JsonObject> out = new ArrayList<>();
		if (!Files.exists(f)) {
			return out;
		}
		for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
			if (!line.isBlank()) {
				try {
					out.add(JsonParser.parseString(line).getAsJsonObject());
				} catch (RuntimeException e) {
					// a damaged line (an edit by hand) is skipped, not fatal for the rest of the record
				}
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Object progress(Context ctx, Args a) throws IOException {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Path file = logFile(ctx, d);
		List<JsonObject> past = read(file);
		String mode = a.str("mode", "record");
		if (mode.equals("history")) {
			return history(file, past);
		}
		Simulation sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), ctx.standards(), false);
		Sims.ensure(sim);
		FlightConfiguration fc = d.doc.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		Map<String, Object> status = StatusTools.status(ctx, d.id, fc.getId().toString());
		JsonObject snap = new JsonObject();
		snap.addProperty("time", Instant.now().toString());
		if (a.has("note") && !a.str("note").isBlank()) {
			snap.addProperty("note", a.str("note"));
		}
		if (a.has("label") && !a.str("label").isBlank()) {
			snap.addProperty("label", a.str("label").trim());
		}
		snap.addProperty("design", d.name());
		snap.addProperty("configuration", fc.getName());
		snap.addProperty("simulation", sim.getName());
		snap.addProperty("readiness", String.valueOf(status.get("readiness")));
		JsonObject facts = new JsonObject();
		for (ProjectFacts.Fact f : ProjectFacts.of(sim, fc, ctx.standards()).values()) {
			JsonObject o = new JsonObject();
			o.addProperty("label", f.label());
			o.addProperty("shown", f.shown());
			if (f.text() != null) {
				o.addProperty("text", f.text());
			} else {
				o.addProperty("si", f.si());
				o.addProperty("dim", f.dim() == null ? "" : f.dim().name());
				o.addProperty("unit", f.unitText());
				o.addProperty("tol", Math.max(f.relTol() * Math.abs(f.si()), f.absTol()));
			}
			facts.add(f.key(), o);
		}
		snap.add("facts", facts);
		JsonArray issues = new JsonArray();
		for (Map<String, Object> i : (List<Map<String, Object>>) status.get("items")) {
			String st = String.valueOf(i.get("status"));
			// Session state (unsaved edits) is not the design's progress.
			if ((st.equals("FAIL") || st.equals("WARN") || st.equals("TODO")) && !"File".equals(String.valueOf(i.get("area")))) {
				JsonObject o = new JsonObject();
				o.addProperty("status", st);
				o.addProperty("area", String.valueOf(i.get("area")));
				String finding = String.valueOf(i.get("finding"));
				o.addProperty("finding", finding);
				o.addProperty("key", st.equals("TODO") ? finding : issueKey(finding));
				issues.add(o);
			}
		}
		snap.add("issues", issues);
		JsonArray next = new JsonArray();
		for (String n : (List<String>) status.get("nextSteps")) {
			next.add(n);
		}
		snap.add("nextSteps", next);

		JsonObject base = since(past, a.str("since", "last"));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("design", d.name());
		out.put("readiness", snap.get("readiness").getAsString());
		Map<String, Object> changes = base == null ? null : compare(base, snap);
		if (changes != null) {
			out.put("comparedWith", describe(base));
			out.put("changes", changes);
		} else {
			out.put("comparedWith", past.isEmpty() ? "nothing yet: this is the first record" : "no record matches since \"" + a.str("since", "last") + "\"");
		}
		String digest = digest(snap, base, changes);
		out.put("digest", digest);
		if (mode.equals("record")) {
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			Files.writeString(file, new Gson().toJson(snap) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			out.put("recorded", file.toString() + " (" + (past.size() + 1) + " record" + (past.isEmpty() ? "" : "s") + ")");
		} else {
			out.put("recorded", "no (preview)");
		}
		if (a.has("path")) {
			Path p = ctx.path(a.str("path"));
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			Files.writeString(p, digest, StandardCharsets.UTF_8);
			out.put("digestFile", p.toString());
		}
		out.put("note", "Post the digest through the team's Slack or Notion connector if they want it there; numbers are from the "
				+ "design and OpenRocket's simulation at the time of the record.");
		return out;
	}

	/**
	 * What stays the same about a rule finding from one record to the next: the item before its value ("...: 1.2 cal at
	 * t=3 s"), with details after a comma in brackets dropped ("Fin flutter margin (Fins, Balsa)" -> "(Fins)"), so a
	 * change of material or wind case does not read as one problem fixed and another new.
	 */
	static String issueKey(String finding) {
		return finding.split(": ", 2)[0].replaceAll("\\(([^,()]*),[^()]*\\)", "($1)").trim();
	}

	/** The headline numbers, in the order a digest lists them. */
	static final List<String> HEADLINE = List.of("motor", "apogee", "maxMach", "railExitVelocity", "minStability", "liftoffMass",
			"thrustToWeight", "drogueDescentRate", "mainDescentRate", "landingDistance", "flutterMargin");

	/** The record to compare with: the last one, the last with a label, or the last on or before a date. */
	static JsonObject since(List<JsonObject> past, String since) {
		if (past.isEmpty()) {
			return null;
		}
		String s = since == null || since.isBlank() ? "last" : since.trim();
		if (s.equalsIgnoreCase("last")) {
			return past.get(past.size() - 1);
		}
		for (int i = past.size() - 1; i >= 0; i--) {
			JsonObject o = past.get(i);
			if (o.has("label") && o.get("label").getAsString().equalsIgnoreCase(s)) {
				return o;
			}
		}
		if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
			for (int i = past.size() - 1; i >= 0; i--) {
				if (past.get(i).get("time").getAsString().substring(0, 10).compareTo(s.substring(0, 10)) <= 0) {
					return past.get(i);
				}
			}
			return null;
		}
		throw new ToolException("No record labelled \"" + s + "\". Labels: " + labels(past) + "; or use \"last\" or a date (2026-10-01).");
	}

	static String labels(List<JsonObject> past) {
		Set<String> l = new LinkedHashSet<>();
		for (JsonObject o : past) {
			if (o.has("label")) {
				l.add(o.get("label").getAsString());
			}
		}
		return l.isEmpty() ? "none" : String.join(", ", l);
	}

	static String describe(JsonObject o) {
		return day(o) + (o.has("label") ? " (" + o.get("label").getAsString() + ")" : "") + (o.has("note") ? ": " + o.get("note").getAsString() : "");
	}

	static String day(JsonObject o) {
		return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.parse(o.get("time").getAsString()));
	}

	/** What moved between two records: numbers beyond a quarter of their tolerance, the motor, issues fixed and new. */
	static Map<String, Object> compare(JsonObject was, JsonObject now) {
		Map<String, Object> out = new LinkedHashMap<>();
		List<String> numbers = new ArrayList<>(), headline = new ArrayList<>();
		JsonObject a = was.getAsJsonObject("facts"), b = now.getAsJsonObject("facts");
		for (String k : b.keySet()) {
			JsonObject nb = b.getAsJsonObject(k);
			if (!a.has(k)) {
				numbers.add(nb.get("label").getAsString() + ": " + nb.get("shown").getAsString() + " (new)");
				continue;
			}
			JsonObject na = a.getAsJsonObject(k);
			if (nb.has("text") || na.has("text")) {
				String ta = na.has("text") ? na.get("text").getAsString() : na.get("shown").getAsString();
				String tb = nb.has("text") ? nb.get("text").getAsString() : nb.get("shown").getAsString();
				if (!ta.equals(tb)) {
					numbers.add(nb.get("label").getAsString() + ": " + ta + " -> " + tb);
					headline.add(nb.get("label").getAsString() + ": " + ta + " -> " + tb);
				}
				continue;
			}
			double va = na.get("si").getAsDouble(), vb = nb.get("si").getAsDouble(), tol = nb.has("tol") ? nb.get("tol").getAsDouble() : 0;
			if (Math.abs(vb - va) > 0.25 * tol && Math.abs(vb - va) > 1e-12) {
				String dim = nb.get("dim").getAsString(), unit = nb.has("unit") ? nb.get("unit").getAsString() : "";
				double dlt = vb - va;
				String delta = dim.isEmpty() ? Units.num(Math.abs(dlt)) + (unit.isEmpty() ? "" : " " + unit) : Units.fmt(Math.abs(dlt), Dim.valueOf(dim));
				String line = nb.get("label").getAsString() + ": " + na.get("shown").getAsString() + " -> " + nb.get("shown").getAsString()
						+ " (" + (dlt >= 0 ? "+" : "-") + delta + ")";
				numbers.add(line);
				if (HEADLINE.contains(k)) {
					headline.add(line);
				}
			}
		}
		for (String k : a.keySet()) {
			if (!b.has(k)) {
				numbers.add(a.getAsJsonObject(k).get("label").getAsString() + ": no longer computed");
			}
		}
		Map<String, String> ia = issues(was), ib = issues(now);
		List<String> fixed = new ArrayList<>(), added = new ArrayList<>(), worse = new ArrayList<>();
		ia.forEach((key, st) -> {
			if (!ib.containsKey(key)) {
				fixed.add(st + " " + key);
			}
		});
		ib.forEach((key, st) -> {
			if (!ia.containsKey(key)) {
				added.add(st + " " + key);
			} else if (st.equals("FAIL") && !ia.get(key).equals("FAIL")) {
				worse.add(ia.get(key) + " -> FAIL " + key);
			}
		});
		out.put("numbers", numbers.isEmpty() ? List.of("no change beyond tolerance") : numbers);
		out.put("headline", headline);
		out.put("resolved", fixed);
		out.put("new", added);
		if (!worse.isEmpty()) {
			out.put("worse", worse);
		}
		return out;
	}

	static Map<String, String> issues(JsonObject o) {
		Map<String, String> m = new LinkedHashMap<>();
		if (o.has("issues")) {
			for (JsonElement e : o.getAsJsonArray("issues")) {
				JsonObject i = e.getAsJsonObject();
				m.put(i.get("key").getAsString(), i.get("status").getAsString());
			}
		}
		return m;
	}

	/** A short update for a team channel or page: readiness, the numbers, what changed, what is open, what is next. */
	@SuppressWarnings("unchecked")
	static String digest(JsonObject now, JsonObject was, Map<String, Object> changes) {
		StringBuilder b = new StringBuilder();
		b.append("**").append(now.get("design").getAsString()).append(": engineering status ").append(day(now)).append("**");
		if (was != null) {
			b.append(" (since ").append(describe(was)).append(")");
		}
		b.append("\n\n").append(now.get("readiness").getAsString()).append(".\n\n");
		b.append("Key numbers (").append(now.get("configuration").getAsString()).append("):\n");
		JsonObject f = now.getAsJsonObject("facts");
		for (String k : HEADLINE) {
			if (f.has(k)) {
				b.append("- ").append(f.getAsJsonObject(k).get("label").getAsString()).append(": ")
						.append(f.getAsJsonObject(k).get("shown").getAsString()).append("\n");
			}
		}
		if (changes != null) {
			List<String> nums = (List<String>) changes.get("headline");
			int all = ((List<String>) changes.get("numbers")).size();
			b.append("\nChanged:\n");
			if (nums.isEmpty()) {
				b.append("- ").append(all == 1 && ((List<String>) changes.get("numbers")).get(0).startsWith("no change")
						? "no change in the headline numbers beyond tolerance" : "headline numbers within tolerance").append("\n");
			}
			for (String n : nums) {
				b.append("- ").append(n).append("\n");
			}
			if (all > nums.size() && !((List<String>) changes.get("numbers")).get(0).startsWith("no change")) {
				b.append("- (").append(all - nums.size()).append(" more in the details)\n");
			}
			list(b, "Fixed", (List<String>) changes.get("resolved"));
			list(b, "New", (List<String>) changes.get("new"));
			list(b, "Worse", (List<String>) changes.getOrDefault("worse", List.of()));
		}
		List<String> open = new ArrayList<>();
		for (JsonElement e : now.getAsJsonArray("issues")) {
			JsonObject i = e.getAsJsonObject();
			if (!i.get("status").getAsString().equals("WARN") || open.size() < 6) {
				open.add(i.get("status").getAsString() + " (" + i.get("area").getAsString() + ") " + i.get("finding").getAsString());
			}
		}
		list(b, "Open", open.size() > 8 ? open.subList(0, 8) : open);
		if (open.size() > 8) {
			b.append("- ... and ").append(open.size() - 8).append(" more (design_status)\n");
		}
		List<String> next = new ArrayList<>();
		for (JsonElement e : now.getAsJsonArray("nextSteps")) {
			next.add(e.getAsString());
		}
		if (!next.isEmpty()) {
			b.append("\nNext steps:\n");
			for (int i = 0; i < Math.min(5, next.size()); i++) {
				b.append(i + 1).append(". ").append(next.get(i)).append("\n");
			}
		}
		b.append("\n_From the design file and OpenRocket's simulation; automated checks only (ground tests and the RSO still apply)._\n");
		return b.toString();
	}

	static void list(StringBuilder b, String title, List<String> items) {
		if (items == null || items.isEmpty()) {
			return;
		}
		b.append("\n").append(title).append(":\n");
		for (String i : items) {
			b.append("- ").append(i).append("\n");
		}
	}

	static Map<String, Object> history(Path file, List<JsonObject> past) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("file", file.toString());
		out.put("records", past.size());
		List<Map<String, Object>> rows = new ArrayList<>();
		for (JsonObject o : past) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("date", day(o));
			if (o.has("label")) {
				r.put("label", o.get("label").getAsString());
			}
			if (o.has("note")) {
				r.put("note", o.get("note").getAsString());
			}
			JsonObject f = o.getAsJsonObject("facts");
			for (String k : List.of("motor", "apogee", "minStability", "liftoffMass")) {
				if (f.has(k)) {
					r.put(k, f.getAsJsonObject(k).get("shown").getAsString());
				}
			}
			Map<String, String> is = issues(o);
			r.put("fails", is.values().stream().filter("FAIL"::equals).count());
			r.put("warnings", is.values().stream().filter("WARN"::equals).count());
			r.put("toDo", is.values().stream().filter("TODO"::equals).count());
			rows.add(r);
		}
		out.put("history", rows);
		if (past.isEmpty()) {
			out.put("note", "No records yet: progress_log (mode record) keeps one.");
		}
		return out;
	}
}
