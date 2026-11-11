package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * A static-fire log, as the test stand's data logger wrote it, turned into a thrust curve a simulation can fly:
 *
 * <ol>
 * <li>the time and thrust columns are found from the header (or given), in the units the header names (or given);</li>
 * <li>the load cell's offset is removed: the median reading before ignition, and, when the log goes on after the
 * burn, a straight line from that to the median after it (load cells drift as the stand heats up);</li>
 * <li>the burn is found the way motor certification defines it: from the first time thrust passes 5 % of its peak
 * to the last time it falls below that;</li>
 * <li>noise is averaged over 10 ms, readings below zero are set to zero, and the curve is reduced to a few hundred
 * points, scaled so its total impulse is exactly that of the full-rate data.</li>
 * </ol>
 *
 * Nothing is fitted or invented: the result is the measurement, tared and trimmed.
 */
public final class StaticFire {
	private StaticFire() {
	}

	/** Fraction of peak thrust that marks the start and end of the burn (NAR / Tripoli certification practice). */
	public static final double BURN_THRESHOLD = 0.05;
	static final int DEFAULT_POINTS = 200;

	/**
	 * @param time         seconds from the start of the burn
	 * @param thrust       N
	 * @param totalImpulse N s, of the full-rate data over the burn
	 * @param notes        what was done to the data, and anything to be wary of
	 */
	public record Curve(double[] time, double[] thrust, double totalImpulse, double burnTime, double peakThrust, double burnStart,
			double burnEnd, double offsetBefore, double offsetAfter, double noise, double sampleRate, int samples, List<String> notes) {
		public double averageThrust() {
			return totalImpulse / burnTime;
		}

		/** For the tool's answer: what was found in the log. */
		public Map<String, Object> describe(double propellantMass) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("samples", samples + " at " + Units.num(sampleRate) + " Hz");
			m.put("burn", "from " + Units.num(burnStart) + " s to " + Units.num(burnEnd) + " s of the log: " + Units.fmt(burnTime, Dim.TIME)
					+ " (thrust above " + Math.round(BURN_THRESHOLD * 100) + "% of peak)");
			m.put("offsetRemoved", Units.fmt(offsetBefore, Dim.FORCE) + " before the burn"
					+ (Double.isNaN(offsetAfter) ? "" : ", " + Units.fmt(offsetAfter, Dim.FORCE) + " after it (a straight line between)"));
			m.put("noise", Units.fmt(noise, Dim.FORCE) + " (scatter of the readings before ignition)");
			m.put("totalImpulse", Units.fmt(totalImpulse, Dim.IMPULSE));
			m.put("peakThrust", Units.fmt(peakThrust, Dim.FORCE));
			m.put("averageThrust", Units.fmt(averageThrust(), Dim.FORCE));
			if (propellantMass > 0) {
				m.put("specificImpulse", Units.num(totalImpulse / (propellantMass * Atmosphere.G0)) + " s (total impulse / propellant weight)");
			}
			m.put("curvePoints", time.length);
			if (!notes.isEmpty()) {
				m.put("notes", notes);
			}
			return m;
		}
	}

	/**
	 * Reads a log: comma, semicolon, tab or space separated, with or without a header line.
	 *
	 * @param timeColumn   the time column's name or number (1 = first), or null to find it
	 * @param thrustColumn the thrust column's name or number, or null to find it
	 * @param timeUnit     s or ms, or null for what the header says (else s)
	 * @param thrustUnit   N, kN, lbf, kgf or g, or null for what the header says (else N)
	 */
	public static Curve fromCsv(String text, String timeColumn, String thrustColumn, String timeUnit, String thrustUnit, int maxPoints) {
		String[] header = null;
		List<double[]> rows = new ArrayList<>();
		int width = 0;
		for (String raw : text.split("\\r?\\n")) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
				continue;
			}
			String[] cells = line.split(line.contains("\t") ? "\t" : line.contains(";") ? ";" : line.contains(",") ? "," : "\\s+");
			double[] v = new double[cells.length];
			boolean numeric = cells.length >= 2;
			for (int i = 0; i < cells.length && numeric; i++) {
				try {
					v[i] = Double.parseDouble(cells[i].strip());
				} catch (NumberFormatException e) {
					numeric = cells[i].isBlank() && i > 1; // an empty later cell is a gap, not a header
					v[i] = Double.NaN;
				}
			}
			if (numeric) {
				rows.add(v);
				width = Math.max(width, v.length);
			} else if (rows.isEmpty() && cells.length >= 2) {
				header = cells; // the last text line before the numbers
			}
		}
		if (rows.size() < 10) {
			throw new ToolException("The log has " + rows.size() + " rows of numbers; a static-fire log has a time and a thrust on "
					+ "every line (comma, semicolon, tab or space between them).");
		}
		int tc = column(timeColumn, header, width, "time", "^(t|time|timestamp|elapsed|sec|secs|seconds|ms|millis)([^a-z].*)?$", 0);
		int fc = column(thrustColumn, header, width, "thrust", ".*(thrust|force|load|lbf|newton).*", tc == 0 ? 1 : 0);
		if (tc == fc) {
			throw new ToolException("The time and thrust columns are the same one (column " + (tc + 1) + "); name them with "
					+ "timeColumn and thrustColumn.");
		}
		double ts = timeScale(timeUnit != null ? timeUnit : unitIn(header, tc, "ms", "s"));
		double fs = thrustScale(thrustUnit != null ? thrustUnit : unitIn(header, fc, "kn", "lbf", "kgf", "kg", "lb", "g", "n"));
		List<double[]> pts = new ArrayList<>();
		for (double[] r : rows) {
			if (tc < r.length && fc < r.length && Double.isFinite(r[tc]) && Double.isFinite(r[fc])) {
				pts.add(new double[] { r[tc] * ts, r[fc] * fs });
			}
		}
		pts.sort((a, b) -> Double.compare(a[0], b[0]));
		double[] t = new double[pts.size()], f = new double[pts.size()];
		for (int i = 0; i < t.length; i++) {
			t[i] = pts.get(i)[0];
			f[i] = pts.get(i)[1];
		}
		return process(t, f, maxPoints);
	}

	private static int column(String asked, String[] header, int width, String what, String pattern, int fallback) {
		if (asked != null && !asked.isBlank()) {
			String a = asked.strip();
			if (a.matches("\\d+")) {
				int i = Integer.parseInt(a) - 1;
				if (i < 0 || i >= width) {
					throw new ToolException("The log has " + width + " columns; " + what + "Column " + a + " is not one of them.");
				}
				return i;
			}
			for (int i = 0; header != null && i < header.length; i++) {
				if (header[i].strip().equalsIgnoreCase(a)) {
					return i;
				}
			}
			throw new ToolException("No column named \"" + a + "\" in the log" + (header == null ? " (it has no header line; give the "
					+ "column's number, 1 for the first)." : ": it has " + String.join(", ", header).strip() + "."));
		}
		for (int i = 0; header != null && i < header.length; i++) {
			if (header[i].strip().toLowerCase(Locale.ROOT).matches(pattern)) {
				return i;
			}
		}
		return Math.min(fallback, width - 1);
	}

	/** The first of {@code units} that the column's header names, e.g. "Thrust (lbf)" or "time_ms"; null when none. */
	private static String unitIn(String[] header, int col, String... units) {
		if (header == null || col >= header.length) {
			return null;
		}
		String h = header[col].toLowerCase(Locale.ROOT);
		for (String u : units) {
			if (h.matches(".*[^a-z]" + u + "[^a-z]*$") || h.matches(".*[\\[(_ ]" + u + "[\\])_ ].*")) {
				return u;
			}
		}
		return null;
	}

	private static double timeScale(String unit) {
		if (unit == null) {
			return 1;
		}
		return switch (unit.strip().toLowerCase(Locale.ROOT)) {
			case "s", "sec", "seconds" -> 1;
			case "ms", "milliseconds" -> 1e-3;
			default -> throw new ToolException("timeUnit is s or ms (got " + unit + ").");
		};
	}

	private static double thrustScale(String unit) {
		if (unit == null) {
			return 1;
		}
		return switch (unit.strip().toLowerCase(Locale.ROOT)) {
			case "n", "newton", "newtons" -> 1;
			case "kn" -> 1000;
			case "lbf", "lb" -> 4.4482216152605;
			case "kgf", "kg" -> Atmosphere.G0;
			case "g", "gf" -> Atmosphere.G0 / 1000;
			default -> throw new ToolException("thrustUnit is N, kN, lbf, kgf or g (got " + unit + ").");
		};
	}

	/** As {@link #fromCsv} from samples already in seconds and newtons, in time order. */
	public static Curve process(double[] t, double[] raw, int maxPoints) {
		int n = t.length;
		if (n < 10 || raw.length != n) {
			throw new ToolException("A static-fire log needs at least ten samples of time and thrust.");
		}
		for (int i = 1; i < n; i++) {
			if (!(t[i] >= t[i - 1])) {
				throw new ToolException("The log's times must not go backwards (sample " + (i + 1) + ").");
			}
		}
		double span = t[n - 1] - t[0];
		if (!(span > 0)) {
			throw new ToolException("The log's times do not advance; is the time column the right one?");
		}
		double rate = (n - 1) / span;
		List<String> notes = new ArrayList<>();

		// Where the thrust is, roughly: smoothed over 50 ms, against the level the log starts at.
		double[] coarse = smooth(t, raw, 0.05);
		int lead = Math.max(5, Math.min(n / 20, (int) Math.round(0.5 * rate)));
		double before = median(raw, 0, lead);
		double noise = 1.4826 * medianAbsDeviation(raw, 0, lead, before);
		double peak0 = 0, lowest = Double.POSITIVE_INFINITY, highest = Double.NEGATIVE_INFINITY;
		for (double v : coarse) {
			peak0 = Math.max(peak0, v - before);
			lowest = Math.min(lowest, v);
			highest = Math.max(highest, v);
		}
		// A log that starts high and only comes down was started with the engine already burning.
		boolean alreadyBurning = before - lowest > Math.max(10 * noise, 0.5 * (highest - lowest));
		if (!alreadyBurning && !(peak0 > Math.max(10 * noise, 1e-9))) {
			throw new ToolException("No burn stands out of the noise in this log (peak " + Units.fmt(peak0, Dim.FORCE) + " above the "
					+ "starting level, scatter " + Units.fmt(noise, Dim.FORCE) + "). Is the thrust column the right one, and in the right unit?");
		}
		int[] burn = alreadyBurning ? new int[] { 0, 0 } : burn(coarse, before, peak0);
		if (burn[0] == 0) {
			// The burn is already on at the first sample: there is no quiet stretch to read the offset from.
			before = 0;
			noise = Double.NaN;
			notes.add("The log starts with the engine already burning, so the load cell's offset could not be read and none was "
					+ "removed: the start of the burn is missing from the impulse. Log a second or two before ignition next time.");
			peak0 = Arrays.stream(coarse).max().orElse(0);
			burn = burn(coarse, 0, peak0);
		} else {
			// The offset again, from everything before the burn (not only the first samples), and the burn with it.
			int quiet = Math.max(5, burn[0] - Math.max(1, (int) Math.round(0.05 * rate)));
			before = median(raw, 0, quiet);
			noise = 1.4826 * medianAbsDeviation(raw, 0, quiet, before);
			peak0 = 0;
			for (double v : coarse) {
				peak0 = Math.max(peak0, v - before);
			}
			burn = burn(coarse, before, peak0);
		}
		// After the burn: the offset the load cell settled at, once the tail-off and the stand's ringing have passed.
		double after = Double.NaN;
		int settle = burn[1] + 1 + (int) Math.round(Math.max(0.2, 0.1 * (t[burn[1]] - t[burn[0]])) * rate);
		if (burn[0] > 0 && n - settle >= Math.max(5, (int) Math.round(0.2 * rate))) {
			after = median(raw, settle, n);
			if (Math.abs(after - before) > 0.01 * peak0) {
				notes.add("The load cell read " + Units.fmt(after - before, Dim.FORCE) + " differently after the burn than before it ("
						+ Units.num(100 * Math.abs(after - before) / peak0) + "% of peak thrust): the offset is taken as a straight line "
						+ "between the two. A shift this large usually means the stand moved or heated; check the mounting.");
			}
		} else if (burn[0] > 0) {
			notes.add("The log ends too soon after the burn to see whether the load cell's offset drifted; the offset before ignition "
					+ "is used throughout.");
		}

		// The burn itself: tared (a line from the offset before to the offset after), averaged over 10 ms, never negative.
		int i0 = burn[0], i1 = burn[1];
		double t0 = t[i0], t1 = t[i1];
		double[] fine = smooth(t, raw, 0.01);
		int m = i1 - i0 + 1;
		double[] bt = new double[m], bf = new double[m];
		for (int i = 0; i < m; i++) {
			double base = Double.isNaN(after) ? before : before + (after - before) * (t[i0 + i] - t0) / Math.max(1e-9, t1 - t0);
			bt[i] = t[i0 + i] - t0;
			bf[i] = Math.max(0, fine[i0 + i] - base);
		}
		bf[0] = 0; // the burn starts and ends at zero thrust: what is below the threshold is the igniter and the tail
		bf[m - 1] = 0;
		double impulse = trapezoid(bt, bf);
		double peak = Arrays.stream(bf).max().orElse(0);
		double burnTime = t1 - t0;
		if (!(impulse > 0) || !(burnTime > 0)) {
			throw new ToolException("The burn found in the log (" + Units.num(t0) + " to " + Units.num(t1) + " s) carries no impulse.");
		}
		if (rate * burnTime < 50) {
			notes.add("Only " + Math.round(rate * burnTime) + " samples cover the burn (" + Units.num(rate) + " Hz): the peak and the "
					+ "ignition transient are under-sampled. Log at 100 Hz or faster.");
		}

		// Fewer points, the same impulse: the mean of each stretch of time, then scaled to the full-rate impulse.
		int keep = Math.max(20, maxPoints <= 0 ? DEFAULT_POINTS : maxPoints);
		double[] rt, rf;
		if (m <= keep) {
			rt = bt;
			rf = bf;
		} else {
			rt = new double[keep];
			rf = new double[keep];
			for (int k = 0; k < keep; k++) {
				double at = burnTime * k / (keep - 1), half = burnTime / (keep - 1) / 2;
				double sum = 0;
				int count = 0;
				for (int i = lowerBound(bt, at - half); i < m && bt[i] <= at + half; i++) {
					sum += bf[i];
					count++;
				}
				rt[k] = at;
				rf[k] = count > 0 ? sum / count : interpolate(bt, bf, at);
			}
			rf[0] = 0;
			rf[keep - 1] = 0;
			double scale = impulse / trapezoid(rt, rf);
			for (int k = 0; k < keep; k++) {
				rf[k] *= scale;
			}
		}
		return new Curve(rt, rf, impulse, burnTime, peak, t0, t1, before, after, noise, rate, n, notes);
	}

	/** First and last sample above 5 % of peak, widened by one sample each way so the curve starts and ends low. */
	private static int[] burn(double[] coarse, double base, double peak) {
		double threshold = base + BURN_THRESHOLD * peak;
		int first = -1, last = -1;
		for (int i = 0; i < coarse.length; i++) {
			if (coarse[i] > threshold) {
				if (first < 0) {
					first = i;
				}
				last = i;
			}
		}
		return new int[] { Math.max(0, first - 1), Math.min(coarse.length - 1, last + 1) };
	}

	/** Centred moving average over {@code window} seconds (by time, so uneven sampling is handled). */
	static double[] smooth(double[] t, double[] v, double window) {
		int n = t.length;
		double[] prefix = new double[n + 1];
		for (int i = 0; i < n; i++) {
			prefix[i + 1] = prefix[i] + v[i];
		}
		double[] out = new double[n];
		int lo = 0, hi = 0;
		for (int i = 0; i < n; i++) {
			while (t[i] - t[lo] > window / 2) {
				lo++;
			}
			while (hi + 1 < n && t[hi + 1] - t[i] <= window / 2) {
				hi++;
			}
			out[i] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1);
		}
		return out;
	}

	private static double median(double[] v, int from, int to) {
		double[] c = Arrays.copyOfRange(v, from, to);
		Arrays.sort(c);
		return c.length % 2 == 1 ? c[c.length / 2] : (c[c.length / 2 - 1] + c[c.length / 2]) / 2;
	}

	private static double medianAbsDeviation(double[] v, int from, int to, double centre) {
		double[] d = new double[to - from];
		for (int i = from; i < to; i++) {
			d[i - from] = Math.abs(v[i] - centre);
		}
		return median(d, 0, d.length);
	}

	static double trapezoid(double[] t, double[] f) {
		double s = 0;
		for (int i = 1; i < t.length; i++) {
			s += 0.5 * (f[i] + f[i - 1]) * (t[i] - t[i - 1]);
		}
		return s;
	}

	private static int lowerBound(double[] t, double x) {
		int lo = 0, hi = t.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (t[mid] < x) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	private static double interpolate(double[] t, double[] f, double x) {
		int i = Math.min(t.length - 1, Math.max(1, lowerBound(t, x)));
		double w = (x - t[i - 1]) / Math.max(1e-12, t[i] - t[i - 1]);
		return f[i - 1] + Math.min(1, Math.max(0, w)) * (f[i] - f[i - 1]);
	}
}
