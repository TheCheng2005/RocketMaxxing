package io.github.openrocketmcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.CallContext;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Targets;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.report.Png;
import io.github.openrocketmcp.report.Reports;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Running simulations, flight data, sweeps and rule checks. */
public final class SimTools {
	private SimTools() {
	}

	static Schema overrides(Schema s) {
		return s.qty("windSpeed", "Average wind speed, e.g. \"30 km/h\".", false)
				.qty("windDirection", "Wind direction (from), e.g. \"270 deg\".", false)
				.num("windTurbulence", "Turbulence intensity 0-1 (e.g. 0.1).", false)
				.qty("railLength", "Launch rail length.", false)
				.qty("launchAngle", "Launch rail angle FROM VERTICAL, e.g. \"6 deg\" (= 84 deg elevation).", false)
				.qty("launchDirection", "Rail direction, e.g. \"90 deg\".", false)
				.bool("launchIntoWind", "Aim the rail into the wind.", false)
				.qty("launchSiteAltitude", "Launch site altitude above sea level.", false)
				.num("latitude", "Launch site latitude (deg).", false)
				.num("longitude", "Launch site longitude (deg).", false)
				.qty("temperature", "Ground temperature, e.g. \"30 C\".", false);
	}

	static Sims.Overrides overrides(Args a) {
		Boolean intoWind = a.has("launchIntoWind") ? a.bool("launchIntoWind", true) : null;
		return new Sims.Overrides(a.qtyOrNaN("windSpeed", Dim.VELOCITY), a.qtyOrNaN("windDirection", Dim.ANGLE),
				a.num("windTurbulence", Double.NaN), a.qtyOrNaN("railLength", Dim.LENGTH), a.qtyOrNaN("launchAngle", Dim.ANGLE),
				a.qtyOrNaN("launchDirection", Dim.ANGLE), a.qtyOrNaN("launchSiteAltitude", Dim.DISTANCE),
				a.num("latitude", Double.NaN), a.num("longitude", Double.NaN), a.qtyOrNaN("temperature", Dim.TEMPERATURE),
				intoWind, Double.NaN);
	}

	/** The simulation choice inputs (design, simulation, configuration), for other tools that pick a flight the same way. */
	public static Schema selectSchema(Schema s) {
		return simSelect(s);
	}

	static Schema simSelect(Schema s) {
		return s.str("designId", DesignTools.DESIGN_ID, false)
				.str("simulation", "Simulation index or name. Default: the first simulation of the configuration; one is "
						+ "created with the team's launch-site defaults if none exists.", false)
				.str("configuration", DesignTools.CONFIG, false);
	}

