package io.github.openrocketmcp.or;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.models.wind.WindModel;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.report.Reports;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Design diff for reviews: two revisions of a design (two open designs, a design and another .ork, or a design and
 * an earlier git revision of its file) compared on the numbers a review board asks about - mass, CG, CP, stability,
 * motors, simulated flight in the same conditions, rule-check results - plus the component edits between them.
 */
public final class Diff {
	private Diff() {
	}

	/** Loads an .ork without registering it as an open design (a revision to compare against). */
	public static Designs.Design load(Path path, String label) throws Exception {
		OrRuntime.init();
		Path p = path.toAbsolutePath().normalize();
		OpenRocketDocument doc = Designs.read(p);
		for (FlightConfiguration fc : doc.getRocket().getFlightConfigurations()) {
			Analysis.settle(fc);
		}
		return new Designs.Design("revision", doc, p, label);
	}

	/** Loads the design's file as it was at a git revision (commit, tag, branch, HEAD~1...). */
	public static Designs.Design loadRevision(Designs.Design d, String revision) throws Exception {
		if (d.path == null) {
			throw new ToolException("This design has no file, so it has no git history; save it first or compare with a path.");
		}
		if (!revision.matches("[A-Za-z0-9._/@{}~^:-]+") || revision.startsWith("-")) {
			throw new ToolException("Not a git revision: " + revision);
		}
		Path dir = d.path.getParent();
		Path tmp = Files.createTempFile("openrocket-revision-", ".ork");
		tmp.toFile().deleteOnExit();
		Process p = new ProcessBuilder("git", "-C", dir.toString(), "show", revision + ":./" + d.path.getFileName())
				.redirectOutput(tmp.toFile()).redirectError(ProcessBuilder.Redirect.PIPE).start();
		String err = new String(p.getErrorStream().readAllBytes()).trim();
		if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
			throw new ToolException("git could not read " + d.path.getFileName() + " at " + revision + ": "
					+ (err.isEmpty() ? "is git installed and the file committed?" : err));
		}
		Designs.Design r = load(tmp, "git:" + revision);
		return r;
	}

	/** One compared quantity (SI values). */
	record Row(String group, String quantity, double a, double b, Dim dim, String textA, String textB) {
		static Row num(String group, String q, double a, double b, Dim dim) {
			return new Row(group, q, a, b, dim, null, null);
		}

		static Row text(String group, String q, String a, String b) {
			return new Row(group, q, Double.NaN, Double.NaN, null, a, b);
		}

		boolean changed() {
			if (dim == null) {
				return !Objects.equals(textA, textB);
			}
			if (Double.isNaN(a) != Double.isNaN(b)) {
				return true;
			}
			return !Double.isNaN(a) && !Units.fmt(a, dim).equals(Units.fmt(b, dim));
		}

		Map<String, Object> render(String labelA, String labelB) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("quantity", quantity);
			if (dim == null) {
				m.put(labelA, textA == null ? "-" : textA);
				m.put(labelB, textB == null ? "-" : textB);
				m.put("change", changed() ? "changed" : "");
				return m;
			}
			m.put(labelA, show(a));
			m.put(labelB, show(b));
			if (Double.isNaN(a) || Double.isNaN(b) || !changed()) {
				m.put("change", "");
			} else {
				String delta = (b >= a ? "+" : "-") + show(Math.abs(b - a));
				if (dim == Dim.DIMENSIONLESS || Math.abs(a) < 1e-9) {
					m.put("change", delta);
				} else {
					m.put("change", delta + " (" + (b >= a ? "+" : "") + Units.num(100 * (b - a) / Math.abs(a)) + "%)");
				}
			}
			return m;
		}

		private String show(double v) {
			if (Double.isNaN(v)) {
				return "-";
			}
			return dim == Dim.DIMENSIONLESS ? Units.num(v) + (quantity.contains("stability") ? " cal" : "") : Units.fmt(v, dim);
		}
	}

	/** The design's flight configuration and a simulation of it (not added to the document). */
	static Simulation simulation(Designs.Design d, String simRef, String configRef, Standards std) {
		Simulation s = Sims.prepare(d, simRef, configRef, Sims.Overrides.none(), std, false);
		return s.copy();
	}

	public static Map<String, Object> compare(Designs.Design da, Designs.Design db, String labelA, String labelB, String simRef,
			String configRef, boolean sameConditions, Standards std, Path markdown) throws Exception {
		// B is the design under review: its simulation and conditions are the reference.
		Simulation sb = simulation(db, simRef, configRef, std);
		Simulation sa;
		try {
			sa = simulation(da, simRef, configRef, std);
		} catch (ToolException e) {
			// The baseline may not have the same simulation / configuration names: fall back to its default.
			sa = simulation(da, null, null, std);
		}
		if (sameConditions) {
			sa.getOptions().copyConditionsFrom(sb.getOptions());
			if (Winds.isMultiLevel(sb.getOptions())) {
				Winds.setProfile(sa.getOptions(), Winds.levels(sb.getOptions()), sb.getOptions().getMultiLevelWindModel()
						.getAltitudeReference() == WindModel.AltitudeReference.AGL);
			}
		}
		List<Variants.Run> runs = Variants.runAll(List.of(sa, sb));
		for (Variants.Run r : runs) {
			if (!r.ok()) {
				throw new ToolException("Simulation failed: " + r.error());
			}
		}
		FlightConfiguration fa = sa.getRocket().getFlightConfiguration(sa.getFlightConfigurationId());
		FlightConfiguration fb = sb.getRocket().getFlightConfiguration(sb.getFlightConfigurationId());
		List<Row> rows = new ArrayList<>();
		vehicle(rows, fa, fb);
		flight(rows, sa, sb);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("a", labelA + " - " + da.name() + " (" + fa.getName() + ")");
		out.put("b", labelB + " - " + db.name() + " (" + fb.getName() + ")");
		out.put("conditions", sameConditions ? "both flown in the conditions of " + labelB + " (" + sb.getName() + ")"
				: "each flown in its own simulation's conditions");
		if (AeroTable.any(db.doc.getRocket()) != null) {
			out.put("aeroTable", "both revisions fly with the imported aero table (" + AeroTable.any(db.doc.getRocket()).source()
					+ "); re-import it for a revision with a different airframe");
		}
		Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
		int changed = 0;
		for (Row r : rows) {
			groups.computeIfAbsent(r.group(), k -> new ArrayList<>()).add(r.render(labelA, labelB));
			if (r.changed()) {
				changed++;
			}
		}
		out.putAll(groups);
		List<Map<String, Object>> rules = rules(sa, sb, std, labelA, labelB);
		out.put("ruleCheckChanges", rules.isEmpty() ? List.of(Map.of("result", "no change in any rule check")) : rules);
		List<Map<String, Object>> comps = components(da.doc.getRocket(), db.doc.getRocket());
		out.put("componentChanges", comps.isEmpty() ? List.of(Map.of("result", "no component changes")) : comps);
		out.put("summary", summary(rows, rules, comps, changed, labelA, labelB));
		if (markdown != null) {
			out.put("file", write(out, markdown, labelA, labelB).toString());
		}
		return out;
	}

	static void vehicle(List<Row> rows, FlightConfiguration fa, FlightConfiguration fb) {
		Analysis.Stability a = Analysis.stability(fa, 0.3), b = Analysis.stability(fb, 0.3);
		String g = "vehicle";
		rows.add(Row.num(g, "length", a.length(), b.length(), Dim.LENGTH));
		rows.add(Row.num(g, "reference diameter", a.referenceDiameter(), b.referenceDiameter(), Dim.LENGTH));
		rows.add(Row.num(g, "launch mass", a.launchMass(), b.launchMass(), Dim.MASS));
		rows.add(Row.num(g, "burnout mass", a.burnoutMass(), b.burnoutMass(), Dim.MASS));
		rows.add(Row.num(g, "CG at launch (from nose tip)", a.cgLaunchX(), b.cgLaunchX(), Dim.LENGTH));
		rows.add(Row.num(g, "CP at Mach 0.3 (from nose tip)", a.cpX(), b.cpX(), Dim.LENGTH));
		rows.add(Row.num(g, "static stability at launch", a.marginCalibers(), b.marginCalibers(), Dim.DIMENSIONLESS));
		rows.add(Row.num(g, "static stability at burnout", a.burnoutMarginCalibers(), b.burnoutMarginCalibers(), Dim.DIMENSIONLESS));
		rows.add(Row.text(g, "motors", motors(fa), motors(fb)));
	}

	static String motors(FlightConfiguration fc) {
		List<String> m = new ArrayList<>();
		for (var mc : fc.getActiveMotors()) {
			if (mc.getMotor() != null) {
				m.add(mc.getMotor().getDesignation() + (mc.getMotorCount() > 1 ? " x" + mc.getMotorCount() : ""));
			}
		}
		return m.isEmpty() ? "none" : String.join(" + ", m);
	}

	static void flight(List<Row> rows, Simulation sa, Simulation sb) {
		FlightData a = sa.getSimulatedData(), b = sb.getSimulatedData();
		String g = "flight";
		rows.add(Row.num(g, "apogee", a.getMaxAltitude(), b.getMaxAltitude(), Dim.DISTANCE));
		rows.add(Row.num(g, "time to apogee", a.getTimeToApogee(), b.getTimeToApogee(), Dim.TIME));
		rows.add(Row.num(g, "max velocity", a.getMaxVelocity(), b.getMaxVelocity(), Dim.VELOCITY));
		rows.add(Row.num(g, "max Mach", a.getMaxMachNumber(), b.getMaxMachNumber(), Dim.DIMENSIONLESS));
		rows.add(Row.num(g, "max acceleration (G)", a.getMaxAcceleration() / Atmosphere.G0, b.getMaxAcceleration() / Atmosphere.G0,
				Dim.DIMENSIONLESS));
		rows.add(Row.num(g, "rail exit velocity", a.getLaunchRodVelocity(), b.getLaunchRodVelocity(), Dim.VELOCITY));
		Sims.Window wa = Sims.ascentStability(a.getBranch(0)), wb = Sims.ascentStability(b.getBranch(0));
		rows.add(Row.num(g, "min stability in flight", wa == null ? Double.NaN : wa.min(), wb == null ? Double.NaN : wb.min(),
				Dim.DIMENSIONLESS));
		rows.add(Row.num(g, "max stability in flight", wa == null ? Double.NaN : wa.max(), wb == null ? Double.NaN : wb.max(),
				Dim.DIMENSIONLESS));
		Map<String, double[]> descent = new LinkedHashMap<>();
		for (Sims.Deployment d : Sims.deployments(sa)) {
			descent.computeIfAbsent(d.device().getName(), k -> new double[] { Double.NaN, Double.NaN })[0] = d.steadyDescentRate();
		}
		for (Sims.Deployment d : Sims.deployments(sb)) {
			descent.computeIfAbsent(d.device().getName(), k -> new double[] { Double.NaN, Double.NaN })[1] = d.steadyDescentRate();
		}
		descent.forEach((k, v) -> rows.add(Row.num(g, "descent rate under " + k, v[0], v[1], Dim.VELOCITY)));
		FlightDataBranch ba = a.getBranch(0), bb = b.getBranch(0);
		rows.add(Row.num(g, "landing velocity (" + ba.getName() + ")", landing(ba, FlightDataType.TYPE_VELOCITY_TOTAL),
				landing(bb, FlightDataType.TYPE_VELOCITY_TOTAL), Dim.VELOCITY));
		rows.add(Row.num(g, "landing distance (" + ba.getName() + ")", landing(ba, FlightDataType.TYPE_POSITION_XY),
				landing(bb, FlightDataType.TYPE_POSITION_XY), Dim.DISTANCE));
	}

	private static double landing(FlightDataBranch b, FlightDataType t) {
		double gh = Sims.eventTime(b, FlightEvent.Type.GROUND_HIT);
		return Double.isNaN(gh) ? Double.NaN : Math.abs(Sims.at(b, t, gh));
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> rules(Simulation sa, Simulation sb, Standards std, String labelA, String labelB) {
		Map<String, Map<String, Object>> ra = byItem((List<Map<String, Object>>) Requirements.check(sa, null, std)
				.render(std.rulesName()).get("checks"));
		Map<String, Map<String, Object>> rb = byItem((List<Map<String, Object>>) Requirements.check(sb, null, std)
				.render(std.rulesName()).get("checks"));
		List<Map<String, Object>> out = new ArrayList<>();
		TreeSet<String> keys = new TreeSet<>(ra.keySet());
		keys.addAll(rb.keySet());
		for (String k : keys) {
			Map<String, Object> x = ra.get(k), y = rb.get(k);
			String sx = x == null ? "-" : (String) x.get("status"), sy = y == null ? "-" : (String) y.get("status");
			if (sx.equals(sy)) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("check", k);
			m.put(labelA, sx + (x == null ? "" : " (" + x.get("value") + ")"));
			m.put(labelB, sy + (y == null ? "" : " (" + y.get("value") + ")"));
			m.put("direction", rank(sy) > rank(sx) ? "worse" : "better");
			out.add(m);
		}
		return out;
	}

	private static Map<String, Map<String, Object>> byItem(List<Map<String, Object>> checks) {
		Map<String, Map<String, Object>> m = new LinkedHashMap<>();
		for (Map<String, Object> c : checks) {
			String k = String.valueOf(c.get("item"));
			int n = 2;
			while (m.containsKey(k)) {
				k = c.get("item") + " #" + n++;
			}
			m.put(k, c);
		}
		return m;
	}

	private static int rank(String status) {
		return switch (status) {
			case "FAIL" -> 3;
			case "WARN" -> 2;
			case "-" -> 1;
			default -> 0;
		};
	}

	/** Component edits: matched by OpenRocket's persistent component id, else by type and name. */
	static List<Map<String, Object>> components(Rocket a, Rocket b) {
		Map<String, RocketComponent> ib = new LinkedHashMap<>();
		Map<String, RocketComponent> nb = new LinkedHashMap<>();
		for (RocketComponent c : b) {
			ib.put(c.getID().toString(), c);
			nb.putIfAbsent(key(c), c);
		}
		List<Map<String, Object>> out = new ArrayList<>();
		Set<RocketComponent> matched = Collections.newSetFromMap(new IdentityHashMap<>());
		for (RocketComponent ca : a) {
			RocketComponent cb = ib.get(ca.getID().toString());
			if (cb == null || cb.getClass() != ca.getClass()) {
				cb = nb.get(key(ca));
			}
			if (cb == null || matched.contains(cb)) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("component", label(ca));
				m.put("change", "removed");
				out.add(m);
				continue;
			}
			matched.add(cb);
			List<String> edits = new ArrayList<>();
			if (!ca.getName().equals(cb.getName())) {
				edits.add("renamed to " + cb.getName());
			}
			if (ca.getParent() != null && cb.getParent() != null && !ca.getParent().getName().equals(cb.getParent().getName())) {
				edits.add("moved from " + ca.getParent().getName() + " to " + cb.getParent().getName());
			}
			Map<String, Object> pa = Components.describe(ca), pb = Components.describe(cb);
			for (Map.Entry<String, Object> e : pa.entrySet()) {
				if (inactiveOverride(e.getKey(), ca, cb)) {
					continue;
				}
				Object vb = pb.get(e.getKey());
				String x = brief(e.getValue()), y = brief(vb);
				if (!Objects.equals(x, y)) {
					edits.add(e.getKey() + " " + x + " -> " + y);
				}
			}
			if (ca instanceof MotorMount ma && cb instanceof MotorMount mb && ma.isMotorMount() != mb.isMotorMount()) {
				edits.add(mb.isMotorMount() ? "now a motor mount" : "no longer a motor mount");
			}
			if (!edits.isEmpty()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("component", label(cb));
				m.put("change", "edited");
				m.put("edits", edits);
				hidden(m, cb);
				out.add(m);
			}
		}
		for (RocketComponent cb : b) {
			if (!matched.contains(cb)) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("component", label(cb));
				m.put("change", "added" + (cb.getParent() == null ? "" : " to " + cb.getParent().getName()));
				m.put("mass", Units.fmt(cb.getMass(), Dim.MASS));
				hidden(m, cb);
				out.add(m);
			}
		}
		return out;
	}

	/** Override values (mass, CG, CD) only matter when that override is switched on in either revision. */
	static boolean inactiveOverride(String property, RocketComponent a, RocketComponent b) {
		return switch (property) {
			case "overrideMass" -> !a.isMassOverridden() && !b.isMassOverridden();
			case "overrideCGX" -> !a.isCGOverridden() && !b.isCGOverridden();
			case "overrideCD" -> !a.isCDOverridden() && !b.isCDOverridden();
			default -> false;
		};
	}

	/** Flags edits whose mass or CG cannot reach the simulation because a parent overrides its subcomponents. */
	private static void hidden(Map<String, Object> m, RocketComponent c) {
		String w = Components.overrideWarning(c);
		if (w != null) {
			m.put("warning", w);
		}
	}

	private static String key(RocketComponent c) {
		return c.getClass().getSimpleName() + ":" + c.getName();
	}

	private static String label(RocketComponent c) {
		return c.getName() + " (" + c.getComponentName() + ")";
	}

	/** Property values without the "(one of ...)" / "(density; set by name ...)" hints. */
	static String brief(Object v) {
		if (v == null) {
			return "-";
		}
		String s = v.toString();
		int i = s.indexOf(" (");
		return i > 0 && (s.contains("one of") || s.contains("set by name")) ? s.substring(0, i) : s;
	}

	static List<String> summary(List<Row> rows, List<Map<String, Object>> rules, List<Map<String, Object>> comps, int changed,
			String labelA, String labelB) {
		List<String> s = new ArrayList<>();
		if (changed == 0 && comps.isEmpty()) {
			s.add("No differences: " + labelB + " matches " + labelA + ".");
			return s;
		}
		for (Row r : rows) {
			if (r.dim() != null && r.changed() && !Double.isNaN(r.a()) && !Double.isNaN(r.b()) && headline(r.quantity())) {
				s.add(r.quantity() + ": " + r.render(labelA, labelB).get(labelA) + " -> " + r.render(labelA, labelB).get(labelB)
						+ " (" + r.render(labelA, labelB).get("change") + ")");
			}
		}
		long worse = rules.stream().filter(m -> "worse".equals(m.get("direction"))).count();
		if (!rules.isEmpty()) {
			s.add(rules.size() + " rule check(s) changed status" + (worse > 0 ? ", " + worse + " got worse" : ", all for the better"));
		}
		if (!comps.isEmpty()) {
			s.add(comps.size() + " component(s) added, removed or edited");
		}
		return s;
	}

	private static boolean headline(String q) {
		return q.equals("launch mass") || q.equals("apogee") || q.equals("static stability at launch")
				|| q.equals("min stability in flight") || q.equals("rail exit velocity") || q.equals("max Mach");
	}

	@SuppressWarnings("unchecked")
	static Path write(Map<String, Object> out, Path file, String labelA, String labelB) throws IOException {
		StringBuilder md = new StringBuilder("# Design changes: ").append(labelA).append(" -> ").append(labelB).append("\n\n");
		md.append("- **").append(labelA).append(":** ").append(out.get("a")).append('\n');
		md.append("- **").append(labelB).append(":** ").append(out.get("b")).append('\n');
		md.append("- **Conditions:** ").append(out.get("conditions")).append("\n\n");
		for (String s : (List<String>) out.get("summary")) {
			md.append("- ").append(s).append('\n');
		}
		md.append("\n## Vehicle\n\n").append(Reports.table((List<Map<String, Object>>) out.get("vehicle")));
		md.append("\n## Flight\n\n").append(Reports.table((List<Map<String, Object>>) out.get("flight")));
		md.append("\n## Rule checks that changed\n\n").append(Reports.table((List<Map<String, Object>>) out.get("ruleCheckChanges")));
		List<Map<String, Object>> comps = new ArrayList<>();
		for (Map<String, Object> c : (List<Map<String, Object>>) out.get("componentChanges")) {
			Map<String, Object> m = new LinkedHashMap<>(c);
			if (m.get("edits") instanceof List<?> l) {
				m.put("edits", String.join("; ", (List<String>) l));
			}
			comps.add(m);
		}
		md.append("\n## Component changes\n\n").append(Reports.table(comps));
		Path p = file.toAbsolutePath();
		if (p.getParent() != null) {
			Files.createDirectories(p.getParent());
		}
		Files.writeString(p, md.toString(), StandardCharsets.UTF_8);
		return p;
	}
}
