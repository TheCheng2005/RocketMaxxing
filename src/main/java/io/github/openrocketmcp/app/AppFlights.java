package io.github.openrocketmcp.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Aero;
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Branch;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Motors;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.units.Dim;

/**
 * The Flights page's tools and the flights behind the Design page: the quick flight, RASAero beside OpenRocket, the
 * simulations with their launch conditions, running them, and their data for the plots. Registered by {@link AppTools}.
 */
final class AppFlights {
	private AppFlights() {
	}

	static Map<String, Object> quickFlight(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		if (!fc.hasMotors()) {
			return Map.of("noMotors", true);
		}
		Simulation sim = Sims.prepare(d, null, fc.getId().toString(), null, ctx.standards(), false);
		if (d.doc.getSimulations().contains(sim)) {
			// The document's flight of this configuration: the rule check and targets read the same one, so after an
			// edit it is flown once for all three (calls on a design run one at a time).
			Sims.ensure(sim);
		} else {
			sim = sim.copy();
			Sims.run(sim);
		}
		return results(sim);
	}

	/** Mach numbers for the side-by-side table, up to a little past the flight's top speed. */
	static final double[] COMPARE_MACHS = { 0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 1.0, 1.05, 1.1, 1.2, 1.3, 1.5, 1.75, 2.0, 2.5, 3.0 };