	static Map<String, Object> extensions(Context ctx, Args a) {
		String act = a.str("action", "list");
		if (act.equals("types")) {
			return Map.of("types", io.github.openrocketmcp.or.Extensions.kinds());
		}
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Simulation sim = Sims.find(d, a.str("simulation", null));
		if (sim == null) {
			if (d.doc.getSimulations().isEmpty()) {
				throw new ToolException("The design has no simulation yet: run_simulation makes one, then add extensions to it.");
			}
			sim = d.doc.getSimulations().get(0);
		}
		List<info.openrocket.core.simulation.extension.SimulationExtension> exts = sim.getSimulationExtensions();
		Map<String, Object> out = new LinkedHashMap<>();
		boolean changed = false;
		switch (act) {
			case "add" -> {
				if (!a.has("type")) {
					throw new ToolException("Say which extension to add (type), e.g. AirStart; action types lists them.");
				}
				var e = io.github.openrocketmcp.or.Extensions.create(a.str("type"));
				if (e instanceof info.openrocket.core.simulation.extension.impl.ScriptingExtension sc) {
					sc.setEnabled(false); // until a person (or settings below) turns it on
				}
				applySettings(ctx, e, a);
				if (e instanceof info.openrocket.core.simulation.extension.example.RollControl rc) {
					// OpenRocket looks for a fin set called "CONTROL": use the design's own fins unless one was named.
					List<String> fins = new ArrayList<>();
					for (info.openrocket.core.rocketcomponent.RocketComponent c : d.doc.getRocket()) {
						if (c instanceof info.openrocket.core.rocketcomponent.FinSet) {
							fins.add(c.getName());
						}
					}
					if (fins.isEmpty()) {
						throw new ToolException("Roll control turns a fin set, and this design has none.");
					}
					if (!fins.contains(rc.getControlFinName())) {
						if (a.has("settings") && a.raw().getAsJsonObject("settings").has("controlFinName")) {
							throw new ToolException("No fin set called \"" + rc.getControlFinName() + "\". Fin sets: " + fins + ".");
						}
						rc.setControlFinName(fins.get(fins.size() - 1)); // the aft-most set
					}
				}
				exts.add(e);
				out.put("added", io.github.openrocketmcp.or.Extensions.describe(e, exts.size() - 1));
				Journal.add(ctx, "edit", d.name(), d.path == null ? null : d.path.toString(), sim.getName() + ": added the extension " + nameOf(e), null);
				changed = true;
			}
			case "set" -> {
				var e = pick(exts, a);
				applySettings(ctx, e, a);
				out.put("changed", io.github.openrocketmcp.or.Extensions.describe(e, exts.indexOf(e)));
				Journal.add(ctx, "edit", d.name(), d.path == null ? null : d.path.toString(), sim.getName() + ": changed the extension " + nameOf(e)
						+ (a.has("settings") ? " (" + String.join(", ", a.raw().getAsJsonObject("settings").keySet()) + ")" : ""), null);
				changed = true;
			}
			case "remove" -> {
				var e = pick(exts, a);
				exts.remove(e);
				out.put("removed", nameOf(e));
				Journal.add(ctx, "edit", d.name(), d.path == null ? null : d.path.toString(), sim.getName() + ": removed the extension " + nameOf(e), null);
				changed = true;
			}
			default -> {
			}
		}
		List<Map<String, Object>> list = new ArrayList<>();
		for (int i = 0; i < exts.size(); i++) {
			list.add(io.github.openrocketmcp.or.Extensions.describe(exts.get(i), i));
		}
		out.put("simulation", sim.getName());
		out.put("extensions", list);
		if (changed) {
			d.doc.setSaved(false);
			if (a.bool("run", true)) {
				try {
					Sims.ensure(sim);
					out.put("flight", Sims.summarize(sim));
				} catch (RuntimeException e) {
					out.put("flightFailed", String.valueOf(e.getMessage()).replace("org.graalvm.polyglot.PolyglotException: ", ""));
				}
			}
		}
		return out;
	}

	private static info.openrocket.core.simulation.extension.SimulationExtension pick(
			List<info.openrocket.core.simulation.extension.SimulationExtension> exts, Args a) {
		if (exts.isEmpty()) {
			throw new ToolException("This simulation has no extensions.");
		}
		int i = a.has("index") ? a.integer("index", 0) : exts.size() == 1 ? 0 : -1;
		if (i < 0 || i >= exts.size()) {
			throw new ToolException("Say which extension (index 0 to " + (exts.size() - 1) + "); action list shows them.");
		}
		return exts.get(i);
	}

	private static String nameOf(info.openrocket.core.simulation.extension.SimulationExtension e) {
		return String.valueOf(io.github.openrocketmcp.or.Extensions.describe(e, 0).get("name"));
	}

