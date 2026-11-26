package io.github.openrocketmcp.or;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.ComponentAssembly;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;

import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Load, shear, axial-force and bending-moment diagrams along the rocket at maximum dynamic pressure, by the method of
 * the University of Toronto Aerospace Team's load calculation script (Load_calculations.py in
 * github.com/ArthurJWH/UTAT-Rocketry: Fred Chun, 2021; edited by Nat Espinosa, 2023, Alexander Boldis, 2024, and Nadim
 * Hatoum, 2025 and 2026), which follows Aspire Space's "Rocket vehicle loads and airframe design".
 *
 * <p>The vehicle flies at max q at a set angle of attack (5 degrees by default). Each lifting part (nose and body, fins
 * with their body interference) carries C_N_alpha * alpha * q * S at its centre of pressure. The rocket answers as a rigid
 * body: lateral acceleration = lift / mass, angular acceleration = lift moment about the CG / pitch inertia (each part a
 * slender bar, m L^2 / 12, plus the parallel-axis term). Each part's inertia pushes back with -m a at its CG, plus
 * -I alpha_dot as a concentrated moment; shear is the running sum of these loads from the nose, bending moment its
 * integral. Axial: drag (split over the parts by length) and inertia, with thrust off as in the script (coast) unless
 * asked for.
 *
 * <p>The script reads AeroLab tables and a parts file; here the parts, masses, CGs, lift slopes and centres of pressure
 * come from the design (OpenRocket's Barrowman model) and the flight state from the simulation, unless the team's files
 * are given, in which case they are read as the script reads them.
 */
public final class LoadDiagrams {
	private LoadDiagrams() {
	}

	public static final String CITATION = "Method: UTAT Rocketry load calculation (Load_calculations.py, github.com/ArthurJWH/UTAT-Rocketry), "
			+ "by Fred Chun (2021), edited by Nat Espinosa (2023), Alexander Boldis (2024) and Nadim Hatoum (2025, 2026); after Aspire "
			+ "Space, \"Rocket vehicle loads and airframe design\".";

	static final double G = 9.81;

	/** An airframe part: name, mass (kg), CG and length (m). */
	public record Part(String name, double mass, double x, double length) {
	}

	/** A lifting part: its lift-curve slope (per radian, on the reference area) and centre of pressure (m). */
	public record Lift(String name, double cnAlpha, double x) {
	}

	/** A joint to report: station (m) and name. */
	public record Joint(String name, double x) {
	}

	/** Flight state at max q: density, airspeed, temperature (K), drag (N, NaN: from cd) and thrust (N). */
	public record State(double rho, double v, double temperature, double mach, double drag, double thrust, double thrustX, double time) {
	}

	/** Everything the calculation takes. */
	public record Input(List<Part> parts, List<Lift> lifts, double cd, double referenceArea, double length, State state, double alpha,
			List<Joint> joints) {
	}

	// ------------------------------------------------------------------------------------------------ the method

	/** The diagrams for one input: stations along the rocket with load, shear, moment and axial force. */
	public static Map<String, Object> compute(Input in) {
		State st = in.state;
		double q = 0.5 * st.rho * st.v * st.v;
		double s = in.referenceArea;
		// Lifts at their centres of pressure
		double totalLift = 0, liftMomentArm = 0;
		double[] lift = new double[in.lifts.size()];
		for (int i = 0; i < lift.length; i++) {
			lift[i] = q * s * in.lifts.get(i).cnAlpha * in.alpha;
			totalLift += lift[i];
		}
		double drag = Double.isNaN(st.drag) ? q * s * in.cd : st.drag;
		double mass = 0, mx = 0;
		for (Part p : in.parts) {
			mass += p.mass;
			mx += p.mass * p.x;
		}
		if (mass <= 0) {
			throw new ToolException("The parts have no mass.");
		}
		double xcg = mx / mass;
		double inertia = 0;
		double[] iLocal = new double[in.parts.size()];
		for (int i = 0; i < iLocal.length; i++) {
			Part p = in.parts.get(i);
			iLocal[i] = p.mass * p.length * p.length / 12;
			inertia += iLocal[i] + p.mass * (p.x - xcg) * (p.x - xcg);
		}
		double moment = 0;
		for (int i = 0; i < lift.length; i++) {
			moment += lift[i] * (in.lifts.get(i).x - xcg);
		}
		double angAcc = inertia > 0 ? moment / inertia : 0;
		double latAcc = totalLift / mass;
		double axAcc = (st.thrust - drag - mass * G) / mass;

		// Transverse loads: each part's inertia (with its own rotary inertia as a concentrated moment), each lift.
		List<double[]> loads = new ArrayList<>(); // x, load, concentrated moment, label index
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < in.parts.size(); i++) {
			Part p = in.parts.get(i);
			double a = latAcc + angAcc * (p.x - xcg);
			loads.add(new double[] { p.x, -p.mass * a, iLocal[i] * angAcc, labels.size() });
			labels.add(p.name);
		}
		for (int i = 0; i < lift.length; i++) {
			loads.add(new double[] { in.lifts.get(i).x, lift[i], 0, labels.size() });
			labels.add(in.lifts.get(i).name + " lift");
		}
		loads.add(new double[] { 0, 0, 0, labels.size() });
		labels.add("Nose tip");
		loads.add(new double[] { in.length, 0, 0, labels.size() });
		labels.add("Aft end");
		for (Joint j : in.joints) {
			loads.add(new double[] { j.x, 0, 0, labels.size() });
			labels.add(j.name);
		}
		loads.sort(Comparator.comparingDouble(v -> v[0])); // stable: parts before lifts before joints at one x

		// Axial force: from the nose, the parts' inertia and their share of drag (by length); thrust aft of its point.
		double lengthSum = in.parts.stream().mapToDouble(Part::length).sum();
		List<Part> byX = new ArrayList<>(in.parts);
		byX.sort(Comparator.comparingDouble(Part::x));

		List<Map<String, Object>> stations = new ArrayList<>();
		double shear = 0, bend = 0, prevX = loads.isEmpty() ? 0 : loads.get(0)[0], prevShear = 0;
		double maxShear = 0, maxShearX = 0, maxMoment = 0, maxMomentX = 0, maxComp = 0, maxCompX = 0;
		for (double[] l : loads) {
			bend += prevShear * (l[0] - prevX) + l[2];
			shear += l[1];
			double axial = axial(byX, l[0], axAcc, drag, lengthSum, in.length, st);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("x", l[0]);
			row.put("at", labels.get((int) l[3]));
			row.put("load", l[1]);
			row.put("shear", shear);
			row.put("moment", bend);
			row.put("axial", axial);
			stations.add(row);
			if (Math.abs(shear) > Math.abs(maxShear)) {
				maxShear = shear;
				maxShearX = l[0];
			}
			if (Math.abs(bend) > Math.abs(maxMoment)) {
				maxMoment = bend;
				maxMomentX = l[0];
			}
			if (axial < maxComp) {
				maxComp = axial;
				maxCompX = l[0];
			}
			prevX = l[0];
			prevShear = shear;
		}
		List<Map<String, Object>> joints = new ArrayList<>();
		for (Joint j : in.joints) {
			for (Map<String, Object> row : stations) {
				if (row.get("at").equals(j.name) && Math.abs((double) row.get("x") - j.x) < 1e-9) {
					joints.add(row);
					break;
				}
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("q", q);
		out.put("mach", st.mach);
		out.put("airspeed", st.v);
		out.put("density", st.rho);
		out.put("alpha", in.alpha);
		out.put("mass", mass);
		out.put("cg", xcg);
		out.put("pitchInertia", inertia);
		out.put("lift", totalLift);
		out.put("drag", drag);
		out.put("thrust", st.thrust);
		out.put("lateralAcceleration", latAcc);
		out.put("angularAcceleration", angAcc);
		out.put("axialAcceleration", axAcc);
		List<Map<String, Object>> lifts = new ArrayList<>();
		for (int i = 0; i < lift.length; i++) {
			lifts.add(Map.of("name", in.lifts.get(i).name, "cnAlpha", in.lifts.get(i).cnAlpha, "cp", in.lifts.get(i).x, "lift", lift[i]));
		}
		out.put("lifts", lifts);
		out.put("maxShear", new double[] { maxShear, maxShearX });
		out.put("maxMoment", new double[] { maxMoment, maxMomentX });
		out.put("maxCompression", new double[] { maxComp, maxCompX });
		out.put("stations", stations);
		out.put("joints", joints);
		return out;
	}

	/**
	 * Axial force at x (negative: compression), as the script has it per part: the parts ahead of x (by CG) accelerate
	 * with the vehicle and carry their share of drag; thrust adds aft of its point.
	 */
	static double axial(List<Part> byX, double x, double axAcc, double drag, double lengthSum, double length, State st) {
		double m = 0, d = 0;
		boolean any = false;
		for (Part p : byX) {
			if (p.x <= x + 1e-12) {
				m += p.mass;
				d += lengthSum > 0 ? drag * p.length / lengthSum : 0;
				any = true;
			}
		}
		if (!any) {
			return 0;
		}
		double c = -(m * (axAcc + G)) - d;
		if (st.thrust != 0 && x >= st.thrustX) {
			c += st.thrust;
		}
		return c;
	}

	// ------------------------------------------------------------------------------------------------ the report

	/**
	 * The bending moment and shear diagrams as SVG line charts (display units), with the joints marked: {moment, shear}.
	 * Shear is drawn as steps, since it jumps at each concentrated load and stays level between them.
	 */
	@SuppressWarnings("unchecked")
	public static String[] svgs(Map<String, Object> r, Input in, String name) {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("stations");
		String lu = Units.plotUnit(Dim.LENGTH), fu = Units.plotUnit(Dim.FORCE);
		boolean imperial = "lbf".equals(fu);
		double xk = Units.fromSi(1, lu), fk = Units.fromSi(1, fu), mk = imperial ? 1 / 1.3558179483 : 1;
		int n = rows.size();
		double[] x = new double[n], m = new double[n], sx = new double[2 * n], sv = new double[2 * n];
		double prev = 0;
		for (int i = 0; i < n; i++) {
			Map<String, Object> row = rows.get(i);
			x[i] = (double) row.get("x") * xk;
			m[i] = (double) row.get("moment") * mk;
			sx[2 * i] = x[i];
			sv[2 * i] = prev;
			prev = (double) row.get("shear") * fk;
			sx[2 * i + 1] = x[i];
			sv[2 * i + 1] = prev;
		}
		List<io.github.openrocketmcp.report.Svg.Marker> marks = new ArrayList<>();
		for (Joint j : in.joints) {
			marks.add(new io.github.openrocketmcp.report.Svg.Marker(j.x * xk, j.name));
		}
		String at = " at max q, " + Units.num(Math.toDegrees(in.alpha())) + " deg angle of attack";
		String xl = "Station from the nose tip (" + lu + ")";
		return new String[] {
				io.github.openrocketmcp.report.Svg.line(name + ": bending moment" + at, xl,
						"Bending moment (" + (imperial ? "lbf·ft" : "N·m") + ")", x, m, Double.NaN, null, marks),
				io.github.openrocketmcp.report.Svg.line(name + ": shear" + at, xl, "Shear (" + fu + ")", sx, sv, Double.NaN, null, marks) };
	}

	/** The result in the team's units, for people: key numbers, the stations (to plot), the joints, how it was made. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> report(Map<String, Object> r, Input in, String source, StringBuilder csv) {
		Map<String, Object> out = new LinkedHashMap<>();
		double[] ms = (double[]) r.get("maxShear"), mm = (double[]) r.get("maxMoment"), mc = (double[]) r.get("maxCompression");
		// The numbers to act on first: the largest of each along the rocket, and the instant they are for.
		Map<String, Object> largest = new LinkedHashMap<>();
		largest.put("name", "Largest along the rocket");
		largest.put("bendingMoment", Loads.fmtMoment(Math.abs(mm[0])) + " at " + Units.fmt(mm[1], Dim.LENGTH));
		largest.put("shear", Units.fmt(Math.abs(ms[0]), Dim.FORCE) + " at " + Units.fmt(ms[1], Dim.LENGTH));
		largest.put("compression", mc[0] < 0 ? Units.fmt(-mc[0], Dim.FORCE) + " at " + Units.fmt(mc[1], Dim.LENGTH) : "none (tension only)");
		Map<String, Object> when = new LinkedHashMap<>();
		when.put("name", "At max q");
		when.put("dynamicPressure", Units.fmt((double) r.get("q"), Dim.PRESSURE));
		when.put("mach", Units.num((double) r.get("mach")));
		when.put("angleOfAttack", Units.fmt(in.alpha(), Dim.ANGLE));
		when.put("lift", Units.fmt((double) r.get("lift"), Dim.FORCE));
		when.put("drag", Units.fmt((double) r.get("drag"), Dim.FORCE));
		out.put("summary", List.of(largest, when));
		out.put("maxBendingMoment", Loads.fmtMoment(Math.abs(mm[0])) + " at " + Units.fmt(mm[1], Dim.LENGTH) + " from the nose tip");
		out.put("maxShear", Units.fmt(Math.abs(ms[0]), Dim.FORCE) + " at " + Units.fmt(ms[1], Dim.LENGTH));
		out.put("maxCompression", Units.fmt(Math.abs(mc[0]), Dim.FORCE) + " at " + Units.fmt(mc[1], Dim.LENGTH));
		Map<String, Object> st = new LinkedHashMap<>();
		State s = in.state();
		st.put("dynamicPressure", Units.fmt((double) r.get("q"), Dim.PRESSURE));
		st.put("mach", Units.num((double) r.get("mach")));
		st.put("airspeed", Units.fmt((double) r.get("airspeed"), Dim.VELOCITY));
		if (!Double.isNaN(s.time())) {
			st.put("time", Units.fmt(s.time(), Dim.TIME));
		}
		st.put("angleOfAttack", Units.fmt(in.alpha(), Dim.ANGLE));
		st.put("mass", Units.fmt((double) r.get("mass"), Dim.MASS));
		st.put("cg", Units.fmt((double) r.get("cg"), Dim.LENGTH) + " from the nose tip");
		st.put("pitchInertia", Units.num((double) r.get("pitchInertia")) + " kg·m²");
		st.put("lift", Units.fmt((double) r.get("lift"), Dim.FORCE));
		st.put("drag", Units.fmt((double) r.get("drag"), Dim.FORCE));
		if (s.thrust() != 0) {
			st.put("thrust", Units.fmt(s.thrust(), Dim.FORCE));
		}
		st.put("lateralAcceleration", Units.fmt((double) r.get("lateralAcceleration"), Dim.ACCELERATION));
		st.put("pitchAcceleration", Units.num((double) r.get("angularAcceleration")) + " rad/s²");
		out.put("atMaxQ", st);
		List<Map<String, Object>> lifts = new ArrayList<>();
		for (Map<String, Object> l : (List<Map<String, Object>>) r.get("lifts")) {
			lifts.add(Map.of("part", l.get("name"), "cnAlpha", Units.num((double) l.get("cnAlpha")) + " /rad", "cp",
					Units.fmt((double) l.get("cp"), Dim.LENGTH), "lift", Units.fmt((double) l.get("lift"), Dim.FORCE)));
		}
		out.put("lifts", lifts);
		List<Map<String, Object>> stations = new ArrayList<>();
		if (csv != null) {
			csv.append("station_m,at,transverse_load_n,shear_n,bending_moment_nm,axial_force_n\n");
		}
		for (Map<String, Object> row : (List<Map<String, Object>>) r.get("stations")) {
			stations.add(row(row));
			if (csv != null) {
				csv.append(String.format(java.util.Locale.ROOT, "%.5f,\"%s\",%.4f,%.4f,%.4f,%.4f%n", (double) row.get("x"),
						String.valueOf(row.get("at")).replace("\"", "'"), (double) row.get("load"), (double) row.get("shear"),
						(double) row.get("moment"), (double) row.get("axial")));
			}
		}
		out.put("stations", stations);
		List<Map<String, Object>> joints = new ArrayList<>();
		for (Map<String, Object> row : (List<Map<String, Object>>) r.get("joints")) {
			joints.add(row(row));
		}
		out.put("joints", joints);
		List<String> notes = new ArrayList<>();
		notes.add(source);
		notes.add("Rigid body at max q, quasi-static: lift at each part's centre of pressure, each part's inertia at its CG (a slender "
				+ "bar for its rotary inertia), shear summed from the nose and bending moment its integral. Axial force (negative: "
				+ "compression) from drag shared by part length and the parts' inertia" + (s.thrust() != 0 ? ", with thrust." : ", thrust off (coast), as in the team's script."));
		notes.add("Use the joint values to size couplers, fasteners and bulkheads with the team's safety factor; structural_loads gives "
				+ "the worst case over the whole flight with a gust instead of a set angle of attack.");
		notes.add(CITATION);
		out.put("notes", notes);
		return out;
	}

	private static Map<String, Object> row(Map<String, Object> row) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("station", Units.fmt((double) row.get("x"), Dim.LENGTH));
		m.put("at", row.get("at"));
		m.put("load", Units.fmt((double) row.get("load"), Dim.FORCE));
		m.put("shear", Units.fmt((double) row.get("shear"), Dim.FORCE));
		m.put("bendingMoment", Loads.fmtMoment((double) row.get("moment")));
		m.put("axialForce", Units.fmt((double) row.get("axial"), Dim.FORCE));
		return m;
	}

	// ------------------------------------------------------------------------------------------------ from the design

	/** The input from a design's simulation: its airframe pieces, OpenRocket's aero at max q. */
	public static Input fromSimulation(Simulation sim, double alpha, boolean withThrust) {
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		double end = Sims.eventTime(b, FlightEvent.Type.APOGEE);
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() == FlightEvent.Type.STAGE_SEPARATION) {
				end = Double.isNaN(end) ? e.getTime() : Math.min(end, e.getTime());
			}
		}
		double[] t = br.time, rho = br.col(FlightDataType.TYPE_AIR_DENSITY), mach = br.col(FlightDataType.TYPE_MACH_NUMBER),
				temp = br.col(FlightDataType.TYPE_AIR_TEMPERATURE), dragF = br.col(FlightDataType.TYPE_DRAG_FORCE),
				thrust = br.col(FlightDataType.TYPE_THRUST_FORCE), massF = br.col(FlightDataType.TYPE_MASS);
		int iq = -1;
		double qMax = 0;
		for (int i = 0; i < t.length && (Double.isNaN(end) || t[i] <= end + 1e-9); i++) {
			double v = br.airspeed(i), q = 0.5 * rho[i] * v * v;
			if (q > qMax) {
				qMax = q;
				iq = i;
			}
		}
		if (iq < 0) {
			throw new ToolException("The simulation has no flight to take max q from: run it first.");
		}
		double v = br.airspeed(iq);
		double machQ = Double.isNaN(mach[iq]) ? 0.3 : mach[iq];

		// Parts: the airframe pieces, each with everything inside its length (motor at its mass at max q).
		List<RocketComponent> pieces = new ArrayList<>();
		for (AxialStage s : fc.getActiveStages()) {
			pieces.addAll(Sections.pieces(s));
		}
		pieces.sort(Comparator.comparingDouble(c -> Structures.absoluteX(c, 0)));
		if (pieces.isEmpty()) {
			throw new ToolException("The design has no airframe pieces (nose cone, body tubes).");
		}
		double[] motor = new double[2];
		Map<RocketComponent, double[]> masses = Loads.componentMasses(fc, motor);
		double[] pm = new double[pieces.size()], pmx = new double[pieces.size()];
		for (double[] mv : masses.values()) {
			int k = pieceAt(pieces, mv[1]);
			pm[k] += mv[0];
			pmx[k] += mv[0] * mv[1];
		}
		double structure = masses.values().stream().mapToDouble(mv -> mv[0]).sum();
		double motorNow = Double.isNaN(massF[iq]) ? motor[0] : Math.max(0, massF[iq] - structure);
		if (motorNow > 0 && !Double.isNaN(motor[1])) {
			int k = pieceAt(pieces, motor[1]);
			pm[k] += motorNow;
			pmx[k] += motorNow * motor[1];
		}
		List<Part> parts = new ArrayList<>();
		for (int k = 0; k < pieces.size(); k++) {
			RocketComponent c = pieces.get(k);
			if (pm[k] > 0) {
				parts.add(new Part(c.getName(), pm[k], pmx[k] / pm[k], c.getLength()));
			}
		}
		double length = 0;
		for (RocketComponent c : pieces) {
			length = Math.max(length, Structures.absoluteX(c, 0) + c.getLength());
		}

		// Lifts: OpenRocket's normal-force slope and CP of each part at the max-q Mach (fins with body interference).
		FlightConditions cond = new FlightConditions(fc);
		cond.setMach(Math.max(0.05, machQ));
		cond.setAOA(0);
		cond.setRollRate(0);
		Map<RocketComponent, AerodynamicForces> forces = new BarrowmanCalculator().getForceAnalysis(fc, cond, new WarningSet());
		List<Lift> lifts = new ArrayList<>();
		for (Map.Entry<RocketComponent, AerodynamicForces> e : forces.entrySet()) {
			RocketComponent c = e.getKey();
			AerodynamicForces f = e.getValue();
			if (c instanceof ComponentAssembly || f.getCP() == null || f.getCP().weight == 0 || Double.isNaN(f.getCP().x)) {
				continue;
			}
			lifts.add(new Lift(c.getName() + (c instanceof FinSet ? " (with body interference)" : ""), f.getCP().weight, f.getCP().x));
		}

		double thrustX = Double.NaN;
		if (withThrust) {
			for (RocketComponent c : fc.getActiveComponents()) {
				if (c instanceof MotorMount mm && mm.isMotorMount() && mm.getMotorConfig(fc.getId()).getMotor() != null) {
					double aft = Structures.absoluteX(c, c.getLength());
					thrustX = Double.isNaN(thrustX) ? aft : Math.max(thrustX, aft);
				}
			}
		}
		double th = withThrust && !Double.isNaN(thrust[iq]) ? thrust[iq] : 0;
		State st = new State(rho[iq], v, temp[iq], machQ, dragF[iq], th, Double.isNaN(thrustX) ? length : thrustX, t[iq]);
		List<Joint> joints = new ArrayList<>();
		for (Loads.Joint j : Loads.joints(fc)) {
			joints.add(new Joint(j.name(), j.x()));
		}
		return new Input(parts, lifts, Double.NaN, fc.getReferenceArea(), length, st, alpha, joints);
	}

	static int pieceAt(List<RocketComponent> pieces, double x) {
		for (int k = pieces.size() - 1; k >= 0; k--) {
			if (x >= Structures.absoluteX(pieces.get(k), 0) - 1e-9) {
				return k;
			}
		}
		return 0;
	}

	// ------------------------------------------------------------------------------------------------ the team's files

	/**
	 * The input from the script's own files, read as the script reads them: the parts file (first row: total length and
	 * reference diameter in cm; every row: part, mass in kg, CG in cm, with the Length column used per part), the
	 * conditions file (density, airspeed, gust, temperature, CG), and AeroLab's CP (cm), lift-slope and CD tables by Mach.
	 */
	public static Input fromFiles(Path partsFile, Path conditionsFile, Path cpFile, Path clFile, Path cdFile, double alpha, List<Joint> joints)
			throws IOException {
		List<String[]> rows = csv(partsFile, ",");
		if (rows.isEmpty()) {
			throw new ToolException("The parts file has no parts: " + partsFile);
		}
		double length = num(rows.get(0)[0]) / 100, diameter = num(rows.get(0)[1]) / 100;
		if (!(length > 0) || !(diameter > 0)) {
			throw new ToolException("The parts file's first row needs the rocket's length and reference diameter in cm (Length, "
					+ "Reference Area columns): " + partsFile);
		}
		List<Part> parts = new ArrayList<>();
		for (String[] r : rows) {
			if (r.length < 5 || Double.isNaN(num(r[3])) || Double.isNaN(num(r[4]))) {
				throw new ToolException("Each row of the parts file needs 5 columns (Length, Reference Area, Rocket_Part, mass_m, x_cm): "
						+ String.join(",", r));
			}
			parts.add(new Part(r[2].trim(), num(r[3]), num(r[4]) / 100, Double.isNaN(num(r[0])) ? 0 : num(r[0]) / 100));
		}
		List<String[]> cond = csv(conditionsFile, ",");
		String[] c = cond.isEmpty() ? new String[0] : cond.get(0);
		double rho = c.length > 3 ? num(c[0]) : Double.NaN, v = c.length > 3 ? num(c[1]) : Double.NaN, temperature = c.length > 3 ? num(c[3]) : Double.NaN;
		if (!(rho > 0) || !(v > 0) || !(temperature > 0)) {
			throw new ToolException("The conditions file needs a row of rho_inf (kg/m3), velocity_v_inf (m/s), gust, T_inf (K), cg_x: " + conditionsFile);
		}
		double mach = v / Math.sqrt(1.4 * 287 * temperature);
		double[][] cp = table(cpFile, 5, "the centres of pressure (Mach, rocket, nose/body, wing, body-wing)"),
				cl = table(clFile, 5, "the lift-curve slopes (Mach, rocket, nose/body, wing, body-wing)"), cd = table(cdFile, 2, "the drag coefficient (Mach, CD)");
		List<Lift> lifts = List.of(
				new Lift("Nose/body", interp(mach, cl, 2), interp(mach, cp, 2) / 100),
				new Lift("Wing", interp(mach, cl, 3), interp(mach, cp, 3) / 100),
				new Lift("Body-wing", interp(mach, cl, 4), interp(mach, cp, 4) / 100));
		State st = new State(rho, v, temperature, mach, Double.NaN, 0, length, Double.NaN);
		return new Input(parts, lifts, interp(mach, cd, 1), Math.PI * diameter * diameter / 4, length, st, alpha, joints);
	}

	static List<String[]> csv(Path p, String sep) throws IOException {
		List<String[]> out = new ArrayList<>();
		List<String> lines = Files.readAllLines(p);
		for (int i = 1; i < lines.size(); i++) { // the first line is the header
			String l = lines.get(i).replace("﻿", "").trim();
			if (!l.isEmpty()) {
				out.add(l.split(sep));
			}
		}
		return out;
	}

	static double[][] table(Path p, int columns, String what) throws IOException {
		double[][] t = table(p);
		if (t.length == 0 || java.util.Arrays.stream(t).anyMatch(r -> r.length < columns || java.util.Arrays.stream(r).limit(columns).anyMatch(Double::isNaN))) {
			throw new ToolException(p.getFileName() + " should be AeroLab's table of " + what + ": a header line, then " + columns
					+ " numbers per line.");
		}
		return t;
	}

	static double[][] table(Path p) throws IOException {
		List<double[]> rows = new ArrayList<>();
		List<String> lines = Files.readAllLines(p);
		for (int i = 1; i < lines.size(); i++) {
			String l = lines.get(i).trim();
			if (l.isEmpty()) {
				continue;
			}
			String[] f = l.split("\\s+");
			double[] r = new double[f.length];
			for (int k = 0; k < f.length; k++) {
				r[k] = num(f[k]);
			}
			rows.add(r);
		}
		return rows.toArray(new double[0][]);
	}

	/** Linear in the first column, held at the ends (numpy.interp). */
	static double interp(double x, double[][] t, int col) {
		if (t.length == 0) {
			return Double.NaN;
		}
		if (x <= t[0][0]) {
			return t[0][col];
		}
		for (int i = 1; i < t.length; i++) {
			if (x <= t[i][0]) {
				double f = (x - t[i - 1][0]) / (t[i][0] - t[i - 1][0]);
				return t[i - 1][col] + f * (t[i][col] - t[i - 1][col]);
			}
		}
		return t[t.length - 1][col];
	}

	static double num(String s) {
		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}
}
