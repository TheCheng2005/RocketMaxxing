package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Aero;
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.FlightLog;
import io.github.openrocketmcp.or.HighSpeedAero;
import io.github.openrocketmcp.or.Presets;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.TextFiles;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.report.Drawing;
import io.github.openrocketmcp.report.Png;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Aerodynamics, wind profiles, the parts database and drawings: OpenRocket features beyond the flight simulation. */
public final class AeroTools {
	private AeroTools() {
	}

	static final double[] DEFAULT_MACHS = { 0.1, 0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 1.0, 1.05, 1.1, 1.2, 1.5, 2.0, 2.5, 3.0 };

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("aero_analysis", "Drag and CP vs Mach, drag breakdown, per stage",
				"Query OpenRocket's aerodynamic model directly (zero angle of attack): total drag coefficient split into "
						+ "friction / pressure / base drag, CP position, CNalpha and static margin (launch and burnout CG) at a "
						+ "range of Mach numbers (default up to just above the simulated max Mach), plus the drag of each "
						+ "component at one Mach number and OpenRocket's geometry warnings. Use it to see where drag comes from "
						+ "(nose shape, fin airfoil, surface finish, launch lugs / rail buttons) and how the margin shifts "
						+ "transonic and supersonic. On a staged rocket, stage gives what is left flying once the stages below it "
						+ "separate (e.g. the sustainer alone: its fins, its CG, its own top speed); \"all\" gives every stack. Each "
						+ "stack also gets its fastest point in the simulated flight and the margin there. A RASAero table "
						+ "imported for the stack (import_aero_table) is shown beside OpenRocket's CD and CP.",
				SimTools.simSelect(Schema.object())
						.str("stage", "Stage id or name: the stack from this stage up, as it flies after the stages below separate; "
								+ "\"all\" for every stack. Default: the whole vehicle on the pad.", false)
						.array("machs", "Mach numbers for the table.", Schema.type("number", null), false)
						.num("breakdownMach", "Mach number for the per-component drag breakdown (default 0.3; with stage, the "
								+ "stack's top speed in flight).", false)
						.num("maxMach", "Highest Mach for the default table (default: just above the simulated max Mach, else 1.2).", false)
						.build(),
				true, a -> aeroAnalysis(ctx, a)));

		s.tool(new ToolDef("import_aero_table", "Use RASAero (or other) CD / CP data",
				"Import an aerodynamic table (RASAero II 'export aero data' CSV, or any CSV with Mach, CD [, CP]) for a design. The "
						+ "drag replaces OpenRocket's in every simulation (power-on / power-off), the CP is used for a stability check "
						+ "against the simulated CG (Launch Canada asks for RASAero CP/CD when the airframe diameter changes). Shows the "
						+ "table next to OpenRocket's own CD / CP and the effect on apogee and minimum stability. On a staged rocket a "
						+ "table describes one stack: the whole vehicle by default, or with stage what flies once the stages below "
						+ "it separate (a RASAero run of the sustainer alone), used from that separation on. mode clear / show. "
						+ "save_design keeps the tables next to the .ork (rocket.aero.json) and open_design loads them again.",
				SimTools.simSelect(Schema.object())
						.enumStr("mode", "import (default) | show | clear", false, "import", "show", "clear")
						.str("stage", "Stage id or name: the table is for the stack from this stage up, flown after the stages "
								+ "below separate (default: the whole vehicle). For show / clear: that stack only (default: every table).", false)
						.str("path", "CSV file path.", false).str("csv", "CSV text (instead of path).", false)
						.str("cpUnit", "Unit of the CP column, measured from the nose tip (default in, as RASAero writes).", false)
						.bool("useDrag", "Apply the table's drag to simulations (default true).", false)
						.str("source", "Label for CSV text, e.g. \"RASAero rev B\".", false).build(),
				false, a -> importAeroTable(ctx, a)));

		s.tool(new ToolDef("high_speed_aero", "High-speed drag model",
				"RocketMaxxing's own transonic and supersonic drag model, for flights above about Mach 0.8 without a RASAero run: "
						+ "OpenRocket's drag with (1) the fins' real section (hexagonal, i.e. a flat plate with bevelled edges, double "
						+ "wedge or biconvex) and its supersonic thickness drag from linear theory, where OpenRocket only knows square, "
						+ "rounded and a drag-free 'airfoil'; and (2) the lower base drag while the motor burns, by the share of the "
						+ "base the nozzle exit covers. Friction, nose and body pressure drag and the centre of pressure stay "
						+ "OpenRocket's. action preview (default) shows CD against Mach beside OpenRocket's, the flight with each, and "
						+ "normal force and CP at angles of attack, changing nothing; use flies every simulation of the design on the "
						+ "model (kept with the design, recalculated as the rocket changes; it replaces an imported table); off goes "
						+ "back to OpenRocket's drag.",
				SimTools.simSelect(Schema.object())
						.enumStr("action", "preview (default), use or off.", false, "preview", "use", "off")
						.enumStr("finProfile", "The fins' section: design (default: as in the design; airfoil fins as biconvex, square and "
								+ "rounded as OpenRocket has them), hexagonal (bevelled edges), double_wedge or biconvex.", false,
								"design", "hexagonal", "double_wedge", "biconvex")
						.qty("bevelLength", "Hexagonal fins: the length of each bevel along the chord (default 3 fin thicknesses).", false)
						.qty("nozzleExitDiameter", "The motor's nozzle exit diameter (default 0.6 of the motor's diameter).", false)
						.array("reset", "Settings to put back to their defaults when the model is already on: bevelLength, "
								+ "nozzleExitDiameter.", Schema.type("string", null), false)
						.array("alphas", "Angles of attack in degrees for the normal force and CP table (default 0, 2, 4, 8).",
								Schema.type("number", null), false).build(),
				false, a -> highSpeed(ctx, a)));

		s.tool(new ToolDef("compare_flight", "Compare an altimeter log with the simulation",
				"Read an altimeter CSV (time, altitude; units from the header or altitudeUnit) and compare apogee, time to "
						+ "apogee and descent rates under drogue and main with the simulation, then fit the drag multiplier that "
						+ "reproduces the measured apogee (model calibration for the next flight). With an accelerometer column in "
						+ "the log (axial acceleration, own time column allowed), it also measures the burnout speed and the coast "
						+ "drag, fits thrust and drag separately (an apogee alone cannot tell a weak motor from extra drag) and "
						+ "reports how well each version follows the measured climb. Optionally writes overlay plots (altitude, "
						+ "and with an accelerometer the boost and the climb). Set the day's conditions with weather_forecast / "
						+ "wind_profile or the overrides.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.str("path", "Altimeter CSV path.", false).str("csv", "CSV text (instead of path).", false)
						.str("altitudeUnit", "ft or m when the header does not say (default: header, else ft).", false)
						.str("plotPath", "Write a simulated-vs-measured altitude SVG here.", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					FlightLog.Log log = FlightLog.parse(readText(ctx, a, "path", "csv"), a.str("altitudeUnit", null));
					Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), SimTools.overrides(a),
							ctx.standards(), false);
					return FlightLog.compare(base, d.doc, log, a.has("plotPath") ? ctx.path(a.str("plotPath")).toString() : null, d.name() + ": simulated vs measured");
				}));

		s.tool(new ToolDef("wind_profile", "Winds aloft (multi-level wind)",
				"Set, show or clear a wind profile on a simulation, using OpenRocket's multi-level wind model (speed, direction "
						+ "and turbulence per altitude, interpolated between levels). Give levels from a forecast or sounding, or "
						+ "mode power_law to build a shear profile v = v_ground (h / 10 m)^alpha from the ground wind (alpha ~ 1/7 "
						+ "open terrain, ~0.1 over water, higher over rough terrain / stable nights). Every tool then uses the profile; "
						+ "tools that override the wind speed (sweeps, the design-wind check, Monte Carlo) scale and rotate it from "
						+ "its lowest level. mode average returns to a single wind.",
				SimTools.simSelect(Schema.object())
						.enumStr("mode", "levels | power_law | average | show", true, "levels", "power_law", "average", "show")
						.array("levels", "For mode levels: {altitude, speed, direction, sd?} (direction the wind blows FROM).",
								Schema.object().qty("altitude", "Altitude.", true).qty("speed", "Wind speed.", true)
										.qty("direction", "Direction the wind blows from.", false).qty("sd", "Speed standard deviation.", false)
										.item(), false)
						.bool("aboveGroundLevel", "Level altitudes are AGL (default true); false = MSL.", false)
						.qty("groundSpeed", "power_law: wind speed at 10 m (default: the simulation's wind speed).", false)
						.qty("direction", "power_law: wind direction (from) (default: the simulation's).", false)
						.num("exponent", "power_law: shear exponent alpha (default 1/7).", false)
						.qty("top", "power_law: highest level (default 3000 m).", false)
						.num("turbulence", "power_law: turbulence as a fraction of each level's speed (default 0.1).", false)
						.bool("allSimulations", "Apply to every simulation of the design (default false).", false).build(),
				false, a -> windProfile(ctx, a)));

		s.tool(new ToolDef("search_parts", "Search the parts database",
				"Search OpenRocket's parts database (manufacturer catalogs) by type: body_tube, nose_cone, transition, "
						+ "tube_coupler, bulk_head, centering_ring, engine_block, launch_lug, rail_button, streamer, parachute. Filter by "
						+ "outer / inner diameter and manufacturer or text (e.g. \"LOC\", \"fiberglass\", \"4 in\"). Apply one with "
						+ "apply_preset.",
				Schema.object().str("type", "Part type, e.g. body_tube.", true)
						.qty("minOuterDiameter", "Minimum outer diameter.", false).qty("maxOuterDiameter", "Maximum outer diameter.", false)
						.qty("minInnerDiameter", "Minimum inner diameter.", false).qty("maxInnerDiameter", "Maximum inner diameter.", false)
						.str("manufacturer", "Manufacturer substring.", false).str("text", "Words that must appear in the part name, description or material.", false)
						.integer("limit", "Maximum results (default 25).", false).build(),
				true, a -> {
					ComponentPreset.Type t = Presets.type(a.str("type"));
					List<ComponentPreset> found = Presets.search(t, a.qtyOrNaN("minOuterDiameter", Dim.LENGTH),
							a.qtyOrNaN("maxOuterDiameter", Dim.LENGTH), a.qtyOrNaN("minInnerDiameter", Dim.LENGTH),
							a.qtyOrNaN("maxInnerDiameter", Dim.LENGTH), a.str("manufacturer", null), a.str("text", null));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("type", t.name());
					out.put("matches", found.size());
					List<Map<String, Object>> rows = new ArrayList<>();
					for (ComponentPreset p : found.subList(0, Math.min(found.size(), Math.max(1, a.integer("limit", 25))))) {
						rows.add(Presets.render(p));
					}
					out.put("parts", rows);
					return out;
				}));

		s.tool(new ToolDef("apply_preset", "Use a catalog part for a component",
				"Load a parts-database preset (\"<manufacturer> <part no>\" from search_parts) into a component: dimensions, "
						+ "material and mass as the catalog gives them. Returns the new properties and stability.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false).str("component", "Component id or name.", true)
						.str("preset", "\"<manufacturer> <part no>\", or a unique part number.", true).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					RocketComponent c = Components.find(d.doc.getRocket(), a.str("component"));
					ComponentPreset p = Presets.apply(c, a.str("preset"));
					d.doc.setSaved(false);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("component", c.getName() + " [" + Components.shortId(c) + "]");
					out.put("preset", Presets.render(p));
					out.put("properties", Components.describe(c));
					out.put("stability", Analysis.stageStacks(d.doc.getRocket().getSelectedConfiguration(), 0.3));
					return out;
				}));

		s.tool(new ToolDef("draw_rocket", "Side-profile drawing (SVG)",
				"Draw the active configuration from OpenRocket's geometry (body profiles, fins, pods) with CG at launch and "
						+ "burnout and CP marked, as an SVG file. Good for design reviews and for checking a design built from "
						+ "scratch looks right.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false).str("configuration", DesignTools.CONFIG, false)
						.str("path", "Output .svg path (default: rocket.svg in the working directory).", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					FlightConfiguration fc = Components.config(d.doc.getRocket(), a.str("configuration", null));
					Path p = ctx.path(a.str("path", "rocket.svg")).toAbsolutePath();
					if (p.getParent() != null) {
						Files.createDirectories(p.getParent());
					}
					Files.writeString(p, Drawing.svg(fc, Drawing.title(d.name(), fc)));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("file", p.toString());
					out.put("shown", Png.attachFile(p, "Cut-away drawing of the rocket")
							? "the drawing is attached as an image" : "open the file to see the drawing");
					out.put("stability", Analysis.render(Analysis.stability(fc, 0.3)));
					return out;
				}));
	}

	/** One stack (or every stack) through Mach, OpenRocket's model beside an imported RASAero table, and its flight. */
	private static Object aeroAnalysis(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Rocket rocket = d.doc.getRocket();
		FlightConfiguration fc = Components.config(rocket, a.str("configuration", null));
		List<AxialStage> stages = new ArrayList<>(rocket.getStageList());
		String which = a.str("stage", null);
		List<Integer> bottoms = new ArrayList<>();
		if (which == null) {
			bottoms.add(stages.size() - 1);
		} else if (which.equalsIgnoreCase("all")) {
			for (int i = stages.size() - 1; i >= 0; i--) {
				if (fc.isStageActive(i)) {
					bottoms.add(i);
				}
			}
		} else {
			AxialStage st = Components.find(rocket, which, AxialStage.class, "stage");
			int k = stages.indexOf(st);
			if (!fc.isStageActive(k)) {
				throw new ToolException(st.getName() + " is not active in configuration " + fc.getName() + ".");
			}
			bottoms.add(k);
		}
		// The flight, for each stack's top speed (and the default Mach range). Without motors there is none.
		Simulation sim = null;
		if (fc.hasMotors() || a.has("simulation")) {
			try {
				sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), ctx.standards(), false);
				Sims.ensure(sim);
			} catch (ToolException e) {
				if (a.has("simulation")) {
					throw e; // a simulation asked for by name that cannot be used is an error, not a quiet omission
				}
				sim = null;
			}
		}
		List<Map<String, Object>> stacks = new ArrayList<>();
		for (int k : bottoms) {
			AxialStage bottom = stages.get(k);
			FlightConfiguration stack = Analysis.stack(fc, k);
			double flightMax = sim == null ? Double.NaN : Aero.maxMach(sim, bottom);
			double[] machs = machs(a, flightMax);
			AeroTable.Table t = AeroTable.of(rocket, bottom);
			Map<String, Object> out = new LinkedHashMap<>();
			if (stages.size() > 1) {
				out.put("stack", Analysis.stackName(fc, k));
				out.put("when", Analysis.stackWhen(fc, k));
			}
			List<Map<String, Object>> table = new ArrayList<>();
			double ref = Analysis.maxDiameter(stack);
			for (Aero.Point p : Aero.sweep(stack, machs)) {
				Map<String, Object> row = Aero.render(p);
				if (t != null) {
					// RASAero beside OpenRocket: its drag and CP, and the margin with its CP and the same CG.
					row.put("cdImportedPowerOff", Units.num(t.cd(p.mach(), false)));
					row.put("cdImportedPowerOn", Units.num(t.cd(p.mach(), true)));
					if (t.hasCp()) {
						double cgBurnout = p.cpX() - p.marginBurnout() * ref, cgLaunch = p.cpX() - p.marginLaunch() * ref;
						row.put("cpImported", Units.fmt(t.cpAt(p.mach()), Dim.LENGTH));
						row.put("marginLaunchImportedCp", Units.num((t.cpAt(p.mach()) - cgLaunch) / ref) + " cal");
						row.put("marginBurnoutImportedCp", Units.num((t.cpAt(p.mach()) - cgBurnout) / ref) + " cal");
					}
				}
				table.add(row);
			}
			out.put("vsMach", table);
			if (t != null) {
				out.put("importedTable", AeroTable.describe(t));
			} else if (k < stages.size() - 1) {
				out.put("importedTable", "none for this stack: import a RASAero run of " + Analysis.stackName(fc, k)
						+ " alone with import_aero_table stage \"" + bottom.getName() + "\"");
			}
			if (sim != null) {
				Map<String, Object> f = Aero.inFlight(sim, stack, bottom, t);
				out.put("inFlight", f != null ? f : "this stack does not fly on its own in " + sim.getName());
			}
			double bm = a.has("breakdownMach") ? a.num("breakdownMach", 0.3)
					: which != null && !Double.isNaN(flightMax) ? Math.round(flightMax * 100) / 100.0 : 0.3;
			out.put("dragBreakdownAtMach", Units.num(bm));
			out.put("dragBreakdown", Aero.breakdown(stack, bm));
			List<String> w = Aero.warnings(stack, bm);
			if (!w.isEmpty()) {
				out.put("openRocketWarnings", w);
			}
			stacks.add(out);
		}
		Map<String, Object> out = stacks.size() == 1 ? stacks.get(0) : new LinkedHashMap<>(Map.of("stacks", stacks));
		List<String> notes = new ArrayList<>(List.of(
				"Zero angle of attack; Reynolds number from sea-level ISA at each Mach. Drag in flight also varies with "
						+ "altitude (Reynolds) and angle of attack; the simulation uses the full model.",
				"Margins use the stack's maximum body diameter as reference. OpenRocket's Barrowman model is least accurate "
						+ "transonic and supersonic; LC expects RASAero CP/CD for airframes with diameter changes."));
		if (stages.size() > 1 && which == null) {
			notes.add("This is the whole vehicle on the pad. After separation the upper stages fly on their own fins and CG, often "
					+ "faster: pass stage (e.g. \"" + stages.get(0).getName() + "\") or \"all\".");
		}
		out.put("notes", notes);
		return out;
	}

	/** The Mach numbers asked for, else the defaults up to just past the stack's top speed (or maxMach, else 1.2). */
	static double[] machs(Args a, double flightMax) {
		double[] machs;
		if (a.has("machs")) {
			machs = a.array("machs").asList().stream().mapToDouble(e -> e.getAsDouble()).toArray();
		} else {
			// At least to Mach 0.5, so a slow rocket still gets a curve.
			double top = a.has("maxMach") ? a.num("maxMach", 1.2) : Double.isNaN(flightMax) ? 1.2 : Math.max(0.5, flightMax);
			List<Double> list = new ArrayList<>();
			for (double m : DEFAULT_MACHS) {
				list.add(m);
				if (m >= top - 1e-9) {
					break; // the first point at or past the top speed covers it
				}
			}
			machs = list.stream().mapToDouble(Double::doubleValue).toArray();
		}
		if (machs.length == 0 || machs.length > 40) {
			throw new ToolException("Give 1-40 Mach numbers.");
		}
		for (double m : machs) {
			if (!(m > 0 && m <= 5)) {
				throw new ToolException("Mach numbers must be between 0 and 5 (got " + Units.num(m) + ").");
			}
		}
		return machs;
	}

	static String readText(Context ctx, Args a, String pathKey, String textKey) throws IOException {
		if (a.has(pathKey)) {
			return TextFiles.read(ctx.path(a.str(pathKey)));
		}
		if (a.has(textKey)) {
			return a.str(textKey);
		}
		throw new ToolException("Give " + pathKey + " (a file) or " + textKey + " (the CSV text).");
	}

	private static Object highSpeed(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Rocket rocket = d.doc.getRocket();
		String action = a.str("action", "preview");
		if (action.equals("off")) {
			boolean was = HighSpeedAero.settings(rocket) != null;
			if (was) {
				AeroTable.clear(rocket);
				d.doc.setSaved(false);
				Journal.add(ctx, "edit", d.name(), d.path == null ? null : d.path.toString(), "Turned the high-speed drag model off", null);
			}
			return Map.of("highSpeedModel", "off", "note", was ? "Simulations use OpenRocket's own drag again."
					: "The model was not on for this design.");
		}
		HighSpeedAero.Settings was = HighSpeedAero.settings(rocket);
		HighSpeedAero.Settings s;
		List<String> reset = a.has("reset") ? a.strList("reset") : List.of();
		try {
			s = new HighSpeedAero.Settings(
					a.has("nozzleExitDiameter") ? a.positive("nozzleExitDiameter", Dim.LENGTH)
							: was != null && !reset.contains("nozzleExitDiameter") ? was.nozzleExit() : Double.NaN,
					a.has("finProfile") ? a.str("finProfile") : was != null ? was.finProfile() : "design",
					a.has("bevelLength") ? a.positive("bevelLength", Dim.LENGTH)
							: was != null && !reset.contains("bevelLength") ? was.bevel() : Double.NaN);
		} catch (IllegalArgumentException e) {
			throw new ToolException(e.getMessage());
		}
		FlightConfiguration fc = Components.config(rocket, a.str("configuration", null));
		Simulation base = null;
		String noFlight = null;
		try {
			base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), ctx.standards(), false);
			fc = base.getActiveConfiguration();
		} catch (ToolException e) {
			if (a.has("simulation")) {
				throw e;
			}
			noFlight = e.getMessage();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		HighSpeedAero.Basis[] basis = new HighSpeedAero.Basis[1];
		double[] ms = { 0.3, 0.6, 0.8, 0.9, 1.0, 1.1, 1.2, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0 };
		List<HighSpeedAero.Row> rows = HighSpeedAero.rows(fc, s, ms, basis);
		Map<String, Object> model = new LinkedHashMap<>();
		model.put("fins", basis[0].fins().isEmpty() ? "no fins" : basis[0].fins());
		model.put("base", basis[0].base() == null ? "no base behind the motor"
				: "behind " + basis[0].base() + ": the nozzle exit" + (Double.isNaN(s.nozzleExit())
						? " (taken as 0.6 of the motor's diameter; give nozzleExitDiameter for yours)"
						: " (" + Units.fmt(s.nozzleExit(), Dim.LENGTH) + ")")
						+ " covers " + Units.num(100 * basis[0].exitShare()) + "% of it, so base drag falls by that much while the motor burns");
		out.put("model", model);
		List<Map<String, Object>> table = new ArrayList<>();
		for (HighSpeedAero.Row r : rows) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("mach", Units.num(r.mach()));
			row.put("cdOpenRocket", Units.num(r.openRocket()));
			row.put("cdModelCoasting", Units.num(r.off()));
			row.put("cdModelBurning", Units.num(r.on()));
			row.put("finsOpenRocket", Units.num(r.finOpenRocket()));
			row.put("finsModel", Units.num(r.finModel()));
			row.put("baseCoasting", Units.num(r.baseOff()));
			row.put("baseBurning", Units.num(r.baseOn()));
			table.add(row);
		}
		out.put("cdByMach", table);

		// The flight on OpenRocket's drag and on the model (switched on for the comparison, then put back for a preview).
		if (base == null) {
			out.put("flight", "not simulated: " + noFlight);
		} else {
			// The model's flight is flown on these settings alone: nothing else (the quick flight running meanwhile) sees them.
			List<Variants.Run> runs = Variants.runAll(List.of(AeroTable.without(Variants.of(base, d.doc, null, null)),
					HighSpeedAero.preview(Variants.of(base, d.doc, null, null), s)));
			if (runs.get(0).ok() && runs.get(1).ok()) {
				var or = runs.get(0).sim().getSimulatedData();
				var hs = runs.get(1).sim().getSimulatedData();
				Map<String, Object> f = new LinkedHashMap<>();
				f.put("simulation", base.getName());
				f.put("apogeeOpenRocket", Units.fmt(or.getMaxAltitude(), Dim.DISTANCE));
				f.put("apogeeModel", Units.fmt(hs.getMaxAltitude(), Dim.DISTANCE));
				f.put("apogeeChange", Units.num(100 * (hs.getMaxAltitude() / or.getMaxAltitude() - 1)) + "%");
				f.put("maxMachOpenRocket", Units.num(or.getMaxMachNumber()));
				f.put("maxMachModel", Units.num(hs.getMaxMachNumber()));
				f.put("maxSpeedModel", Units.fmt(hs.getMaxVelocity(), Dim.VELOCITY));
				out.put("flight", f);
				if (hs.getMaxMachNumber() > 5) {
					out.put("beyondTheModel", "This flight passes Mach 5, where linear theory no longer holds: treat the drag as a rough guide.");
				}
			} else {
				out.put("flight", "not simulated: the flight did not complete");
			}
		}

		// Normal force and CP against angle of attack (OpenRocket's model): how stability moves in a gust.
		double[] alphas = a.has("alphas") ? a.strList("alphas").stream().mapToDouble(Double::parseDouble).toArray() : new double[] { 0, 2, 4, 8 };
		for (double al : alphas) {
			if (!(al >= 0 && al <= 20)) {
				throw new ToolException("Angles of attack must be between 0 and 20 degrees (got " + Units.num(al) + ").");
			}
		}
		double dia = Analysis.maxDiameter(fc);
		List<Map<String, Object>> aoa = new ArrayList<>();
		for (HighSpeedAero.Alpha p : HighSpeedAero.alphas(fc, new double[] { 0.3, 0.9, 1.5, 2.5 }, alphas)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("mach", Units.num(p.mach()));
			row.put("alpha", Units.num(p.alphaDeg()) + " deg");
			row.put("cn", Units.num(p.cn()));
			row.put("cdAxial", Units.num(p.cdAxial()));
			row.put("cp", Double.isNaN(p.cpX()) ? "-" : Units.fmt(p.cpX(), Dim.LENGTH) + " (" + Units.num(p.cpX() / dia) + " cal from the nose)");
			aoa.add(row);
		}
		out.put("angleOfAttack", aoa);

		if (action.equals("use")) {
			AeroTable.clear(rocket);
			HighSpeedAero.enable(rocket, s);
			AeroTable.shown(rocket);
			d.doc.setSaved(false);
			Journal.add(ctx, "edit", d.name(), d.path == null ? null : d.path.toString(), (was == null ? "Turned the high-speed drag model on" : "Changed the high-speed drag model")
					+ " (fins " + s.finProfile().replace('_', ' ') + (Double.isNaN(s.bevel()) ? "" : ", bevels " + Units.fmt(s.bevel(), Dim.LENGTH))
					+ (Double.isNaN(s.nozzleExit()) ? "" : ", nozzle exit " + Units.fmt(s.nozzleExit(), Dim.LENGTH)) + ")", null);
			out.put("highSpeedModel", "on: every simulation of this design flies on it, recalculated as the rocket changes. "
					+ "save_design keeps the choice with the design; action off goes back to OpenRocket's drag.");
		} else {
			out.put("highSpeedModel", was != null ? "on (this was a preview of other settings)" : "off: this was a preview; action use flies on it");
		}
		List<String> notes = new ArrayList<>();
		notes.add("The model changes two terms of OpenRocket's drag: the fins' thickness drag for their real section (linear theory, "
				+ "K (t/c)^2 / sqrt(M^2-1), faired through Mach 1) and the base drag while the motor burns. Friction, nose and body "
				+ "pressure drag, coasting base drag and the centre of pressure are OpenRocket's.");
		notes.add("Limits: sharp-edged sections are given no pressure drag below Mach 0.85; the fins' finite span does not reduce "
				+ "the thickness drag (a little high for short fins); valid to about Mach 5; it has not been checked against flight data above Mach 1. "
				+ "Where a competition asks for RASAero's CP and CD, import RASAero's table (import_aero_table): it then replaces the model.");
		out.put("notes", notes);
		return out;
	}

	private static Object importAeroTable(Context ctx, Args a) throws IOException {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		var rocket = d.doc.getRocket();
		String mode = a.str("mode", "import");
		List<AxialStage> stages = new ArrayList<>(rocket.getStageList());
		AxialStage bottom = a.has("stage") ? Components.find(rocket, a.str("stage"), AxialStage.class, "stage") : null;
		FlightConfiguration whole = Components.config(rocket, a.str("configuration", null));
		if (bottom != null && !mode.equals("clear") && !whole.isStageActive(stages.indexOf(bottom))) {
			throw new ToolException(bottom.getName() + " is not active in configuration " + whole.getName()
					+ "; pick the configuration that flies it.");
		}
		// The flight to compare with: a simulation named that cannot be used is an error before anything changes.
		Simulation base = null;
		String noFlight = null;
		if (!mode.equals("clear")) {
			try {
				base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), ctx.standards(), false);
			} catch (ToolException e) {
				if (a.has("simulation")) {
					throw e;
				}
				noFlight = e.getMessage();
			}
		}
		if (mode.equals("clear")) {
			if (bottom != null) {
				AeroTable.clear(rocket, bottom);
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("cleared", Analysis.stackName(whole, stages.indexOf(bottom)));
				out.put("note", "That stack flies on OpenRocket's own aerodynamics again.");
				return out;
			}
			AeroTable.clear(rocket);
			return Map.of("cleared", true, "note", "Simulations use OpenRocket's own drag again.");
		}
		if (mode.equals("show") && bottom == null) {
			Map<AxialStage, AeroTable.Table> all = AeroTable.all(rocket);
			if (all.isEmpty()) {
				return Map.of("table", "none imported");
			}
			if (all.size() > 1 || AeroTable.of(rocket) == null) {
				List<Map<String, Object>> list = new ArrayList<>();
				all.forEach((st, tb) -> {
					int k = stages.indexOf(st);
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("stack", Analysis.stackName(whole, k));
					row.put("when", Analysis.stackWhen(whole, k));
					row.put("table", AeroTable.describe(tb));
					list.add(row);
				});
				return Map.of("tables", list);
			}
		}
		int k = bottom == null ? stages.size() - 1 : stages.indexOf(bottom);
		AxialStage stackBottom = stages.get(k);
		AeroTable.Table t;
		if (mode.equals("show")) {
			t = AeroTable.of(rocket, stackBottom);
			if (t == null) {
				return Map.of("table", "none imported for " + Analysis.stackName(whole, k));
			}
		} else {
			String text = readText(ctx, a, "path", "csv");
			String unit = a.str("cpUnit", "in").isBlank() ? "in" : a.str("cpUnit", "in");
			double cpUnit = Units.toSi("1 " + unit, Dim.LENGTH);
			String source = a.str("source", "").isBlank() ? "imported table" : a.str("source", "");
			t = AeroTable.parse(text, a.has("path") ? ctx.path(a.str("path")).getFileName().toString() : source,
					cpUnit, a.bool("useDrag", true));
			AeroTable.set(rocket, stackBottom, t);
		}
		FlightConfiguration fc = Analysis.stack(whole, k);
		Map<String, Object> out = new LinkedHashMap<>();
		if (stages.size() > 1) {
			out.put("stack", Analysis.stackName(whole, k));
			out.put("when", Analysis.stackWhen(whole, k));
		}
		out.put("table", AeroTable.describe(t));
		// Side by side with OpenRocket's own model.
		List<Map<String, Object>> cmp = new ArrayList<>();
		double[] ms = { 0.3, 0.6, 0.8, 0.95, 1.1, 1.5, 2.0 };
		double top = t.mach()[t.mach().length - 1];
		for (double m : ms) {
			if (m > top + 1e-9) {
				continue;
			}
			Aero.Point p = Aero.sweep(fc, new double[] { m }).get(0);
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("mach", Units.num(m));
			r.put("cdImportedPowerOff", Units.num(t.cd(m, false)));
			r.put("cdImportedPowerOn", Units.num(t.cd(m, true)));
			r.put("cdOpenRocket", Units.num(p.cd()));
			if (t.hasCp()) {
				r.put("cpImported", Units.fmt(t.cpAt(m), Dim.LENGTH));
			}
			r.put("cpOpenRocket", Units.fmt(p.cpX(), Dim.LENGTH));
			cmp.add(r);
		}
		out.put("vsOpenRocket", cmp);
		// Effect on the flight, when there is one to fly: the table is in place either way.
		if (base == null) {
			out.put("effect", "not simulated: " + noFlight);
		}
		List<Variants.Run> runs = base == null ? List.of() : Variants.runAll(List.of(
				AeroTable.without(Variants.of(base, d.doc, null, null)),
				Variants.of(base, d.doc, null, null)));
		if (base != null && !(runs.get(0).ok() && runs.get(1).ok())) {
			out.put("effect", "not simulated: the flight did not complete");
		}
		if (base != null && runs.get(0).ok() && runs.get(1).ok()) {
			Map<String, Object> eff = new LinkedHashMap<>();
			eff.put("apogeeOpenRocketDrag", Units.fmt(runs.get(0).sim().getSimulatedData().getMaxAltitude(), Dim.DISTANCE));
			eff.put("apogeeImportedDrag", t.useDrag() ? Units.fmt(runs.get(1).sim().getSimulatedData().getMaxAltitude(), Dim.DISTANCE)
					: "not applied (useDrag false)");
			Map<String, Object> f = Aero.inFlight(runs.get(1).sim(), fc, stackBottom, t);
			if (f != null) {
				if (f.containsKey("minMarginImportedCp")) {
					eff.put("minAscentStabilityImportedCp", f.get("minMarginImportedCp"));
				}
				if (f.containsKey("minMargin")) {
					eff.put("minAscentStabilityOpenRocketCp", f.get("minMargin"));
				}
				eff.put("atMaxMach", f.get("atMaxMach"));
			} else if (k < stages.size() - 1) {
				eff.put("note", Analysis.stackName(whole, k) + " does not fly on its own in " + base.getName() + ".");
			}
			out.put("effect", eff);
		}
		List<String> notes = new ArrayList<>();
		notes.add(k == stages.size() - 1
				? "Drag replaces OpenRocket's axial CD in every simulation of this design (power-on while a motor burns) while the "
						+ "whole vehicle flies; after a separation each stack uses its own table if it has one (import_aero_table "
						+ "with stage), else OpenRocket's model."
				: "Used from the separation of " + stages.get(k + 1).getName() + " until " + (k == 0 ? "apogee"
						: stages.get(k).getName() + " separates") + ", in every simulation of this design (power-on while a motor "
						+ "burns). The RASAero run must be of this stack alone: " + Analysis.stackName(whole, k) + ".");
		notes.add("save_design keeps the tables next to the .ork. check_requirements adds a stability item with each imported CP "
				+ "(DTEG R10.3.1 for airframes with diameter changes).");
		out.put("notes", notes);
		return out;
	}

	private static Object windProfile(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Simulation sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
				ctx.standards());
		List<Simulation> targets = a.bool("allSimulations", false) ? d.doc.getSimulations() : List.of(sim);
		String mode = a.str("mode");
		List<Winds.Level> levels = null;
		switch (mode) {
			case "levels" -> {
				levels = new ArrayList<>();
				for (Args l : a.objList("levels")) {
					double h = l.qty("altitude", Dim.DISTANCE);
					if (levels.stream().anyMatch(x -> Math.abs(x.altitude() - h) < 1e-6)) {
						throw new ToolException("Two wind levels at " + Units.fmt(h, Dim.DISTANCE) + ": give each altitude once.");
					}
					levels.add(new Winds.Level(h, l.qty("speed", Dim.VELOCITY), l.qty("direction", Dim.ANGLE, 0),
							l.qtyOrNaN("sd", Dim.VELOCITY)));
				}
			}
			case "power_law" -> {
				var o = sim.getOptions();
				double g = a.qty("groundSpeed", Dim.VELOCITY, Winds.speed(o));
				levels = Winds.powerLaw(g, a.qty("direction", Dim.ANGLE, Winds.direction(o)), a.num("exponent", 1.0 / 7),
						a.qty("top", Dim.DISTANCE, 3000), a.num("turbulence", 0.1));
			}
			case "average" -> targets.forEach(t -> Winds.useAverage(t.getOptions()));
			case "show" -> {
				// no change
			}
			default -> throw new ToolException("mode must be levels, power_law, average or show.");
		}
		if (levels != null) {
			boolean agl = a.bool("aboveGroundLevel", true);
			for (Simulation t : targets) {
				Winds.setProfile(t.getOptions(), levels, agl);
			}
		}
		if (!"show".equals(mode)) {
			d.doc.setSaved(false);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("simulation", targets.size() == 1 ? targets.get(0).getName() : targets.size() + " simulations");
		out.put("wind", Winds.describe(sim.getOptions()));
		return out;
	}
}
