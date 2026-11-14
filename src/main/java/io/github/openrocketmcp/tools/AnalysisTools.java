package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.InternalComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.CallContext;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.MonteCarlo;
import io.github.openrocketmcp.or.Optimizer;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Goal-seeking optimization and Monte Carlo dispersion. */
public final class AnalysisTools {
	private AnalysisTools() {
	}

	public static void register(McpServer s, Context ctx) {
		JsonObject variable = Schema.object()
				.str("component", "Component id or name.", true)
				.str("property", "Property to vary (see describe_component), e.g. \"height\" (fin span), \"rootChord\", "
						+ "\"componentMass\" (ballast MassComponent), \"length\", \"diameter\" (parachute).", true)
				.qty("min", "Lower bound.", true)
				.qty("max", "Upper bound.", true)
				.build();
		s.tool(new ToolDef("optimize", "Goal-seek design parameters",
				"Find values for 1-3 component properties that reach a goal while meeting constraints, by simulating batches "
						+ "of candidates in parallel and zooming in on the best. Objectives: target_apogee (value = apogee), "
						+ "max_apogee, target_stability (value = launch margin in cal; static, fast), min_mass. Constraints use the "
						+ "simulated ascent stability (rail exit to apogee, > 100 ft/s), rail exit velocity, Mach, apogee and fin "
						+ "flutter margin along the flight; minRailExit defaults to the rule set. Examples: fin span for 1.8 cal with max apogee; ballast for a "
						+ "10,000 ft target. The design is unchanged unless apply=true.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("simulation", "Simulation index or name.", false)
						.str("configuration", DesignTools.CONFIG, false)
						.array("variables", "1-3 variables {component, property, min, max}.", variable, true)
						.enumStr("objective", "What to optimize.", true, "target_apogee", "max_apogee", "target_stability", "min_mass")
						.qty("targetApogee", "Target apogee for target_apogee.", false)
						.num("targetStability", "Target launch margin (cal) for target_stability.", false)
						.bool("meetRules", "Derive stability limits from the rule set: floor = max(minimum calibers, % of body "
								+ "length x L:D) (LC 2027: 10% BL), ceiling = over-stability; rail exit from the rules; fin flutter "
								+ "margin from the team standards (structures.flutterMinMargin).", false)
						.num("minStability", "Minimum simulated ascent stability, cal (e.g. 1.5).", false)
						.num("maxStability", "Maximum simulated ascent stability, cal.", false)
						.qty("minRailExit", "Minimum rail exit velocity (default: rule set).", false)
						.num("maxMach", "Maximum Mach number.", false)
						.qty("minApogee", "Minimum apogee.", false)
						.num("minFlutterMargin", "Minimum fin flutter margin (flutter speed / airspeed, e.g. 1.5), so the fins "
								+ "found do not flutter at the speeds they fly.", false)
						.integer("maxEvaluations", "Simulation budget (default 40, max 200).", false)
						.bool("checkDesignWind", "Also evaluate each candidate at the rule set's maximum ground wind and apply "
								+ "constraints to the worse case (default true when a stability constraint is given).", false)
						.bool("apply", "Apply the best values to the design (default false).", false).build(),
				false, a -> optimize(ctx, a)));

		s.tool(new ToolDef("monte_carlo", "Dispersion analysis (landing ellipse, worst cases)",
				"Run many simulations with randomized wind speed and direction, launch angle and direction, and turbulence, "
						+ "optionally also structure mass, airframe drag, motor thrust and parachute Cd (massSd, dragSd, thrustSd, "
						+ "chuteCdSd), and report which inputs drive the spread (correlations). "
						+ "Returns apogee spread, landing ellipse and distances per stage, worst ascent stability and rail exit "
						+ "(with how many runs break the rules), and the worst deployment airspeed and opening load per recovery "
						+ "device. Runs in parallel; 100 runs typically take a few seconds.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("simulation", "Simulation index or name.", false)
						.str("configuration", DesignTools.CONFIG, false)
						.integer("runs", "Number of runs (default 100, max 1000).", false)
						.qty("windSpeed", "Mean wind speed (default: the simulation's).", false)
						.qty("windSpeedSd", "Wind speed standard deviation (default 2 m/s).", false)
						.qty("windDirection", "Mean wind direction; omit for uniformly random direction.", false)
						.qty("windDirectionSd", "Wind direction standard deviation (default 30 deg).", false)
						.qty("launchAngle", "Mean launch angle from vertical (default: the simulation's).", false)
						.qty("launchAngleSd", "Launch angle standard deviation (default 1 deg).", false)
						.qty("launchDirectionSd", "Rail direction standard deviation (default 5 deg).", false)
						.num("turbulence", "Wind turbulence intensity 0-1.", false)
						.integer("seed", "Random seed for repeatable results (default 1).", false)
						.num("massSd", "Structure mass uncertainty, fractional 1 sd (e.g. 0.05 = 5%; motors excluded).", false)
						.num("dragSd", "Airframe drag coefficient uncertainty, fractional 1 sd (e.g. 0.1).", false)
						.num("thrustSd", "Motor thrust / total impulse uncertainty, fractional 1 sd (e.g. 0.03).", false)
						.num("chuteCdSd", "Parachute Cd uncertainty, fractional 1 sd, per chute (e.g. 0.1).", false)
						.str("plotPath", "Also draw the landing map (SVG): every landing and the 2-sigma ellipse per stage, e.g. "
								+ "\"plots/landing.svg\".", false)
						.str("kmlPath", "Also write the landing zones for Google Earth (KML): the pad, every landing and the 2-sigma "
								+ "ellipse per stage on the map, e.g. \"landing.kml\".", false)
						.num("siteLatitude", "Pad latitude for the KML (default: the launch site in the team standards).", false)
						.num("siteLongitude", "Pad longitude for the KML.", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null),
							Sims.Overrides.none(), ctx.standards(), false);
					int runs = Math.max(2, Math.min(1000, a.integer("runs", 100)));
					MonteCarlo.Settings st = new MonteCarlo.Settings(runs, a.qtyOrNaN("windSpeed", Dim.VELOCITY),
							a.qty("windSpeedSd", Dim.VELOCITY, 2.0), a.qtyOrNaN("windDirection", Dim.ANGLE),
							a.qty("windDirectionSd", Dim.ANGLE, Math.toRadians(30)), a.qtyOrNaN("launchAngle", Dim.ANGLE),
							a.qty("launchAngleSd", Dim.ANGLE, Math.toRadians(1)), a.qty("launchDirectionSd", Dim.ANGLE, Math.toRadians(5)),
							a.num("turbulence", Double.NaN), a.integer("seed", 1), frac("massSd", a.num("massSd", 0)), frac("dragSd", a.num("dragSd", 0)),
							frac("thrustSd", a.num("thrustSd", 0)), frac("chuteCdSd", a.num("chuteCdSd", 0)));
					long t0 = System.nanoTime();
					double[] site = a.has("siteLatitude") && a.has("siteLongitude")
							? new double[] { a.num("siteLatitude"), a.num("siteLongitude") } : null;
					Map<String, Object> out = MonteCarlo.run(base, d.doc, st, ctx.standards(),
							a.has("plotPath") ? ctx.path(a.str("plotPath")) : null, a.has("kmlPath") ? ctx.path(a.str("kmlPath")) : null, site);
					out.put("elapsed", Units.num((System.nanoTime() - t0) / 1e9) + " s");
					return out;
				}));
	}

	/**
	 * Optimizer constraints from the arguments; with meetRules (or {@code meetRulesDefault} when not given) the stability
	 * floor / ceiling come from the rule set, the rail exit from the rules and the flutter margin from the team
	 * standards. {@code basis[0]} receives a description of the rule-derived limits.
	 */
	static Optimizer.Constraints constraints(Context ctx, Designs.Design d, Args a, Optimizer.Objective obj,
			boolean meetRulesDefault, String[] basis) {
		boolean meetRules = a.bool("meetRules", meetRulesDefault);
		double railRule = ctx.standards().rule("railDepartureVelocity.min", Dim.VELOCITY);
		double minStab = a.num("minStability", Double.NaN), maxStab = a.num("maxStability", Double.NaN);
		if (meetRules) {
			// Stability floor from the rule set: max(minimum calibers, % of body length x L:D); ceiling: over-stability.
			var std = ctx.standards();
			double[] fl = Requirements.stabilityFloor(std, d.doc.getRocket().getSelectedConfiguration());
			double floor = fl[0];
			if (Double.isNaN(minStab) && floor > 0) {
				minStab = floor;
			}
			double over = std.rule("stability.overStable", Dim.DIMENSIONLESS);
			if (Double.isNaN(maxStab) && !Double.isNaN(over)) {
				maxStab = over;
			}
			basis[0] = "minStability " + Requirements.floorText(fl) + "; maxStability "
					+ Units.num(maxStab) + " cal (over-stable)";
		}
		// Fin flutter: a constraint when asked for or with meetRules (the team's structures.flutterMinMargin); always reported.
		double minFlutter = a.num("minFlutterMargin", meetRules
				? ctx.standards().q("structures.flutterMinMargin", Dim.DIMENSIONLESS, 1.5) : Double.NaN);
		return new Optimizer.Constraints(minStab, maxStab,
				a.qty("minRailExit", Dim.VELOCITY, obj == Optimizer.Objective.TARGET_STABILITY && Double.isNaN(minStab) ? Double.NaN : railRule),
				a.num("maxMach", Double.NaN), a.qtyOrNaN("minApogee", Dim.DISTANCE), minFlutter, ctx.standards());
	}

	/** The constraints as reported to the user. */
	static Map<String, Object> describe(Optimizer.Constraints c, double windCase, String ruleBasis) {
		Map<String, Object> cons = new LinkedHashMap<>();
		if (!Double.isNaN(c.minStability())) {
			cons.put("minAscentStability", Units.num(c.minStability()) + " cal");
		}
		if (!Double.isNaN(c.maxStability())) {
			cons.put("maxAscentStability", Units.num(c.maxStability()) + " cal");
		}
		if (!Double.isNaN(c.minRailExit())) {
			cons.put("minRailExit", Units.fmt(c.minRailExit(), Dim.VELOCITY));
		}
		if (!Double.isNaN(c.maxMach())) {
			cons.put("maxMach", Units.num(c.maxMach()));
		}
		if (!Double.isNaN(c.minApogee())) {
			cons.put("minApogee", Units.fmt(c.minApogee(), Dim.DISTANCE));
		}
		if (!Double.isNaN(c.minFlutterMargin())) {
			cons.put("minFinFlutterMargin", Units.num(c.minFlutterMargin()) + " (flutter speed / airspeed along the flight; fins of "
					+ "unknown stiffness are not constrained)");
		}
		if (!Double.isNaN(windCase)) {
			cons.put("evaluatedAt", "nominal wind and " + Units.fmt(windCase, Dim.VELOCITY) + " (rule set maximum ground wind); worse of the two");
		}
		if (ruleBasis != null) {
			cons.put("fromRules", ruleBasis);
		}
		return cons;
	}

	private static double frac(String name, double v) {
		if (v < 0 || v > 0.5) {
			throw new ToolException("'" + name + "' = " + v + " is not a usable spread: it is a fraction of the value, from 0 to 0.5 "
					+ "(0.05 for 5%), not a percentage.").field(name);
		}
		return v;
	}

	private static Object optimize(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		List<Optimizer.Variable> vars = new ArrayList<>();
		Set<String> hidden = new LinkedHashSet<>();
		for (Args v : a.objList("variables")) {
			RocketComponent c = Components.find(d.doc.getRocket(), v.str("component"));
			String prop = v.str("property");
			String hw = Components.overrideWarning(c);
			if (hw != null) {
				hidden.add(hw);
			}
			Object raw = Components.getRaw(c, prop);
			if (!(raw instanceof Double)) {
				throw new ToolException(c.getName() + "." + prop + " is not a continuous (number) property.");
			}
			Dim dim = Components.dimOf(prop);
			var table = AeroTable.any(d.doc.getRocket());
			if (table != null && table.useDrag() && (c instanceof FinSet
					|| c instanceof SymmetricComponent
							&& !(c instanceof InternalComponent))) {
				hidden.add("The imported aero table (" + table.source() + ") sets the drag of every candidate, so changing "
						+ c.getName() + "'s shape does not change drag here. For shape changes use optimize_fins (OpenRocket's "
						+ "shape-aware drag), or clear the table first.");
			}
			// Bounds follow the property's own rules (a position may be negative, a thickness may not).
			double lo = Components.physical(c, prop, dim, v.qtyUnchecked("min", dim));
			double hi = Components.physical(c, prop, dim, v.qtyUnchecked("max", dim));
			vars.add(new Optimizer.Variable(c.getID().toString(), c.getName(), prop, lo, hi));
		}
		Optimizer.Objective obj = Optimizer.Objective.valueOf(a.str("objective").toUpperCase(Locale.ROOT));
		double target = switch (obj) {
			case TARGET_APOGEE -> a.qty("targetApogee", Dim.DISTANCE);
			case TARGET_STABILITY -> a.num("targetStability");
			default -> Double.NaN;
		};
		String[] basis = new String[1];
		Optimizer.Constraints c = constraints(ctx, d, a, obj, false, basis);
		String ruleBasis = basis[0];
		Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
				ctx.standards(), false);
		long t0 = System.nanoTime();
		double maxWind = ctx.standards().rule("maxGroundWind.value", Dim.VELOCITY);
		boolean stabilityConstrained = !Double.isNaN(c.minStability()) || !Double.isNaN(c.maxStability());
		double windCase = a.bool("checkDesignWind", stabilityConstrained) && !Double.isNaN(maxWind) ? maxWind : Double.NaN;
		int budget = Math.max(4, Math.min(200, a.integer("maxEvaluations", 40)));
		CallContext.current().expect((budget + 1) * (Double.isNaN(windCase) ? 1 : 2), "simulations");
		Optimizer.Result r = Optimizer.run(base, d.doc, vars, obj, target, c, budget, 1,
				windCase);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("objective", obj.name().toLowerCase(Locale.ROOT) + (Double.isNaN(target) ? ""
				: " " + (obj == Optimizer.Objective.TARGET_APOGEE ? Units.fmt(target, Dim.DISTANCE) : Units.num(target) + " cal")));
		Map<String, Object> cons = describe(c, windCase, ruleBasis);
		out.put("constraints", cons);
		out.put("feasible", r.feasible());
		out.put("best", r.best() == null ? null : Optimizer.render(r.best(), vars));
		// Baseline: the current design, for comparison.
		double[] current = new double[vars.size()];
		for (int i = 0; i < vars.size(); i++) {
			current[i] = (Double) Components.getRaw(Components.find(d.doc.getRocket(), vars.get(i).componentId()), vars.get(i).property());
		}
		Optimizer.Point cur = Optimizer.evaluateOne(base, d.doc, vars, current, true, windCase, ctx.standards());
		Map<String, Object> curOut = Optimizer.render(cur, vars);
		List<String> curV = Optimizer.violations(cur, c);
		curOut.put("meetsConstraints", curV.isEmpty() ? "yes" : "NO: " + String.join("; ", curV));
		out.put("current", curOut);
		List<Map<String, Object>> runners = new ArrayList<>();
		for (Optimizer.Point p : Optimizer.top(r, obj, target, c, 5)) {
			if (p != r.best()) {
				runners.add(Optimizer.render(p, vars));
			}
		}
		out.put("alternatives", runners);
		out.put("evaluations", r.evaluated().size());
		if (!hidden.isEmpty()) {
			out.put("warnings", hidden);
		}
		out.put("elapsed", Units.num((System.nanoTime() - t0) / 1e9) + " s");
		if (!r.feasible()) {
			List<String> why = Optimizer.violations(r.best(), c);
			boolean flutter = why.stream().anyMatch(v -> v.startsWith("fin flutter"));
			out.put("note", "No evaluated point met every constraint; 'best' is the least-violating one (" + String.join("; ", why)
					+ "). " + (flutter ? "Fin size alone cannot fix flutter at these speeds: use thicker or stiffer fins (fin_flutter "
							+ "gives the thickness or shear modulus needed; record a measured modulus with update_standards), or a "
							+ "motor that flies slower. " : "")
					+ "Widen the bounds, add a variable (e.g. nose ballast to cut CG travel), or relax a constraint.");
		}
		if (a.bool("apply", false) && r.best() != null && r.best().error() == null) {
			List<String> applied = new ArrayList<>();
			for (int i = 0; i < vars.size(); i++) {
				RocketComponent comp = Components.find(d.doc.getRocket(), vars.get(i).componentId());
				applied.add(Components.set(comp, vars.get(i).property(), new JsonPrimitive(r.best().x()[i])));
			}
			d.doc.setSaved(false);
			out.put("applied", applied);
			out.put("stability", Analysis.stageStacks(d.doc.getRocket().getSelectedConfiguration(), 0.3));
		}
		return out;
	}
}
