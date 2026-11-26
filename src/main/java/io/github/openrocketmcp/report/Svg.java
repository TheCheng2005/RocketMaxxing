package io.github.openrocketmcp.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Minimal static SVG line chart for design-review documents: one series, one y axis, recessive grid, an optional
 * labelled reference line (e.g. a rule minimum) and thin event markers. Light and dark themes via
 * prefers-color-scheme. The data table is the CSV written next to it.
 */
public final class Svg {
	private Svg() {
	}

	public record Marker(double x, String label) {
	}

	private static final int W = 720, H = 360, L = 64, R = 24, T = 48, B = 52;

	/** One named data series. */
	public record Series(String name, double[] x, double[] y) {
	}

	private static final String[] SERIES_CLASSES = { "series", "series2", "series3", "series4" };

	public static String line(String title, String xLabel, String yLabel, double[] x, double[] y, double refY,
			String refLabel, List<Marker> markers) {
		return lines(title, xLabel, yLabel, List.of(new Series(null, x, y)), refY, refLabel, markers);
	}

	/** Up to four series on shared axes (e.g. simulated vs measured), with a legend when named. */
	public static String lines(String title, String xLabel, String yLabel, List<Series> series, double refY,
			String refLabel, List<Marker> markers) {
		double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE, ymin = Double.MAX_VALUE, ymax = -Double.MAX_VALUE;
		for (Series ser : series) {
			double[] x = ser.x(), y = ser.y();
			for (int i = 0; i < x.length; i++) {
				if (Double.isNaN(y[i]) || Double.isInfinite(y[i])) {
					continue;
				}
				xmin = Math.min(xmin, x[i]);
				xmax = Math.max(xmax, x[i]);
				ymin = Math.min(ymin, y[i]);
				ymax = Math.max(ymax, y[i]);
			}
		}
		if (xmin > xmax) {
			xmin = 0;
			xmax = 1;
			ymin = 0;
			ymax = 1;
		}
		if (!Double.isNaN(refY)) {
			ymin = Math.min(ymin, refY);
			ymax = Math.max(ymax, refY);
		}
		double[] yt = ticks(ymin, ymax);
		double[] xt = ticks(xmin, xmax);
		final double x0 = xt[0], x1 = xt[xt.length - 1], y0 = yt[0], y1 = yt[yt.length - 1];
		DoubleUnaryOperator sx = v -> L + (v - x0) / (x1 - x0) * (W - L - R);
		DoubleUnaryOperator sy = v -> H - B - (v - y0) / (y1 - y0) * (H - T - B);

		StringBuilder s = new StringBuilder();
		s.append(String.format(Locale.ROOT,
				"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" role=\"img\" aria-label=\"%s\">%n",
				W, H, W, H, Xml.esc(title)));
		s.append("<style>\n")
				.append(".bg{fill:#fcfcfb}.grid{stroke:#e4e3de;stroke-width:1}.axis{stroke:#b8b7ae;stroke-width:1}")
				.append(".t1{fill:#1a1a19;font:600 15px system-ui,sans-serif}.t2{fill:#5f5e57;font:12px system-ui,sans-serif}")
				.append(".series{fill:none;stroke:#2a78d6;stroke-width:2;stroke-linejoin:round;stroke-linecap:round}")
				.append(".series2{fill:none;stroke:#d9480f;stroke-width:2;stroke-dasharray:5 3}")
				.append(".series3{fill:none;stroke:#2f9e44;stroke-width:2;stroke-dasharray:2 3}")
				.append(".series4{fill:none;stroke:#7048e8;stroke-width:2;stroke-dasharray:9 3 2 3}")
				.append(".ref{stroke:#5f5e57;stroke-width:1.5;stroke-dasharray:6 4}.mark{stroke:#b8b7ae;stroke-width:1}\n")
				.append("@media (prefers-color-scheme: dark){.bg{fill:#1a1a19}.grid{stroke:#34332f}.axis{stroke:#5f5e57}")
				.append(".t1{fill:#ffffff}.t2{fill:#c3c2b7}.series{stroke:#3987e5}.series2{stroke:#f08c4f}.series3{stroke:#51cf66}.series4{stroke:#9775fa}.ref{stroke:#c3c2b7}.mark{stroke:#5f5e57}}\n")
				.append("</style>\n");
		s.append(String.format(Locale.ROOT, "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>%n", W, H));
		s.append(String.format(Locale.ROOT, "<text class=\"t1\" x=\"%d\" y=\"26\">%s</text>%n", L, Xml.esc(title)));
		for (double v : yt) {
			double py = sy.applyAsDouble(v);
			s.append(String.format(Locale.ROOT, "<line class=\"grid\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>%n", L, W - R, py, py));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>%n", L - 8, py + 4, fmt(v)));
		}
		for (double v : xt) {
			double px = sx.applyAsDouble(v);
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>%n", px, H - B + 18, fmt(v)));
		}
		s.append(String.format(Locale.ROOT, "<line class=\"axis\" x1=\"%d\" x2=\"%d\" y1=\"%d\" y2=\"%d\"/>%n", L, W - R, H - B, H - B));
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%d\" text-anchor=\"middle\">%s</text>%n",
				(L + W - R) / 2, H - 12, Xml.esc(xLabel)));
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" transform=\"translate(16 %d) rotate(-90)\" text-anchor=\"middle\">%s</text>%n",
				(T + H - B) / 2, Xml.esc(yLabel)));
		if (markers != null) {
			appendMarkers(s, markers, sx, x0, x1);
		}
		if (!Double.isNaN(refY)) {
			double py = sy.applyAsDouble(refY);
			s.append(String.format(Locale.ROOT, "<line class=\"ref\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>%n", L, W - R, py, py));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>%n", W - R, py - 6, Xml.esc(refLabel)));
		}
		for (int k = 0; k < series.size() && k < SERIES_CLASSES.length; k++) {
			double[] x = series.get(k).x(), y = series.get(k).y();
			StringBuilder path = new StringBuilder();
			boolean pen = false;
			for (int i = 0; i < x.length; i++) {
				if (Double.isNaN(y[i]) || Double.isInfinite(y[i]) || x[i] < x0 || x[i] > x1) {
					pen = false;
					continue;
				}
				path.append(pen ? " L" : " M").append(String.format(Locale.ROOT, "%.1f %.1f", sx.applyAsDouble(x[i]), sy.applyAsDouble(y[i])));
				pen = true;
			}
			s.append("<path class=\"").append(SERIES_CLASSES[k]).append("\" d=\"").append(path.toString().trim()).append("\"/>\n");
			if (series.get(k).name() != null) {
				// Two series: legend above the plot, right; more: two rows inside the plot's top left.
				int ly = series.size() <= 2 ? T - 14 : T + 16 + 16 * (k / 2);
				int lx = series.size() <= 2 ? W - R - 220 + 110 * k : L + 14 + 250 * (k % 2);
				s.append(String.format(Locale.ROOT, "<line class=\"%s\" x1=\"%d\" x2=\"%d\" y1=\"%d\" y2=\"%d\"/>"
						+ "<text class=\"t2\" x=\"%d\" y=\"%d\">%s</text>%n", SERIES_CLASSES[k], lx, lx + 18, ly, ly, lx + 24, ly + 4,
						Xml.esc(series.get(k).name())));
			}
		}
		s.append("</svg>\n");
		return s.toString();
	}

	/** A cloud of landing points with its 2-sigma ellipse (centre, semi-axes, major-axis angle from +x, radians). */
	public record Cloud(String name, double[] x, double[] y, double cx, double cy, double a, double b, double theta) {
	}

	/**
	 * Landing map: points east (x) / north (y) of the pad at equal scale, 2-sigma ellipses and the pad. Up to two clouds
	 * (e.g. sustainer and booster).
	 */
	public static String landingMap(String title, String unit, List<Cloud> clouds) {
		return landingMap(title, unit, clouds, "2-sigma ellipse");
	}

	/** As {@link #landingMap(String, String, List)}, naming the ellipse in the legend (e.g. "95% of landings"). */
	public static String landingMap(String title, String unit, List<Cloud> clouds, String ellipse) {
		final int w = 620, h = 620, l = 64, r = 24, t = 56, b = 52;
		double xmin = 0, xmax = 0, ymin = 0, ymax = 0; // always show the pad
		for (Cloud c : clouds) {
			for (int i = 0; i < c.x().length; i++) {
				xmin = Math.min(xmin, c.x()[i]);
				xmax = Math.max(xmax, c.x()[i]);
				ymin = Math.min(ymin, c.y()[i]);
				ymax = Math.max(ymax, c.y()[i]);
			}
			double e = Math.max(c.a(), c.b());
			xmin = Math.min(xmin, c.cx() - e);
			xmax = Math.max(xmax, c.cx() + e);
			ymin = Math.min(ymin, c.cy() - e);
			ymax = Math.max(ymax, c.cy() + e);
		}
		// Equal scale on both axes, so the ellipse and the drift direction look right.
		double span = Math.max(Math.max(xmax - xmin, ymax - ymin), 1) * 1.1;
		double mx = (xmin + xmax) / 2, my = (ymin + ymax) / 2;
		double[] xt = ticks(mx - span / 2, mx + span / 2), yt = ticks(my - span / 2, my + span / 2);
		double step = Math.max(xt[1] - xt[0], yt[1] - yt[0]);
		double x0 = Math.floor((mx - span / 2) / step) * step, y0 = Math.floor((my - span / 2) / step) * step;
		int n = (int) Math.ceil(Math.max(mx + span / 2 - x0, my + span / 2 - y0) / step);
		double size = n * step;
		double pw = Math.min(w - l - r, h - t - b);
		DoubleUnaryOperator sx = v -> l + (v - x0) / size * pw;
		DoubleUnaryOperator sy = v -> t + pw - (v - y0) / size * pw;
		StringBuilder s = new StringBuilder();
		s.append(String.format(Locale.ROOT,
				"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" role=\"img\" aria-label=\"%s\">%n",
				w, h, w, h, Xml.esc(title)));
		s.append("<style>\n")
				.append(".bg{fill:#fcfcfb}.grid{stroke:#e4e3de;stroke-width:1}.axis{stroke:#b8b7ae;stroke-width:1}")
				.append(".t1{fill:#1a1a19;font:600 15px system-ui,sans-serif}.t2{fill:#5f5e57;font:12px system-ui,sans-serif}")
				.append(".p0{fill:#2a78d6;fill-opacity:.45}.p1{fill:#d9480f;fill-opacity:.45}")
				.append(".e0{fill:none;stroke:#2a78d6;stroke-width:2}.e1{fill:none;stroke:#d9480f;stroke-width:2;stroke-dasharray:5 3}")
				.append(".pad{fill:#1a1a19}\n")
				.append("@media (prefers-color-scheme: dark){.bg{fill:#1a1a19}.grid{stroke:#34332f}.axis{stroke:#5f5e57}")
				.append(".t1{fill:#ffffff}.t2{fill:#c3c2b7}.p0{fill:#3987e5}.p1{fill:#f08c4f}.e0{stroke:#3987e5}.e1{stroke:#f08c4f}")
				.append(".pad{fill:#ffffff}}\n</style>\n");
		s.append(String.format(Locale.ROOT, "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>%n", w, h));
		s.append(String.format(Locale.ROOT, "<text class=\"t1\" x=\"%d\" y=\"26\">%s</text>%n", l, Xml.esc(title)));
		for (int i = 0; i <= n; i++) {
			double vx = x0 + i * step, vy = y0 + i * step;
			double px = sx.applyAsDouble(vx), py = sy.applyAsDouble(vy);
			s.append(String.format(Locale.ROOT, "<line class=\"grid\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%d\" y2=\"%.1f\"/>%n", px, px, t, t + pw));
			s.append(String.format(Locale.ROOT, "<line class=\"grid\" x1=\"%d\" x2=\"%.1f\" y1=\"%.1f\" y2=\"%.1f\"/>%n", l, l + pw, py, py));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>%n", px,
					t + pw + 18, fmt(vx)));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>%n", l - 8, py + 4, fmt(vy)));
		}
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">East of the pad (%s)</text>%n",
				l + pw / 2, h - 12, Xml.esc(unit)));
		s.append(String.format(Locale.ROOT,
				"<text class=\"t2\" transform=\"translate(16 %.1f) rotate(-90)\" text-anchor=\"middle\">North of the pad (%s)</text>%n",
				t + pw / 2, Xml.esc(unit)));
		double scale = pw / size;
		for (int k = 0; k < clouds.size() && k < 2; k++) {
			Cloud c = clouds.get(k);
			for (int i = 0; i < c.x().length; i++) {
				s.append(String.format(Locale.ROOT, "<circle class=\"p%d\" cx=\"%.1f\" cy=\"%.1f\" r=\"3\"/>%n", k,
						sx.applyAsDouble(c.x()[i]), sy.applyAsDouble(c.y()[i])));
			}
			if (c.a() > 0) {
				s.append(String.format(Locale.ROOT,
						"<ellipse class=\"e%d\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" transform=\"rotate(%.2f %.1f %.1f)\"/>%n", k,
						sx.applyAsDouble(c.cx()), sy.applyAsDouble(c.cy()), c.a() * scale, Math.max(c.b() * scale, 0.5),
						-Math.toDegrees(c.theta()), sx.applyAsDouble(c.cx()), sy.applyAsDouble(c.cy())));
			}
			int ly = t - 14;
			int lx = l + 240 * k;
			s.append(String.format(Locale.ROOT, "<circle class=\"p%d\" cx=\"%d\" cy=\"%d\" r=\"4\"/>"
					+ "<text class=\"t2\" x=\"%d\" y=\"%d\">%s (%d, %s)</text>%n", k, lx + 4, ly, lx + 12, ly + 4,
					Xml.esc(c.name()), c.x().length, Xml.esc(ellipse)));
		}
		double px = sx.applyAsDouble(0), py = sy.applyAsDouble(0);
		s.append(String.format(Locale.ROOT, "<path class=\"pad\" d=\"M%.1f %.1f l6 10 h-12 z\"/>%n", px, py - 6));
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%.1f\" y=\"%.1f\">pad</text>%n", px + 9, py + 4));
		s.append("</svg>\n");
		return s.toString();
	}

	/** Event markers: events at the same time share one label; labels that would collide drop to the next row. */
	private static void appendMarkers(StringBuilder s, List<Marker> markers, DoubleUnaryOperator sx, double x0, double x1) {
		List<Marker> merged = new ArrayList<>();
		for (Marker m : markers) {
			if (m.x() < x0 || m.x() > x1) {
				continue;
			}
			Marker last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
			if (last != null && Math.abs(last.x() - m.x()) < 1e-3) {
				if (!last.label().contains(m.label())) {
					merged.set(merged.size() - 1, new Marker(last.x(), last.label() + " + " + m.label()));
				}
			} else {
				merged.add(m);
			}
		}
		double[] rowEnd = { -1e9, -1e9, -1e9 };
		for (Marker m : merged) {
			double px = sx.applyAsDouble(m.x());
			int row = 0;
			while (row < rowEnd.length - 1 && px < rowEnd[row] + 6) {
				row++;
			}
			rowEnd[row] = px + 4 + m.label().length() * 6.5;
			s.append(String.format(Locale.ROOT, "<line class=\"mark\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%d\" y2=\"%d\"/>%n", px, px, T, H - B));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%.1f\" y=\"%d\" text-anchor=\"start\">%s</text>%n",
					px + 4, T + 12 + 14 * row, Xml.esc(m.label())));
		}
	}

	/** "Nice" axis ticks (1, 2, 5 x 10^n steps) covering [lo, hi]. */
	static double[] ticks(double lo, double hi) {
		if (hi - lo < 1e-9) {
			hi = lo + 1;
		}
		double raw = (hi - lo) / 5;
		double mag = Math.pow(10, Math.floor(Math.log10(raw)));
		double step = raw / mag >= 5 ? 5 * mag : raw / mag >= 2 ? 2 * mag : mag;
		double start = Math.floor(lo / step) * step;
		double end = Math.ceil(hi / step) * step;
		int n = (int) Math.round((end - start) / step) + 1;
		double[] t = new double[n];
		for (int i = 0; i < n; i++) {
			t[i] = start + i * step;
		}
		return t;
	}

	private static String fmt(double v) {
		if (Math.abs(v - Math.rint(v)) < 1e-9) {
			return String.valueOf((long) Math.rint(v));
		}
		return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
	}
}
