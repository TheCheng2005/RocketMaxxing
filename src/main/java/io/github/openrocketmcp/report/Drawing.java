package io.github.openrocketmcp.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.InternalComponent;
import info.openrocket.core.rocketcomponent.LaunchLug;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RingComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.ShockCord;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Sections;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Cut-away side view of the active configuration from OpenRocket's own geometry: the airframe (body radius profiles
 * and fin outlines, including pods) drawn translucent over what is inside it (couplers, bulkheads, centering rings,
 * motor mounts and motors, parachutes and shock cords where they are packed, electronics coloured by role, rail
 * buttons), with the separation points and the CG (launch and burnout) and CP marked, as SVG.
 */
public final class Drawing {
	private Drawing() {
	}

	private static final int W = 1200, PAD = 40, TOP = 56, BOTTOM = 84;

	record Shape(List<double[]> pts, boolean fin) {
	}

	/** An internal part as a rectangle in rocket coordinates, its style class, and a label (null = unlabelled). */
	record Part(double x0, double x1, double y0, double y1, String cls, String label) {
	}

	/** Style class of a mass component by its OpenRocket type. */
	static String massClass(MassComponent m) {
		return switch (m.getMassComponentType()) {
			case ALTIMETER, FLIGHTCOMPUTER -> "elec";
			case BATTERY -> "batt";
			case TRACKER -> "track";
			case DEPLOYMENTCHARGE -> "charge";
			case RECOVERYHARDWARE -> "hw";
			case PAYLOAD -> "payload";
			// OpenRocket has no switch type: arming switches are generic masses named as such (add_avionics_bay does).
			default -> m.getName().toLowerCase(Locale.ROOT).contains("switch") ? "switch" : "mass";
		};
	}

	static final Map<String, String> LEGEND = legend();

