package io.github.openrocketmcp.report;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Shaded 3-D pictures of the rocket: assembled, cut away (the near half of the airframe removed so the parts inside
 * show in place), or exploded (airframe pieces pulled apart along the axis, fins slid out, and everything inside laid
 * out below its piece with guide lines back to where it fits). Parts carry numbered balloons that key into a parts list
 * with OpenRocket's masses, as on an assembly drawing.
 */
public final class View3d {
	private View3d() {
	}

	public enum Mode {
		EXPLODED, ASSEMBLED, CUTAWAY;

		public static Mode of(String s) {
			return s == null ? EXPLODED : valueOf(s.trim().toUpperCase(Locale.ROOT));
		}
	}

	/** The picture and its parts list (number, name, kind, mass, piece). */
	public record Result(BufferedImage image, List<Map<String, Object>> parts) {
	}

	static final Map<String, String> KINDS = kinds();

	private static Map<String, String> kinds() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("nose", "nose cone");
		m.put("body", "body tube");
		m.put("transition", "transition");
		m.put("fins", "fin set");
		m.put("tube", "coupler / inner tube");
		m.putAll(Drawing.LEGEND);
		m.put("struct", "bulkhead / centering ring");
		return m;
	}

	private static final int PAD = 36, TITLE = 64;

	public static Result render(FlightConfiguration fc, String title, Mode mode, double azimuthDeg, double elevationDeg,
			int width) {
		List<Model3d.Part> parts = Model3d.build(fc, 64);
		if (parts.stream().noneMatch(p -> !p.internal)) {
			throw new ToolException("The design has no airframe yet (nose cone, body tube or fins) to draw.");
		}
		if (mode == Mode.ASSEMBLED) {
			parts.removeIf(p -> p.internal);
		}
		double length = Math.max(1e-3, fc.getLength()), maxR = 0;
		int pieces = 0;
		for (Model3d.Part p : parts) {
			if (!p.internal) {
				maxR = Math.max(maxR, p.rMax);
				pieces = Math.max(pieces, p.piece + 1);
			}
		}
		maxR = Math.max(maxR, 1e-3);
		double finOut = 0;
		List<double[]> guides = new ArrayList<>(); // installed centre, exploded centre (rocket frame)
		if (mode == Mode.EXPLODED) {
			double gap = Math.max(3 * maxR, 0.06 * length);
			finOut = 1.2 * maxR;
			int stages = 0;
			for (Model3d.Part p : parts) {
				stages = Math.max(stages, p.axialStage);
			}
			double metresPerPixel = (length + (pieces + STAGE_GAP * stages) * gap) / (width - 2.0 * PAD);
			for (Model3d.Part p : parts) {
				p.opened(); // parachutes laid out open
			}
			layOut(parts, gap, maxR, metresPerPixel, guides);
		}
		// Camera: looking at the rocket's side (nose to the left), turned toward the nose and down from above.
		double az = Math.toRadians(azimuthDeg), el = Math.toRadians(elevationDeg);
		double[] toEye = { -Math.sin(az) * Math.cos(el), -Math.cos(az) * Math.cos(el), Math.sin(el) };
		Raster3d.Camera cam = Raster3d.Camera.ortho(new double[] { -toEye[0], -toEye[1], -toEye[2] });
		List<Raster3d.Mesh> meshes = new ArrayList<>();
		List<double[]> all = new ArrayList<>();
		for (Model3d.Part p : parts) {
			List<double[]> tris = p.placed(finOut);
			if (mode == Mode.CUTAWAY && (!p.internal && !p.kind.equals("fins") || p.kind.equals("tube"))) {
				tris = cutNearHalf(tris, toEye);
			}
			meshes.add(new Raster3d.Mesh(tris, p.rgb()));
			for (double[] t : tris) {
				for (int v = 0; v < 3; v++) {
					all.add(new double[] { t[3 * v], t[3 * v + 1], t[3 * v + 2] });
				}
			}
		}
		double[] b = cam.bounds(all);
		int listRows = (int) Math.ceil(parts.size() / (double) columns(width));
		int bottom = PAD + 20 + 22 * listRows + 28;
		int top = TITLE + (staged(parts) ? 76 : 46); // room for the stage brackets
		double scale = (width - 2.0 * PAD) / Math.max(1e-9, b[1] - b[0]);
		// Height follows the picture, up to 2.5 widths (a view down the axis would otherwise be very tall); frame() then
		// scales the picture down to fit.
		int height = (int) Math.min(Math.ceil((b[3] - b[2]) * scale), 2.5 * width) + top + bottom + 30;
		cam.frame(b, width, height, PAD, PAD, top, bottom + 30);

		// 2 x 2 supersampling costs 32 bytes per pixel: large posters render without it rather than run out of memory.
		Raster3d r = new Raster3d(width, height, (long) width * height > 6_000_000 ? 1 : 2, cam, new double[] { -0.35, -0.75, 0.6 });
		r.clear(0xf7f8fa);
		for (Raster3d.Mesh m : meshes) {
			r.draw(new Raster3d.Placed(m, Raster3d.Placed.IDENTITY, new double[] { 0, 0, 0 }));
		}
		BufferedImage img = r.image();
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		// Guide lines from each laid-out part back up to where it is installed.
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 5f, 4f }, 0f));
		g.setColor(new Color(0x9aa3ae));
		for (double[] gl : guides) {
			double[] a = r.project(new double[] { gl[0], gl[1], gl[2] }), c = r.project(new double[] { gl[3], gl[4], gl[5] });
			g.drawLine((int) a[0], (int) a[1], (int) c[0], (int) c[1]);
		}
		stageBrackets(g, r, parts, finOut);
		List<Map<String, Object>> list = balloons(g, r, parts, finOut, mode, top);
		g.setColor(new Color(0x1a1a19));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
		g.drawString(title, PAD, 40);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
		g.setColor(new Color(0x5f5e57));
		String sub = switch (mode) {
			case EXPLODED -> "Exploded view: airframe pieces pulled apart along the axis, fins slid out, internal parts laid out below "
					+ "their piece (dashed lines show where they fit)" + (parts.stream().anyMatch(p -> !p.open.isEmpty())
							? parts.stream().anyMatch(p -> !p.open.isEmpty() && p.openScale < 0.999) ? "; parachutes shown open, smaller than life" : "; parachutes shown open"
							: "");
			case CUTAWAY -> "Cut-away view: the near half of the airframe removed";
			case ASSEMBLED -> "Assembled view";
		};
		g.drawString(sub + ". Geometry and masses from OpenRocket.", PAD, 62);
		partsList(g, list, width, height - bottom + 12);
		g.dispose();
		return new Result(img, list);
	}

	/** Extra gap, in piece gaps, between one stage and the next in an exploded view. */
	static final double STAGE_GAP = 1.5;

	/** How many gaps a part moves aft: one per airframe piece ahead of it, more for each stage boundary. */
	static double slot(Model3d.Part p) {
		return p.piece + STAGE_GAP * p.axialStage;
	}

	/**
	 * Exploded layout: piece k moves aft by k gaps (and 1.5 more at each stage boundary); each internal part drops below the airframe into a lane (structure,
	 * then recovery and electronics, then motors), stacking into sub-lanes where parts would overlap along the axis.
	 */
	static void layOut(List<Model3d.Part> parts, double gap, double maxR, double metresPerPixel, List<double[]> guides) {
		String[] bands = { "struct tube", "chute cord elec batt track charge switch hw payload mass", "motor" };
		double margin = 32 * metresPerPixel; // room for a balloon to the right of each part
		double top = -1.9 * maxR - 1.2 * maxR; // below the fins, which slide out by 1.2 R
		for (String band : bands) {
			List<Model3d.Part> in = new ArrayList<>();
			for (Model3d.Part p : parts) {
				if (p.internal && (" " + band + " ").contains(" " + p.kind + " ")) {
					in.add(p);
				}
			}
			in.sort((a, b) -> Double.compare(a.x0 + slot(a) * gap, b.x0 + slot(b) * gap));
			List<List<Model3d.Part>> lanes = new ArrayList<>();
			List<Double> laneEnd = new ArrayList<>();
			for (Model3d.Part p : in) {
				double x0 = p.x0 + slot(p) * gap, x1 = p.x1 + slot(p) * gap;
				int lane = 0;
				while (lane < lanes.size() && laneEnd.get(lane) + margin > x0) {
					lane++;
				}
				if (lane == lanes.size()) {
					lanes.add(new ArrayList<>());
					laneEnd.add(-Double.MAX_VALUE);
				}
				lanes.get(lane).add(p);
				laneEnd.set(lane, x1);
			}
			for (List<Model3d.Part> lane : lanes) {
				double r = 0;
				for (Model3d.Part p : lane) {
					r = Math.max(r, halfHeight(p));
				}
				double centre = top - r;
				for (Model3d.Part p : lane) {
					double[] c = centre(p);
					p.shift = new double[] { slot(p) * gap, -c[1], centre - c[2] };
					guides.add(new double[] { c[0] + slot(p) * gap, 0, c[2], c[0] + slot(p) * gap, 0, centre + halfHeight(p) });
				}
				top = centre - r - Math.max(0.4 * maxR, 18 * metresPerPixel);
			}
		}
		for (Model3d.Part p : parts) {
			if (!p.internal) {
				p.shift = new double[] { slot(p) * gap, 0, 0 };
			}
		}
	}

	private static double[] centre(Model3d.Part p) {
		double[] lo = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE }, hi = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for (double[] t : p.tris) {
			for (int v = 0; v < 3; v++) {
				for (int k = 0; k < 3; k++) {
					lo[k] = Math.min(lo[k], t[3 * v + k]);
					hi[k] = Math.max(hi[k], t[3 * v + k]);
				}
			}
		}
		return new double[] { (lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2 };
	}

	private static double halfHeight(Model3d.Part p) {
		double[] c = centre(p);
		double h = 0;
		for (double[] t : p.tris) {
			for (int v = 0; v < 3; v++) {
				h = Math.max(h, Math.hypot(t[3 * v + 1] - c[1], t[3 * v + 2] - c[2]));
			}
		}
		return h;
	}

	/** Drops the triangles of a shell (airframe, coupler, motor mount) on the viewer's side of its axis. */
	static List<double[]> cutNearHalf(List<double[]> tris, double[] toEye) {
		double ny = toEye[1], nz = toEye[2], l = Math.hypot(ny, nz);
		List<double[]> out = new ArrayList<>();
		for (double[] t : tris) {
			double cy = (t[1] + t[4] + t[7]) / 3, cz = (t[2] + t[5] + t[8]) / 3;
			if ((cy * ny + cz * nz) / Math.max(l, 1e-9) <= 1e-6) {
				out.add(t);
			}
		}
		return out;
	}

	/** Numbered balloons with leader lines; returns the parts list in balloon order. */
	private static List<Map<String, Object>> balloons(Graphics2D g, Raster3d r, List<Model3d.Part> parts, double finOut, Mode mode,
			int top) {
		List<Model3d.Part> order = new ArrayList<>(parts);
		order.sort((a, b) -> a.piece != b.piece ? Integer.compare(a.piece, b.piece)
				: a.internal != b.internal ? (a.internal ? 1 : -1) : Double.compare(a.x0, b.x0));
		List<double[]> placed = new ArrayList<>(); // balloon centres
		List<Map<String, Object>> list = new ArrayList<>();
		Font f = new Font(Font.SANS_SERIF, Font.BOLD, 12);
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();
		int n = 0;
		for (Model3d.Part p : order) {
			n++;
			double[] box = screenBox(r, p.placed(finOut));
			if (box == null) {
				continue;
			}
			// Anchor on the part; the balloon above airframe parts, to the right of laid-out parts, below parts seen
			// through a cut-away.
			double ax, ay, bx, by, dx, dy;
			if (!p.internal) {
				ax = p.kind.equals("fins") ? box[0] + 0.7 * (box[2] - box[0]) : (box[0] + box[2]) / 2;
				ay = box[1] + (p.kind.equals("fins") ? 0.25 * (box[3] - box[1]) : 0.15 * (box[3] - box[1]));
				bx = ax;
				by = Math.max(top - 8, box[1] - 26);
				dx = 26;
				dy = -26;
			} else if (mode == Mode.EXPLODED) {
				ax = box[2];
				ay = (box[1] + box[3]) / 2;
				bx = box[2] + 22;
				by = ay;
				dx = 0;
				dy = 26;
			} else {
				ax = (box[0] + box[2]) / 2;
				ay = (box[1] + box[3]) / 2;
				bx = ax;
				by = box[3] + 30;
				dx = 26;
				dy = 26;
			}
			for (int tries = 0; tries < 12 && clash(placed, bx, by); tries++) {
				if (tries % 2 == 0) {
					bx += dx == 0 ? 26 : dx;
				} else {
					by += dy;
				}
			}
			placed.add(new double[] { bx, by });
			g.setStroke(new BasicStroke(1f));
			g.setColor(new Color(0x55606e));
			g.drawLine((int) ax, (int) ay, (int) bx, (int) by);
			g.fillOval((int) ax - 2, (int) ay - 2, 5, 5);
			g.setColor(Color.WHITE);
			g.fillOval((int) bx - 11, (int) by - 11, 22, 22);
			g.setColor(new Color(0x1a1a19));
			g.drawOval((int) bx - 11, (int) by - 11, 22, 22);
			String s = String.valueOf(n);
			g.drawString(s, (int) (bx - fm.stringWidth(s) / 2.0), (int) (by + fm.getAscent() / 2.0 - 1));
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("n", n);
			row.put("name", p.name);
			row.put("kind", KINDS.getOrDefault(p.kind, p.kind));
			if (staged(parts)) {
				row.put("stage", p.stageName);
			}
			row.put("mass", Units.fmt(p.mass, Dim.MASS));
			row.put("rgb", p.rgb());
			list.add(row);
		}
		return list;
	}

	static boolean staged(List<Model3d.Part> parts) {
		return parts.stream().anyMatch(p -> p.axialStage > 0);
	}

	/** For a staged rocket: a bracket over each stage with its name and total mass (motor included). */
	private static void stageBrackets(Graphics2D g, Raster3d r, List<Model3d.Part> parts, double finOut) {
		if (!staged(parts)) {
			return;
		}
		Map<Integer, double[]> span = new TreeMap<>(); // stage -> {min x, max x, mass}
		Map<Integer, String> names = new LinkedHashMap<>();
		for (Model3d.Part p : parts) {
			double[] box = screenBox(r, p.placed(finOut));
			if (box == null) {
				continue;
			}
			double[] s = span.computeIfAbsent(p.axialStage, k -> new double[] { Double.MAX_VALUE, -Double.MAX_VALUE, 0 });
			s[0] = Math.min(s[0], box[0]);
			s[1] = Math.max(s[1], box[2]);
			s[2] += p.mass;
			if (p.stage == p.axialStage && !p.stageName.isEmpty()) { // the in-line stage itself, not a side booster
				names.putIfAbsent(p.axialStage, p.stageName);
			}
		}
		int y = TITLE + 30;
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		FontMetrics fm = g.getFontMetrics();
		for (Map.Entry<Integer, double[]> e : span.entrySet()) {
			double[] s = e.getValue();
			g.setColor(new Color(0x2d5d9f));
			g.setStroke(new BasicStroke(1.5f));
			g.drawLine((int) s[0], y, (int) s[1], y);
			g.drawLine((int) s[0], y, (int) s[0], y + 8);
			g.drawLine((int) s[1], y, (int) s[1], y + 8);
			String label = names.getOrDefault(e.getKey(), "Stage " + (e.getKey() + 1)) + "  \u00b7  " + Units.fmt(s[2], Dim.MASS);
			int lw = fm.stringWidth(label);
			int lx = (int) ((s[0] + s[1]) / 2 - lw / 2.0);
			g.setColor(new Color(0xf7f8fa));
			g.fillRect(lx - 6, y - 10, lw + 12, 16);
			g.setColor(new Color(0x2d5d9f));
			g.drawString(label, lx, y + 4);
		}
	}

	private static boolean clash(List<double[]> placed, double x, double y) {
		for (double[] q : placed) {
			if (Math.abs(q[0] - x) < 24 && Math.abs(q[1] - y) < 24) {
				return true;
			}
		}
		return false;
	}

	/** {minX, minY, maxX, maxY} on screen. */
	private static double[] screenBox(Raster3d r, List<double[]> tris) {
		double[] b = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for (double[] t : tris) {
			for (int v = 0; v < 3; v++) {
				double[] s = r.project(new double[] { t[3 * v], t[3 * v + 1], t[3 * v + 2] });
				if (s == null) {
					continue;
				}
				b[0] = Math.min(b[0], s[0]);
				b[1] = Math.min(b[1], s[1]);
				b[2] = Math.max(b[2], s[0]);
				b[3] = Math.max(b[3], s[1]);
			}
		}
		return b[0] > b[2] ? null : b;
	}

	static int columns(int width) {
		return Math.max(1, (width - 2 * PAD) / 380);
	}

	private static void partsList(Graphics2D g, List<Map<String, Object>> list, int width, int y0) {
		int cols = columns(width), colW = (width - 2 * PAD) / cols;
		int rows = (int) Math.ceil(list.size() / (double) cols);
		g.setColor(new Color(0x1a1a19));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		g.drawString("Parts", PAD, y0);
		g.setColor(new Color(0xd8d8d2));
		g.drawLine(PAD, y0 + 6, width - PAD, y0 + 6);
		Font plain = new Font(Font.SANS_SERIF, Font.PLAIN, 13), bold = new Font(Font.SANS_SERIF, Font.BOLD, 13);
		for (int i = 0; i < list.size(); i++) {
			Map<String, Object> row = list.get(i);
			int x = PAD + (i / rows) * colW, y = y0 + 26 + (i % rows) * 22;
			g.setColor(new Color((int) row.get("rgb")));
			g.fillRect(x, y - 11, 12, 12);
			g.setColor(new Color(0x77776f));
			g.drawRect(x, y - 11, 12, 12);
			g.setColor(new Color(0x1a1a19));
			g.setFont(bold);
			g.drawString(row.get("n") + ".", x + 18, y);
			g.setFont(plain);
			String text = row.get("name") + "  ·  " + row.get("mass");
			FontMetrics fm = g.getFontMetrics();
			while (fm.stringWidth(text) > colW - 50 && text.length() > 8) {
				text = text.substring(0, text.length() - 2);
			}
			g.drawString(text, x + 42, y);
		}
		for (Map<String, Object> row : list) {
			row.remove("rgb");
		}
	}
}
