package io.github.openrocketmcp.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.InternalComponent;
import info.openrocket.core.rocketcomponent.LaunchLug;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.ParallelStage;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RingComponent;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.ShockCord;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import info.openrocket.core.rocketcomponent.TubeFinSet;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Geometry;
import io.github.openrocketmcp.or.Loads;

/**
 * The rocket as coloured 3-D parts built from OpenRocket's geometry, for the exploded view and the flight animation.
 *
 * <p>Rocket frame as in OpenRocket: x along the axis from the nose tip aft, y and z across. Airframe pieces are hollow
 * shells (outer and inner surface, so a cut or an open end shows the wall); nose cone and transition shoulders belong to
 * their piece. Fins are thin plates. Internal parts (couplers, bulkheads, centering rings, motor mounts, parachutes,
 * shock cords, electronics and other masses, motors) sit at their instances' positions: rings, tubes, ballast and
 * motors as cylinders, the rest as what they are (a packed parachute, a coiled cord, boards, batteries; see Shapes3d).
 */
public final class Model3d {
	private Model3d() {
	}

	/** Paint: white airframe, red nose, blue fins; internal parts use the cut-away drawing's colours. */
	static final Map<String, Integer> COLOURS = colours();

	private static Map<String, Integer> colours() {
		Map<String, Integer> m = new HashMap<>();
		m.put("nose", 0xc8392f);
		m.put("body", 0xe9ecf1);
		m.put("transition", 0x3f6aa8);
		m.put("fins", 0x2d5d9f);
		m.put("struct", 0x8a96a8);
		m.put("tube", 0xaab4c3);
		m.put("chute", 0xf0a64a);
		m.put("cord", 0xf3d6a6);
		m.put("elec", 0x2e9e5b);
		m.put("batt", 0xe8c33c);
		m.put("track", 0x8e5cc9);
		m.put("charge", 0xd63b2f);
		m.put("switch", 0xf4f4f4);
		m.put("hw", 0xb9b3a8);
		m.put("payload", 0x4aa3c9);
		m.put("mass", 0x9b9b9b);
		m.put("motor", 0x4b4d52);
		m.put("nozzle", 0x26272a);
		m.put("lug", 0x7d848e);
		return m;
	}

	/** One component's geometry (all its instances) in the rocket frame. */
	public static final class Part {
		public final RocketComponent component; // null for a motor
		public final String name, kind;
		public final boolean internal;
		/** Index of the airframe piece it belongs to (0 = the nose), in axial order; -1 before layout. */
		int piece = -1;
		final int stage;
		/** The component whose airframe piece this part travels with (a motor's mount; else the component). */
		RocketComponent owner;
		/** The in-line stage it belongs to (side boosters count with the stage they are attached to) and its name. */
		int axialStage;
		String stageName = "";
		final List<double[]> tris = new ArrayList<>();
		double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, rMax;
		double mass;
		/** How deep it sits: 0 for the airframe and fins, 1 for a part in the airframe, 2 for one inside that, ... */
		int depth;
		/** Opened by a cut-away: shells, tubes and rings, and any part with others inside it (a sled round its electronics). */
		boolean section;
		/** Internal parts: the first instance's axis (y, z) and outer radius, to find what encloses what. */
		double cy, cz, radius;
		/** Exploded-view offset of the whole part (m, rocket frame). */
		double[] shift = { 0, 0, 0 };
		/** Fins only: one sub-list per fin with the fin's outward direction (for sliding fins out radially). */
		final List<List<double[]>> fins = new ArrayList<>();
		final List<double[]> finDirs = new ArrayList<>();

		Part(RocketComponent c, String name, String kind, boolean internal, int stage) {
			this.component = c;
			this.name = name;
			this.kind = kind;
			this.internal = internal;
			this.stage = stage;
			this.owner = c;
		}

		public int rgb() {
			return COLOURS.getOrDefault(kind, 0xbbbbbb);
		}

		public double mass() {
			return mass;
		}

		void add(double[] t) {
			tris.add(t);
			for (int v = 0; v < 3; v++) {
				x0 = Math.min(x0, t[3 * v]);
				x1 = Math.max(x1, t[3 * v]);
				rMax = Math.max(rMax, Math.hypot(t[3 * v + 1], t[3 * v + 2]));
			}
		}

