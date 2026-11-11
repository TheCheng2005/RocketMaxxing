package io.github.openrocketmcp.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.Versions;
import io.github.openrocketmcp.or.Aero;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.ElectronicsSummary;
import io.github.openrocketmcp.or.Recovery;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Design-review report in the team's test-report style: inputs, results, rule checks, the two stability-vs-time
 * plots Launch Canada asks for (DTEG R10.3.2), the recovery chain, and the methods and assumptions used.
 */
public final class Reports {
	private Reports() {
	}

	/** Markdown table from rows of key/value maps (union of keys, in first-seen order). */
	public static String table(List<Map<String, Object>> rows) {
		List<String> keys = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			for (String k : r.keySet()) {
				if (!keys.contains(k)) {
					keys.add(k);
				}
			}
		}
		StringBuilder s = new StringBuilder("| ").append(String.join(" | ", keys)).append(" |\n|");
		keys.forEach(k -> s.append("---|"));
		s.append('\n');
		for (Map<String, Object> r : rows) {
			s.append("| ");
			for (String k : keys) {
				Object v = r.get(k);
				s.append(v == null ? "" : cell(v)).append(" | ");
			}
			s.append('\n');
		}
		return s.toString();
	}

	static String cell(Object v) {
		String t = v instanceof List<?> l ? String.join("; ", l.stream().map(String::valueOf).toList()) : String.valueOf(v);
		return t.replace("|", "\\|").replace("\n", " ");
	}

	public static String kv(Map<String, Object> m) {
		StringBuilder s = new StringBuilder("| Quantity | Value |\n|---|---|\n");
		for (Map.Entry<String, Object> e : m.entrySet()) {
			s.append("| ").append(e.getKey()).append(" | ").append(cell(e.getValue())).append(" |\n");
		}
		return s.toString();
	}

	/** Writes CSV of the given variables for one branch in the display unit system. Returns the path. */
	public static Path csv(Simulation sim, String branch, List<String> variables, Path file) throws IOException {
		Map<String, Object> series = Sims.series(sim, branch, variables, Integer.MAX_VALUE, Double.NaN, Double.NaN);
		StringBuilder s = new StringBuilder();
		@SuppressWarnings("unchecked")
		List<String> cols = (List<String>) series.get("columns");
		s.append(String.join(",", cols.stream().map(c -> "\"" + c.replace("\"", "'") + "\"").toList())).append('\n');
		@SuppressWarnings("unchecked")
		List<List<Object>> rows = (List<List<Object>>) series.get("rows");
		for (List<Object> row : rows) {
			List<String> cells = new ArrayList<>();
			for (Object o : row) {
				cells.add(o == null ? "" : String.valueOf(o));
			}
			s.append(String.join(",", cells)).append('\n');
		}
		Path p = file.toAbsolutePath().normalize();
		if (p.getParent() != null) {
			Files.createDirectories(p.getParent());
		}
		Files.writeString(p, s.toString(), StandardCharsets.UTF_8);
		return p;
	}

	/**
	 * Stability vs time. OpenRocket does not record stability while the rocket is on the rail; there it is filled in
	 * as (Barrowman CP at that instant's Mach - simulated CG) / reference diameter.
	 */
	/** Altitude vs time for each stage (up to two) with the flight's events marked. */
	public static String profileSvg(Simulation sim) {
		FlightData data = sim.getSimulatedData();
		String unit = Units.plotUnit(Dim.DISTANCE);
		double k = Units.fromSi(1, unit);
		List<Svg.Series> series = new ArrayList<>();
		for (FlightDataBranch b : data.getBranches()) {
			double[] t = column(b, FlightDataType.TYPE_TIME), alt = column(b, FlightDataType.TYPE_ALTITUDE);
			for (int i = 0; i < alt.length; i++) {
				alt[i] *= k;
			}
			series.add(new Svg.Series(data.getBranchCount() > 1 ? b.getName() : null, t, alt));
		}
		List<Svg.Marker> markers = new ArrayList<>();
		for (FlightEvent e : data.getBranch(0).getEvents()) {
			String label = switch (e.getType()) {
				case BURNOUT -> "burnout";
				case STAGE_SEPARATION -> "separation";
				case APOGEE -> "apogee";
				case RECOVERY_DEVICE_DEPLOYMENT -> e.getSource() == null ? "deploy" : e.getSource().getName().toLowerCase(Locale.ROOT);
				default -> null;
			};
			if (label != null) {
				markers.add(new Svg.Marker(e.getTime(), label));
			}
		}
		return Svg.lines(sim.getRocket().getName() + ": apogee " + Units.fmt(data.getMaxAltitude(), Dim.DISTANCE),
				"Time (s)", "Altitude above the pad (" + unit + ")", series.subList(0, Math.min(2, series.size())), Double.NaN, null, markers);
	}

	static String stabilityPlot(Simulation sim, FlightDataBranch b, double t0, double t1, String title, double minRule,
			String ruleRef) {
		double[] t = column(b, FlightDataType.TYPE_TIME);
		double[] st = column(b, FlightDataType.TYPE_STABILITY);
		double[] cg = column(b, FlightDataType.TYPE_CG_LOCATION);
		double[] mach = column(b, FlightDataType.TYPE_MACH_NUMBER);
		var fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		double ref = Analysis.maxDiameter(fc);
		Map<Long, Double> cpCache = new HashMap<>();
		boolean filled = false;
		List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
		for (int i = 0; i < t.length; i++) {
			if (t[i] < t0 || t[i] > t1) {
				continue;
			}
			double v = st[i];
			if (Double.isNaN(v) && i < cg.length && !Double.isNaN(cg[i]) && ref > 0) {
				double m = i < mach.length && !Double.isNaN(mach[i]) ? mach[i] : 0.01;
				double cp = cpCache.computeIfAbsent(Math.round(m * 100), k -> Analysis.cp(fc, k / 100.0));
				v = (cp - cg[i]) / ref;
				filled = true;
			}
			xs.add(t[i]);
			ys.add(v);
		}
		List<Svg.Marker> markers = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			if (e.getTime() < t0 || e.getTime() > t1) {
				continue;
			}
			String label = switch (e.getType()) {
				case LAUNCHROD -> "rail exit";
				case BURNOUT -> "burnout";
				case STAGE_SEPARATION -> "separation";
				case IGNITION -> e.getTime() > 0.01 ? "ignition" : null;
				default -> null;
			};
			if (label != null) {
				markers.add(new Svg.Marker(e.getTime(), label));
			}
		}
		return Svg.line(filled ? title + " *" : title, "Time (s)", "Stability margin (cal)", xs.stream().mapToDouble(Double::doubleValue).toArray(),
				ys.stream().mapToDouble(Double::doubleValue).toArray(), minRule,
				Double.isNaN(minRule) ? "" : "minimum " + Units.num(minRule) + " cal (" + ruleRef + ")", markers);
	}

	private static double[] column(FlightDataBranch b, FlightDataType type) {
		List<Double> l = b.get(type);
		double[] out = new double[l == null ? 0 : l.size()];
		for (int i = 0; i < out.length; i++) {
			Double v = l.get(i);
			out[i] = v == null ? Double.NaN : v;
		}
		return out;
	}

	/** Apogee, rail exit, minimum stability and landing distance per stage at 0 / 10 / 20 / 30 km/h (to the rule maximum). */
	public static List<Map<String, Object>> windTable(Designs.Design d, Simulation sim, Standards std) {
		double max = std.rule("maxGroundWind.value", Dim.VELOCITY);
		if (Double.isNaN(max)) {
			max = 30 / 3.6;
		}
		List<Double> winds = new ArrayList<>();
		for (int i = 0; i <= 3; i++) {
			winds.add(max * i / 3);
		}
		List<Variants.Run> runs = Variants.windCases(sim, d.doc, winds);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < runs.size(); i++) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("wind", Units.fmt(winds.get(i), Dim.VELOCITY));
			var run = runs.get(i);
			if (!run.ok()) {
				r.put("result", "failed: " + run.error());
			} else {
				r.putAll(Sims.flightMetrics(run.sim().getSimulatedData()));
			}
			rows.add(r);
		}
		return rows;
	}

	/**
	 * Writes report.md, the two stability plots (SVG) and flight-data.csv into {@code dir}. Returns written files.
	 */
	@SuppressWarnings("unchecked")
	public static List<Path> write(Designs.Design d, Simulation sim, Simulation windCase, Standards std, double pinStrength,
			String pinName, String title, Path dir) throws IOException {
		Files.createDirectories(dir);
		List<Path> files = new ArrayList<>();
		FlightData data = sim.getSimulatedData();
		FlightDataBranch main = data.getBranch(0);
		Map<String, Object> summary = Sims.summarize(sim);
		Map<String, Object> checks = Requirements.check(sim, windCase, std).render(std.rulesName());
		Map<String, Object> recovery = Recovery.analyze(sim, std, pinStrength, pinName, Double.NaN);
		var fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());

		double rail = main.getFirstEvent(FlightEvent.Type.LAUNCHROD) == null ? Double.NaN
				: main.getFirstEvent(FlightEvent.Type.LAUNCHROD).getTime();
		double apogee = main.getFirstEvent(FlightEvent.Type.APOGEE) == null ? Double.NaN
				: main.getFirstEvent(FlightEvent.Type.APOGEE).getTime();
		double minRule = std.rule(Analysis.hasDiameterChange(fc) ? "stability.minCalibersWithDiameterChange" : "stability.minCalibers",
				Dim.DIMENSIONLESS);
		String ref = std.ruleRef("stability");
		if (!Double.isNaN(rail)) {
			Path p = dir.resolve("stability-to-rail-exit.svg");
			Files.writeString(p, stabilityPlot(sim, main, 0, rail * 1.05, "Stability until rail departure", minRule, ref));
			files.add(p);
		}
		if (!Double.isNaN(apogee)) {
			Path p = dir.resolve("stability-ascent.svg");
			Files.writeString(p, stabilityPlot(sim, main, Double.isNaN(rail) ? 0 : rail, apogee, "Stability during ascent", minRule, ref));
			files.add(p);
		}
		files.add(csv(sim, null, List.of("altitude", "velocitytotal", "velocityz", "accelerationtotal", "machnumber",
				"stability", "cglocation", "cplocation", "thrustforce", "dragforce", "mass", "aoa", "airdensity"),
				dir.resolve("flight-data.csv")));

		StringBuilder md = new StringBuilder();
		md.append("# ").append(title == null ? d.name() + " design review" : title).append("\n\n");
		md.append("| | |\n|---|---|\n");
		md.append("| Design | ").append(d.name()).append(d.path == null ? "" : " (`" + d.path.getFileName() + "`)").append(" |\n");
		md.append("| Flight configuration | ").append(cell(summary.get("flightConfiguration"))).append(" |\n");
		md.append("| Simulation | ").append(cell(summary.get("simulation"))).append(" |\n");
		md.append("| Rule set | ").append(std.rulesName()).append(" |\n");
		md.append("| Team standards | ").append(std.source() == null ? "built-in defaults" : std.source().getFileName()).append(" |\n");
		md.append("| Generated | ").append(LocalDate.now()).append(" by openrocket-mcp (OpenRocket " + Versions.openRocket() + ") |\n\n");
		for (String p : Sims.problems(sim)) {
			md.append("> **Not a sound flight:** ").append(p).append("\n");
		}
		if (!Sims.problems(sim).isEmpty()) {
			md.append("\n");
		}

		md.append("## 1. Requirement checks\n\n").append(checks.get("summary")).append("\n\n");
		md.append(table((List<Map<String, Object>>) checks.get("checks"))).append('\n');

		Path drawing = dir.resolve("rocket.svg");
		Files.writeString(drawing, Drawing.svg(fc, Drawing.title(d.name(), fc)));
		files.add(drawing);
		md.append("## 2. Vehicle\n\n![rocket.svg](rocket.svg)\n\n### Stability by stage stack (static, Mach 0.3)\n\n");
		md.append(table(Analysis.stageStacks(fc, 0.3))).append('\n');
		List<Map<String, Object>> motors = Analysis.motors(fc);
		if (!motors.isEmpty()) {
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Map<String, Object> m : motors) {
				Map<String, Object> r = new LinkedHashMap<>();
				Map<String, Object> motor = (Map<String, Object>) m.get("motor");
				r.put("stage", m.get("stage"));
				r.put("mount", m.get("mount"));
				r.put("motor", motor.get("manufacturer") + " " + motor.get("designation"));
				r.put("class", motor.get("impulseClass"));
				r.put("total impulse", motor.get("totalImpulse"));
				r.put("avg thrust", motor.get("averageThrust"));
				r.put("burn time", motor.get("burnTime"));
				r.put("ignition", m.get("ignition"));
				rows.add(r);
			}
			md.append("### Motors\n\n").append(table(rows)).append('\n');
		}

		double topMach = Math.max(0.5, data.getMaxMachNumber() * 1.1);
		List<Map<String, Object>> aero = new ArrayList<>();
		for (double m : new double[] { 0.1, 0.3, 0.5, 0.7, 0.9, 1.0, 1.1, 1.3, 1.6, 2.0, 2.5, 3.0 }) {
			if (m <= topMach) {
				aero.add(Aero.render(Aero.sweep(fc, new double[] { m }).get(0)));
			}
		}
		md.append("### Aerodynamics vs Mach (OpenRocket, zero AoA)\n\n").append(table(aero)).append('\n');
		md.append("## 3. Flight simulation\n\n### Conditions\n\n").append(kv((Map<String, Object>) summary.get("conditions")));
		md.append("\n### Wind model\n\n").append(kv(Winds.describe(sim.getOptions())));
		md.append("\n### Results\n\n").append(kv((Map<String, Object>) summary.get("flight"))).append('\n');
		Path profile = dir.resolve("flight-profile.svg");
		Files.writeString(profile, profileSvg(sim));
		md.append("![flight-profile.svg](flight-profile.svg)\n\n");
		List<Map<String, Object>> ign = (List<Map<String, Object>>) summary.get("ignitions");
		if (ign.size() > 1) {
			md.append("### Ignitions\n\n").append(table(ign)).append('\n');
		}
		List<Map<String, Object>> branches = new ArrayList<>();
		for (Map<String, Object> b : (List<Map<String, Object>>) summary.get("branches")) {
			Map<String, Object> r = new LinkedHashMap<>(b);
			r.remove("events");
			branches.add(r);
		}
		md.append("### Stages\n\n").append(table(branches)).append('\n');
		md.append("### Wind sensitivity (flight card)\n\n").append(table(windTable(d, sim, std))).append('\n');

		md.append("## 4. Stability vs time (DTEG R10.3.2)\n\n");
		for (Path p : files) {
			if (p.getFileName().toString().endsWith(".svg")) {
				md.append("![").append(p.getFileName()).append("](").append(p.getFileName()).append(")\n\n");
			}
		}
		md.append("\\* OpenRocket does not compute stability while the rocket is on the rail. There the plot shows the static "
				+ "margin at zero angle of attack: (Barrowman CP at the instant's Mach - simulated CG) / reference diameter. At rail "
				+ "exit the simulation's value includes the angle of attack from wind, so the curve can step.\n\n");
		md.append("Data: `flight-data.csv`.\n\n");

		files.add(profile);
		md.append("## 5. Recovery\n\n");
		List<Map<String, Object>> deps = new ArrayList<>();
		for (Map<String, Object> dep : (List<Map<String, Object>>) recovery.get("deployments")) {
			Map<String, Object> r = new LinkedHashMap<>(dep);
			r.remove("deviceId");
			r.remove("time");
			deps.add(r);
		}
		md.append(table(deps)).append('\n');
		for (String n : (List<String>) recovery.get("notes")) {
			md.append("- ").append(n).append('\n');
		}

		md.append('\n').append(ElectronicsSummary.markdown(ElectronicsSummary.of(d, sim, std), "## 6. Electronics"));
		md.append("Sensor ranges, power and radio against this flight use the team's parts in electronics (standards); "
				+ "sensor_data writes simulated sensor logs for testing flight software.\n");

		md.append("\n## 7. Methods and assumptions\n\n");
		md.append("- Flight: OpenRocket " + Versions.openRocket() + " six-degree-of-freedom simulation, Barrowman aerodynamics. ");
		md.append("Stability margin = (CP - CG) / maximum body diameter.\n");
		md.append("- Opening load: Knacke (NWC TP 6575) infinite-mass F = Cx q CdA with Cx = ")
				.append(Units.num(std.q("recovery.openingForceCoefficient", Dim.DIMENSIONLESS, 1.4)))
				.append("; finite-mass inflation with fill constant n = ")
				.append(Units.num(std.q("recovery.canopyFillConstant", Dim.DIMENSIONLESS, 4)))
				.append(" and exponent j = ")
				.append(Units.num(std.q("recovery.inflationExponent", Dim.DIMENSIONLESS, 1)))
				.append(" (assumed; calibrate with test data). Design load is never below steady-descent drag.\n");
		md.append("- Shear pins: n = ceil(F SF / F_pin), SF = ")
				.append(Units.num(std.q("recovery.shearPinHoldSafetyFactor", Dim.DIMENSIONLESS, 2)))
				.append(pinName == null ? "" : ", pin: " + pinName).append(".\n");
		md.append("- Fin flutter: NACA TN 4197 screen with K = ")
				.append(Units.num(std.q("structures.flutterConstant", Dim.DIMENSIONLESS, 2.674)))
				.append(" (Peak of Flight #615 correction), shear modulus from structures.shearModulus; required margin ")
				.append(Units.num(std.q("structures.flutterMinMargin", Dim.DIMENSIONLESS, 1.5)))
				.append(" (team standard).\n");
		md.append("- Values labelled simulated come from OpenRocket; loads and pins are calculated; Cx, n, j and safety factors ");
		md.append("are team assumptions. Estimates do not replace ground tests, RSO review or mentors.\n");
		Path report = dir.resolve("report.md");
		Files.writeString(report, md.toString(), StandardCharsets.UTF_8);
		files.add(0, report);
		return files;
	}
}
