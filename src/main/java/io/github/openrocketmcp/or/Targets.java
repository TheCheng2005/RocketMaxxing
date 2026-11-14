package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * The team's own targets (standards "targets": apogee, rail exit speed, stability window, descent rates...) and the
 * margin of a simulated flight to each. Competition rules are {@link Requirements}; these are the team's design goals.
 */
public final class Targets {
	private Targets() {
	}

	/** Within this fraction of a limit a met target is reported as close (WARN). */
	public static final double CLOSE = 0.05;

	/** A flight number a target can be set on; {@code suffix} labels dimensionless values ("cal"). */
	public record Metric(String id, String label, Dim dim, String suffix, ToDoubleFunction<Flight> value) {
	}

	/** A simulated flight and the standards it was flown with, read lazily by the metrics. */
	public static final class Flight {
		final Simulation sim;
		final Standards std;
		final FlightData data;
		final FlightDataBranch main;
		private List<Sims.Deployment> deployments;
		private Double flutter;

		Flight(Simulation sim, Standards std) {
			this.sim = sim;
			this.std = std;
			this.data = sim.getSimulatedData();
			this.main = data.getBranch(0);
		}

		double event(FlightEvent.Type t) {
			return Sims.eventTime(main, t);
		}

		/** Steady descent rates under each device of the main branch, in deployment order. */
		List<Sims.Deployment> deployments() {
			if (deployments == null) {
				deployments = new ArrayList<>();
				for (Sims.Deployment d : Sims.deployments(sim)) {
					if (d.branch().equals(main.getName())) {
						deployments.add(d);
					}
				}
			}
			return deployments;
		}

		double flutter() {
			if (flutter == null) {
				double min = Double.NaN;
				for (Structures.FlutterResult r : Structures.flutter(sim, std, Map.of(), Double.NaN)) {
					if (!Double.isNaN(r.minMargin()) && (Double.isNaN(min) || r.minMargin() < min)) {
						min = r.minMargin();
					}
				}
				flutter = min;
			}
			return flutter;
		}
	}

	public static final List<Metric> METRICS = List.of(
			new Metric("apogee", "Apogee", Dim.DISTANCE, null, f -> f.data.getMaxAltitude()),
			new Metric("maxVelocity", "Top speed", Dim.VELOCITY, null, f -> f.data.getMaxVelocity()),
			new Metric("maxMach", "Top Mach number", Dim.DIMENSIONLESS, null, f -> f.data.getMaxMachNumber()),
			new Metric("maxAcceleration", "Peak acceleration", Dim.ACCELERATION, null, f -> f.data.getMaxAcceleration()),
			new Metric("railExitVelocity", "Rail exit speed", Dim.VELOCITY, null, f -> f.data.getLaunchRodVelocity()),
			new Metric("thrustToWeight", "Thrust to weight (rail)", Dim.DIMENSIONLESS, null, f -> {
				double rail = f.event(FlightEvent.Type.LAUNCHROD);
				return Double.isNaN(rail) ? Double.NaN : Sims.mean(f.main, FlightDataType.TYPE_THRUST_WEIGHT_RATIO, 0, rail);
			}),
			new Metric("minStability", "Lowest stability", Dim.DIMENSIONLESS, "cal", f -> {
				Sims.Window w = Sims.ascentStability(f.main);
				return w == null ? Double.NaN : w.min();
			}),
			new Metric("maxStability", "Highest stability", Dim.DIMENSIONLESS, "cal", f -> {
				Sims.Window w = Sims.ascentStability(f.main);
				return w == null ? Double.NaN : w.max();
			}),
			new Metric("flutterMargin", "Fin flutter margin", Dim.DIMENSIONLESS, null, Flight::flutter),
			new Metric("liftoffMass", "Lift-off mass", Dim.MASS, null, f -> Sims.at(f.main, FlightDataType.TYPE_MASS, 0)),
			new Metric("timeToApogee", "Time to apogee", Dim.TIME, null, f -> f.data.getTimeToApogee()),
			// One device: it is the main. Two or more: the first is the drogue, the last the main.
			new Metric("drogueDescentRate", "Drogue descent rate", Dim.VELOCITY, null, f -> {
				List<Sims.Deployment> d = f.deployments();
				return d.size() < 2 ? Double.NaN : d.get(0).steadyDescentRate();
			}),
			new Metric("mainDescentRate", "Main descent rate", Dim.VELOCITY, null, f -> {
				List<Sims.Deployment> d = f.deployments();
				return d.isEmpty() ? Double.NaN : d.get(d.size() - 1).steadyDescentRate();
			}),
			new Metric("groundHitVelocity", "Landing speed", Dim.VELOCITY, null, f -> f.data.getGroundHitVelocity()),
			new Metric("landingDistance", "Landing distance", Dim.DISTANCE, null, f -> {
				double gh = f.event(FlightEvent.Type.GROUND_HIT);
				return Double.isNaN(gh) ? Double.NaN : Sims.at(f.main, FlightDataType.TYPE_POSITION_XY, gh);
			}),
			new Metric("flightTime", "Flight time", Dim.TIME, null, f -> f.data.getFlightTime()));

