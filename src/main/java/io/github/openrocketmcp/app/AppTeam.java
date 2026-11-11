package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.FlightLog;
import io.github.openrocketmcp.or.Library;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Targets;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.units.Dim;

/**
 * The Standards, Targets and Flight data pages' tools: the team's standards file, its targets, flight logs and the
 * library of past rockets. Registered by {@link AppTools}.
 */
final class AppTeam {
	private AppTeam() {
	}

	// ------------------------------------------------------------------------------------------- flight data

	static Map<String, Object> flightLog(Context ctx, Args a) throws IOException {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		FlightLog.Log log = FlightLog.parse(Files.readString(ctx.path(a.str("path"))), a.str("altitudeUnit", null));
		Sims.Overrides o = new Sims.Overrides(a.qtyOrNaN("windSpeed", Dim.VELOCITY), Double.NaN, Double.NaN, Double.NaN,
				a.qtyOrNaN("launchAngle", Dim.ANGLE), Double.NaN, a.qtyOrNaN("launchSiteAltitude", Dim.DISTANCE), Double.NaN,
				Double.NaN, a.qtyOrNaN("temperature", Dim.TEMPERATURE), null, Double.NaN);
		Simulation base = Sims.prepare(d, null, a.str("configuration", null), o, ctx.standards(), false);
		Map<String, Object> out = FlightLog.compare(base, d.doc, log, null, d.name(), true);
		out.put("simulation", base.getName());
		return out;
	}

	static Map<String, Object> library(Context ctx, Args a) throws IOException {
		Path root = a.has("folder") ? ctx.path(a.str("folder")) : ctx.workspace();
		Library.Scan scan = Library.scan(root, a.bool("simulate", true));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("folder", scan.root().toString());
		List<Map<String, Object>> designs = new ArrayList<>();
		for (Library.Entry e : scan.designs()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", e.name());
			m.put("path", e.file().toString());
			m.put("file", e.relPath());
			m.put("year", e.year());
			m.put("yearFromPath", e.yearSource().equals("path"));
			if (e.error() != null) {
				m.put("error", e.error());
			} else {
				m.put("stages", e.stages());
				m.put("length", e.length());
				m.put("diameter", e.diameter());
				m.put("launchMass", e.launchMass());
				m.put("motors", e.motors());
				m.put("motorClass", e.motorClass());
				m.put("totalImpulse", e.totalImpulse());
				m.put("predictedApogee", e.predictedApogee());
				m.put("predictionSource", e.predictionSource());
				m.put("stability", e.stability());
				m.put("airframe", e.airframeMaterial());
				m.put("fins", e.finMaterial());
				m.put("recovery", e.recovery());
			}
			designs.add(m);
		}
		out.put("designs", designs);
		List<Map<String, Object>> flights = new ArrayList<>();
		for (Library.Flight f : scan.flights()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("log", f.relPath());
			m.put("path", f.file().toString());
			m.put("year", f.year());
			m.put("apogee", f.apogee());
			if (f.design() != null) {
				m.put("design", f.design().relPath());
				m.put("predictedApogee", f.design().predictedApogee());
			}
			flights.add(m);
		}
		out.put("flights", flights);
		out.put("skipped", scan.skipped());
		return out;
	}

	// ------------------------------------------------------------------------------------------- targets

	static Map<String, Object> setTargets(Context ctx, JsonObject patch) throws IOException {
		for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
			Targets.Metric m = Targets.metric(e.getKey());
			if (m == null || !e.getValue().isJsonObject()) {
				throw new ToolException("Unknown target '" + e.getKey() + "'.");
			}
			for (String k : e.getValue().getAsJsonObject().keySet()) {
				if (!List.of("min", "max", "goal", "note").contains(k)) {
					throw new ToolException("A target has min, max, goal and note, not '" + k + "'.");
				}
			}
			Targets.bound(e.getValue().getAsJsonObject(), "min", m);
			Targets.bound(e.getValue().getAsJsonObject(), "max", m);
			Targets.bound(e.getValue().getAsJsonObject(), "goal", m);
		}
		JsonObject wrapped = new JsonObject();
		wrapped.add("targets", patch);
		Map<String, Object> out = new LinkedHashMap<>(setStandards(ctx, wrapped));
		JsonObject targets = Targets.targetsOf(ctx.standards());
		out.put("targets", targets.keySet());
		return out;
	}

	/** The team standards file: the one loaded, else openrocket-mcp.json in the designs folder. */
	static Path standardsFile(Context ctx) {
		return ctx.standards().source() != null ? ctx.standards().source()
				: (ctx.workspace() != null ? ctx.workspace() : Path.of("").toAbsolutePath()).resolve(Standards.FILE_NAME);
	}

	static JsonObject teamFile(Path file) throws IOException {
		return Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
	}

	/**
	 * Merges a change into the team standards file and uses the result at once. The file keeps only what the team set
	 * (the built-in defaults stay defaults, so later improvements to them still apply); null puts a value back to its
	 * default. The change is checked before anything is written.
	 */
	static Map<String, Object> setStandards(Context ctx, JsonObject patch) throws IOException {
		Path file = standardsFile(ctx);
		JsonObject team = teamFile(file);
		apply(team, patch);
		Standards next = Standards.fromTeam(team, file);
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, Standards.pretty(team));
		ctx.setStandards(next);
		return Map.of("saved", file.toString());
	}

	/** Deep merge where null removes the key, and an object left empty goes too. */
	static void apply(JsonObject target, JsonObject patch) {
		for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
			JsonElement v = e.getValue();
			if (v.isJsonNull()) {
				target.remove(e.getKey());
			} else if (v.isJsonObject() && target.has(e.getKey()) && target.get(e.getKey()).isJsonObject()) {
				apply(target.getAsJsonObject(e.getKey()), v.getAsJsonObject());
				if (target.getAsJsonObject(e.getKey()).size() == 0) {
					target.remove(e.getKey());
				}
			} else if (v.isJsonObject()) {
				JsonObject fresh = new JsonObject();
				apply(fresh, v.getAsJsonObject());
				if (fresh.size() > 0) {
					target.add(e.getKey(), fresh);
				}
			} else {
				target.add(e.getKey(), v.deepCopy());
			}
		}
	}

	/** Values not set (null, such as the launch site's altitude) as "", so they reach the page as fields to fill in. */
	static JsonObject unset(JsonObject o) {
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			if (e.getValue().isJsonNull()) {
				e.setValue(new com.google.gson.JsonPrimitive(""));
			} else if (e.getValue().isJsonObject()) {
				unset(e.getValue().getAsJsonObject());
			}
		}
		return o;
	}

	/** What the standards page shows: the team's own values, the values in use, the defaults, and the rule sets. */
	static Map<String, Object> standards(Context ctx) throws IOException {
		Path file = standardsFile(ctx);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("file", file.toString());
		out.put("fileExists", Files.exists(file));
		out.put("team", teamFile(file));
		out.put("merged", unset(ctx.standards().data().deepCopy()));
		out.put("defaults", unset(Standards.defaultsJson()));
		List<Map<String, String>> rules = new ArrayList<>();
		for (String id : Standards.BUILT_IN_RULES) {
			rules.add(Map.of("id", id, "name", Standards.ruleSetName(id)));
		}
		rules.add(Map.of("id", "none", "name", "None (team standards only)"));
		out.put("ruleSets", rules);
		out.put("ruleSet", ctx.standards().rulesName());
		return out;
	}
}