	/**
	 * The flight on both models: RASAero's drag (when a table for the whole vehicle is imported and its drag used) and
	 * OpenRocket's own; the stability margin with each model's CP; CD and CP by Mach from both. Transonic and supersonic
	 * flights are where the two differ most, and where OpenRocket warns its model is least sure.
	 */
	static Map<String, Object> aeroCompare(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket rocket = d.doc.getRocket();
		FlightConfiguration fc = rocket.getSelectedConfiguration();
		AeroTable.Table t = AeroTable.of(rocket);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("hasTable", t != null);
		if (t != null) {
			out.put("source", t.source());
			out.put("useDrag", t.useDrag());
			out.put("hasCp", t.hasCp());
			out.put("tableMaxMach", t.mach()[t.mach().length - 1]);
		}
		double ref = Analysis.stability(fc, 0.3).referenceDiameter();
		double topMach = Double.NaN;
		if (fc.hasMotors()) {
			Simulation base = Sims.prepare(d, null, fc.getId().toString(), null, ctx.standards(), false);
			List<Variants.Run> runs = Variants.runAll(t != null && t.useDrag()
					? List.of(AeroTable.without(Variants.of(base, d.doc, null, null)), Variants.of(base, d.doc, null, null))
					: List.of(AeroTable.without(Variants.of(base, d.doc, null, null))));
			Variants.Run or = runs.get(0);
			if (or.ok()) {
				out.put("openRocket", flightNumbers(or.sim(), null, ref));
				topMach = or.sim().getSimulatedData().getMaxMachNumber();
			}
			if (t != null) {
				Variants.Run tb = runs.size() > 1 ? runs.get(1) : or;
				if (tb.ok()) {
					// With the table's drag (or, when its drag is not used, the same flight), and the table's CP.
					out.put("rasaero", flightNumbers(tb.sim(), t, ref));
					topMach = Math.max(Double.isNaN(topMach) ? 0 : topMach, tb.sim().getSimulatedData().getMaxMachNumber());
				}
			}
		}
		out.put("maxMach", Double.isFinite(topMach) ? topMach : null);
		double upTo = Double.isFinite(topMach) ? Math.max(1.0, topMach + 0.15) : 1.5;
		List<Double> ms = new ArrayList<>();
		for (double m : COMPARE_MACHS) {
			if (m <= upTo + 1e-9 && (t == null || m <= t.mach()[t.mach().length - 1] + 1e-9 || ms.size() < 3)) {
				ms.add(m);
			}
		}
		if (Double.isFinite(topMach) && !ms.contains(topMach)) {
			ms.add(topMach);
			ms.sort(Double::compare);
		}
		double[] arr = ms.stream().mapToDouble(Double::doubleValue).toArray();
		List<Aero.Point> pts;
		try {
			pts = Aero.sweep(fc, arr);
		} catch (RuntimeException e) {
			pts = List.of();
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Aero.Point p : pts) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("mach", p.mach());
			r.put("cdOpenRocket", finiteOrNull(p.cd()));
			r.put("cpOpenRocket", finiteOrNull(p.cpX()));
			if (t != null && p.mach() <= t.mach()[t.mach().length - 1] + 1e-9) {
				r.put("cdRasaeroPowerOff", finiteOrNull(t.cd(p.mach(), false)));
				r.put("cdRasaeroPowerOn", finiteOrNull(t.cd(p.mach(), true)));
				if (t.hasCp()) {
					r.put("cpRasaero", finiteOrNull(t.cpAt(p.mach())));
				}
			}
			rows.add(r);
		}
		out.put("byMach", rows);
		out.put("referenceDiameter", ref);
		return out;
	}

	static Object finiteOrNull(double v) {
		return Double.isFinite(v) ? v : null;
	}

	/** A flight's main numbers in SI, and its lowest ascent margin (with the table's CP when one is given). */
	static Map<String, Object> flightNumbers(Simulation sim, AeroTable.Table cpFrom, double ref) {
		FlightData f = sim.getSimulatedData();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("apogee", finiteOrNull(f.getMaxAltitude()));
		m.put("maxVelocity", finiteOrNull(f.getMaxVelocity()));
		m.put("maxMach", finiteOrNull(f.getMaxMachNumber()));
		m.put("timeToApogee", finiteOrNull(f.getTimeToApogee()));
		m.put("railExitVelocity", finiteOrNull(f.getLaunchRodVelocity()));
		if (cpFrom != null && cpFrom.hasCp()) {
			AeroTable.Margin mg = AeroTable.minMargin(sim, cpFrom, ref);
			if (mg != null) {
				m.put("minStability", mg.min());
				m.put("minStabilityMach", mg.mach());
			}
		} else {
			Sims.Window w = Sims.ascentStability(f.getBranch(0));
			if (w != null) {
				m.put("minStability", w.min());
			}
		}
		return m;
	}

	static Map<String, Object> results(Simulation sim) {
		FlightData f = sim.getSimulatedData();
		Map<String, Object> m = new LinkedHashMap<>();
		if (f == null || f.getBranchCount() == 0) {
			return m;
		}
		m.put("apogee", AppDesign.finite(f.getMaxAltitude()));
		m.put("maxVelocity", AppDesign.finite(f.getMaxVelocity()));
		m.put("maxMach", AppDesign.finite(f.getMaxMachNumber()));
		m.put("maxAcceleration", AppDesign.finite(f.getMaxAcceleration()));
		m.put("timeToApogee", AppDesign.finite(f.getTimeToApogee()));
		m.put("flightTime", AppDesign.finite(f.getFlightTime()));
		m.put("groundHitVelocity", AppDesign.finite(f.getGroundHitVelocity()));
		m.put("railExitVelocity", AppDesign.finite(f.getLaunchRodVelocity()));
		m.put("optimumDelay", AppDesign.finite(f.getOptimumDelay()));
		m.put("deploymentVelocity", AppDesign.finite(f.getDeploymentVelocity()));
		Sims.Window w = Sims.ascentStability(f.getBranch(0));
		if (w != null) {
			m.put("minStability", w.min());
			m.put("maxStability", w.max());
		}
		List<String> problems = Sims.problems(sim);
		if (!problems.isEmpty()) {
			m.put("problems", problems);
		}
		return m;
	}

	// ------------------------------------------------------------------------------------------- simulations

	static Simulation sim(Designs.Design d, int i) {
		List<Simulation> all = d.doc.getSimulations();
		if (i < 0 || i >= all.size()) {
			throw new ToolException("No simulation " + i + ".");
		}
		return all.get(i);
	}

	static Map<String, Object> sims(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		List<Map<String, Object>> out = new ArrayList<>();
		int i = 0;
		for (Simulation s : d.doc.getSimulations()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("index", i++);
			m.put("name", s.getName());
			FlightConfigurationId id = s.getFlightConfigurationId();
			m.put("configuration", id.toString());
			m.put("configurationName", r.getFlightConfiguration(id).getName());
			m.put("status", s.getStatus().name());
			m.put("options", options(s.getOptions()));
			m.put("results", results(s));
			out.add(m);
		}
		return Map.of("simulations", out, "configurations", AppDesign.configList(r));
	}

	static Map<String, Object> options(SimulationOptions o) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("rodLength", o.getLaunchRodLength());
		m.put("rodAngle", o.getLaunchRodAngle());
		m.put("rodDirection", o.getLaunchRodDirection());
		m.put("launchIntoWind", o.getLaunchIntoWind());
		m.put("windSpeed", Winds.speed(o));
		m.put("windDirection", Winds.direction(o));
		m.put("windTurbulence", Winds.turbulence(o));
		m.put("windProfile", Winds.isMultiLevel(o));
		m.put("launchAltitude", o.getLaunchAltitude());
		m.put("latitude", o.getLaunchLatitude());
		m.put("longitude", o.getLaunchLongitude());
		m.put("isaAtmosphere", o.isISAAtmosphere());
		m.put("temperature", o.getLaunchTemperature());
		m.put("pressure", o.getLaunchPressure());
		m.put("timeStep", o.getTimeStep());
		return m;
	}

	static Map<String, Object> simNew(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		FlightConfiguration fc = Components.config(r, a.str("configuration", null));
		Simulation s = new Simulation(d.doc, r);
		s.setFlightConfigurationId(fc.getId());
		s.setName("Simulation " + (d.doc.getSimulations().size() + 1));
		ctx.standards().applyLaunchDefaults(s.getOptions());
		d.doc.addSimulation(s);
		d.doc.setSaved(false);
		return Map.of("index", d.doc.getSimulations().size() - 1, "name", s.getName());
	}

	static Map<String, Object> simEdit(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Simulation s = sim(d, a.integer("simulation", -1));
		SimulationOptions o = s.getOptions();
		String key = a.str("key");
		var v = a.get("value");
		switch (key) {
			case "name" -> s.setName(v.getAsString());
			case "configuration" -> s.setFlightConfigurationId(Components.config(d.doc.getRocket(), v.getAsString()).getId());
			case "rodLength" -> o.setLaunchRodLength(positive(Args.qtyOf(v, key, Dim.LENGTH), key));
			case "rodAngle" -> o.setLaunchRodAngle(Args.qtyOf(v, key, Dim.ANGLE));
			case "rodDirection" -> o.setLaunchRodDirection(Args.qtyOf(v, key, Dim.ANGLE));
			case "launchIntoWind" -> o.setLaunchIntoWind(v.getAsBoolean());
			case "windSpeed" -> Winds.setGround(o, positive(Args.qtyOf(v, key, Dim.VELOCITY), key), Winds.direction(o));
			case "windDirection" -> Winds.setGround(o, Winds.speed(o), Args.qtyOf(v, key, Dim.ANGLE));
			case "windTurbulence" -> Winds.setTurbulence(o, positive(v.getAsDouble(), key));
			case "launchAltitude" -> o.setLaunchAltitude(Args.qtyOf(v, key, Dim.DISTANCE));
			case "latitude" -> o.setLaunchLatitude(v.getAsDouble());
			case "longitude" -> o.setLaunchLongitude(v.getAsDouble());
			case "isaAtmosphere" -> o.setISAAtmosphere(v.getAsBoolean());
			case "temperature" -> o.setLaunchTemperature(Args.qtyOf(v, key, Dim.TEMPERATURE));
			case "pressure" -> o.setLaunchPressure(positive(Args.qtyOf(v, key, Dim.PRESSURE), key));
			case "timeStep" -> o.setTimeStep(positive(Args.qtyOf(v, key, Dim.TIME), key));
			default -> throw new ToolException("Unknown simulation field '" + key + "'.");
		}
		d.doc.setSaved(false);
		return Map.of("options", options(o), "status", s.getStatus().name());
	}

	static double positive(double v, String key) {
		if (!(v >= 0) || !Double.isFinite(v)) {
			throw new ToolException(key + " must be zero or more.");
		}
		return v;
	}

	static Map<String, Object> simRun(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		List<Simulation> todo = a.has("simulation") ? List.of(sim(d, a.integer("simulation", -1))) : d.doc.getSimulations();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Simulation s : todo) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", s.getName());
			if (!s.getRocket().getFlightConfiguration(s.getFlightConfigurationId()).hasMotors()) {
				m.put("error", "The configuration has no motors: choose one on the Motors page.");
				m.put("errorKind", ToolException.Kind.INPUT.id());
			} else {
				try {
					Sims.ensure(s);
					m.put("results", results(s));
				} catch (ToolException e) {
					m.put("error", e.getMessage());
					m.put("errorKind", e.kind().id());
				} catch (RuntimeException e) {
					// OpenRocket failing inside its own simulation: not the user's input; the log has the trace.
					Log.error("Simulation " + s.getName() + " failed", e);
					m.put("error", "OpenRocket failed to simulate it (" + e.getClass().getSimpleName()
							+ (e.getMessage() == null ? "" : ": " + e.getMessage()) + ").");
					m.put("errorKind", ToolException.Kind.SYSTEM.id());
				}
			}
			out.add(m);
		}
		d.doc.setSaved(false);
		return Map.of("runs", out);
	}

	static Map<String, Object> series(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Simulation s = sim(d, a.integer("simulation", -1));
		FlightData f = s.getSimulatedData();
		if (f == null || f.getBranchCount() == 0) {
			throw new ToolException("Run the simulation first.");
		}
		int bi = Math.max(0, Math.min(f.getBranchCount() - 1, a.integer("branch", 0)));
		FlightDataBranch b = f.getBranch(bi);
		Branch br = Branch.of(b);
		int n = br.size(), step = Math.max(1, (int) Math.ceil(n / 2000.0));
		List<Map<String, Object>> vars = new ArrayList<>();
		for (FlightDataType t : b.getTypes()) {
			double[] col = br.col(t);
			boolean any = false;
			for (double v : col) {
				if (Double.isFinite(v)) {
					any = true;
					break;
				}
			}
			if (!any) {
				continue;
			}
			List<Double> vals = new ArrayList<>();
			for (int i = 0; i < n; i += step) {
				vals.add(i < col.length && Double.isFinite(col[i]) ? col[i] : null);
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", t.getSymbol());
			m.put("name", t.getName());
			Dim dim = Sims.dimOf(t.getUnitGroup());
			if (dim != null) {
				m.put("dim", dim.name());
			}
			m.put("values", vals);
			vars.add(m);
		}
		List<Map<String, Object>> events = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			events.add(Map.of("type", e.getType().name(), "time", e.getTime()));
		}
		List<String> branches = new ArrayList<>();
		for (FlightDataBranch fb : f.getBranches()) {
			branches.add(fb.getName());
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("branches", branches);
		out.put("branch", bi);
		out.put("variables", vars);
		out.put("events", events);
		return out;
	}
}
