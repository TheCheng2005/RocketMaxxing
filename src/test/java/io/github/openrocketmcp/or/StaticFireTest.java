package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.mcp.ToolException;

/** A static-fire log as a data logger writes it, against the burn that was put into it. */
class StaticFireTest {
	/** Thrust of the "true" burn: 0.15 s ramps to 3000 N held from 2.15 s to 6.85 s. Impulse 3000 x 4.85 = 14550 N s. */
	static double truth(double t) {
		double up = (t - 2.0) / 0.15, down = (7.0 - t) / 0.15;
		return 3000 * Math.max(0, Math.min(1, Math.min(up, down)));
	}

	static final double IMPULSE = 3000 * 4.85;

	/** 1 kHz, an offset, noise, and a load cell that drifts by {@code drift} N over the burn. */
	static String log(double offset, double drift, double noise, String header, double thrustPerN, double timePerS) {
		Random r = new Random(7);
		StringBuilder b = new StringBuilder(header == null ? "" : header + "\n");
		for (int i = 0; i <= 9000; i++) {
			double t = i / 1000.0;
			double shift = offset + drift * Math.max(0, Math.min(1, (t - 2.0) / 5.0));
			b.append(String.format(Locale.ROOT, "%.4f,%.4f\n", t * timePerS, (truth(t) + shift + noise * r.nextGaussian()) * thrustPerN));
		}
		return b.toString();
	}

	@Test
	void rawLogGivesTheBurnThatWasFired() {
		StaticFire.Curve c = StaticFire.fromCsv(log(37, 0, 8, "time (s),thrust (N)", 1, 1), null, null, null, null, 0);
		assertEquals(IMPULSE, c.totalImpulse(), 0.005 * IMPULSE, "total impulse within 0.5%");
		assertEquals(37, c.offsetBefore(), 1.0, "the load cell's offset");
		assertEquals(8, c.noise(), 2.0, "the scatter before ignition");
		assertEquals(3000, c.peakThrust(), 15);
		assertEquals(5.0, c.burnTime(), 0.05, "5% of peak to 5% of peak");
		assertEquals(2.0, c.burnStart(), 0.03);
		assertEquals(1000, c.sampleRate(), 1);
		// What goes to the simulation: few points, from zero to zero, never negative, the same impulse exactly.
		assertTrue(c.time().length <= 200 && c.time().length >= 20);
		assertEquals(0, c.time()[0], 1e-12);
		assertEquals(0, c.thrust()[0], 1e-12);
		assertEquals(0, c.thrust()[c.thrust().length - 1], 1e-12);
		for (double f : c.thrust()) {
			assertTrue(f >= 0);
		}
		assertEquals(c.totalImpulse(), StaticFire.trapezoid(c.time(), c.thrust()), 1e-6 * IMPULSE, "the reduced curve keeps the impulse");
	}

	@Test
	void loadCellDriftIsTakenOut() {
		// The cell reads 60 N more after the burn than before: untreated, that is 150 N s (1%) of false impulse.
		StaticFire.Curve c = StaticFire.fromCsv(log(20, 60, 5, "t,F", 1, 1), null, null, null, null, 0);
		assertEquals(80, c.offsetAfter(), 2.0);
		assertEquals(IMPULSE, c.totalImpulse(), 0.004 * IMPULSE);
		assertTrue(c.notes().stream().anyMatch(n -> n.contains("differently after the burn")), c.notes().toString());
	}

	@Test
	void unitsComeFromTheHeaderOrAreGiven() {
		double lbf = 1 / 4.4482216152605;
		StaticFire.Curve fromHeader = StaticFire.fromCsv(log(10, 0, 3, "Time [ms],Load cell (lbf)", lbf, 1000), null, null, null, null, 0);
		assertEquals(IMPULSE, fromHeader.totalImpulse(), 0.005 * IMPULSE);
		assertEquals(5.0, fromHeader.burnTime(), 0.05);
		StaticFire.Curve given = StaticFire.fromCsv(log(10, 0, 3, null, lbf, 1000), "1", "2", "ms", "lbf", 0);
		assertEquals(fromHeader.totalImpulse(), given.totalImpulse(), 1e-6 * IMPULSE);
		// Columns by name, in another order, with a column that is neither.
		StringBuilder b = new StringBuilder("pressure_psi;thrust_N;time_s\n");
		for (int i = 0; i <= 900; i++) {
			double t = i / 100.0;
			b.append(String.format(Locale.ROOT, "%.1f;%.3f;%.2f\n", 500 * truth(t) / 3000, truth(t) + 4, t));
		}
		StaticFire.Curve named = StaticFire.fromCsv(b.toString(), "time_s", "thrust_N", null, null, 0);
		assertEquals(IMPULSE, named.totalImpulse(), 0.01 * IMPULSE);
		StaticFire.Curve found = StaticFire.fromCsv(b.toString(), null, null, null, null, 0);
		assertEquals(named.totalImpulse(), found.totalImpulse(), 1e-9, "time and thrust are found by their names");
	}

	@Test
	void saysWhatIsWrongWithALog() {
		assertTrue(assertThrows(ToolException.class, () -> StaticFire.fromCsv("a,b\n1,2\n", null, null, null, null, 0))
				.getMessage().contains("rows of numbers"));
		// Noise alone: no burn.
		Random r = new Random(1);
		StringBuilder quiet = new StringBuilder();
		for (int i = 0; i < 2000; i++) {
			quiet.append(i / 1000.0).append(',').append(5 + r.nextGaussian()).append('\n');
		}
		assertTrue(assertThrows(ToolException.class, () -> StaticFire.fromCsv(quiet.toString(), null, null, null, null, 0))
				.getMessage().contains("No burn"));
		assertTrue(assertThrows(ToolException.class, () -> StaticFire.fromCsv(log(0, 0, 1, "t,F", 1, 1), "nope", null, null, null, 0))
				.getMessage().contains("No column named"));
		// A log that starts mid-burn is used, with a warning that the offset is unknown.
		StringBuilder late = new StringBuilder();
		for (int i = 3000; i <= 9000; i++) {
			late.append(i / 1000.0).append(',').append(truth(i / 1000.0) + 25).append('\n');
		}
		StaticFire.Curve c = StaticFire.fromCsv(late.toString(), null, null, null, null, 0);
		assertTrue(c.notes().stream().anyMatch(n -> n.contains("already burning")), c.notes().toString());
	}
}
