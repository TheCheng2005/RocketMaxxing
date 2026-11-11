package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;

/** simulate_engine through the server: each engine type, the motor it makes, and the flight it follows. */
class EngineToolsTest {
	static final String HYBRID = "\"type\":\"hybrid\",\"throatDiameter\":\"22 mm\",\"exitDiameter\":\"45 mm\",\"gamma\":1.25,"
			+ "\"cstarTable\":[[3,1450],[4,1540],[6,1620],[7,1625],[8,1610],[10,1560]],\"oxidizerMass\":\"6 kg\","
			+ "\"injectorHoleDiameter\":\"1.5 mm\",\"injectorHoleCount\":12,\"dischargeCoefficient\":0.4,"
			+ "\"oxidizerDensity\":\"750 kg/m3\",\"tankPressure\":\"50 bar\",\"tankPressureEnd\":\"35 bar\",\"portDiameter\":\"30 mm\","
			+ "\"grainOuterDiameter\":\"90 mm\",\"grainLength\":\"500 mm\",\"fuelDensity\":\"920 kg/m3\","
			+ "\"regressionCoefficient\":0.155,\"regressionExponent\":0.5";
	static final String MOTOR = ",\"manufacturer\":\"Team\",\"diameter\":\"75 mm\",\"length\":\"1.5 m\",\"dryMass\":\"6 kg\","
			+ "\"dryCgFromTop\":\"0.8 m\",\"oxidizerCgFromTop\":\"0.45 m\",\"fuelCgFromTop\":\"1.2 m\"";

	@TempDir
	Path ws;
	McpServer server;
	int id;

	@BeforeEach
	void start() {
		server = Main.build(new Context(Standards.defaults(), ws, false));
	}

