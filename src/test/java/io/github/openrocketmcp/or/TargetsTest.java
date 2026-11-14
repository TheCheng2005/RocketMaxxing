package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;

class TargetsTest {
	@SuppressWarnings("unchecked")
	static Map<String, Object> row(Map<String, Object> out, String id) {
		return ((List<Map<String, Object>>) out.get("targets")).stream().filter(r -> r.get("id").equals(id)).findFirst().orElseThrow();
	}

	static Simulation flown() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		return sim;
	}

	@Test
	void marginsToTheNearerLimit() throws Exception {
		Simulation sim = flown();
		double apogee = sim.getSimulatedData().getMaxAltitude();
		Standards std = Standards.defaults().patched(JsonParser.parseString("{\"targets\": {"
				+ "\"apogee\": {\"min\": " + (apogee - 100) + ", \"max\": " + (apogee + 1000) + ", \"goal\": " + apogee + "},"
				+ "\"railExitVelocity\": {\"min\": \"1000 ft/s\"},"
				+ "\"minStability\": {\"min\": \"0.5 cal\", \"max\": \"40 cal\"}}}").getAsJsonObject());
		Map<String, Object> out = Targets.evaluate(sim, std);

		Map<String, Object> a = row(out, "apogee");
		// Written with units, as the team would: an apogee in kilometres is not checked against part lengths.
		Standards.defaults().patched(JsonParser.parseString("{\"targets\": {\"apogee\": {\"min\": \"3 km\", \"max\": \"30000 ft\"}}}").getAsJsonObject());
		assertEquals("PASS", a.get("status"), a.toString());
		assertEquals(100, (double) a.get("marginSI"), 1e-6, "the minimum is the nearer limit");
		assertTrue(a.get("margin").toString().contains("above the minimum"), a.toString());
		assertEquals("DISTANCE", a.get("dim"));

		assertEquals("FAIL", row(out, "railExitVelocity").get("status"));
		Map<String, Object> s = row(out, "minStability");
		assertNull(s.get("dim"), "stability is in calibers");
		assertEquals(0.5, (double) s.get("minSI"), 1e-9);
		assertTrue(s.get("value").toString().endsWith(" cal"), s.toString());

		// No target: the number alone.
		assertNull(row(out, "maxVelocity").get("status"));
		// Flutter defaults to the team's flutter margin (this example's fins are below it).
		Map<String, Object> fl = row(out, "flutterMargin");
		assertEquals("structures.flutterMinMargin", fl.get("from"));
		assertEquals("FAIL", fl.get("status"));
		assertTrue(fl.get("margin").toString().endsWith("below the minimum"), fl.toString());
		assertTrue(out.get("summary").toString().startsWith("1 of 3 targets missed") && out.get("summary").toString().contains("flutter margin is also missed"), out.toString());
	}

	/** Cardboard fins have no shear modulus: the flutter target says why, and the other targets still show. */
	@Test
	void aValueThatCannotBeWorkedOutLeavesTheOthers() throws Exception {
		Designs.Design d = new Designs().openExample("A simple model rocket");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		Map<String, Object> out = Targets.evaluate(sim, Standards.defaults());
		Map<String, Object> fl = row(out, "flutterMargin");
		assertEquals("INFO", fl.get("status"), fl.toString());
		assertNull(fl.get("valueSI"));
		assertTrue(fl.get("note").toString().contains("shear modulus"), fl.toString());
		assertTrue((double) row(out, "apogee").get("valueSI") > 50, out.toString());
	}

	@Test
	void closeToALimitWarns() throws Exception {
		Simulation sim = flown();
		double apogee = sim.getSimulatedData().getMaxAltitude();
		Standards std = Standards.defaults().patched(JsonParser.parseString(
				"{\"targets\": {\"apogee\": {\"max\": " + (apogee * 1.02) + "}}}").getAsJsonObject());
		assertEquals("WARN", row(Targets.evaluate(sim, std), "apogee").get("status"));
	}

	/** A typo in the standards file is reported on its own row (or as an unknown target); the others are still checked. */
	@Test
	void aBadEntryDoesNotHideTheOthers() throws Exception {
		Simulation sim = flown();
		Standards std = Standards.defaults().patched(JsonParser.parseString("{\"targets\": {\"apogeee\": {\"min\": 10}, "
				+ "\"apogee\": {\"min\": \"10 m/s\"}, \"railExitVelocity\": {\"min\": \"1 m/s\"}}}").getAsJsonObject());
		Map<String, Object> out = Targets.evaluate(sim, std);
		assertTrue(out.get("unknownTargets").toString().contains("apogeee (did you mean apogee?)"), out.toString());
		Map<String, Object> a = row(out, "apogee");
		assertTrue(a.get("invalid").toString().contains("Apogee minimum") && a.get("invalid").toString().contains("velocity"), a.toString());
		assertEquals("PASS", row(out, "railExitVelocity").get("status"), "the other targets are still checked");
		assertTrue(out.get("summary").toString().contains("1 target cannot be read"), out.toString());
	}

	/** What a change to the targets may not do, each refused with what to type instead. */
	@Test
	void refusesTargetsThatMakeNoSense() {
		java.util.function.Function<String, String> refused = json -> {
			com.google.gson.JsonObject change = JsonParser.parseString(json).getAsJsonObject();
			return assertThrows(ToolException.class, () -> Targets.validate(change, change)).getMessage();
		};
		assertTrue(refused.apply("{\"apogees\": {\"min\": \"1 km\"}}").contains("Did you mean apogee?"));
		assertTrue(refused.apply("{\"apogee\": \"10000 ft\"}").contains("takes limits by name"));
		assertTrue(refused.apply("{\"apogee\": {\"minimum\": \"1 km\"}}").contains("not 'minimum'"));
		assertTrue(refused.apply("{\"maxMach\": {\"min\": 2, \"max\": 1}}").contains("has to be below the maximum"));
		assertTrue(refused.apply("{\"apogee\": {\"min\": \"-5 m\"}}").contains("below zero"));
		assertTrue(refused.apply("{\"apogee\": {\"min\": \"3 km\", \"goal\": \"2 km\"}}").contains("outside the limits"));
		String unit = refused.apply("{\"apogee\": {\"min\": \"20 kg\"}}");
		assertTrue(unit.contains("Apogee minimum") && unit.contains("cannot be read") && unit.contains("9500 ft"), unit);
		// A decimal comma and a thousands separator are both read.
		Targets.validate(JsonParser.parseString("{\"minStability\": {\"min\": \"1,5\"}, \"apogee\": {\"max\": \"10,500 ft\"}}").getAsJsonObject(),
				JsonParser.parseString("{\"minStability\": {\"min\": \"1,5\"}, \"apogee\": {\"max\": \"10,500 ft\"}}").getAsJsonObject());
		Targets.Metric stab = Targets.metric("minStability");
		assertEquals(1.5, Targets.bound(JsonParser.parseString("{\"min\": \"1,5\"}").getAsJsonObject(), "min", stab), 1e-9);
		assertEquals(10500 * 0.3048, Targets.bound(JsonParser.parseString("{\"max\": \"10,500 ft\"}").getAsJsonObject(), "max",
				Targets.metric("apogee")), 1e-6);
	}

	/** The short form for assistants: rows for the targets set, the other numbers one line each, no SI copies. */
	@Test
	void shortFormForAssistants() throws Exception {
		Simulation sim = flown();
		Standards std = Standards.defaults().patched(JsonParser.parseString("{\"targets\": {\"apogee\": {\"min\": \"1 m\"}}}").getAsJsonObject());
		Map<String, Object> out = Targets.evaluate(sim, std, false);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("targets");
		assertEquals(List.of("apogee", "flutterMargin"), rows.stream().map(r -> r.get("id")).toList());
		assertNull(rows.get(0).get("valueSI"));
		assertNull(rows.get(0).get("minSI"));
		@SuppressWarnings("unchecked")
		Map<String, Object> others = (Map<String, Object>) out.get("otherValues");
		assertTrue(others.containsKey("maxVelocity") && !others.containsKey("apogee"), others.toString());
		int full = new com.google.gson.Gson().toJson(Targets.evaluate(sim, std, true)).length();
		int brief = new com.google.gson.Gson().toJson(out).length();
		assertTrue(brief < full * 0.7, "the short form is much shorter: " + brief + " vs " + full);
	}

	/** One recovery device is the main: a drogue target does not apply (not missed), and the row says why. */
	@Test
	void noDrogueIsNotAMiss() throws Exception {
		Designs.Design d = new Designs().openExample("A simple model rocket");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		Standards std = Standards.defaults().patched(JsonParser.parseString("{\"targets\": {\"drogueDescentRate\": {\"max\": \"30 m/s\"}}}").getAsJsonObject());
		Map<String, Object> r = row(Targets.evaluate(sim, std), "drogueDescentRate");
		assertEquals("INFO", r.get("status"), r.toString());
		assertTrue(r.get("value").toString().contains("no drogue"), r.toString());
	}

	/** With no targets set, the summary does not count the team's default flutter margin as one the team set. */
	@Test
	void summaryWithoutTargets() throws Exception {
		Map<String, Object> out = Targets.evaluate(flown(), Standards.defaults());
		assertTrue(out.get("summary").toString().startsWith("No team targets set (the team's minimum flutter margin is missed)"),
				out.get("summary").toString());
	}
}
