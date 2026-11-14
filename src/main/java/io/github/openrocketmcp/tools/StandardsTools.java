package io.github.openrocketmcp.tools;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.UnitSystem;
import io.github.openrocketmcp.units.Units;

/** Team standards, rule set and units. */
public final class StandardsTools {
	private StandardsTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("get_standards", "Show team standards and rule set",
				"The active team standards (units, launch site, safety factors, Cx, fill constant, packing factors, shear pin "
						+ "ratings) and the competition rule set thresholds with section references.",
				Schema.object().build(), true, a -> {
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("source", ctx.standards().source() == null ? "built-in defaults" : ctx.standards().source().toString());
					out.put("standards", ctx.standards().data());
					out.put("ruleSet", ctx.standards().rules());
					return Standards.pretty(out);
				}));

		s.tool(new ToolDef("update_standards", "Change team standards",
				"Merge a JSON patch into the team standards, e.g. {\"launchSite\": {\"altitudeMsl\": \"300 m\"}}, "
						+ "{\"recovery\": {\"shearPins\": {\"2-56 nylon\": {\"strength\": \"35 lbf\", \"source\": \"test 2026-03\"}}}} or "
						+ "{\"units\": \"imperial\"}. Pass saveTo to write the result (commit it so the team shares it). For targets, use "
						+ "the targets tool's set: it saves them and checks the flight in one call.",
				Schema.object().obj("patch", "JSON object merged into the standards (null removes a key).", true)
						.str("saveTo", "Write the merged standards to this file (e.g. openrocket-mcp.json).", false).build(),
				false, a -> {
					JsonObject patch = a.obj("patch").raw();
					if (patch.has("targets") && patch.get("targets").isJsonObject()) {
						// The same checks as the targets tool: a typo here would otherwise only show when the targets are read.
						JsonObject merged = io.github.openrocketmcp.or.Targets.targetsOf(ctx.standards()).deepCopy();
						TeamFile.apply(merged, patch.getAsJsonObject("targets"));
						io.github.openrocketmcp.or.Targets.validate(patch.getAsJsonObject("targets"), merged);
					}
					Standards next = ctx.standards().patched(patch);
					ctx.setStandards(next);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("updated", patch.keySet());
					if (a.has("saveTo")) {
						Path p = ctx.path(a.str("saveTo")).toAbsolutePath().normalize();
						next.save(p);
						out.put("saved", p.toString());
					}
					return out;
				}));

		s.tool(new ToolDef("load_standards", "Load a team standards file",
				"Load a team standards JSON file (see openrocket-mcp.example.json in the repository).",
				Schema.object().str("path", "Path to the JSON file.", true).build(), false, a -> {
					ctx.setStandards(Standards.load(ctx.path(a.str("path"))));
					return Map.of("loaded", ctx.standards().source().toString(), "ruleSet", ctx.standards().rulesName());
				}));

		s.tool(new ToolDef("set_units", "Set display units",
				"Display results in metric, imperial or both (e.g. \"30.48 m/s (100 ft/s)\"). Inputs always accept any unit.",
				Schema.object().enumStr("system", "Unit system.", true, "metric", "imperial", "both").build(), false, a -> {
					UnitSystem us;
					try {
						us = UnitSystem.parse(a.str("system"));
					} catch (IllegalArgumentException e) {
						throw new ToolException(e.getMessage());
					}
					ctx.setStandards(ctx.standards().patched(JsonParser.parseString("{\"units\":\"" + us.name().toLowerCase(Locale.ROOT) + "\"}").getAsJsonObject()));
					Units.setSystem(us);
					return Map.of("units", us.name().toLowerCase(Locale.ROOT));
				}));
	}
}