		/** Triangles with the exploded offsets applied ({@code finOut}: how far each fin slides out, m). */
		public List<double[]> placed(double finOut) {
			List<double[]> out = new ArrayList<>();
			if (!fins.isEmpty()) {
				for (int k = 0; k < fins.size(); k++) {
					double[] d = finDirs.get(k);
					for (double[] t : fins.get(k)) {
						out.add(offset(t, shift[0], shift[1] + d[1] * finOut, shift[2] + d[2] * finOut));
					}
				}
				return out;
			}
			if (shift[0] == 0 && shift[1] == 0 && shift[2] == 0) {
				return tris;
			}
			for (double[] t : tris) {
				out.add(offset(t, shift[0], shift[1], shift[2]));
			}
			return out;
		}
	}

	static double[] offset(double[] t, double dx, double dy, double dz) {
		return new double[] { t[0] + dx, t[1] + dy, t[2] + dz, t[3] + dx, t[4] + dy, t[5] + dz, t[6] + dx, t[7] + dy, t[8] + dz };
	}

	/** Every part of the active configuration, airframe pieces first in axial order. */
	public static List<Part> build(FlightConfiguration fc, int segments) {
		Analysis.settle(fc);
		Map<RocketComponent, double[]> masses = Loads.componentMasses(fc, null);
		List<Part> out = new ArrayList<>();
		Map<RocketComponent, Part> pieces = new LinkedHashMap<>();
		for (RocketComponent c : fc.getAllActiveComponents()) {
			int stage = c instanceof Rocket ? 0 : c.getStage().getStageNumber();
			Part p = null;
			if (c instanceof SymmetricComponent sc && sc.getLength() > 0 && !(c instanceof InternalComponent)) {
				p = new Part(c, c.getName(), c instanceof NoseCone ? "nose" : c instanceof Transition ? "transition" : "body", false, stage);
				p.section = true;
				for (Coordinate a : c.toAbsolute(Coordinate.NUL)) {
					shell(p, sc, a, segments);
				}
				pieces.put(c, p);
			} else if (c instanceof FinSet f) {
				p = new Part(c, c.getName(), "fins", false, stage);
				fins(p, f);
			} else if (c instanceof TubeFinSet tf) {
				p = new Part(c, c.getName(), "fins", false, stage);
				tubeFins(p, tf, segments);
			} else if (c instanceof LaunchLug lug) {
				p = new Part(c, c.getName(), "lug", false, stage);
				p.section = true;
				for (Coordinate a : c.toAbsolute(Coordinate.NUL)) {
					cylinder(p, a.x, a.x + lug.getLength(), Math.min(lug.getInnerRadius(), wall(lug.getOuterRadius())), lug.getOuterRadius(), a.y, a.z,
							Math.max(12, segments / 2));
				}
			} else if (c instanceof RailButton rb) {
				p = new Part(c, c.getName(), "lug", false, stage);
				p.section = true;
				railButtons(p, rb, segments);
			} else if (c instanceof InternalComponent) {
				p = internal(c, stage, segments);
			}
			if (p != null && !p.tris.isEmpty()) {
				p.depth = depth(c);
				double[] m = masses.get(c);
				p.mass = m != null ? m[0] : c.getComponentMass() * Math.max(1, c.getInstanceCount());
				out.add(p);
			}
		}
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (mc.getMotor() == null) {
				continue;
			}
			RocketComponent mount = (RocketComponent) mc.getMount();
			Part p = new Part(null, mc.getMotor().getDesignation(), "motor", true, mount.getStage().getStageNumber());
			p.owner = mount;
			p.depth = depth(mount) + 1;
			double ml = mc.getMotor().getLength(), mr = mc.getMotor().getDiameter() / 2;
			double bore = mount instanceof RingComponent rc ? rc.getInnerRadius()
					: mount instanceof SymmetricComponent sc && !sc.isFilled() ? sc.getInnerRadius(sc.getLength()) : 0;
			if (bore > 0) {
				mr = Math.min(mr, bore); // a motor listed wider than its tube is drawn in it, not through its wall
			}
			Coordinate[] at = mount.toAbsolute(Coordinate.NUL);
			p.cy = at[0].y;
			p.cz = at[0].z;
			p.radius = mr;
			for (Coordinate a : at) {
				double aft = a.x + mount.getLength() + mc.getMount().getMotorOverhang();
				cylinder(p, aft - ml, aft - 0.06 * ml, 0, mr, a.y, a.z, segments);
				// Nozzle: a short dark cone at the aft end.
				lathe(p, new double[][] { { aft - 0.06 * ml, 0 }, { aft - 0.06 * ml, mr }, { aft, 0.62 * mr }, { aft, 0.4 * mr },
						{ aft - 0.03 * ml, 0.3 * mr } }, a.y, a.z, segments, true);
			}
			p.mass = mc.getMotor().getLaunchMass();
			out.add(p);
		}
		// Airframe piece of every part: the stage child that holds it (pods and boosters travel with their own pieces).
		List<RocketComponent> order = new ArrayList<>(pieces.keySet());
		order.sort((a, b) -> Double.compare(a.toAbsolute(Coordinate.NUL)[0].x, b.toAbsolute(Coordinate.NUL)[0].x));
		for (Part p : out) {
			p.piece = Math.max(0, order.indexOf(pieceOf(p.owner)));
			if (!(p.owner instanceof Rocket)) {
				AxialStage st = p.owner.getStage();
				p.stageName = st.getName();
				while (st instanceof ParallelStage && st.getParent() != null && !(st.getParent() instanceof Rocket)) {
					st = st.getParent().getStage();
				}
				p.axialStage = st.getStageNumber();
			}
		}
		out.sort((a, b) -> a.internal != b.internal ? (a.internal ? 1 : -1) : Integer.compare(a.piece, b.piece));
		// A part with others inside it (a sled round its altimeters, a payload can) is opened by a cut-away too.
		for (Part p : out) {
			if (!p.internal || p.section || p.radius <= 0) {
				continue;
			}
			for (Part q : out) {
				if (q != p && q.internal && q.radius > 0 && holder(q) == holder(p) && q.x0 >= p.x0 - 1e-6 && q.x1 <= p.x1 + 1e-6
						&& Math.hypot(q.cy - p.cy, q.cz - p.cz) + q.radius <= p.radius + 1e-6 && q.radius < p.radius) {
					p.section = true;
					break;
				}
			}
		}
		return out;
	}

	/**
	 * What a part is mounted in: a motor's mount, else the component's parent. Only parts in the same tube count as
	 * inside one another (a fillet mass entered on the axis is not inside the motor).
	 */
	static RocketComponent holder(Part p) {
		return p.component == null ? p.owner : p.component.getParent();
	}

	/** Nesting depth: 0 for the airframe, one more for each internal part it sits in. */
	static int depth(RocketComponent c) {
		int d = 0;
		for (RocketComponent p = c; p != null && !(p instanceof AxialStage) && !(p instanceof Rocket); p = p.getParent()) {
			if (p instanceof InternalComponent) {
				d++;
			}
		}
		return d;
	}

	/**
	 * Inner radius of the component a part sits in at the given distance from that component's front, so the part is
	 * drawn inside it (a part listed wider than its tube would otherwise poke through the skin and flicker against it).
	 */
	static double bore(RocketComponent parent, double local) {
		if (parent instanceof SymmetricComponent sc && !(parent instanceof InternalComponent)) {
			return sc.isFilled() ? Double.MAX_VALUE : sc.getInnerRadius(Math.max(0, Math.min(sc.getLength(), local)));
		}
		if (parent instanceof RingComponent rc) {
			return rc.getInnerRadius() > 0 ? rc.getInnerRadius() : Double.MAX_VALUE;
		}
		return Double.MAX_VALUE;
	}

	static RocketComponent pieceOf(RocketComponent c) {
		RocketComponent p = c;
		while (p.getParent() != null && !(p.getParent() instanceof AxialStage)) {
			p = p.getParent();
		}
		return p;
	}

	private static Part internal(RocketComponent c, int stage, int seg) {
		String kind;
		double len, rOut, rIn = 0;
		if (c instanceof RecoveryDevice rd) {
			kind = "chute";
			len = rd.getLength();
			rOut = rd.getRadius();
		} else if (c instanceof ShockCord sc) {
			kind = "cord";
			len = sc.getLength();
			rOut = sc.getRadius();
		} else if (c instanceof MassComponent m) {
			kind = Drawing.massClass(m);
			len = m.getLength();
			rOut = m.getRadius();
		} else if (c instanceof RingComponent rc) {
			kind = c instanceof InnerTube || c instanceof TubeCoupler ? "tube" : "struct";
			len = rc.getLength();
			rOut = rc.getOuterRadius();
			rIn = rc.getInnerRadius();
		} else {
			return null;
		}
		if (len <= 0 || rOut <= 0) {
			return null;
		}
		Part p = new Part(c, c.getName(), kind, true, stage);
		p.section = c instanceof RingComponent;
		// The outer surface follows the bore of what it sits in (ballast in a nose cone fills its taper).
		RocketComponent parent = c.getParent();
		double front = c.getPosition().x;
		boolean tapered = parent instanceof Transition;
		int n = tapered ? 12 : 1;
		double[] xs = new double[n + 1], rs = new double[n + 1];
		for (int i = 0; i <= n; i++) {
			xs[i] = len * i / n;
			double b = Math.min(bore(parent, front + xs[i]), tapered ? Double.MAX_VALUE : bore(parent, front + len));
			rs[i] = Math.max(Math.min(rOut, b), 1e-4);
		}
		double inner = Math.min(rIn, wall(Math.min(rs[0], rs[n])));
		Coordinate[] at = c.toAbsolute(Coordinate.NUL);
		double room = Double.MAX_VALUE;
		for (double v : rs) {
			room = Math.min(room, v);
		}
		for (Coordinate a : at) {
			// A parachute, cord, circuit board, battery, charge, switch, U-bolt or sled is drawn as one; anything else
			// (ballast, couplers, rings, mounts) as the cylinder or slug it is.
			if (!(c instanceof RingComponent) && Shapes3d.draw(p, c, kind, a.x, len, room, a.y, a.z, seg)) {
				continue;
			}
			List<double[]> prof = new ArrayList<>();
			prof.add(new double[] { a.x, inner });
			for (int i = 0; i <= n; i++) {
				prof.add(new double[] { a.x + xs[i], rs[i] });
			}
			prof.add(new double[] { a.x + len, inner });
			lathe(p, prof.toArray(new double[0][]), a.y, a.z, seg, inner > 1e-9);
		}
		p.cy = at[0].y;
		p.cz = at[0].z;
		p.radius = Math.max(rs[0], rs[n]);
		return p;
	}

	/** Outer surface, inner surface (unless filled) and end rings of a nose cone, tube or transition, with shoulders. */
	private static void shell(Part p, SymmetricComponent sc, Coordinate a, int seg) {
		int n = sc instanceof Transition ? 40 : 1;
		double len = sc.getLength();
		List<double[]> outer = new ArrayList<>(), inner = new ArrayList<>();
		for (int i = 0; i <= n; i++) {
			double u = (double) i / n;
			double x = len * (n == 1 ? u : 0.5 * (1 - Math.cos(Math.PI * u)));
			double r = sc.getRadius(x);
			outer.add(new double[] { a.x + x, r });
			double ri = sc.isFilled() ? 0 : Math.max(0, Math.min(wall(r), sc.getInnerRadius(x)));
			inner.add(0, new double[] { a.x + x, ri });
		}
		List<double[]> profile = new ArrayList<>(outer);
		profile.addAll(inner);
		lathe(p, profile.toArray(new double[0][]), a.y, a.z, seg, true);
		if (sc instanceof Transition t) {
			if (t.getAftShoulderLength() > 0 && t.getAftShoulderRadius() > 0) {
				double r = fit(t.getAftShoulderRadius(), t.getNextSymmetricComponent(), 0);
				double ri = Math.max(0, Math.min(wall(r), r - shoulderWall(t, t.getAftShoulderThickness())));
				cylinder(p, a.x + len, a.x + len + t.getAftShoulderLength(), t.isAftShoulderCapped() ? 0 : ri, r, a.y, a.z, seg);
			}
			if (t.getForeShoulderLength() > 0 && t.getForeShoulderRadius() > 0) {
				SymmetricComponent prev = t.getPreviousSymmetricComponent();
				double r = fit(t.getForeShoulderRadius(), prev, prev == null ? 0 : prev.getLength());
				double ri = Math.max(0, Math.min(wall(r), r - shoulderWall(t, t.getForeShoulderThickness())));
				cylinder(p, a.x - t.getForeShoulderLength(), a.x, t.isForeShoulderCapped() ? 0 : ri, r, a.y, a.z, seg);
			}
		}
	}

	/**
	 * A shoulder's radius as drawn: inside the component it plugs into (a shoulder entered as wide as the airframe would
	 * show through the skin), a hair under its bore so the two surfaces do not fight.
	 */
	static double fit(double r, SymmetricComponent into, double at) {
		if (into == null || into.isFilled()) {
			return r;
		}
		double bore = into.getInnerRadius(Math.max(0, Math.min(into.getLength(), at)));
		return bore > 0 && r > bore - 2e-4 ? Math.max(1e-4, bore - Math.max(2e-4, 0.003 * bore)) : r;
	}

	/**
	 * The largest inner radius a wall of outer radius r is drawn with: a wall entered as having no thickness would put
	 * its inner and outer surfaces on top of each other, which flicker against each other in a cut-away.
	 */
	static double wall(double r) {
		return Math.max(0, r - Math.max(2e-4, 0.01 * r));
	}

	/** A shoulder's wall: as entered, or the component's own wall where none is given (OpenRocket's default 0). */
	static double shoulderWall(Transition t, double thickness) {
		return thickness > 0 ? thickness : Math.max(t.getThickness(), 1e-3);
	}

	/** A tube (or a solid cylinder when rIn = 0) from x0 to x1. */
	static void cylinder(Part p, double x0, double x1, double rIn, double rOut, double cy, double cz, int seg) {
		lathe(p, new double[][] { { x0, rIn }, { x0, rOut }, { x1, rOut }, { x1, rIn } }, cy, cz, seg, rIn > 1e-9);
	}

	/**
	 * Revolves a profile of (x, r) points about the axis at (cy, cz). A closed profile (a wall section) is closed back
	 * to its first point; points on the axis end the surface there.
	 */
	static void lathe(Part p, double[][] prof, double cy, double cz, int seg, boolean closed) {
		revolve(p, prof, new double[] { 0, cy, cz }, new double[] { 1, 0, 0 }, new double[] { 0, 1, 0 }, new double[] { 0, 0, 1 }, seg, closed);
	}

	/**
	 * Revolves a profile of (h, r) points about the axis through o along a; u and v span the plane across it, with
	 * u x v = a so the surfaces face outward.
	 */
	static void revolve(Part p, double[][] prof, double[] o, double[] a3, double[] u, double[] v, int seg, boolean closed) {
		int m = prof.length;
		for (int i = 0; i < (closed ? m : m - 1); i++) {
			double[] a = prof[i], b = prof[(i + 1) % m];
			if (a[1] < 1e-9 && b[1] < 1e-9) {
				continue; // along the axis
			}
			for (int j = 0; j < seg; j++) {
				double t0 = 2 * Math.PI * j / seg, t1 = 2 * Math.PI * (j + 1) / seg;
				double[] p00 = ring(a, o, a3, u, v, t0), p10 = ring(b, o, a3, u, v, t0);
				double[] p11 = ring(b, o, a3, u, v, t1), p01 = ring(a, o, a3, u, v, t1);
				if (a[1] > 1e-9) {
					p.add(new double[] { p00[0], p00[1], p00[2], p01[0], p01[1], p01[2], p10[0], p10[1], p10[2] });
				}
				if (b[1] > 1e-9) {
					p.add(new double[] { p10[0], p10[1], p10[2], p01[0], p01[1], p01[2], p11[0], p11[1], p11[2] });
				}
			}
		}
	}

	private static double[] ring(double[] hr, double[] o, double[] a, double[] u, double[] v, double th) {
		double c = hr[1] * Math.cos(th), s = hr[1] * Math.sin(th), h = hr[0];
		return new double[] { o[0] + h * a[0] + c * u[0] + s * v[0], o[1] + h * a[1] + c * u[1] + s * v[1], o[2] + h * a[2] + c * u[2] + s * v[2] };
	}

	/** Tube fins: a short tube against the body at each fin's angle, on every instance of the body. */
	private static void tubeFins(Part p, TubeFinSet f, int seg) {
		double ro = f.getOuterRadius(), ri = Math.min(f.getInnerRadius(), wall(ro)), len = f.getLength(), rBody = f.getBodyRadius();
		double fx = f.toAbsolute(Coordinate.NUL)[0].x;
		int n = f.getFinCount();
		List<String> seen = new ArrayList<>();
		for (Coordinate axis : f.getParent().toAbsolute(Coordinate.NUL)) {
			String key = Math.round(axis.y * 1e4) + ":" + Math.round(axis.z * 1e4);
			if (seen.contains(key)) {
				continue;
			}
			seen.add(key);
			for (int k = 0; k < n; k++) {
				double th = f.getBaseRotation() + 2 * Math.PI * k / n, cy = Math.cos(th), cz = Math.sin(th);
				Part one = new Part(null, "", "", false, 0);
				cylinder(one, fx, fx + len, ri, ro, axis.y + (rBody + ro) * cy, axis.z + (rBody + ro) * cz, Math.max(16, seg / 2));
				for (double[] t : one.tris) {
					p.add(t);
				}
				p.fins.add(one.tris);
				p.finDirs.add(new double[] { 0, cy, cz });
			}
		}
	}

	/** Rail buttons: base, standoff and flange, standing out from the body at each button's position. */
	private static void railButtons(Part p, RailButton b, int seg) {
		double ro = b.getOuterDiameter() / 2, rs = Math.min(b.getInnerDiameter() / 2, ro), base = b.getBaseHeight(), total = b.getTotalHeight();
		double flange = b.getFlangeHeight(), stand = Math.max(0, total - base - flange), sink = Math.min(0.3 * ro, 2e-3);
		Coordinate[] axes = b.getParent().toAbsolute(Coordinate.NUL);
		for (Coordinate a : b.toAbsolute(Coordinate.NUL)) {
			Coordinate axis = axes[0];
			for (Coordinate q : axes) {
				if (Math.hypot(q.y - a.y, q.z - a.z) < Math.hypot(axis.y - a.y, axis.z - a.z)) {
					axis = q;
				}
			}
			double dy = a.y - axis.y, dz = a.z - axis.z, l = Math.hypot(dy, dz);
			if (l < 1e-9) {
				dy = Math.cos(b.getAngleOffset());
				dz = Math.sin(b.getAngleOffset());
				l = 1;
			}
			double[] dir = { 0, dy / l, dz / l }, u = { 1, 0, 0 }, v = { 0, dir[2], -dir[1] };
			// Its foot sinks a little into the curved skin so no gap shows under it.
			double[][] prof = { { -sink, 0 }, { -sink, ro }, { base, ro }, { base, rs }, { base + stand, rs }, { base + stand, ro }, { total, ro },
					{ total, 0 } };
			revolve(p, prof, new double[] { a.x + ro, a.y, a.z }, dir, u, v, Math.max(12, seg / 3), false);
		}
	}

	/** Each fin of the set as a closed plate at its angle, on every instance of the body it is attached to. */
	private static void fins(Part p, FinSet f) {
		Coordinate[] pts = f.getFinPoints();
		double rBody = f.getBodyRadius(), half = Math.max(f.getThickness(), 1e-4) / 2;
		double fx = f.toAbsolute(Coordinate.NUL)[0].x;
		List<double[]> poly = new ArrayList<>();
		for (Coordinate q : pts) {
			poly.add(new double[] { q.x, Math.max(0, q.y) });
		}
		if (Geometry.area(poly) < 0) {
			Collections.reverse(poly);
		}
		List<int[]> faces = Geometry.triangulate(poly);
		int n = f.getFinCount();
		List<String> seen = new ArrayList<>();
		for (Coordinate axis : f.getParent().toAbsolute(Coordinate.NUL)) {
			String key = Math.round(axis.y * 1e4) + ":" + Math.round(axis.z * 1e4);
			if (seen.contains(key)) {
				continue;
			}
			seen.add(key);
			for (int k = 0; k < n; k++) {
				double th = f.getBaseRotation() + 2 * Math.PI * k / n;
				double cy = Math.cos(th), cz = Math.sin(th), ty = -Math.sin(th), tz = Math.cos(th);
				List<double[]> fin = new ArrayList<>();
				BiFunction<double[], Double, double[]> at = (q, side) -> new double[] { fx + q[0],
						axis.y + (rBody + q[1]) * cy + side * ty, axis.z + (rBody + q[1]) * cz + side * tz };
				for (int[] t : faces) {
					fin.add(tri(at.apply(poly.get(t[0]), half), at.apply(poly.get(t[1]), half), at.apply(poly.get(t[2]), half)));
					fin.add(tri(at.apply(poly.get(t[0]), -half), at.apply(poly.get(t[2]), -half), at.apply(poly.get(t[1]), -half)));
				}
				for (int i = 0; i < poly.size(); i++) {
					double[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
					double[] a1 = at.apply(a, half), b1 = at.apply(b, half), b2 = at.apply(b, -half), a2 = at.apply(a, -half);
					fin.add(tri(a1, a2, b2));
					fin.add(tri(a1, b2, b1));
				}
				for (double[] t : fin) {
					p.add(t);
				}
				p.fins.add(fin);
				p.finDirs.add(new double[] { 0, cy, cz });
			}
		}
	}

	private static double[] tri(double[] a, double[] b, double[] c) {
		return new double[] { a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2] };
	}
}
