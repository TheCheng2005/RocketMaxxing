package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** The launch planner on an ensemble forecast made up for the test (Open-Meteo's layout: control and _memberNN keys). */
class LaunchPlanTest {

	/** A west wind (from 270 deg) for every member, its speed spread by member, with pressure levels; 3 hours. */
	static JsonObject ensemble(int members, boolean aloft) {
		JsonObject j = new JsonObject();
		j.addProperty("latitude", 47.97);
		j.addProperty("longitude", -81.85);
		j.addProperty("elevation", 378);
		j.addProperty("utc_offset_seconds", -14400);
		j.addProperty("timezone", "America/Toronto");
		JsonObject h = new JsonObject();
		JsonArray t = new JsonArray();
		for (String s : List.of("2027-08-21T09:00", "2027-08-21T10:00", "2027-08-21T11:00")) {
			t.add(s);
		}
		h.add("time", t);
		Random r = new Random(1);
		for (int m = 0; m <= members; m++) {
			String sfx = m == 0 ? "" : String.format("_member%02d", m);
			double ground = 4 + 2 * r.nextGaussian();
			put(h, "wind_speed_10m" + sfx, Math.max(0.5, ground));
			put(h, "wind_direction_10m" + sfx, 270 + 10 * r.nextGaussian());
			put(h, "wind_gusts_10m" + sfx, Math.max(1, ground * 1.5));
			put(h, "temperature_2m" + sfx, 18);
			put(h, "surface_pressure" + sfx, 970);
			if (aloft) {
				put(h, "wind_speed_100m" + sfx, Math.max(1, ground * 1.4));
				put(h, "wind_direction_100m" + sfx, 275);
				int[] p = { 925, 850, 700, 500 };
				double[] z = { 800, 1500, 3100, 5800 };
				for (int k = 0; k < p.length; k++) {
					put(h, "wind_speed_" + p[k] + "hPa" + sfx, Math.max(1, ground * (2 + k)));
					put(h, "wind_direction_" + p[k] + "hPa" + sfx, 280);
					put(h, "geopotential_height_" + p[k] + "hPa" + sfx, z[k]);
				}
			}
		}
		j.add("hourly", h);
		return j;
	}

	static void put(JsonObject h, String k, double v) {
		JsonArray a = new JsonArray();
		for (int i = 0; i < 3; i++) {
			a.add(v);
		}
		h.add(k, a);
	}

	@Test
	void membersAndTheirWindsAloft() {
		LaunchPlan.Ensemble e = LaunchPlan.parse(ensemble(10, true), LaunchPlan.Model.ECMWF, "2027-08-21T10:20", null);
		assertEquals(11, e.members().size(), "the control and 10 members");
		assertEquals("2027-08-21T10:00", e.time());
		assertEquals("control", e.members().get(0).name());
		List<Winds.Level> ls = e.members().get(3).levels();
		assertEquals(5800 - 378, ls.get(ls.size() - 1).altitude(), 1e-9, "pressure levels by geopotential height above the ground");
		assertEquals(Math.toRadians(280), ls.get(ls.size() - 1).direction(), 1e-9);

		// GFS: ground wind per member, winds aloft from the deterministic forecast moved by each member's difference.
		Weather.Forecast det = new Weather.Forecast(47.97, -81.85, 378, "2027-08-21T10:00", "x", 4, Math.toRadians(270), 6, 291, 97000,
				List.of(new Winds.Level(0, 4, Math.toRadians(270), 0.4), new Winds.Level(10, 4, Math.toRadians(270), 0.4),
						new Winds.Level(1500, 15, Math.toRadians(270), 1.5)));
		LaunchPlan.Ensemble g = LaunchPlan.parse(ensemble(6, false), LaunchPlan.Model.GFS, "2027-08-21T10:00", det);
		assertEquals(7, g.members().size());
		for (LaunchPlan.Member m : g.members()) {
			assertEquals(1500, m.levels().get(m.levels().size() - 1).altitude(), 1e-9, "the deterministic levels aloft");
		}
		double spread = g.members().stream().mapToDouble(m -> m.levels().get(m.levels().size() - 1).speed()).max().orElse(0)
				- g.members().stream().mapToDouble(m -> m.levels().get(m.levels().size() - 1).speed()).min().orElse(0);
		assertTrue(spread > 0.5, "members differ aloft by their ground difference: " + spread);
	}

	@Test
	@SuppressWarnings("unchecked")
	void landingAreasDownwind() throws Exception {
		OrRuntime.init();
		Designs ds = new Designs();
		Designs.Design d = ds.openExample("Dual parachute");
		var base = Sims.prepare(d, null, null, Sims.Overrides.none(), io.github.openrocketmcp.standards.Standards.defaults());
		LaunchPlan.Ensemble e = LaunchPlan.parse(ensemble(10, true), LaunchPlan.Model.ECMWF, "2027-08-21T10:00", null);
		Map<String, Object> out = LaunchPlan.plan(base, d.doc, e, new LaunchPlan.Settings(60, Math.toRadians(1), Math.toRadians(5), 7, 2000),
				47.97, -81.85);
		assertEquals(0, out.get("failedRuns"), out.toString());
		List<Map<String, Object>> land = (List<Map<String, Object>>) out.get("landing");
		Map<String, Object> main = land.get(0);
		List<double[]> pts = (List<double[]>) main.get("points");
		assertEquals(60, pts.size());
		double[] c = (double[]) main.get("centreLatLon");
		assertTrue(c[1] > -81.85, "a west wind carries it east of the pad: " + c[1]);
		List<Map<String, Object>> areas = (List<Map<String, Object>>) main.get("areas");
		assertEquals(List.of(50, 95, 99), areas.stream().map(a -> a.get("percent")).toList());
		assertTrue((double) areas.get(0).get("semiMajor") < (double) areas.get(1).get("semiMajor")
				&& (double) areas.get(1).get("semiMajor") < (double) areas.get(2).get("semiMajor"), "nested areas");
		assertEquals(73, ((List<?>) areas.get(1).get("polygon")).size());
		Map<String, Object> asIs = (Map<String, Object>) out.get("forecastAsIs");
		List<Map<String, Object>> tracks = (List<Map<String, Object>>) asIs.get("tracks");
		assertTrue(((List<?>) tracks.get(0).get("path")).size() > 20 && tracks.get(0).containsKey("landing"), asIs.toString());
		assertTrue(((List<?>) out.get("winds")).size() >= 5, "winds by height");
		assertTrue(String.valueOf(out.get("verdict")).startsWith("95% of 60 flights land inside"), String.valueOf(out.get("verdict")));
		ds.close(d.id);
	}
}
