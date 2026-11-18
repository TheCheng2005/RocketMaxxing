package io.github.openrocketmcp.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.Streamer;

/**
 * Recognisable shapes for the parts inside the rocket, in place of plain cylinders: a packed parachute, a rolled
 * streamer, a coiled shock cord, circuit boards with chips, a GPS board with its antenna, a 9 V battery or a LiPo pack,
 * an ejection-charge canister, an arming switch, a U-bolt and its plate, a sled with its rods, a payload box. Each
 * stays inside the space OpenRocket gives the part (its length, and its radius within the tube it sits in), so the
 * cut-away, the exploded view and the mass layout read the same as before.
 *
 * <p>Every face is wound to face away from the shape's own middle, so it is lit from outside.
 */
final class Shapes3d {
	private Shapes3d() {
	}

	/**
	 * Draws one instance of {@code c} from x0 for len, within radius r of the axis at (cy, cz); false when the part has
	 * no shape of its own here (it stays a cylinder).
	 */
	static boolean draw(Model3d.Part p, RocketComponent c, String kind, double x0, double len, double r, double cy, double cz, int seg) {
		if (len <= 0 || r <= 0) {
			return false;
		}
		String name = c.getName() == null ? "" : c.getName().toLowerCase(Locale.ROOT);
		switch (kind) {
			case "chute" -> {
				if (c instanceof Streamer) {
					pillow(p, x0, len, r * 0.94, cy, cz, seg, 0, 0, 3.5);
				} else {
					pillow(p, x0, len, r * 0.94, cy, cz, Math.max(seg, 30), 10, 0.08, 6);
				}
			}
			case "cord" -> coil(p, x0, len, r, cy, cz);
			case "elec" -> board(p, x0, len, r, cy, cz, false);
			case "track" -> board(p, x0, len, r, cy, cz, true);
			case "batt" -> battery(p, x0, len, r, cy, cz, name, seg);
			case "charge" -> canister(p, x0, len, r, cy, cz, seg);
			case "switch" -> armSwitch(p, x0, len, r, cy, cz, seg);
			case "payload" -> box(p, x0 + len / 2, cy, cz, len / 2, r * 0.66, r * 0.66);
			case "hw" -> {
				if (name.contains("sled")) {
					sled(p, x0, len, r, cy, cz, seg);
				} else {
					uBolt(p, x0, len, r, cy, cz);
				}
			}
			case "mass" -> {
				if (c instanceof MassComponent && name.contains("sled")) {
					sled(p, x0, len, r, cy, cz, seg);
				} else {
					return false; // ballast and other masses: a slug that fills its space (a nose cone's taper too)
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	// -------------------------------------------------------------------------------------------------- the shapes

	/**
	 * A soft bundle with rounded ends (a packed parachute, a rolled streamer): radius r in the middle, folds round it
	 * ({@code folds} pleats of depth {@code amp}), ends as round as {@code k} makes them (2: a lozenge, higher: flatter).
	 */
	static void pillow(Model3d.Part p, double x0, double len, double r, double cy, double cz, int seg, int folds, double amp, double k) {
		int nx = 24;
		double[][][] v = new double[nx + 1][seg][];
		for (int i = 0; i <= nx; i++) {
			double t = (double) i / nx, e = Math.abs(2 * t - 1);
			double along = Math.pow(Math.max(0, 1 - Math.pow(e, k)), 1 / k);
			// a little cinched in at the middle, as a deployment bag or a rolled chute is where it is wrapped
			double waist = 1 - 0.05 * Math.exp(-Math.pow((t - 0.5) / 0.08, 2));
			for (int j = 0; j < seg; j++) {
				double th = 2 * Math.PI * j / seg;
				double rr = r * along * waist * (folds > 0 ? 1 - amp * (0.5 + 0.5 * Math.cos(folds * th)) : 1);
				v[i][j] = new double[] { x0 + t * len, cy + rr * Math.cos(th), cz + rr * Math.sin(th) };
			}
		}
		for (int i = 0; i < nx; i++) {
			double[] mid = { x0 + (i + 0.5) / nx * len, cy, cz };
			for (int j = 0; j < seg; j++) {
				int j1 = (j + 1) % seg;
				quad(p, v[i][j], v[i][j1], v[i + 1][j1], v[i + 1][j], mid);
			}
		}
	}

	/** A shock cord coiled into a hank: a cord swept along a helix that fills the part's space. */
	static void coil(Model3d.Part p, double x0, double len, double r, double cy, double cz) {
		double rc = Math.min(Math.min(Math.max(r * 0.13, 1.2e-3), r * 0.28), len / 6);
		double R = r - rc;
		double pitch = Math.max(rc * 2.3, (len - 2 * rc) / 30); // at most 30 turns: a looser coil when long
		int turns = Math.max(1, (int) Math.round((len - 2 * rc) / pitch));
		int per = 16;
		List<double[]> path = new ArrayList<>();
		for (int i = 0; i <= turns * per; i++) {
			double th = 2 * Math.PI * i / per;
			double x = x0 + rc + (len - 2 * rc) * i / (turns * per);
			path.add(new double[] { x, cy + R * Math.cos(th), cz + R * Math.sin(th) });
		}
		sweep(p, path, rc, 8);
	}

	/**
	 * A circuit board along the axis (an altimeter or flight computer), with a processor, a pressure sensor, a screw
	 * terminal block at the aft end and, for a GPS tracker, a whip antenna off the front.
	 */
	static void board(Model3d.Part p, double x0, double len, double r, double cy, double cz, boolean antenna) {
		double ant = antenna ? len * 0.35 : 0;
		double bx0 = x0 + ant, bl = len - ant;
		double w = r * 0.85, th = Math.min(1.6e-3, r * 0.1), y = cy - r * 0.15;
		box(p, bx0 + bl / 2, y, cz, bl / 2, th / 2, w);
		double top = y + th / 2;
		double chip = Math.min(w * 0.35, bl * 0.18), ch = Math.min(1.2e-3, r * 0.08);
		box(p, bx0 + bl * 0.42, top + ch / 2, cz - w * 0.2, chip, ch / 2, chip);
		box(p, bx0 + bl * 0.68, top + ch / 2, cz + w * 0.35, chip * 0.45, ch / 2, chip * 0.45);
		// screw terminals for the battery, switch and charges
		double tl = Math.min(bl * 0.12, 6e-3), thh = Math.min(8e-3, r * 0.45);
		box(p, bx0 + bl - tl / 2 - th, top + thh / 2, cz, tl / 2, thh / 2, w * 0.8);
		if (antenna) {
			Model3d.cylinder(p, x0, bx0 + bl * 0.1, 0, Math.max(5e-4, r * 0.05), top + Math.min(r * 0.15, 3e-3), cz, 10);
		}
	}

	/** A 9 V battery (a block with its two snaps) or, named as one, a flat LiPo pack with its lead. */
	static void battery(Model3d.Part p, double x0, double len, double r, double cy, double cz, String name, int seg) {
		boolean lipo = name.matches(".*(lipo|li-po|li-ion|1s|2s|3s|pouch|18650).*");
		if (lipo) {
			double w = r * 0.85, h = r * 0.28, body = len * 0.88;
			box(p, x0 + len - body / 2, cy, cz, body / 2, h, w);
			Model3d.cylinder(p, x0, x0 + len - body, 0, Math.max(6e-4, r * 0.06), cy, cz + w * 0.4, 10);
			return;
		}
		double w = r * 0.78, h = w * 0.66, snap = Math.min(len * 0.12, 4e-3);
		box(p, x0 + snap + (len - snap) / 2, cy, cz, (len - snap) / 2, h, w);
		double sr = Math.min(h, w) * 0.32;
		Model3d.cylinder(p, x0, x0 + snap, 0, sr, cy, cz - w * 0.45, Math.max(12, seg / 2));
		Model3d.lathe(p, new double[][] { { x0, 0 }, { x0, sr * 1.05 }, { x0 + snap, sr * 1.05 }, { x0 + snap, 0 } }, cy, cz + w * 0.45,
				6, false); // the other snap: six-sided
	}

	/** An ejection-charge well: a canister with a wider cap at the front. */
	static void canister(Model3d.Part p, double x0, double len, double r, double cy, double cz, int seg) {
		double cap = Math.min(len * 0.25, Math.max(len * 0.12, 3e-3));
		Model3d.lathe(p, new double[][] { { x0, 0 }, { x0, r * 0.98 }, { x0 + cap, r * 0.98 }, { x0 + cap, r * 0.78 }, { x0 + len, r * 0.78 },
				{ x0 + len, 0 } }, cy, cz, Math.max(seg, 20), false);
	}

	/** An arming switch: a small block with a screw head on top (a screw switch, the usual kind). */
	static void armSwitch(Model3d.Part p, double x0, double len, double r, double cy, double cz, int seg) {
		double w = r * 0.62, h = r * 0.42;
		box(p, x0 + len / 2, cy - h * 0.2, cz, len / 2, h * 0.8, w);
		double hr = Math.min(len, 2 * w) * 0.3, hh = Math.min(h * 0.6, r - h * 0.6);
		// the screw head, standing up across the axis
		Model3d.revolve(p, new double[][] { { 0, 0 }, { 0, hr }, { hh, hr }, { hh, 0 } }, new double[] { x0 + len / 2, cy + h * 0.6, cz },
				new double[] { 0, 1, 0 }, new double[] { 0, 0, 1 }, new double[] { 1, 0, 0 }, Math.max(12, seg / 2), false);
	}

	/** A U-bolt through its backing plate: the plate at the front, the bend aft. */
	static void uBolt(Model3d.Part p, double x0, double len, double r, double cy, double cz) {
		double rod = Math.min(Math.min(Math.max(r * 0.12, 1.5e-3), len / 6), r * 0.25);
		double s = Math.min(r * 0.5, Math.max(len * 0.35, 2 * rod)), plate = Math.min(len * 0.15, 3e-3);
		box(p, x0 + plate / 2, cy, cz, plate / 2, r * 0.25, Math.min(r * 0.85, s + 2.5 * rod));
		double xb = x0 + len - s - rod;
		List<double[]> path = new ArrayList<>();
		path.add(new double[] { x0, cy, cz - s });
		for (int i = 0; i <= 8; i++) {
			path.add(new double[] { x0 + (xb - x0) * i / 8, cy, cz - s });
		}
		for (int i = 1; i < 16; i++) {
			double a = -Math.PI / 2 + Math.PI * i / 16;
			path.add(new double[] { xb + s * Math.cos(a), cy, cz + s * Math.sin(a) });
		}
		for (int i = 0; i <= 8; i++) {
			path.add(new double[] { xb - (xb - x0) * i / 8, cy, cz + s });
		}
		path.remove(0);
		sweep(p, path, rod, 8);
	}

	/** An electronics sled: a plate along the axis between two threaded rods. */
	static void sled(Model3d.Part p, double x0, double len, double r, double cy, double cz, int seg) {
		double w = r * 0.7, th = Math.min(3e-3, r * 0.12);
		box(p, x0 + len / 2, cy - r * 0.2, cz, len / 2, th / 2, w);
		double rr = Math.min(Math.max(r * 0.05, 1.2e-3), r * 0.1);
		for (double side : new double[] { -1, 1 }) {
			Model3d.cylinder(p, x0, x0 + len, 0, rr, cy + r * 0.35, cz + side * r * 0.75, 10);
		}
	}

	// ----------------------------------------------------------------------------------------------- the building blocks

	/** A box centred at (x, y, z) with half sizes hx (along the axis), hy and hz. */
	static void box(Model3d.Part p, double x, double y, double z, double hx, double hy, double hz) {
		double[][] c = new double[8][];
		for (int i = 0; i < 8; i++) {
			c[i] = new double[] { x + ((i & 1) == 0 ? -hx : hx), y + ((i & 2) == 0 ? -hy : hy), z + ((i & 4) == 0 ? -hz : hz) };
		}
		double[] mid = { x, y, z };
		int[][] faces = { { 0, 1, 3, 2 }, { 4, 6, 7, 5 }, { 0, 4, 5, 1 }, { 2, 3, 7, 6 }, { 0, 2, 6, 4 }, { 1, 5, 7, 3 } };
		for (int[] f : faces) {
			quad(p, c[f[0]], c[f[1]], c[f[2]], c[f[3]], mid);
		}
	}

	/** A round cord of radius rc along a path, its ends closed. */
	static void sweep(Model3d.Part p, List<double[]> path, double rc, int sides) {
		int n = path.size();
		double[][][] ring = new double[n][sides][];
		double[] prevU = null;
		for (int i = 0; i < n; i++) {
			double[] a = path.get(Math.max(0, i - 1)), b = path.get(Math.min(n - 1, i + 1));
			double[] t = norm(sub(b, a));
			// carried along from the last ring, so the cord does not twist
			double[] u = prevU == null ? anyNormal(t) : norm(sub(prevU, scale(t, dot(prevU, t))));
			double[] v = cross(t, u);
			prevU = u;
			double[] c = path.get(i);
			for (int j = 0; j < sides; j++) {
				double th = 2 * Math.PI * j / sides;
				ring[i][j] = add(c, add(scale(u, rc * Math.cos(th)), scale(v, rc * Math.sin(th))));
			}
		}
		for (int i = 0; i < n - 1; i++) {
			double[] mid = scale(add(path.get(i), path.get(i + 1)), 0.5);
			for (int j = 0; j < sides; j++) {
				int j1 = (j + 1) % sides;
				quad(p, ring[i][j], ring[i][j1], ring[i + 1][j1], ring[i + 1][j], mid);
			}
		}
		for (int end : new int[] { 0, n - 1 }) {
			double[] c = path.get(end), out = norm(sub(c, path.get(end == 0 ? 1 : n - 2)));
			double[] inside = sub(c, scale(out, rc));
			for (int j = 0; j < sides; j++) {
				tri(p, c, ring[end][j], ring[end][(j + 1) % sides], inside);
			}
		}
	}

	/** Two triangles a-b-c, a-c-d, facing away from {@code inside}. */
	static void quad(Model3d.Part p, double[] a, double[] b, double[] c, double[] d, double[] inside) {
		tri(p, a, b, c, inside);
		tri(p, a, c, d, inside);
	}

	/** One triangle, wound to face away from {@code inside}; a degenerate one is left out. */
	static void tri(Model3d.Part p, double[] a, double[] b, double[] c, double[] inside) {
		double[] n = cross(sub(b, a), sub(c, a));
		if (dot(n, n) < 1e-24) {
			return;
		}
		double[] centre = scale(add(add(a, b), c), 1.0 / 3);
		if (dot(n, sub(centre, inside)) < 0) {
			double[] t = b;
			b = c;
			c = t;
		}
		p.add(new double[] { a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2] });
	}

	private static double[] anyNormal(double[] t) {
		double[] k = Math.abs(t[0]) < 0.9 ? new double[] { 1, 0, 0 } : new double[] { 0, 1, 0 };
		return norm(cross(t, k));
	}

	private static double[] add(double[] a, double[] b) {
		return new double[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] };
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
	}

	private static double[] scale(double[] a, double s) {
		return new double[] { a[0] * s, a[1] * s, a[2] * s };
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
	}

	private static double[] norm(double[] a) {
		double l = Math.sqrt(dot(a, a));
		return l < 1e-15 ? new double[] { 1, 0, 0 } : scale(a, 1 / l);
	}
}