	JsonObject call(String tool, String args) {
		JsonObject r = server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
				+ tool + "\",\"arguments\":" + args + "}}").getAsJsonObject("result");
		JsonObject out = r.getAsJsonArray("content").get(0).getAsJsonObject().deepCopy();
		out.addProperty("isError", r.get("isError").getAsBoolean());
		return out;
	}

	JsonObject ok(String tool, String args) {
		JsonObject r = call(tool, args);
		assertTrue(!r.get("isError").getAsBoolean(), () -> tool + ": " + r);
		return JsonParser.parseString(r.get("text").getAsString()).getAsJsonObject();
	}

	static double first(String s) {
		return Double.parseDouble(s.split(" ")[0]);
	}

	@Test
	void hybridBecomesAMotorWhoseMassAndCgFollowTheBurn() throws Exception {
		JsonObject r = ok("simulate_engine", "{" + HYBRID + ",\"designation\":\"Test-H1\"" + MOTOR + "}");
		double impulse = first(r.get("totalImpulse").getAsString());
		assertTrue(impulse > 12000 && impulse < 17000, "an N-class hybrid: " + impulse);
		assertTrue(r.get("totalImpulse").getAsString().contains("N class"));
		assertEquals(impulse, first(r.getAsJsonObject("motor").get("totalImpulse").getAsString()), 0.01 * impulse,
				"the motor carries the predicted impulse");
		String rse = Files.readString(ws.resolve("motors/Test-H1.rse"));
		// Loaded: 6 kg dry at 0.8 m, 6 kg oxidizer at 0.45 m, the fuel grain at 1.2 m. Empty: the oxidizer is gone.
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("m=\"([0-9.]+)\" cg=\"([0-9.]+)\"").matcher(rse);
		assertTrue(m.find());
		double m0 = Double.parseDouble(m.group(1)) / 1000, cg0 = Double.parseDouble(m.group(2)) / 1000, m1 = m0, cg1 = cg0;
		while (m.find()) {
			m1 = Double.parseDouble(m.group(1)) / 1000;
			cg1 = Double.parseDouble(m.group(2)) / 1000;
		}
		double fuel = m0 - 12;
		assertEquals((6 * 0.8 + 6 * 0.45 + fuel * 1.2) / m0, cg0, 1e-3, "CG loaded");
		assertTrue(m1 < m0 - 6.5 && m1 > 6, "the oxidizer and some fuel are gone: " + m1);
		assertTrue(cg1 > cg0 + 0.05, "the forward oxidizer leaves, so the CG moves aft: " + cg0 + " -> " + cg1);
		// The motor is in the list.
		assertTrue(ok("search_motors", "{\"designationContains\":\"Test-H1\"}").get("matches").getAsInt() >= 1);
	}

	@Test
	void thrustFollowsTheFlightWithoutChangingTheDesign() {
		String design = ok("open_design", "{\"path\":\"" + Path.of("docs/examples/maple-10k.ork").toAbsolutePath().toString()
				.replace("\\", "\\\\") + "\"}").get("designId").getAsString();
		JsonObject before = ok("get_design", "{\"designId\":\"" + design + "\"}");
		JsonObject pad = ok("simulate_engine", "{" + HYBRID + "}");
		JsonObject flown = ok("simulate_engine", "{" + HYBRID + MOTOR + ",\"designId\":\"" + design + "\"}");
		double gain = first(flown.get("totalImpulse").getAsString()) / first(pad.get("totalImpulse").getAsString());
		assertTrue(gain > 1.003 && gain < 1.06, "more impulse in thinner air, by a little: " + gain);
		assertTrue(flown.getAsJsonObject("flight").get("altitudeAtBurnout").getAsString().matches("\\d+.*"));
		JsonObject after = ok("get_design", "{\"designId\":\"" + design + "\"}");
		assertEquals(before.get("components"), after.get("components"), "the design's parts are as they were");
		assertEquals(before.get("unsavedChanges"), after.get("unsavedChanges"), "and it is not marked as changed");
		// Without the engine's size it cannot be flown: the answer says what to add instead of failing.
		JsonObject noSize = ok("simulate_engine", "{" + HYBRID + ",\"designId\":\"" + design + "\"}");
		assertTrue(noSize.get("notes").toString().contains("also give the engine's diameter, length, dryMass"), noSize.get("notes").toString());
	}

	@Test
	void solidAndLiquidRunAndSayWhatIsMissing() {
		JsonObject solid = ok("simulate_engine", "{\"type\":\"solid\",\"throatDiameter\":\"20 mm\",\"expansionRatio\":6,"
				+ "\"cstar\":\"1500 m/s\",\"grainCount\":3,\"grainOuterDiameter\":\"70 mm\",\"grainCoreDiameter\":\"25 mm\","
				+ "\"grainLength\":\"120 mm\",\"propellantDensity\":\"1750 kg/m3\",\"burnRateCoefficient\":5,\"burnRateExponent\":0.35}");
		assertTrue(first(solid.get("totalImpulse").getAsString()) > 1000);
		// The same burn rate in inches per second at psi: 5 mm/s at 1 MPa = 0.19685 in/s at 145.04 psi.
		double aImperial = 5 / 25.4 / Math.pow(145.0377, 0.35);
		JsonObject imperial = ok("simulate_engine", "{\"type\":\"solid\",\"throatDiameter\":\"20 mm\",\"expansionRatio\":6,"
				+ "\"cstar\":\"1500 m/s\",\"grainCount\":3,\"grainOuterDiameter\":\"70 mm\",\"grainCoreDiameter\":\"25 mm\","
				+ "\"grainLength\":\"120 mm\",\"propellantDensity\":\"1750 kg/m3\",\"burnRateCoefficient\":" + aImperial
				+ ",\"burnRateExponent\":0.35,\"burnRateUnits\":\"in/s-psi\"}");
		assertEquals(first(solid.get("totalImpulse").getAsString()), first(imperial.get("totalImpulse").getAsString()), 2,
				"the burn rate in either unit system gives the same engine");
		JsonObject liquid = ok("simulate_engine", "{\"type\":\"liquid\",\"throatDiameter\":\"30 mm\",\"exitDiameter\":\"60 mm\","
				+ "\"cstar\":\"1700 m/s\",\"oxidizerMass\":\"6 kg\",\"fuelMass\":\"3 kg\",\"chamberPressure\":\"20 bar\","
				+ "\"feed\":\"blowdown\",\"ullageFraction\":0.4}");
		assertTrue(liquid.get("burnTime").getAsString().contains("both propellants ran out"), liquid.get("burnTime").getAsString());
		JsonObject missing = call("simulate_engine", "{\"type\":\"hybrid\",\"throatDiameter\":\"22 mm\",\"expansionRatio\":4,"
				+ "\"cstar\":\"1600 m/s\"}");
		assertTrue(missing.get("isError").getAsBoolean() && missing.get("text").getAsString().contains("also needs: oxidizerMass"),
				missing.get("text").getAsString());
	}
}
