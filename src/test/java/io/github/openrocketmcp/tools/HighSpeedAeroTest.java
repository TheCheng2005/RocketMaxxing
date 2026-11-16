package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.HighSpeedAero;
import io.github.openrocketmcp.standards.Standards;

/**
 * The high-speed drag model: fin thickness drag from linear theory (against the textbook values), base drag while the
 * motor burns, and its use in flights: recalculated as the rocket changes, kept with the design, replaced by an
 * imported table.
 */
class HighSpeedAeroTest {
	static double num(JsonObject o, String k) {
		return Double.parseDouble(o.get(k).getAsString().replaceAll("[^0-9.eE+-].*$", ""));
	}

	static JsonObject row(JsonObject r, double mach) {
		for (var e : r.getAsJsonArray("cdByMach")) {
			if (Math.abs(num(e.getAsJsonObject(), "mach") - mach) < 1e-9) {
				return e.getAsJsonObject();
			}
		}
		throw new AssertionError("no Mach " + mach);
	}

	@Test
	void thicknessDragIsLinearTheory() throws Exception {
		var wave = HighSpeedAero.class.getDeclaredMethod("waveDrag", double.class, double.class, double.class);
		var factor = HighSpeedAero.class.getDeclaredMethod("sectionFactor", String.class, double.class, double.class);
		wave.setAccessible(true);
		factor.setAccessible(true);
		// Ackeret: a 5% double wedge at Mach 2 has cd = 4 (t/c)^2 / sqrt(M^2 - 1) = 0.005774.
		assertEquals(4 * 0.05 * 0.05 / Math.sqrt(3), (double) wave.invoke(null, 2.0, 4.0, 0.05), 1e-12);
		assertEquals(16.0 / 3, (double) factor.invoke(null, "biconvex", 0.1, 0.01), 1e-12);
		assertEquals(4, (double) factor.invoke(null, "hexagonal", 0.1, 0.05), 1e-12, "bevels meeting mid-chord are a double wedge");
		assertEquals(10, (double) factor.invoke(null, "hexagonal", 0.1, 0.02), 1e-12, "2 c / a");
		assertEquals(0, (double) wave.invoke(null, 0.8, 4.0, 0.05), 0, "none below Mach 0.85");
		assertEquals((double) wave.invoke(null, 1.2, 4.0, 0.05), (double) wave.invoke(null, 1.1, 4.0, 0.05), 1e-12, "held through Mach 1");
		assertTrue((double) wave.invoke(null, 0.95, 4.0, 0.05) < (double) wave.invoke(null, 1.0, 4.0, 0.05));
		var swept = HighSpeedAero.class.getDeclaredMethod("waveDrag", double.class, double.class, double.class, double.class);
		swept.setAccessible(true);
		// A 45 degree leading edge is subsonic until Mach 1.414: K (t/c)^2 / tan(sweep) until then, the unswept value after.
		assertEquals(4 * 0.05 * 0.05, (double) swept.invoke(null, 1.3, 4.0, 0.05, Math.PI / 4), 1e-12);
		assertEquals(4 * 0.05 * 0.05 / Math.sqrt(3), (double) swept.invoke(null, 2.0, 4.0, 0.05, Math.PI / 4), 1e-12);
	}