	public static Metric metric(String id) {
		for (Metric m : METRICS) {
			if (m.id().equals(id)) {
				return m;
			}
		}
		return null;
	}

	/** Why a number is missing from this flight, in words, or null when it is simply not reached. */
	static String missing(Metric m, Flight f) {
		return switch (m.id()) {
			case "drogueDescentRate" -> f.deployments().isEmpty() ? "no parachute or streamer opens"
					: "no drogue (one recovery device, counted as the main)";
			case "mainDescentRate" -> "no parachute or streamer opens";
			case "minStability", "maxStability" -> "not read (never faster than 100 ft/s)";
			case "railExitVelocity", "thrustToWeight" -> "does not leave the rail";
			case "landingDistance", "groundHitVelocity" -> "the simulation ends before landing";
			default -> null;
		};
	}

	/** The drogue target does not apply to a rocket without a drogue: neither met nor missed. */
	private static boolean notApplicable(Metric m, Flight f) {
		return m.id().equals("drogueDescentRate") && f.deployments().size() == 1;
	}

	/** As {@link #evaluate(Simulation, Standards, boolean)} with every number, for the app's page. */
	public static Map<String, Object> evaluate(Simulation sim, Standards std) {
		return evaluate(sim, std, true);
	}

	/**
	 * Every metric of the flight with the team's target for it, if any: min, max (pass/fail) and goal (the number the
	 * team aims for). The margin is the distance to the nearer limit; a met target within {@link #CLOSE} of it is WARN.
	 * A target that cannot be read (a typo in the file) is reported on its row and the others are still checked.
	 * {@code detail} false is the short form for assistants: rows only for the targets set, without the SI copies,
	 * and the other numbers as one line each.
	 */
	public static Map<String, Object> evaluate(Simulation sim, Standards std, boolean detail) {
		JsonObject targets = targets(std);
		List<String> unknown = new ArrayList<>();
		for (String key : targets.keySet()) {
			if (metric(key) == null && !key.endsWith("Note")) {
				unknown.add(key);
			}
		}
		Flight f = new Flight(sim, std);
		List<Map<String, Object>> rows = new ArrayList<>();
		Map<String, Object> others = new LinkedHashMap<>();
		int set = 0, fail = 0, close = 0, invalid = 0, byDefault = 0, defaultFail = 0;
		for (Metric m : METRICS) {
			JsonObject t = targets.has(m.id()) && targets.get(m.id()).isJsonObject() ? targets.getAsJsonObject(m.id()) : null;
			if (targets.has(m.id()) && t == null && !targets.get(m.id()).isJsonNull()) {
				invalid++;
			}
			boolean fromDefault = false;
			if (t == null && m.id().equals("flutterMargin")) {
				// Flutter is the one metric that costs time; without a target the team's flutterMinMargin applies.
				t = new JsonObject();
				t.addProperty("min", std.q("structures.flutterMinMargin", Dim.DIMENSIONLESS, 1.5));
				t.addProperty("from", "structures.flutterMinMargin");
				fromDefault = true;
			}
			// One value that cannot be worked out (no shear modulus for the fin material) leaves the others standing.
			double v;
			String unavailable = null;
			try {
				v = m.value().applyAsDouble(f);
			} catch (ToolException ex) {
				v = Double.NaN;
				unavailable = ex.getMessage();
			}
			String why = Double.isFinite(v) || unavailable != null ? null : missing(m, f);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", m.id());
			row.put("label", m.label());
			if (detail) {
				row.put("dim", m.dim() == Dim.DIMENSIONLESS ? null : m.dim().name());
			}
			row.put("value", Double.isFinite(v) ? text(v, m) : unavailable != null ? "not worked out"
					: why != null ? why : "not reached in this flight");
			if (detail) {
				row.put("valueSI", Double.isFinite(v) ? v : null);
			}
			if (unavailable != null) {
				row.put("note", unavailable);
			}
			if (t != null) {
				double min, max, goal;
				try {
					min = bound(t, "min", m);
					max = bound(t, "max", m);
					goal = bound(t, "goal", m);
				} catch (ToolException ex) {
					// A value the team typed that cannot be read: say so on its row, check the rest.
					row.put("status", "INFO");
					row.put("invalid", ex.getMessage() + " Fix it on the Targets page or in the standards file.");
					invalid++;
					rows.add(row);
					continue;
				}
				if (Double.isNaN(min) && Double.isNaN(max) && Double.isNaN(goal)) {
					addOther(rows, others, row, detail);
					continue;
				}
				set++;
				put(row, "min", min, m, detail);
				put(row, "max", max, m, detail);
				put(row, "goal", goal, m, detail);
				if (t.has("note") && t.get("note").isJsonPrimitive() && unavailable == null) {
					row.put("note", t.get("note").getAsString());
				}
				if (t.has("from")) {
					row.put("from", t.get("from").getAsString());
				}
				String status = "PASS";
				if (unavailable != null || notApplicable(m, f)) {
					status = "INFO"; // not known, or not this rocket's: neither met nor missed
				} else if (!Double.isFinite(v)) {
					status = Double.isNaN(min) && Double.isNaN(max) ? "INFO" : "FAIL";
				} else if (!Double.isNaN(min) || !Double.isNaN(max)) {
					double toMin = Double.isNaN(min) ? Double.POSITIVE_INFINITY : v - min;
					double toMax = Double.isNaN(max) ? Double.POSITIVE_INFINITY : max - v;
					double margin = Math.min(toMin, toMax);
					double limit = toMin <= toMax ? min : max;
					boolean atMin = toMin <= toMax;
					row.put("margin", text(Math.abs(margin), m) + (atMin ? margin >= 0 ? " above the minimum" : " below the minimum"
							: margin >= 0 ? " under the maximum" : " over the maximum"));
					if (detail) {
						row.put("marginSI", margin);
					}
					double pct = limit == 0 ? Double.NaN : 100 * margin / Math.abs(limit);
					if (!Double.isNaN(pct)) {
						row.put("marginPercent", Units.num(pct));
					}
					status = margin < 0 ? "FAIL" : !Double.isNaN(pct) && pct < 100 * CLOSE ? "WARN" : "PASS";
				} else {
					status = "INFO";
				}
				if (!Double.isNaN(goal) && Double.isFinite(v)) {
					row.put("fromGoal", (v >= goal ? "+" : "") + text(v - goal, m));
				}
				row.put("status", status);
				if (fromDefault) {
					set--;
					byDefault++;
					defaultFail += status.equals("FAIL") ? 1 : 0;
				} else {
					fail += status.equals("FAIL") ? 1 : 0;
					close += status.equals("WARN") ? 1 : 0;
				}
				rows.add(row);
				continue;
			}
			addOther(rows, others, row, detail);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("simulation", sim.getName());
		if (f.data.getBranchCount() > 1) {
			// Every number is the top stage's (the one that reaches apogee); the boosters are in run_simulation.
			out.put("stage", f.main.getName());
		}
		StringBuilder sum = new StringBuilder(set == 0 ? "No team targets set" + (byDefault > 0 ? " (the team's minimum flutter margin "
				+ (defaultFail > 0 ? "is missed" : "is checked") + ")" : "") + ". Set them on the Targets page or with targets set."
				: fail == 0 ? (close == 0 ? "All " + set + " targets met." : "All " + set + " targets met; " + close + " within "
						+ Units.num(100 * CLOSE) + "% of a limit.")
						: fail + " of " + set + " targets missed" + (close > 0 ? "; " + close + " close." : "."));
		if (set > 0 && defaultFail > 0) {
			sum.append(" The team's minimum flutter margin is also missed.");
		}
		if (invalid > 0) {
			sum.append(" ").append(invalid).append(invalid == 1 ? " target cannot be read" : " targets cannot be read").append(": see invalid.");
		}
		if (!unknown.isEmpty()) {
			sum.append(" Not a target here: ").append(String.join(", ", unknown)).append(".");
			out.put("unknownTargets", unknown.stream().map(k -> k + (suggest(k) == null ? "" : " (did you mean " + suggest(k) + "?)")).toList());
		}
		out.put("summary", sum.toString());
		out.put("targets", rows);
		if (!detail && !others.isEmpty()) {
			out.put("otherValues", others);
		}
		List<String> problems = Sims.problems(sim);
		if (!problems.isEmpty()) {
			out.put("PROBLEMS", problems);
		}
		return out;
	}

	/** A number without a target: a row of its own for the page, one line for an assistant. */
	private static void addOther(List<Map<String, Object>> rows, Map<String, Object> others, Map<String, Object> row, boolean detail) {
		if (detail) {
			rows.add(row);
		} else {
			others.put((String) row.get("id"), row.get("value"));
		}
	}

	/** The metric whose name is nearest to one that is not a metric (apogeeFt, railExit), or null. */
	static String suggest(String key) {
		String k = key.toLowerCase(java.util.Locale.ROOT);
		for (Metric m : METRICS) {
			String id = m.id().toLowerCase(java.util.Locale.ROOT);
			if (k.startsWith(id) || id.startsWith(k) || k.contains(id) || id.contains(k)) {
				return m.id();
			}
		}
		return null;
	}

	/**
	 * Refuses a change to the targets that could not be checked or would make no sense: an unknown target, a field other
	 * than min, max, goal and note, a value that cannot be read, a negative limit, a minimum at or above the maximum,
	 * a goal outside the limits. {@code merged} is every target as it would be after the change.
	 */
	public static void validate(JsonObject change, JsonObject merged) {
		for (Map.Entry<String, JsonElement> e : change.entrySet()) {
			String key = e.getKey();
			Metric m = metric(key);
			if (m == null) {
				String near = suggest(key);
				throw new ToolException("'" + key + "' is not a target." + (near != null ? " Did you mean " + near + "?" : "")
						+ " Targets: " + String.join(", ", METRICS.stream().map(Metric::id).toList()) + ".").field(key);
			}
			if (e.getValue().isJsonNull()) {
				continue; // clears the whole target
			}
			if (!e.getValue().isJsonObject()) {
				throw new ToolException("Target " + key + " takes limits by name, e.g. {\"" + key + "\": {\"min\": " + example(m)
						+ "}}, not " + e.getValue() + ".").field(key);
			}
			for (String k : e.getValue().getAsJsonObject().keySet()) {
				if (!List.of("min", "max", "goal", "note").contains(k)) {
					throw new ToolException("A target has min, max, goal and note, not '" + k + "'.").field(key + "." + k);
				}
			}
		}
		for (Map.Entry<String, JsonElement> e : merged.entrySet()) {
			Metric m = metric(e.getKey());
			if (m == null || !e.getValue().isJsonObject() || !change.has(e.getKey())) {
				continue;
			}
			JsonObject t = e.getValue().getAsJsonObject();
			double min = bound(t, "min", m), max = bound(t, "max", m), goal = bound(t, "goal", m);
			for (String k : List.of("min", "max", "goal")) {
				double v = bound(t, k, m);
				if (v < 0) {
					throw new ToolException(m.label() + ": a " + word(k) + " of " + text(v, m) + " is below zero, which this number "
							+ "never is.").field(m.id() + "." + k);
				}
			}
			if (!Double.isNaN(min) && !Double.isNaN(max) && min >= max) {
				throw new ToolException(m.label() + ": the minimum (" + text(min, m) + ") has to be below the maximum ("
						+ text(max, m) + ").").field(m.id() + "." + (change.getAsJsonObject(m.id()).has("max") ? "max" : "min"));
			}
			if (!Double.isNaN(goal) && (!Double.isNaN(min) && goal < min || !Double.isNaN(max) && goal > max)) {
				throw new ToolException(m.label() + ": the goal (" + text(goal, m) + ") is outside the limits ("
						+ (Double.isNaN(min) ? "" : "at least " + text(min, m)) + (!Double.isNaN(min) && !Double.isNaN(max) ? ", " : "")
						+ (Double.isNaN(max) ? "" : "at most " + text(max, m)) + ").").field(m.id() + ".goal");
			}
		}
	}

	private static String word(String key) {
		return key.equals("min") ? "minimum" : key.equals("max") ? "maximum" : "goal";
	}

	private static String example(Metric m) {
		return switch (m.dim()) {
			case DISTANCE -> "\"9500 ft\"";
			case VELOCITY -> "\"100 ft/s\"";
			case MASS -> "\"12 kg\"";
			case TIME -> "\"20 s\"";
			case ACCELERATION -> "\"15 G\"";
			default -> "\"1.5\"";
		};
	}

	public static JsonObject targetsOf(Standards std) {
		return targets(std);
	}

	static JsonObject targets(Standards std) {
		JsonElement t = std.data().get("targets");
		return t != null && t.isJsonObject() ? t.getAsJsonObject() : new JsonObject();
	}

	/** A target bound in SI; dimensionless ones may carry a word after the number ("1.5 cal", "8 x"). */
	public static double bound(JsonObject t, String key, Metric m) {
		JsonElement e = t.get(key);
		if (e == null || e.isJsonNull() || e.isJsonPrimitive() && e.getAsString().isBlank()) {
			return Double.NaN;
		}
		try {
			if (e.getAsJsonPrimitive().isNumber()) {
				return e.getAsDouble();
			}
			// 1,5 is a decimal comma; 9,500 a thousands separator.
			String s = e.getAsString().trim().replaceFirst("^(-?\\d+),(\\d{1,2}|\\d{4,})(?!\\d)", "$1.$2")
					.replaceAll("(\\d),(\\d{3})(?!\\d)", "$1$2");
			if (m.dim() == Dim.DIMENSIONLESS) {
				return Double.parseDouble(s.replaceFirst("\\s*[A-Za-z%]*$", ""));
			}
			return Units.toSi(s, m.dim());
		} catch (RuntimeException ex) {
			// "Cannot parse ..." says no more than "cannot be read"; a unit of the wrong kind says which it is.
			String why = ex.getMessage() == null || ex.getMessage().startsWith("Cannot parse") ? ""
					: " (" + ex.getMessage().replaceAll("\\s*\\(e\\.g\\..*$", "").replaceAll("[.\\s]+$", "") + ")";
			throw new ToolException(m.label() + " " + word(key) + ": " + e + " cannot be read" + why + ". Use a number with a unit, e.g. "
					+ example(m) + ".").field(m.id() + "." + key);
		}
	}

	private static void put(Map<String, Object> row, String key, double v, Metric m, boolean detail) {
		if (!Double.isNaN(v)) {
			row.put(key, text(v, m));
			if (detail) {
				row.put(key + "SI", v);
			}
		}
	}

	static String text(double v, Metric m) {
		if (m.dim() == Dim.DIMENSIONLESS) {
			return Units.num(v) + (m.suffix() == null ? "" : " " + m.suffix());
		}
		return Units.fmt(v, m.dim());
	}
}
