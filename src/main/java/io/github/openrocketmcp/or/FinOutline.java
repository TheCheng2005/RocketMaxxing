package io.github.openrocketmcp.or;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fin outline from a file, as OpenRocket's freeform fin editor imports one: a CSV or text file of points, or a
 * picture of the fin (a drawing, a scan, a photo of a template on plain paper) traced round its edge. Outlines come
 * back from the root's leading edge (0, 0) over the tip to the root's trailing edge (chord, 0), in metres.
 */
public final class FinOutline {
	private FinOutline() {
	}

	private static final Pattern UNIT = Pattern.compile("\\b(mm|cm|m|in|inch|inches|ft)\\b", Pattern.CASE_INSENSITIVE);

	static double metres(String unit) {
		return switch (unit.toLowerCase(Locale.ROOT)) {
			case "mm" -> 0.001;
			case "cm" -> 0.01;
			case "m" -> 1;
			case "in", "inch", "inches" -> 0.0254;
			case "ft" -> 0.3048;
			default -> throw new IllegalArgumentException("Unit \"" + unit + "\" is not a length: use mm, cm, m, in or ft.");
		};
	}

	/**
	 * Points from text: two numbers a line (comma, semicolon, tab or spaces between), lines without two numbers
	 * skipped. The unit is {@code unit} if given, else one named in a heading line ("x (mm), y (mm)"), else mm.
	 */
	public static List<double[]> csv(String text, String unit) {
		String u = unit;
		List<double[]> pts = new ArrayList<>();
		for (String line : text.split("\\R")) {
			String t = line.trim();
			if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) {
				continue;
			}
			// "12,5;40,0" (decimal commas, as a spreadsheet set to most European languages writes them) or "12.5,40.0"
			boolean commas = (t.contains(";") || t.contains("\t")) && t.contains(",");
			String[] parts = commas ? t.replace(',', '.').split("\\s*[;\\t]\\s*") : t.split("\\s*[,;\\t]\\s*|\\s+");
			if (parts.length >= 2) {
				try {
					pts.add(new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) });
					continue;
				} catch (NumberFormatException notNumbers) {
					// a heading
				}
			}
			Matcher m = UNIT.matcher(t);
			if (u == null && pts.isEmpty() && m.find()) {
				u = m.group(1);
			}
		}
		if (pts.size() < 3) {
			throw new IllegalArgumentException("The file has " + pts.size() + " point" + (pts.size() == 1 ? "" : "s")
					+ "; a fin outline needs at least three lines of two numbers (x along the root, y out from the body).");
		}
		double k = metres(u == null ? "mm" : u);
		pts.replaceAll(p -> new double[] { p[0] * k, p[1] * k });
		return onRoot(pts);
	}

	/** Puts the outline on the root: starting at (0, 0), ending on y = 0, the root line itself left out. */
	static List<double[]> onRoot(List<double[]> in) {
		List<double[]> pts = new ArrayList<>(in);
		// a closed outline repeats its first point; an outline traced back along the root ends at its start
		if (pts.size() > 3 && same(pts.get(0), pts.get(pts.size() - 1))) {
			pts.remove(pts.size() - 1);
		}
		double base = Double.MAX_VALUE;
		for (double[] p : pts) {
			base = Math.min(base, p[1]);
		}
		double b = base;
		pts.replaceAll(p -> new double[] { p[0], p[1] - b });
		if (Math.abs(pts.get(0)[1]) > 1e-9) {
			pts.add(0, new double[] { pts.get(0)[0], 0 });
		}
		if (Math.abs(pts.get(pts.size() - 1)[1]) > 1e-9) {
			pts.add(new double[] { pts.get(pts.size() - 1)[0], 0 });
		}
		if (pts.get(pts.size() - 1)[0] < pts.get(0)[0]) { // drawn from the trailing edge: turn it round
			java.util.Collections.reverse(pts);
		}
		double x0 = pts.get(0)[0];
		pts.replaceAll(p -> new double[] { p[0] - x0, p[1] });
		if (pts.get(pts.size() - 1)[0] <= 0) {
			throw new IllegalArgumentException("The outline's root has no length: its first and last points should be the root's two ends.");
		}
		return pts;
	}

	private static boolean same(double[] a, double[] b) {
		return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9;
	}

	/**
	 * Traces the fin in a picture: the largest dark shape on a light background (or light on dark), with its root
	 * along the bottom. Returns the outline in pixels, y up, from the root's leading edge; scale it afterwards.
	 */
	public static List<double[]> trace(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		if (w < 8 || h < 8) {
			throw new IllegalArgumentException("The picture is too small to trace (" + w + " × " + h + " pixels).");
		}
		int[] grey = new int[w * h];
		int[] hist = new int[256];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = img.getRGB(x, y), a = argb >>> 24;
				int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
				int v = (r * 299 + g * 587 + b * 114) / 1000;
				v = (v * a + 255 * (255 - a)) / 255; // transparent reads as white paper
				grey[y * w + x] = v;
				hist[v]++;
			}
		}
		int t = otsu(hist, w * h);
		boolean[] ink = new boolean[w * h];
		int borderInk = 0, border = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				ink[y * w + x] = grey[y * w + x] < t;
				if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
					border++;
					borderInk += ink[y * w + x] ? 1 : 0;
				}
			}
		}
		if (borderInk > border / 2) { // a light fin on a dark background
			for (int i = 0; i < ink.length; i++) {
				ink[i] = !ink[i];
			}
		}
		// the largest shape
		int[] label = new int[w * h];
		int best = 0, bestSize = 0, next = 0;
		ArrayDeque<Integer> q = new ArrayDeque<>();
		for (int i = 0; i < ink.length; i++) {
			if (!ink[i] || label[i] != 0) {
				continue;
			}
			next++;
			int size = 0;
			label[i] = next;
			q.add(i);
			while (!q.isEmpty()) {
				int p = q.poll(), x = p % w, y = p / w;
				size++;
				int[] nb = { x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1 };
				for (int n : nb) {
					if (n >= 0 && ink[n] && label[n] == 0) {
						label[n] = next;
						q.add(n);
					}
				}
			}
			if (size > bestSize) {
				bestSize = size;
				best = next;
			}
		}
		if (bestSize < 50) {
			throw new IllegalArgumentException("No fin shape found in the picture: it needs a dark fin on light paper (or light on dark).");
		}
		final int shape = best;
		// fill holes (lightening holes, a paper texture) so only the outer edge is traced
		boolean[] in = new boolean[w * h];
		boolean[] outside = new boolean[w * h];
		for (int i = 0; i < in.length; i++) {
			in[i] = label[i] == shape;
		}
		for (int x = 0; x < w; x++) {
			for (int y : new int[] { 0, h - 1 }) {
				if (!in[y * w + x] && !outside[y * w + x]) {
					outside[y * w + x] = true;
					q.add(y * w + x);
				}
			}
		}
		for (int y = 0; y < h; y++) {
			for (int x : new int[] { 0, w - 1 }) {
				if (!in[y * w + x] && !outside[y * w + x]) {
					outside[y * w + x] = true;
					q.add(y * w + x);
				}
			}
		}
		while (!q.isEmpty()) {
			int p = q.poll(), x = p % w, y = p / w;
			int[] nb = { x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1 };
			for (int n : nb) {
				if (n >= 0 && !in[n] && !outside[n]) {
					outside[n] = true;
					q.add(n);
				}
			}
		}
		for (int i = 0; i < in.length; i++) {
			in[i] = !outside[i];
		}
		List<int[]> edge = boundary(in, w, h);
		// The root: the shape's lowest row. Leading edge at its left end, trailing at its right.
		int bottom = 0;
		for (int[] p : edge) {
			bottom = Math.max(bottom, p[1]);
		}
		int lead = -1, trail = -1;
		for (int i = 0; i < edge.size(); i++) {
			int[] p = edge.get(i);
			if (p[1] >= bottom - 1) {
				if (lead < 0 || p[0] < edge.get(lead)[0]) {
					lead = i;
				}
				if (trail < 0 || p[0] > edge.get(trail)[0]) {
					trail = i;
				}
			}
		}
		// Of the two ways round from the leading edge to the trailing edge, the one over the top.
		List<int[]> a = walk(edge, lead, trail, 1), b = walk(edge, lead, trail, -1);
		List<int[]> over = top(a) < top(b) ? a : b;
		List<double[]> pts = new ArrayList<>();
		for (int[] p : over) { // pixel centres, the root row at y = 0
			pts.add(new double[] { p[0], bottom - p[1] });
		}
		pts.set(0, new double[] { pts.get(0)[0], 0 });
		pts.set(pts.size() - 1, new double[] { pts.get(pts.size() - 1)[0], 0 });
		double size = Math.max(w, h);
		List<double[]> simple = simplify(pts, size * 0.004);
		return onRoot(simple);
	}

	private static int top(List<int[]> path) {
		int min = Integer.MAX_VALUE;
		for (int[] p : path) {
			min = Math.min(min, p[1]);
		}
		return min;
	}

	private static List<int[]> walk(List<int[]> ring, int from, int to, int step) {
		List<int[]> out = new ArrayList<>();
		int n = ring.size();
		for (int i = from;; i = ((i + step) % n + n) % n) {
			out.add(ring.get(i));
			if (i == to || out.size() > n) {
				break;
			}
		}
		return out;
	}

	/** The shape's outer edge, in order (Moore neighbour tracing). */
	static List<int[]> boundary(boolean[] in, int w, int h) {
		int start = -1;
		for (int i = 0; i < in.length && start < 0; i++) {
			if (in[i]) {
				start = i;
			}
		}
		int[][] dirs = { { -1, 0 }, { -1, -1 }, { 0, -1 }, { 1, -1 }, { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 } };
		List<int[]> out = new ArrayList<>();
		int cx = start % w, cy = start / w, back = 0; // came from the left (west)
		int sx = cx, sy = cy;
		int guard = 0;
		do {
			out.add(new int[] { cx, cy });
			int found = -1;
			for (int k = 0; k < 8; k++) {
				int d = (back + 1 + k) % 8;
				int nx = cx + dirs[d][0], ny = cy + dirs[d][1];
				if (nx >= 0 && ny >= 0 && nx < w && ny < h && in[ny * w + nx]) {
					found = d;
					break;
				}
			}
			if (found < 0) {
				break; // a single pixel
			}
			cx += dirs[found][0];
			cy += dirs[found][1];
			back = (found + 5) % 8; // the next search starts two steps on from the way back (Moore's rule)
		} while ((cx != sx || cy != sy) && ++guard < 4 * in.length);
		return out;
	}

	/** Douglas-Peucker: drops points closer than {@code tol} to the line between their neighbours kept. */
	static List<double[]> simplify(List<double[]> pts, double tol) {
		if (pts.size() < 3) {
			return pts;
		}
		boolean[] keep = new boolean[pts.size()];
		keep[0] = keep[pts.size() - 1] = true;
		ArrayDeque<int[]> stack = new ArrayDeque<>();
		stack.push(new int[] { 0, pts.size() - 1 });
		while (!stack.isEmpty()) {
			int[] s = stack.pop();
			double[] a = pts.get(s[0]), b = pts.get(s[1]);
			double max = -1;
			int at = -1;
			for (int i = s[0] + 1; i < s[1]; i++) {
				double d = dist(pts.get(i), a, b);
				if (d > max) {
					max = d;
					at = i;
				}
			}
			if (max > tol) {
				keep[at] = true;
				stack.push(new int[] { s[0], at });
				stack.push(new int[] { at, s[1] });
			}
		}
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < pts.size(); i++) {
			if (keep[i]) {
				out.add(pts.get(i));
			}
		}
		return out;
	}

	private static double dist(double[] p, double[] a, double[] b) {
		double dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy);
		if (len == 0) {
			return Math.hypot(p[0] - a[0], p[1] - a[1]);
		}
		return Math.abs(dy * p[0] - dx * p[1] + b[0] * a[1] - b[1] * a[0]) / len;
	}

	private static int otsu(int[] hist, int total) {
		double sum = 0;
		for (int i = 0; i < 256; i++) {
			sum += i * (double) hist[i];
		}
		double sumB = 0, best = -1;
		int wB = 0, t = 128;
		for (int i = 0; i < 256; i++) {
			wB += hist[i];
			if (wB == 0) {
				continue;
			}
			int wF = total - wB;
			if (wF == 0) {
				break;
			}
			sumB += i * (double) hist[i];
			double mB = sumB / wB, mF = (sum - sumB) / wF, between = (double) wB * wF * (mB - mF) * (mB - mF);
			if (between > best) {
				best = between;
				t = i + 1;
			}
		}
		return t;
	}
}