	private static Map<String, String> legend() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("elec", "altimeter / flight computer");
		m.put("batt", "battery");
		m.put("track", "GPS tracker");
		m.put("charge", "ejection charge");
		m.put("switch", "arming switch");
		m.put("chute", "parachute / streamer");
		m.put("cord", "shock cord");
		m.put("hw", "connector / sled");
		m.put("payload", "payload");
		m.put("mass", "mass / ballast");
		m.put("motor", "motor");
		m.put("struct", "coupler, bulkhead, ring, mount");
		return m;
	}

	/** Everything inside the airframe, at its instances' positions, drawn under the translucent outer shell. */
	static List<Part> internals(FlightConfiguration fc) {
		List<Part> out = new ArrayList<>();
		for (RocketComponent c : fc.getAllActiveComponents()) {
			if (c instanceof SymmetricComponent sc && sc.getLength() > 0) {
				// Nose cone / transition shoulders sit inside the next tube.
				double sl = 0, sr = 0, sx = 0;
				if (c instanceof Transition t && t.getAftShoulderLength() > 0) {
					sl = t.getAftShoulderLength();
					sr = t.getAftShoulderRadius();
					sx = t.getLength();
				}
				if (sl > 0) {
					for (Coordinate a : unique(c.toAbsolute(Coordinate.NUL))) {
						out.add(new Part(a.x + sx, a.x + sx + sl, a.y - sr, a.y + sr, "shoulder", null));
					}
				}
				continue;
			}
			String cls = null, label = null;
			double len = 0, rOut = 0, rIn = 0;
			if (c instanceof RecoveryDevice rd) {
				cls = "chute";
				len = rd.getLength();
				rOut = rd.getRadius();
				label = rd.getName() + (rd instanceof Parachute p ? " (" + Units.fmt(p.getDiameter(), Dim.LENGTH).split(" \\(")[0] + ")" : "");
			} else if (c instanceof ShockCord sc) {
				cls = "cord";
				len = sc.getLength();
				rOut = sc.getRadius();
			} else if (c instanceof MassComponent m) {
				cls = massClass(m);
				len = m.getLength();
				rOut = m.getRadius();
				// Label what a reviewer looks for; batteries, charges, switches and hardware are told apart by colour.
				boolean key = switch (m.getMassComponentType()) {
					case ALTIMETER, FLIGHTCOMPUTER, TRACKER, PAYLOAD -> true;
					case MASSCOMPONENT -> m.getComponentMass() >= 0.05; // ballast and other significant masses
					default -> false;
				};
				label = key ? m.getName() : null;
			} else if (c instanceof RingComponent rc && c instanceof InternalComponent) {
				cls = "struct";
				len = rc.getLength();
				rOut = rc.getOuterRadius();
				rIn = rc.getInnerRadius();
				if (c instanceof InnerTube || c instanceof TubeCoupler) {
					cls = "tube";
				}
			} else if (c instanceof RailButton || c instanceof LaunchLug) {
				cls = "struct";
				len = c instanceof RailButton rb ? rb.getOuterDiameter() : c.getLength();
				rOut = 0;
			} else {
				continue;
			}
			if (len <= 0) {
				continue;
			}
			for (Coordinate a : unique(c.toAbsolute(Coordinate.NUL))) {
				double x0 = a.x, x1 = a.x + len;
				if (c instanceof RailButton || c instanceof LaunchLug) {
					double body = c.getParent() instanceof SymmetricComponent p ? p.getRadius(Math.max(0, a.x - p.toAbsolute(Coordinate.NUL)[0].x)) : 0;
					out.add(new Part(x0 - len / 2 * (c instanceof RailButton ? 1 : 0), x1 - len / 2 * (c instanceof RailButton ? 1 : 0),
							body, body + Math.max(len * 0.4, 0.004), "struct", null));
				} else if ("tube".equals(cls)) {
					// Tube walls (top and bottom) so what is inside stays visible.
					out.add(new Part(x0, x1, a.y + rIn, a.y + rOut, "tube", null));
					out.add(new Part(x0, x1, a.y - rOut, a.y - rIn, "tube", null));
				} else if (c instanceof RingComponent) {
					out.add(new Part(x0, x1, a.y + rIn, a.y + rOut, cls, null));
					out.add(new Part(x0, x1, a.y - rOut, a.y - rIn, cls, null));
				} else {
					out.add(new Part(x0, x1, a.y - rOut, a.y + rOut, cls, label));
					label = null; // label one instance
				}
			}
		}
		// Motors, from the configuration's motor choices.
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (mc.getMotor() == null) {
				continue;
			}
			RocketComponent mount = (RocketComponent) mc.getMount();
			double ml = mc.getMotor().getLength(), mr = mc.getMotor().getDiameter() / 2;
			boolean first = true;
			for (Coordinate a : unique(mount.toAbsolute(Coordinate.NUL))) {
				double aft = a.x + mount.getLength() + mc.getMount().getMotorOverhang();
				out.add(new Part(aft - ml, aft, a.y - mr, a.y + mr, "motor", first ? mc.getMotor().getDesignation() : null));
				first = false;
			}
		}
		return out;
	}

	/** Where the airframe separates in flight: the forward end of every section after the first. */
	static List<Double> separations(FlightConfiguration fc) {
		List<Double> xs = new ArrayList<>();
		try {
			List<Sections.Section> secs = Sections.of(fc, null);
			for (int i = 1; i < secs.size(); i++) {
				RocketComponent first = secs.get(i).pieces().get(0);
				xs.add(first.toAbsolute(Coordinate.NUL)[0].x);
			}
		} catch (RuntimeException e) {
			// no recovery devices, or a layout Sections cannot split: draw without separation marks
		}
		return xs;
	}

	/** Outlines in rocket coordinates (x aft from the nose tip, y up), one closed polygon per body / fin instance. */
	static List<Shape> outlines(FlightConfiguration fc) {
		List<Shape> out = new ArrayList<>();
		for (RocketComponent c : fc.getAllActiveComponents()) {
			if (c instanceof SymmetricComponent sc && sc.getLength() > 0) {
				for (Coordinate a : unique(c.toAbsolute(Coordinate.NUL))) {
					int n = 40;
					List<double[]> top = new ArrayList<>(), bottom = new ArrayList<>();
					for (int i = 0; i <= n; i++) {
						double x = sc.getLength() * i / n;
						double r = sc.getRadius(x);
						top.add(new double[] { a.x + x, a.y + r });
						bottom.add(0, new double[] { a.x + x, a.y - r });
					}
					top.addAll(bottom);
					out.add(new Shape(top, false));
				}
			} else if (c instanceof FinSet f) {
				Coordinate[] pts = f.getFinPoints();
				double r = f.getBodyRadius();
				// Fin instances sit around the body; draw two (top and bottom) per body instance (pods included).
				double fx = f.toAbsolute(Coordinate.NUL)[0].x;
				for (Coordinate a : unique(f.getParent().toAbsolute(Coordinate.NUL))) {
					for (int side : new int[] { 1, -1 }) {
						List<double[]> poly = new ArrayList<>();
						for (Coordinate p : pts) {
							poly.add(new double[] { fx + p.x, a.y + side * (r + p.y) });
						}
						out.add(new Shape(poly, true));
					}
				}
			}
		}
		return out;
	}

	/** Instance positions projected on the drawing plane, without duplicates (fins at different angles share one). */
	private static List<Coordinate> unique(Coordinate[] cs) {
		Set<String> seen = new LinkedHashSet<>();
		List<Coordinate> out = new ArrayList<>();
		for (Coordinate c : cs) {
			if (seen.add(Math.round(c.x * 1e4) + ":" + Math.round(c.y * 1e4))) {
				out.add(c);
			}
		}
		return out;
	}

	/** "Design (motor)": OpenRocket names a configuration "[motor-delay]"; the brackets are dropped. */
	public static String title(String design, FlightConfiguration fc) {
		String n = fc.getName().replaceAll("^\\[(.*)]$", "$1");
		return n.isBlank() ? design : design + " (" + n + ")";
	}

	public static String svg(FlightConfiguration fc, String title) {
		List<Shape> shapes = outlines(fc);
		List<Part> parts = internals(fc);
		double xmin = 0, xmax = 0, ymax = 0;
		for (Shape s : shapes) {
			for (double[] p : s.pts()) {
				xmin = Math.min(xmin, p[0]);
				xmax = Math.max(xmax, p[0]);
				ymax = Math.max(ymax, Math.abs(p[1]));
			}
		}
		if (xmax - xmin <= 0) {
			xmax = xmin + 1;
		}
		double scale = (W - 2 * PAD) / (xmax - xmin);
		final double x0 = xmin;
		DoubleUnaryOperator sx = x -> PAD + (x - x0) * scale;

		// Labels for internal parts sit above the rocket in rows, so they never overlap.
		List<Part> labelled = new ArrayList<>();
		for (Part p : parts) {
			if (p.label() != null) {
				labelled.add(p);
			}
		}
		labelled.sort((a, b) -> Double.compare(a.x0() + a.x1(), b.x0() + b.x1()));
		List<Double> rowEnd = new ArrayList<>();
		int[] row = new int[labelled.size()];
		for (int i = 0; i < labelled.size(); i++) {
			Part p = labelled.get(i);
			double cx = sx.applyAsDouble((p.x0() + p.x1()) / 2), half = p.label().length() * 3.3 + 4;
			int r = 0;
			while (r < rowEnd.size() && cx - half < rowEnd.get(r)) {
				r++;
			}
			if (r == rowEnd.size()) {
				rowEnd.add(0.0);
			}
			rowEnd.set(r, cx + half);
			row[i] = r;
		}
		int labelH = rowEnd.size() * 15 + (rowEnd.isEmpty() ? 0 : 10);
		int bodyH = (int) Math.ceil(2 * ymax * scale);
		int top = TOP + labelH;
		Set<String> used = new LinkedHashSet<>();
		for (Part p : parts) {
			used.add(p.cls().equals("tube") || p.cls().equals("shoulder") ? "struct" : p.cls());
		}
		List<String> legend = new ArrayList<>();
		for (String k : LEGEND.keySet()) {
			if (used.contains(k)) {
				legend.add(k);
			}
		}
		int legendRows = (legend.size() + 3) / 4;
		// Below the body: CG / CP labels (50 px), the legend, then two lines of figures.
		int h = top + Math.max(bodyH, 20) + 58 + legendRows * 18 + 44;
		double cy = top + Math.max(bodyH, 20) / 2.0;
		DoubleUnaryOperator sy = y -> cy - y * scale;

		Analysis.Stability st = Analysis.stability(fc, 0.3);
		StringBuilder s = new StringBuilder();
		s.append(String.format(Locale.ROOT,
				"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" role=\"img\" aria-label=\"%s\">%n",
				W, h, W, h, Xml.esc(title)));
		s.append("<style>.bg{fill:#fcfcfb}.bodyfill{fill:#eef2f8;stroke:none}.body{fill:none;stroke:#2a4a78;stroke-width:1.2}")
				.append(".fin{fill:#c9d7ec;stroke:#2a4a78;stroke-width:1.2}")
				.append(".axis{stroke:#b8b7ae;stroke-width:1;stroke-dasharray:4 4}.t1{fill:#1a1a19;font:600 15px system-ui,sans-serif}")
				.append(".t2{fill:#5f5e57;font:12px system-ui,sans-serif}.t3{fill:#1a1a19;font:11px system-ui,sans-serif}")
				.append(".lead{stroke:#8a897f;stroke-width:.8}.sep{stroke:#c0392b;stroke-width:1.5;stroke-dasharray:5 3}")
				.append(".cg{fill:#1a1a19}.cp{fill:#c0392b}.cgb{fill:#7f8c8d}")
				.append(".struct{fill:#8a96a8;stroke:#5a6678;stroke-width:.6}.tube{fill:#aab4c3;stroke:#5a6678;stroke-width:.6}")
				.append(".shoulder{fill:none;stroke:#2a4a78;stroke-width:.8;stroke-dasharray:3 2}")
				.append(".chute{fill:#f0a64a;stroke:#b86e12;stroke-width:.8}.cord{fill:#f6d7a8;stroke:#c9a063;stroke-width:.6}")
				.append(".elec{fill:#2e9e5b;stroke:#1d6b3c;stroke-width:.8}.batt{fill:#e8c33c;stroke:#9c7f14;stroke-width:.8}")
				.append(".track{fill:#8e5cc9;stroke:#5d3a8a;stroke-width:.8}.charge{fill:#d63b2f;stroke:#8f2019;stroke-width:.8}")
				.append(".hw{fill:#b9b3a8;stroke:#7d776c;stroke-width:.6}.payload{fill:#4aa3c9;stroke:#2a6f8c;stroke-width:.8}")
				.append(".mass{fill:#9b9b9b;stroke:#666;stroke-width:.8}.switch{fill:#ffffff;stroke:#1a1a19;stroke-width:1.2}.motor{fill:#4a4a4a;stroke:#222;stroke-width:.8}")
				.append("@media (prefers-color-scheme: dark){.bg{fill:#1a1a19}.bodyfill{fill:#22324a}.body{stroke:#9db8e0}.fin{fill:#34496a;stroke:#9db8e0}")
				.append(".t1{fill:#fff}.t2{fill:#c3c2b7}.t3{fill:#eee}.cg{fill:#fff}.cgb{fill:#aab}.axis{stroke:#5f5e57}.lead{stroke:#77766c}")
				.append(".shoulder{stroke:#9db8e0}.motor{fill:#777;stroke:#bbb}}</style>\n");
		s.append(String.format(Locale.ROOT, "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>%n", W, h));
		s.append(String.format(Locale.ROOT, "<text class=\"t1\" x=\"%d\" y=\"26\">%s</text>%n", PAD, Xml.esc(title)));
		// The shell's fill behind everything, the inside over it, then the shell's outline and the fins on top.
		for (Shape sh : shapes) {
			if (!sh.fin()) {
				s.append(poly(sh, "bodyfill", sx, sy));
			}
		}
		s.append(String.format(Locale.ROOT, "<line class=\"axis\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>%n", PAD - 10, W - PAD + 10, cy, cy));
		for (String layer : List.of("tube", "struct", "shoulder", "hw", "cord", "chute", "motor", "mass", "payload", "batt", "elec",
				"track", "charge", "switch")) {
			for (Part p : parts) {
				if (p.cls().equals(layer)) {
					double px0 = sx.applyAsDouble(p.x0()), px1 = sx.applyAsDouble(p.x1());
					double py0 = sy.applyAsDouble(p.y1()), py1 = sy.applyAsDouble(p.y0());
					s.append(String.format(Locale.ROOT, "<rect class=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\"/>%n",
							p.cls(), px0, py0, Math.max(px1 - px0, 1.2), Math.max(py1 - py0, 1.2),
							p.cls().equals("chute") || p.cls().equals("cord") ? "2" : "0.5"));
				}
			}
		}
		for (Shape sh : shapes) {
			if (!sh.fin()) {
				s.append(poly(sh, "body", sx, sy));
			}
		}
		for (Shape sh : shapes) {
			if (sh.fin()) {
				s.append(poly(sh, "fin", sx, sy));
			}
		}
		double bodyTop = cy - Math.max(bodyH, 20) / 2.0;
		for (double x : separations(fc)) {
			double px = sx.applyAsDouble(x);
			s.append(String.format(Locale.ROOT, "<line class=\"sep\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%.1f\" y2=\"%.1f\"/>%n", px, px,
					bodyTop - 6, cy + Math.max(bodyH, 20) / 2.0 + 6));
		}
		for (int i = 0; i < labelled.size(); i++) {
			Part p = labelled.get(i);
			double cx = sx.applyAsDouble((p.x0() + p.x1()) / 2);
			double ly = top - 10 - row[i] * 15;
			double partTop = sy.applyAsDouble(p.y1());
			s.append(String.format(Locale.ROOT, "<line class=\"lead\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%.1f\" y2=\"%.1f\"/>%n", cx, cx,
					ly + 3, partTop));
			s.append(String.format(Locale.ROOT, "<text class=\"t3\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>%n", cx, ly,
					Xml.esc(p.label())));
		}
		marker(s, "cg", sx.applyAsDouble(st.cgLaunchX()), cy, "CG " + Units.fmt(st.cgLaunchX(), Dim.LENGTH), cy + Math.max(bodyH, 20) / 2.0 + 24);
		marker(s, "cgb", sx.applyAsDouble(st.cgBurnoutX()), cy, null, 0);
		marker(s, "cp", sx.applyAsDouble(st.cpX()), cy, "CP " + Units.fmt(st.cpX(), Dim.LENGTH), cy + Math.max(bodyH, 20) / 2.0 + 42);
		int ly = (int) (cy + Math.max(bodyH, 20) / 2.0 + 72);
		for (int i = 0; i < legend.size(); i++) {
			int lx = PAD + (i % 4) * 260, yy = ly + (i / 4) * 18;
			s.append(String.format(Locale.ROOT, "<rect class=\"%s\" x=\"%d\" y=\"%d\" width=\"14\" height=\"9\"/>"
					+ "<text class=\"t2\" x=\"%d\" y=\"%d\">%s</text>%n", legend.get(i), lx, yy - 8, lx + 20, yy, Xml.esc(LEGEND.get(legend.get(i)))));
		}
		String sepNote = separations(fc).isEmpty() ? "" : " Red dashes: where the airframe separates.";
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%d\">Length %s, max diameter %s, launch mass %s.%s</text>%n"
				+ "<text class=\"t2\" x=\"%d\" y=\"%d\">Stability %s cal at launch, %s cal at burnout (Mach 0.3). Grey marker: burnout CG.</text>%n",
				PAD, h - 24,
				Xml.esc(Units.fmt(st.length(), Dim.LENGTH)), Xml.esc(Units.fmt(st.referenceDiameter(), Dim.LENGTH)),
				Xml.esc(Units.fmt(st.launchMass(), Dim.MASS)), sepNote, PAD, h - 8, Units.num(st.marginCalibers()),
				Units.num(st.burnoutMarginCalibers())));
		s.append("</svg>\n");
		return s.toString();
	}

	private static String poly(Shape sh, String cls, DoubleUnaryOperator sx, DoubleUnaryOperator sy) {
		StringBuilder p = new StringBuilder();
		for (double[] pt : sh.pts()) {
			p.append(String.format(Locale.ROOT, "%.1f,%.1f ", sx.applyAsDouble(pt[0]), sy.applyAsDouble(pt[1])));
		}
		return "<polygon class=\"" + cls + "\" points=\"" + p.toString().trim() + "\"/>\n";
	}

	/** CG: black/white quartered circle; CP: red circle with a dot. */
	private static void marker(StringBuilder s, String cls, double x, double cy, String label, double labelY) {
		if (Double.isNaN(x)) {
			return;
		}
		if (cls.startsWith("cg")) {
			s.append(String.format(Locale.ROOT, "<circle class=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"7\"/>"
					+ "<path d=\"M%.1f %.1f h7 a7 7 0 0 1 -7 7 z M%.1f %.1f h-7 a7 7 0 0 1 7 -7 z\" fill=\"#fcfcfb\"/>%n",
					cls, x, cy, x, cy, x, cy));
		} else {
			s.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"7\" fill=\"none\" stroke=\"#c0392b\" stroke-width=\"2\"/>"
					+ "<circle class=\"cp\" cx=\"%.1f\" cy=\"%.1f\" r=\"2.5\"/>%n", x, cy, x, cy));
		}
		if (label != null) {
			s.append(String.format(Locale.ROOT, "<line class=\"axis\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%.1f\" y2=\"%.1f\"/>%n", x, x, cy + 9, labelY - 12));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>%n", x, labelY,
					Xml.esc(label)));
		}
	}
}