	@Test
	void modelInFlights(@TempDir Path ws) throws Exception {
		Context ctx = new Context(Standards.defaults(), ws, false);
		McpServer s = Main.build(ctx, x -> {
		}, false);
		TemplatesTest.call(s, "set_units", "{\"system\":\"metric\"}", false);
		Path ork = Path.of("docs/examples/maple-10k.ork").toAbsolutePath();
		TemplatesTest.call(s, "open_design", "{\"path\":" + new Gson().toJson(ork.toString()) + "}", false);
		Rocket r = ctx.designs.get(null).doc.getRocket();
		double baseApogee = ExtensionsTest.apogee(TemplatesTest.call(s, "run_simulation", "{}", false));

		// As designed (square fins): only the base drag while burning differs.
		JsonObject p = TemplatesTest.call(s, "high_speed_aero", "{}", false);
		JsonObject m15 = row(p, 1.5);
		assertEquals(num(m15, "cdOpenRocket"), num(m15, "cdModelCoasting"), 1e-3);
		double share = (num(m15, "baseCoasting") - num(m15, "baseBurning")) / num(m15, "baseCoasting");
		assertTrue(share > 0.05 && share < 0.6, "nozzle exit share of the base: " + share);
		assertEquals(num(m15, "cdModelCoasting") - num(m15, "cdModelBurning"), num(m15, "baseCoasting") - num(m15, "baseBurning"), 1e-3);
		assertNull(HighSpeedAero.settings(r), "a preview changes nothing");
		assertEquals(baseApogee, ExtensionsTest.apogee(TemplatesTest.call(s, "run_simulation", "{}", false)), 1e-9);
		JsonObject f = p.getAsJsonObject("flight");
		assertTrue(num(f, "apogeeModel") > num(f, "apogeeOpenRocket"), "less drag while burning: " + f);
		assertTrue(num(f, "apogeeModel") < 1.05 * num(f, "apogeeOpenRocket"), "and not by much: " + f);
		assertTrue(p.getAsJsonArray("angleOfAttack").size() == 16);

		// Bevelled fins: the square edges' drag goes, linear-theory thickness drag comes in above Mach 0.85.
		FinSet fins = null;
		for (RocketComponent c : r) {
			if (c instanceof FinSet x) {
				fins = x;
			}
		}
		JsonObject h = TemplatesTest.call(s, "high_speed_aero", "{\"action\":\"use\",\"finProfile\":\"hexagonal\",\"bevelLength\":\"19 mm\"}", false);
		JsonObject h05 = row(h, 0.6), h15 = row(h, 1.5);
		assertEquals(0, num(h05, "finsModel"), 1e-12);
		assertTrue(num(h05, "finsOpenRocket") > 0.3, "square edges are most of OpenRocket's drag here");
		double chord = fins.getPlanformArea() / fins.getSpan(), tc = fins.getThickness() / chord;
		double sref = Math.PI * Math.pow(io.github.openrocketmcp.or.Analysis.maxDiameter(r.getSelectedConfiguration()) / 2, 2);
		double tipLe = 0;
		for (var pt : fins.getFinPoints()) {
			if (pt.y >= fins.getSpan() - 1e-9) {
				tipLe = pt.x;
				break;
			}
		}
		double beta = Math.max(Math.sqrt(1.5 * 1.5 - 1), tipLe / fins.getSpan()); // DATCOM: a subsonic leading edge's sweep sets it
		double expect = fins.getFinCount() * fins.getPlanformArea() / sref * (2 * chord / 0.019) * tc * tc / beta;
		assertEquals(expect, num(h15, "finsModel"), 0.002 * expect + 1e-4, "N S_fin / S_ref K (t/c)^2 / beta");
		assertEquals(num(h15, "cdOpenRocket") - num(h15, "finsOpenRocket") + num(h15, "finsModel"), num(h15, "cdModelCoasting"), 2e-3);

		// In use: every simulation flies on it; the preview's number is the flight's.
		assertTrue(HighSpeedAero.settings(r) != null);
		double on = ExtensionsTest.apogee(TemplatesTest.call(s, "run_simulation", "{}", false));
		assertEquals(num(h.getAsJsonObject("flight"), "apogeeModel"), on, 0.002 * on);
		assertTrue(on > 1.15 * baseApogee, "bevelled fins fly much higher than square ones: " + on + " vs " + baseApogee);

		// It follows the rocket: thicker fins, more thickness drag, lower apogee.
		double cdBefore = AeroTable.shown(r).cd(1.5, false);
		TemplatesTest.call(s, "edit_components", "{\"changes\":[{\"component\":\"Fins\",\"properties\":{\"thickness\":\"9.5 mm\"}}]}", false);
		assertTrue(AeroTable.shown(r).cd(1.5, false) > cdBefore + 0.05, "the table is worked out again");
		assertTrue(ExtensionsTest.apogee(TemplatesTest.call(s, "run_simulation", "{}", false)) < on);

		// Kept with the design.
		Path saved = ws.resolve("maple-hs.ork");
		TemplatesTest.call(s, "save_design", "{\"path\":" + new Gson().toJson(saved.toString()) + "}", false);
		assertTrue(Files.readString(AeroTable.sidecar(saved)).contains("\"finProfile\": \"hexagonal\""));
		TemplatesTest.call(s, "close_design", "{\"designId\":\"" + ctx.designs.get(null).id + "\"}", false);
		TemplatesTest.call(s, "open_design", "{\"path\":" + new Gson().toJson(saved.toString()) + "}", false);
		Rocket again = ctx.designs.get(null).doc.getRocket();
		assertEquals("hexagonal", HighSpeedAero.settings(again).finProfile());
		assertEquals(0.019, HighSpeedAero.settings(again).bevel(), 1e-12);

		// Off: OpenRocket's drag again. An imported table also takes its place.
		double offApogee = ExtensionsTest.apogee(TemplatesTest.call(s, "run_simulation", "{}", false));
		TemplatesTest.call(s, "high_speed_aero", "{\"action\":\"off\"}", false);
		assertNull(HighSpeedAero.settings(again));
		assertTrue(ExtensionsTest.apogee(TemplatesTest.call(s, "run_simulation", "{}", false)) < offApogee);
		TemplatesTest.call(s, "high_speed_aero", "{\"action\":\"use\"}", false);
		TemplatesTest.call(s, "import_aero_table", "{\"csv\":\"Mach,CD\\n0.1,0.5\\n1,0.6\\n3,0.5\\n\"}", false);
		assertNull(HighSpeedAero.settings(again), "imported data replaces the model");
		assertFalse(AeroTable.of(again).source().equals(HighSpeedAero.SOURCE));

		TemplatesTest.call(s, "high_speed_aero", "{\"finProfile\":\"round\"}", true);
		// Tables are kept by the rocket's own id, which every copy of this example file shares: leave none behind.
		TemplatesTest.call(s, "close_design", "{\"designId\":\"" + ctx.designs.get(null).id + "\"}", false);
		assertNull(AeroTable.of(again));
	}
}