	static boolean scriptsAllowed() {
		String v = System.getProperty("openrocketmcp.scripts", System.getenv("OPENROCKET_MCP_SCRIPTS"));
		return v != null && (v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true") || v.equals("1"));
	}

	private static void applySettings(Context ctx, info.openrocket.core.simulation.extension.SimulationExtension e, Args a) {
		if (!a.has("settings")) {
			return;
		}
		com.google.gson.JsonObject set = a.raw().getAsJsonObject("settings");
		Boolean enable = null;
		for (var entry : set.entrySet()) {
			if (e instanceof info.openrocket.core.simulation.extension.impl.ScriptingExtension && entry.getKey().equalsIgnoreCase("enabled")) {
				enable = entry.getValue().getAsBoolean();
				continue;
			}
			io.github.openrocketmcp.or.Extensions.set(e, entry.getKey(), entry.getValue());
		}
		if (e instanceof info.openrocket.core.simulation.extension.impl.ScriptingExtension sc) {
			if (Boolean.TRUE.equals(enable)) {
				// A script runs as code on this computer. In the desktop app the assistant may write one, but only the
				// person turns it on (app_trust_script, which assistants do not have).
				boolean person = ctx.journal() && "you".equals(Journal.actor());
				if (!person && ctx.journal()) {
					throw new ToolException("A script runs as code on this computer, so the person using RocketMaxxing turns it on "
							+ "(Flights page, the simulation's Extensions). Add it with enabled false and ask them to read it and turn it on.");
				}
				// Outside the app (an MCP or team server) nobody is there to read it: off unless whoever runs the server allows it.
				if (!person && !scriptsAllowed()) {
					throw new ToolException("A script runs as code on the computer running this server, so it cannot be turned on from "
							+ "here. The script is kept, off: turn it on in RocketMaxxing (Flights page, the simulation's Extensions) or in "
							+ "OpenRocket, or start this server with OPENROCKET_MCP_SCRIPTS=on to allow it.");
				}
				io.github.openrocketmcp.or.Extensions.trust(sc, true);
			} else if (Boolean.FALSE.equals(enable)) {
				sc.setEnabled(false);
			} else if (set.has("script") && sc.isEnabled()) {
				sc.setEnabled(false); // a changed script is a new script: on again only when someone says so
			}
		}
	}

	/** Prepares and runs the selected simulation with overrides from the arguments. */
	static Simulation runSelected(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Simulation sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), overrides(a), ctx.standards());
		Sims.ensure(sim);
		return sim;
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("simulation_extensions", "Simulation extensions",
				"Set up a simulation's extensions, as OpenRocket's Extensions tab: air-start (the flight starts at an altitude and "
						+ "speed, for an upper stage lit in the air), roll control (canted fins holding a roll rate), damping moment, "
						+ "stop the simulation at a time or step, save every step as CSV, a script run during the flight (JavaScript "
						+ "defining OpenRocket's listener functions such as postStep or preWindModel), or a Java listener class. "
						+ "action: list (default), types, add, set, remove. add and set take settings in units (\"500 ft\", \"20 deg/s\"). "
						+ "After a change the simulation flies again and the result comes back. A script runs as code on this "
						+ "computer: one from a file stays off until a person turns it on, and in the desktop app only the person "
						+ "can turn one on.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("simulation", "Simulation index or name (default the first).", false)
						.enumStr("action", "list, types, add, set or remove.", false, "list", "types", "add", "set", "remove")
						.str("type", "For add: the extension, e.g. AirStart, RollControl, DampingMoment, StopSimulation, CSVSave, "
								+ "ScriptingExtension (a script), JavaCode.", false)
						.integer("index", "For set or remove: which of the simulation's extensions (from list, 0 first).", false)
						.obj("settings", "For add or set: settings by key, e.g. {\"launchAltitude\": \"1000 ft\"} or "
								+ "{\"script\": \"function postStep(status) { ... }\", \"enabled\": true}.", false)
						.bool("run", "Fly the simulation after a change (default true).", false).build(),
				false, a -> extensions(ctx, a)));
		s.tool(new ToolDef("run_simulation", "Simulate a flight",
				"Run an OpenRocket 6-DOF simulation and return a compact summary: apogee, max velocity/Mach/acceleration, rail exit "
						+ "velocity, thrust-to-weight, stability at rail exit and min/max during ascent, each stage's events, "
						+ "ignitions (tilt and altitude for air starts), every recovery deployment (airspeed, altitude, density, mass, "
						+ "steady descent rate) and landing distance per stage. Overrides apply to this run only.",
				overrides(simSelect(Schema.object()))
						.str("plotPath", "Also draw the flight profile (SVG): altitude vs time for each stage with burnout, "
								+ "separation, apogee and deployments marked, e.g. \"plots/flight.svg\".", false).build(),
				false, a -> {
					Simulation sim = runSelected(ctx, a);
					Map<String, Object> out = Sims.summarize(sim);
					if (a.has("plotPath")) {
						Path p = ctx.path(a.str("plotPath"));
						if (p.getParent() != null) {
							Files.createDirectories(p.getParent());
						}
						Files.writeString(p, Reports.profileSvg(sim));
						out.put("plot", p.toString());
						Png.attachFile(p, "Flight profile");
					}
					return out;
				}));

		s.tool(new ToolDef("get_flight_data", "Get time series from a simulation",
				"Down-sampled time series for plotting or inspection (e.g. stability vs time for the design review plots). "
						+ "Variables are OpenRocket flight data names such as altitude, velocitytotal, verticalvelocity (velocityz), "
						+ "accelerationtotal, machnumber, stability, cglocation, cplocation, thrustforce, dragforce, mass, aoa, "
						+ "airdensity, orientationtheta. Runs the simulation first.",
				overrides(simSelect(Schema.object()))
						.array("variables", "Flight variables to include (time is always included).", Schema.type("string", null), true)
						.str("branch", "Stage branch name or index (default 0 = sustainer / main vehicle).", false)
						.integer("maxPoints", "Maximum rows (default 60, max 500).", false)
						.qty("tStart", "Start time.", false).qty("tEnd", "End time.", false).build(),
				false, a -> {
					Simulation sim = runSelected(ctx, a);
					return Sims.series(sim, a.str("branch", null), a.strList("variables"),
							Math.min(500, a.integer("maxPoints", 60)), a.qtyOrNaN("tStart", Dim.TIME), a.qtyOrNaN("tEnd", Dim.TIME));
				}));

		s.tool(new ToolDef("sweep", "Parameter sweep",
				"Run the simulation for several values of one parameter and tabulate the results. parameter is either a launch "
						+ "condition (windSpeed, launchAngle, railLength, launchSiteAltitude, temperature) or a component property "
						+ "(give component + property, e.g. fin \"height\" or a MassComponent \"componentMass\"). Component values "
						+ "are restored afterwards. Use it to explore trade-offs such as fin span vs stability vs apogee, or ballast "
						+ "vs margin.",
				simSelect(Schema.object())
						.str("parameter", "Launch condition name, or a component property name when component is given.", true)
						.str("component", "Component id/name for a property sweep.", false)
						.array("values", "Values to try (numbers in SI or strings with units).", Schema.quantityItem(), true).build(),
				true, a -> sweep(ctx, a)));

		s.tool(new ToolDef("check_requirements", "Check the design against competition rules",
				"Simulate and check against the active rule set (default Launch Canada 2027 = DTEG R4 + 2027 edicts) and team "
						+ "standards: launch angle, rail departure velocity, thrust-to-weight (per stage), ascent stability and "
						+ "over-stability (also in the design wind), L:D ratio, damping ratio, static margin as % of body length, "
						+ "rail button material, SRAD engine Isp, air-start tilt and altitude inhibit, early/late deployments, "
						+ "dual-event recovery, drogue and main descent rates, main deployment altitude, and the dress-rehearsal pop "
						+ "tests to plan. Returns PASS/FAIL/WARN/INFO with rule references plus a manual checklist (electronics, "
						+ "radio, structures, operations).",
				overrides(simSelect(Schema.object()))
						.bool("includeWindCase", "Also simulate at the rule set's maximum ground wind (default true).", false).build(),
				false, a -> {
					Simulation sim = runSelected(ctx, a);
					Simulation wind = null;
					double maxWind = ctx.standards().rule("maxGroundWind.value", Dim.VELOCITY);
					if (a.bool("includeWindCase", true) && !Double.isNaN(maxWind)) {
						wind = Variants.windCase(sim, ctx.designs.get(a.str("designId", null)).doc, maxWind);
					}
					return Requirements.check(sim, wind, ctx.standards()).render(ctx.standards().rulesName());
				}));

		s.tool(new ToolDef("targets", "The team's targets and the margin to each",
				"Simulate and compare the flight with the team's own targets: PASS / WARN (met within 5%) / FAIL, the margin "
						+ "to the nearer limit and the distance from the goal. Targets: apogee, maxVelocity, maxMach, "
						+ "maxAcceleration, railExitVelocity, thrustToWeight, minStability and maxStability (cal, ascent above "
						+ "100 ft/s), flutterMargin, liftoffMass, timeToApogee, drogueDescentRate, mainDescentRate, "
						+ "groundHitVelocity, landingDistance, flightTime; each takes min, max, goal (with units) and note. "
						+ "set changes them and checks in the same call, e.g. {\"apogee\": {\"min\": \"9500 ft\", \"goal\": "
						+ "\"10000 ft\"}}; null clears a limit or a whole target. The change is saved in the team standards "
						+ "file the app's Targets page shows. Rows are returned for the targets set; otherValues has the other "
						+ "numbers. Competition rules are check_requirements; these are the team's design goals.",
				simSelect(Schema.object())
						.obj("set", "Targets to change before checking, e.g. {\"railExitVelocity\": {\"min\": \"100 ft/s\"}}; "
								+ "null clears.", false)
						.bool("detail", "Every number as a row with SI values (what the app's page uses). Default false.", false)
						.build(),
				true, a -> {
					Map<String, Object> saved = null;
					if (a.has("set")) {
						saved = setTargets(ctx, a.obj("set").raw());
					}
					Map<String, Object> out = Targets.evaluate(runSelected(ctx, a), ctx.standards(), a.bool("detail", false));
					if (saved != null) {
						Map<String, Object> withSave = new LinkedHashMap<>();
						withSave.put("saved", saved.get("saved"));
						withSave.putAll(out);
						return withSave;
					}
					return out;
				}));
	}

	/** Checks a change to the targets, then writes it to the team standards file and uses it at once. */
	public static Map<String, Object> setTargets(Context ctx, JsonObject change) throws java.io.IOException {
		JsonObject merged = Targets.targetsOf(ctx.standards()).deepCopy();
		TeamFile.apply(merged, change);
		Targets.validate(change, merged);
		JsonObject wrapped = new JsonObject();
		wrapped.add("targets", change);
		return TeamFile.save(ctx, wrapped);
	}

	private static Object sweep(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		String asked = a.str("parameter");
		RocketComponent comp = a.has("component") ? Components.find(d.doc.getRocket(), a.str("component")) : null;
		JsonArray values = a.array("values");
		if (values.isEmpty()) {
			throw new ToolException("values must not be empty.");
		}
		String match = asked;
		if (comp != null) {
			Components.getRaw(comp, asked); // validates the property name up front
		} else {
			// A launch condition: one of the overrides, any case ("WindSpeed", "wind speed").
			match = null;
			Set<String> conditions = overrides(Schema.object()).build().getAsJsonObject("properties").keySet();
			for (String c : conditions) {
				if (c.equalsIgnoreCase(asked.replaceAll("[\\s_-]", ""))) {
					match = c;
				}
			}
			if (match == null) {
				throw new ToolException("'" + asked + "' is not a launch condition (" + String.join(", ", conditions)
						+ "). To sweep a part's property, also give component.");
			}
		}
		String param = match;
		Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
				ctx.standards(), false);
		String compId = comp == null ? null : comp.getID().toString();
		List<Simulation> variants = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (JsonElement v : values) {
			if (comp != null) {
				String[] label = new String[1];
				variants.add(Variants.of(base, d.doc, r -> label[0] = Components.set(Components.find(r, compId), param, v), null));
				labels.add(label[0]);
			} else {
				JsonObject one = a.raw().deepCopy();
				one.add(param, v);
				variants.add(Variants.of(base, d.doc, null, overrides(new Args(one))));
				labels.add(v.isJsonPrimitive() ? v.getAsString() : v.toString());
			}
		}
		CallContext.current().expect(variants.size(), "cases flown");
		List<Variants.Run> runs = Variants.runAll(variants);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < runs.size(); i++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put(param, labels.get(i));
			Variants.Run run = runs.get(i);
			if (!run.ok()) {
				row.put("error", run.error());
			} else {
				FlightData data = run.sim().getSimulatedData();
				row.putAll(Sims.flightMetrics(data));
				if (comp != null) {
					row.put("launchMass", Units.fmt(data.getBranch(0).getByIndex(FlightDataType.TYPE_MASS, 0), Dim.MASS));
				}
			}
			rows.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("parameter", comp == null ? param : comp.getName() + "." + param);
		out.put("results", rows);
		String hw = comp == null ? null : Components.overrideWarning(comp);
		if (hw != null) {
			out.put("warning", hw);
		}
		return out;
	}
}
