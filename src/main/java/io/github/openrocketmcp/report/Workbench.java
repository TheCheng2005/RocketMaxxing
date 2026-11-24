package io.github.openrocketmcp.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Branch;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.FlightTrack;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Data for the interactive design view (the "workbench"): the rocket as per-part triangle meshes with each part's
 * properties, the simulated flight as plot-ready series in the team's units, the 3-D flight path, events and the rule
 * checks. The same view runs inside Claude (MCP Apps, {@code ui://openrocket/workbench.html}) and as a standalone
 * HTML file with this data embedded.
 */
public final class Workbench {
	public static final String URI = "ui://openrocket/workbench.html";
	/** Points per flight series: enough for smooth curves and hover readouts, small enough to send quickly. */
	static final int MAX_POINTS = 1200;
	static final int SEGMENTS = 36;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static volatile String template;

	private Workbench() {
	}

	/** One plotted quantity: its flight-data column, label and dimension (null = no unit conversion). */
	record Quantity(String key, String label, FlightDataType type, Dim dim) {
	}

	/** Plots in the Flight tab, one measure per chart (never two y-scales on one plot). */
	static final List<List<Quantity>> CHARTS = List.of(
			List.of(new Quantity("altitude", "Altitude", FlightDataType.TYPE_ALTITUDE, Dim.DISTANCE)),
			List.of(new Quantity("speed", "Total speed", FlightDataType.TYPE_VELOCITY_TOTAL, Dim.VELOCITY),
					new Quantity("vz", "Vertical speed", FlightDataType.TYPE_VELOCITY_Z, Dim.VELOCITY)),
			List.of(new Quantity("acc", "Acceleration", FlightDataType.TYPE_ACCELERATION_TOTAL, Dim.ACCELERATION)),
			List.of(new Quantity("mach", "Mach number", FlightDataType.TYPE_MACH_NUMBER, null)),
			List.of(new Quantity("stability", "Stability margin", FlightDataType.TYPE_STABILITY, null)),
			List.of(new Quantity("thrust", "Thrust", FlightDataType.TYPE_THRUST_FORCE, Dim.FORCE),
					new Quantity("drag", "Drag", FlightDataType.TYPE_DRAG_FORCE, Dim.FORCE)),
			List.of(new Quantity("aoa", "Angle of attack", FlightDataType.TYPE_AOA, Dim.ANGLE)),
			List.of(new Quantity("mass", "Mass", FlightDataType.TYPE_MASS, Dim.MASS)));

	/**
	 * Everything the view shows for one simulation (already flown). {@code checks} is a rendered rule report, or null.
	 */
	public static Map<String, Object> data(String designName, String designId, Simulation sim, Map<String, Object> checks,
			Standards std) {
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		FlightData data = sim.getSimulatedData();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("v", 1);
		Map<String, Object> design = new LinkedHashMap<>();
		design.put("name", designName);
		design.put("designId", designId);
		design.put("simulation", sim.getName());
		design.put("configuration", fc.getName());
		design.put("ruleSet", std.rulesName());
		out.put("design", design);
		out.put("units", units());
		FlightTrack.Flight track = FlightTrack.of(sim);
		FlightAnimation anim = scene(track, fc, sim.getOptions().getLaunchRodLength(), designName);
		out.put("rocket", rocket(fc, anim));
		out.put("metrics", metrics(sim, fc, std));
		out.put("flight", flight(sim, track, anim));
		if (checks != null) {
			out.put("checks", checks);
		}
		List<String> problems = Sims.problems(sim);
		if (!problems.isEmpty()) {
			out.put("problems", problems);
		}
		return out;
	}

	static Map<String, String> units() {
		Map<String, String> u = new LinkedHashMap<>();
		for (Dim d : new Dim[] { Dim.LENGTH, Dim.DISTANCE, Dim.VELOCITY, Dim.ACCELERATION, Dim.MASS, Dim.FORCE, Dim.ANGLE,
				Dim.TIME }) {
			u.put(d.name().toLowerCase(java.util.Locale.ROOT), Units.plotUnit(d));
		}
		return u;
	}

	static double show(double si, Dim d) {
		return d == null || Double.isNaN(si) ? si : Units.fromSi(si, Units.plotUnit(d));
	}

	// ------------------------------------------------------------------------------------------- rocket

	/** The flight animation's scene (pad, pacing, sections, stages), or null if it cannot be staged. */
	static FlightAnimation scene(FlightTrack.Flight f, FlightConfiguration fc, double railLength, String name) {
		try {
			return new FlightAnimation(f, fc, railLength, new FlightAnimation.Options(480, 5, 25, name, ""));
		} catch (RuntimeException e) {
			return null; // the view replays the flight without the animation's staging
		}
	}

	/** The parts of a configuration as the view draws them (meshes, colours, exploded layout), without a flight. */
	public static Map<String, Object> rocket(FlightConfiguration fc) {
		return rocket(fc, null);
	}

	static Map<String, Object> rocket(FlightConfiguration fc, FlightAnimation anim) {
		Map<String, Object> r = new LinkedHashMap<>();
		Analysis.Stability st = Analysis.stability(fc, 0.3);
		r.put("length", fc.getLength());
		r.put("diameter", st.referenceDiameter());
		r.put("cgLaunch", st.cgLaunchX());
		r.put("cgBurnout", st.cgBurnoutX());
		r.put("cp", st.cpX());
		r.put("marginLaunch", st.marginCalibers());
		r.put("marginBurnout", st.burnoutMarginCalibers());
		r.put("launchMass", Units.fmt(st.launchMass(), Dim.MASS));
		r.put("lengthText", Units.fmt(fc.getLength(), Dim.LENGTH));
		r.put("diameterText", Units.fmt(st.referenceDiameter(), Dim.LENGTH));
		List<Map<String, Object>> parts = new ArrayList<>();
		int i = 0;
		List<Model3d.Part> built = Model3d.build(fc, SEGMENTS);
		Map<Integer, Double> pieceStart = new HashMap<>();
		for (Model3d.Part p : built) {
			if (!p.internal && !p.kind.equals("fins")) {
				pieceStart.merge(p.piece, p.x0, Math::min);
			}
		}
		double[] layout = explodedLayout(built, fc.getLength());
		r.put("finOut", layout[0]);
		for (Model3d.Part p : built) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("i", i++);
			m.put("name", p.name);
			m.put("kind", p.kind);
			m.put("color", String.format("#%06x", p.rgb()));
			m.put("internal", p.internal);
			m.put("piece", p.piece);
			m.put("stage", p.stageName);
			m.put("stageNo", p.stage);
			if (anim != null) {
				m.put("section", anim.sectionOf(p, pieceStart));
			}
			m.put("x0", p.x0);
			m.put("x1", p.x1);
			m.put("depth", p.depth);
			m.put("cutOpen", p.section); // opened by a cut-away
			if (!p.fins.isEmpty()) {
				List<List<Double>> dirs = new ArrayList<>();
				for (double[] d : p.finDirs) {
					dirs.add(List.of(round(d[1]), round(d[2])));
				}
				m.put("plateDirs", dirs); // each fin's outward direction (y, z); its triangles follow in that order
			}
			m.put("explode", List.of(round(p.shift[0]), round(p.shift[1]), round(p.shift[2])));
			m.put("mass", Units.fmt(p.mass, Dim.MASS));
			if (p.component != null) {
				m.put("id", Components.shortId(p.component));
				m.put("type", p.component.getClass().getSimpleName());
				Map<String, Object> props = new LinkedHashMap<>(Components.describe(p.component));
				props.keySet().removeIf(k -> k.startsWith("displayOrder")); // OpenRocket's own GUI bookkeeping
				m.put("props", props);
			} else {
				m.put("type", "Motor");
				m.put("props", motorProps(fc, p.name));
			}
			m.put("mesh", mesh(p.tris));
			if (!p.open.isEmpty()) {
				m.put("openMesh", mesh(p.open)); // a parachute open, for the exploded view
			}
			parts.add(m);
		}
		r.put("parts", parts);
		return r;
	}

	/**
	 * The exploded view's layout, as render_3d draws it: airframe pieces pulled apart along the axis, internal parts laid
	 * out below their piece in lanes. Sets each part's offset at full explosion; returns {how far the fins slide out}.
	 */
	static double[] explodedLayout(List<Model3d.Part> parts, double length) {
		double maxR = 1e-3;
		int pieces = 0, stages = 0;
		for (Model3d.Part p : parts) {
			if (!p.internal) {
				maxR = Math.max(maxR, p.rMax);
				pieces = Math.max(pieces, p.piece + 1);
			}
			stages = Math.max(stages, p.axialStage);
		}
		length = Math.max(1e-3, length);
		double gap = Math.max(3 * maxR, 0.06 * length);
		// Room between laid-out parts as on a 1400 px wide picture.
		double metresPerPixel = (length + (pieces + View3d.STAGE_GAP * stages) * gap) / 1300;
		View3d.layOut(parts, gap, maxR, metresPerPixel, new ArrayList<>());
		return new double[] { 1.2 * maxR };
	}

	private static Map<String, Object> motorProps(FlightConfiguration fc, String designation) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (var mc : fc.getActiveMotors()) {
			Motor mo = mc.getMotor();
			if (mo == null || !mo.getDesignation().equals(designation)) {
				continue;
			}
			m.put("designation", mo.getDesignation());
			m.put("manufacturer", mo instanceof info.openrocket.core.motor.ThrustCurveMotor t ? t.getManufacturer().getDisplayName() : "");
			m.put("totalImpulse", Units.fmt(mo.getTotalImpulseEstimate(), Dim.IMPULSE));
			m.put("averageThrust", Units.fmt(mo.getAverageThrustEstimate(), Dim.FORCE));
			m.put("maxThrust", Units.fmt(mo.getMaxThrustEstimate(), Dim.FORCE));
			m.put("burnTime", Units.fmt(mo.getBurnTimeEstimate(), Dim.TIME));
			m.put("launchMass", Units.fmt(mo.getLaunchMass(), Dim.MASS));
			m.put("burnoutMass", Units.fmt(mo.getBurnoutMass(), Dim.MASS));
			m.put("diameter", Units.fmt(mo.getDiameter(), Dim.LENGTH));
			m.put("length", Units.fmt(mo.getLength(), Dim.LENGTH));
			m.put("ejectionDelay", Units.fmt(mc.getEjectionDelay(), Dim.TIME));
			break;
		}
		return m;
	}

	/** Triangles (9 floats each, rocket frame, m) as base64 little-endian float32. */
	static String mesh(List<double[]> tris) {
		ByteBuffer b = ByteBuffer.allocate(tris.size() * 36).order(ByteOrder.LITTLE_ENDIAN);
		for (double[] t : tris) {
			for (int k = 0; k < 9; k++) {
				b.putFloat((float) t[k]);
			}
		}
		return Base64.getEncoder().encodeToString(b.array());
	}

	// ------------------------------------------------------------------------------------------- metrics

	static List<Map<String, Object>> metrics(Simulation sim, FlightConfiguration fc, Standards std) {
		FlightData data = sim.getSimulatedData();
		FlightDataBranch b = data.getBranch(0);
		List<Map<String, Object>> m = new ArrayList<>();
		metric(m, "apogee", "Apogee", data.getMaxAltitude(), Dim.DISTANCE, null);
		metric(m, "speed", "Max speed", data.getMaxVelocity(), Dim.VELOCITY, "Mach " + Units.num(data.getMaxMachNumber()));
		metric(m, "rail", "Rail exit", data.getLaunchRodVelocity(), Dim.VELOCITY, null);
		Sims.Window w = Sims.ascentStability(b);
		if (w != null) {
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("key", "stability");
			s.put("label", "Stability (ascent)");
			s.put("text", String.format(java.util.Locale.ROOT, "%.2f–%.2f cal", w.min(), w.max()));
			s.put("min", w.min());
			s.put("max", w.max());
			double floor = Requirements.stabilityFloor(std, fc)[0];
			if (floor > 0) {
				s.put("floor", floor);
			}
			m.add(s);
		}
		metric(m, "acc", "Max accel.", data.getMaxAcceleration(), Dim.ACCELERATION,
				String.format(java.util.Locale.ROOT, "%.1f G", data.getMaxAcceleration() / 9.80665));
		for (Sims.Deployment d : Sims.deployments(sim)) {
			if (d.branch().equals(b.getName()) && !Double.isNaN(d.steadyDescentRate())) {
				String dev = d.device().getName();
				metric(m, "descent-" + dev, dev + " descent", d.steadyDescentRate(), Dim.VELOCITY, null);
			}
		}
		double land = Branch.of(b).last(FlightDataType.TYPE_POSITION_XY);
		metric(m, "landing", "Landing", land, Dim.DISTANCE, "from the pad");
		return m;
	}

	private static void metric(List<Map<String, Object>> m, String key, String label, double si, Dim d, String sub) {
		if (Double.isNaN(si)) {
			return;
		}
		Map<String, Object> x = new LinkedHashMap<>();
		x.put("key", key);
		x.put("label", label);
		x.put("value", round(show(si, d)));
		x.put("unit", Units.plotUnit(d));
		if (sub != null) {
			x.put("sub", sub);
		}
		m.add(x);
	}

	// ------------------------------------------------------------------------------------------- flight

	static Map<String, Object> flight(Simulation sim, FlightTrack.Flight f, FlightAnimation anim) {
		FlightData data = sim.getSimulatedData();
		FlightDataBranch b0 = data.getBranch(0);
		Branch br = Branch.of(b0);
		int[] idx = sample(br, f);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("t", pick(br.time, idx, null));
		List<Map<String, Object>> charts = new ArrayList<>();
		Map<String, Object> series = new LinkedHashMap<>();
		for (List<Quantity> chart : CHARTS) {
			List<String> keys = new ArrayList<>();
			Quantity first = chart.get(0);
			for (Quantity q : chart) {
				double[] col = br.col(q.type());
				if (!anyValue(col)) {
					continue;
				}
				if (q.type() == FlightDataType.TYPE_AOA) {
					col = afterRail(br, b0, col);
				}
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("label", q.label());
				s.put("values", pick(col, idx, q.dim()));
				series.put(q.key(), s);
				keys.add(q.key());
			}
			if (keys.isEmpty()) {
				continue;
			}
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("title", chart.size() == 1 ? first.label() : first.dim() == Dim.FORCE ? "Thrust and drag" : "Speed");
			c.put("unit", first.dim() == null ? (first.key().equals("stability") ? "cal" : "") : Units.plotUnit(first.dim()));
			c.put("series", keys);
			charts.add(c);
		}
		out.put("series", series);
		out.put("charts", charts);
		// For the 3-D view: CG and CP from the nose tip (m) and the flight path in the world frame (m, rad).
		out.put("cg", pick(br.col(FlightDataType.TYPE_CG_LOCATION), idx, null));
		out.put("cp", pick(br.col(FlightDataType.TYPE_CP_LOCATION), idx, null));
		FlightTrack.Track m = f.main();
		Map<String, Object> path = new LinkedHashMap<>();
		path.put("east", pick(m.east, idx, null));
		path.put("north", pick(m.north, idx, null));
		path.put("up", pick(m.alt, idx, null));
		path.put("theta", pick(m.theta, idx, null));
		path.put("phi", pick(m.phi, idx, null));
		path.put("roll", pick(m.roll, idx, null));
		out.put("path", path);
		List<Map<String, Object>> stages = new ArrayList<>();
		for (int k = 1; k < f.tracks().size(); k++) {
			FlightTrack.Track t = f.tracks().get(k);
			int[] j = stride(t.t.length, 400);
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("name", t.name);
			s.put("separation", t.separation);
			s.put("t", pick(t.t, j, null));
			s.put("east", pick(t.east, j, null));
			s.put("north", pick(t.north, j, null));
			s.put("up", pick(t.alt, j, null));
			s.put("theta", pick(t.theta, j, null));
			s.put("phi", pick(t.phi, j, null));
			s.put("roll", pick(t.roll, j, null));
			s.put("cg", pick(t.cg, j, null));
			s.put("track", k);
			stages.add(s);
		}
		out.put("stages", stages);
		if (anim != null) {
			out.put("anim", anim.plan());
		}
		List<Map<String, Object>> events = new ArrayList<>();
		for (FlightTrack.Event e : f.events()) {
			Map<String, Object> ev = new LinkedHashMap<>();
			ev.put("t", round(e.time()));
			ev.put("kind", e.kind());
			ev.put("title", e.title());
			if (e.detail() != null && !e.detail().isBlank()) {
				ev.put("detail", e.detail());
			}
			if (e.device() != null) {
				ev.put("device", e.device());
				ev.put("size", e.size());
				ev.put("streamer", e.streamer());
			}
			ev.put("branch", e.branch());
			events.add(ev);
		}
		out.put("events", events);
		Map<String, Object> wind = new LinkedHashMap<>();
		wind.put("speed", round(show(m.at(m.windSpeed, 0), Dim.VELOCITY)));
		wind.put("direction", round(Math.toDegrees(m.at(m.windDir, 0))));
		out.put("wind", wind);
		return out;
	}

	/** The column with the points before rail clearance blanked (angle of attack has no meaning at ~0 airspeed). */
	static double[] afterRail(Branch br, FlightDataBranch b, double[] col) {
		FlightEvent rail = b.getFirstEvent(FlightEvent.Type.LAUNCHROD);
		if (rail == null) {
			return col;
		}
		double[] out = col.clone();
		for (int i = 0; i < out.length && i < br.time.length && br.time[i] < rail.getTime(); i++) {
			out[i] = Double.NaN;
		}
		return out;
	}

	/**
	 * Indices to send: an even stride through the points, plus each event and the peaks of the plotted quantities,
	 * so maxima and event times survive the thinning.
	 */
	static int[] sample(Branch br, FlightTrack.Flight f) {
		int n = br.time.length;
		TreeSet<Integer> keep = new TreeSet<>();
		for (int i : stride(n, MAX_POINTS)) {
			keep.add(i);
		}
		for (FlightTrack.Event e : f.events()) {
			int i = br.index(e.time());
			if (i >= 0) {
				keep.add(i);
				keep.add(Math.min(n - 1, i + 1));
			}
		}
		for (List<Quantity> chart : CHARTS) {
			for (Quantity q : chart) {
				double[] col = br.col(q.type());
				int hi = -1, lo = -1;
				for (int i = 0; i < col.length; i++) {
					if (Double.isNaN(col[i])) {
						continue;
					}
					if (hi < 0 || col[i] > col[hi]) {
						hi = i;
					}
					if (lo < 0 || col[i] < col[lo]) {
						lo = i;
					}
				}
				if (hi >= 0) {
					keep.add(hi);
					keep.add(lo);
				}
			}
		}
		return keep.stream().mapToInt(Integer::intValue).toArray();
	}

	static int[] stride(int n, int max) {
		if (n <= max) {
			int[] all = new int[n];
			for (int i = 0; i < n; i++) {
				all[i] = i;
			}
			return all;
		}
		int[] out = new int[max];
		for (int k = 0; k < max; k++) {
			out[k] = (int) Math.round((double) k * (n - 1) / (max - 1));
		}
		return out;
	}

	private static boolean anyValue(double[] col) {
		for (double v : col) {
			if (!Double.isNaN(v) && v != 0) {
				return true;
			}
		}
		return false;
	}

	/** The chosen samples in display units, 5 significant digits; null where OpenRocket has no value. */
	static List<Double> pick(double[] col, int[] idx, Dim d) {
		List<Double> out = new ArrayList<>(idx.length);
		for (int i : idx) {
			double v = i < col.length ? col[i] : Double.NaN;
			out.add(Double.isFinite(v) ? round(show(v, d)) : null);
		}
		return out;
	}

	static double round(double v) {
		if (!Double.isFinite(v) || v == 0) {
			return v;
		}
		double scale = Math.pow(10, 4 - (int) Math.floor(Math.log10(Math.abs(v))));
		return Math.round(v * scale) / scale;
	}

	// ------------------------------------------------------------------------------------------- HTML

	/** The view's HTML (the MCP Apps resource): data arrives from the host. */
	public static String html() {
		String t = template;
		if (t == null) {
			try (InputStream in = Workbench.class.getResourceAsStream("/ui/workbench.html")) {
				if (in == null) {
					throw new IllegalStateException("ui/workbench.html is missing from the build");
				}
				t = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			template = t;
		}
		return t;
	}

	/** A standalone copy with the data embedded, to open in any browser or send to the team. */
	public static String standalone(Map<String, Object> data) {
		// "</" cannot appear inside a script element; the JSON parser reads "<\/" as "</".
		String json = GSON.toJson(data).replace("</", "<\\/");
		String tag = "<script type=\"application/json\" id=\"or-data\">" + json + "</script>";
		String page = html();
		int at = page.indexOf("<!--OR-DATA-->");
		if (at < 0) {
			throw new IllegalStateException("ui/workbench.html has no <!--OR-DATA--> marker");
		}
		return page.substring(0, at) + tag + page.substring(at + "<!--OR-DATA-->".length());
	}
}
