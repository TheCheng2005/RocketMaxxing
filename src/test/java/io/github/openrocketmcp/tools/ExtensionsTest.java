package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;

/** OpenRocket's simulation extensions, set up per simulation: air-start, scripts (off until a person turns them on). */
class ExtensionsTest {
	static double apogee(JsonObject flight) {
		JsonObject o = flight.has("apogee") ? flight : flight.getAsJsonObject("flight");
		String s = o.get("apogee").getAsString();
		return Double.parseDouble(s.replaceAll("[^0-9.].*$", ""));
	}

	@Test
	void airStartAndScripts(@TempDir Path ws) throws Exception {
		Context ctx = new Context(Standards.defaults(), ws, false);
		McpServer s = Main.build(ctx, x -> {
		}, false);
		TemplatesTest.call(s, "set_units", "{\"system\":\"metric\"}", false);
		TemplatesTest.call(s, "open_design", "{\"example\":\"A simple model rocket\"}", false);
		double base = apogee(TemplatesTest.call(s, "run_simulation", "{}", false));

		String types = TemplatesTest.call(s, "simulation_extensions", "{\"action\":\"types\"}", false).toString();
		assertTrue(types.contains("AirStart") && types.contains("ScriptingExtension") && types.contains("RollControl"), types);

		JsonObject r = TemplatesTest.call(s, "simulation_extensions",
				"{\"action\":\"add\",\"type\":\"air-start\",\"settings\":{\"launchAltitude\":\"1000 m\",\"launchVelocity\":\"20 m/s\"}}", false);
		assertTrue(apogee(r.getAsJsonObject("flight")) > 1000 && base < 1000, "starts 1000 m up: " + r.get("flight") + " vs " + base);
		assertEquals("1000 m", r.getAsJsonArray("extensions").get(0).getAsJsonObject().getAsJsonObject("settings").get("launchAltitude").getAsString());
		TemplatesTest.call(s, "simulation_extensions", "{\"action\":\"remove\",\"index\":0}", false);

		// A script that stops the flight with a message: off when added, so the flight is untouched.
		String script = new Gson().toJson("function postStep(status) { if (status.getSimulationTime() > 1) throw new Error('script ran'); }");
		r = TemplatesTest.call(s, "simulation_extensions", "{\"action\":\"add\",\"type\":\"ScriptingExtension\",\"settings\":{\"script\":" + script + "}}", false);
		assertFalse(r.has("flightFailed"), r.toString());
		assertTrue(r.getAsJsonArray("extensions").get(0).getAsJsonObject().has("off"), "a new script is off");

		// The assistant in the desktop app cannot turn it on.
		ctx.keepJournal(true);
		String refused = Journal.as("Claude", () -> TemplatesTest.callText(s, "simulation_extensions",
				"{\"action\":\"set\",\"index\":0,\"settings\":{\"enabled\":true}}"));
		assertTrue(refused.contains("turns it on"), refused);

		// The person can: the script runs during the flight.
		r = TemplatesTest.call(s, "simulation_extensions", "{\"action\":\"set\",\"index\":0,\"settings\":{\"enabled\":true}}", false);
		String flown = r.toString();
		assertTrue(flown.contains("script ran"), flown);
	}
}
