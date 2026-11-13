package io.github.openrocketmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/**
 * Drives the server through JSON-RPC exactly as an MCP client would, on OpenRocket's two-stage example.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpIntegrationTest {
	static McpServer server;
	static int id;
	static final boolean VERBOSE = Boolean.getBoolean("mcp.verbose");

	@BeforeAll
	static void setUp() {
		server = Main.build(new Context(Standards.defaults()));
	}

	static JsonObject rpc(String method, String params) {
		String line = "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
		return server.handleLine(line);
	}

	static String call(String tool, String args) {
		JsonObject r = rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}");
		assertTrue(r.has("result"), () -> "protocol error: " + r);
		JsonObject result = r.getAsJsonObject("result");
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		if (VERBOSE) {
			try {
				Files.writeString(Path.of("build/mcp-transcript.txt"), "=== " + tool + " " + args + "\n" + text + "\n",
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		assertFalse(result.get("isError").getAsBoolean(), () -> tool + " failed: " + text);
		return text;
	}

	static String callExpectError(String tool, String args) {
		JsonObject r = rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}");
		JsonObject result = r.getAsJsonObject("result");
		assertTrue(result.get("isError").getAsBoolean());
		return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	@Test
	@Order(1)
	void handshake() {
		JsonObject init = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
		assertEquals("2025-06-18", init.getAsJsonObject("result").get("protocolVersion").getAsString());
		assertEquals(null, server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
		JsonObject tools = rpc("tools/list", "{}");
		int n = tools.getAsJsonObject("result").getAsJsonArray("tools").size();
		assertTrue(n >= 25, "tools: " + n);
		assertTrue(rpc("prompts/list", "{}").getAsJsonObject("result").getAsJsonArray("prompts").size() >= 3);
		assertTrue(rpc("resources/read", "{\"uri\":\"openrocket://rules\"}").toString().contains("100 ft/s"));
		assertTrue(rpc("nope", "{}").has("error"));
	}

	@Test
	@Order(2)
	void openAndInspect() {
		String open = call("open_design", "{\"example\":\"Two stage high power\"}");
		assertTrue(open.contains("\"designId\":\"d1\""));
		String design = call("get_design", "{}");
		assertTrue(design.contains("Sustainer") && design.contains("Booster"));
		assertTrue(design.contains("after Booster separates"), "per-stack stability");
		String comp = call("describe_component", "{\"component\":\"Sustainer Drogue\"}");
		assertTrue(comp.contains("diameter") && comp.contains("deployment"));
	}

	@Test
	@Order(3)
	void simulateAndCheck() {
		String sim = call("run_simulation", "{}");
		assertTrue(sim.contains("railExitVelocity") && sim.contains("deployments") && sim.contains("Booster"));
		String check = call("check_requirements", "{}");
		assertTrue(check.contains("Rail departure velocity") && check.contains("drogue descent rate"));
		String data = call("get_flight_data", "{\"variables\":[\"altitude\",\"stability\"],\"maxPoints\":10}");
		assertTrue(data.contains("rows"));
		String err = callExpectError("get_flight_data", "{\"variables\":[\"bogus\"]}");
		assertTrue(err.contains("Unknown flight variable"));
	}

	@Test
	@Order(4)
	void recoveryChain() {
		String rec = call("recovery_analysis", "{\"pinType\":\"4-40 nylon\"}");
		assertTrue(rec.contains("openingLoadInfiniteMass") && rec.contains("shearPinsForLaterBays") && rec.contains("harnessWorkingLoad"), rec);
		String sweep = call("deployment_delay_sweep", "{\"device\":\"Sustainer Drogue\",\"delays\":[0,2,4],\"pinType\":\"4-40 nylon\",\"pinCount\":4}");
		assertTrue(sweep.contains("latestDelayWithinCapacity"));
		String size = call("size_parachute", "{\"device\":\"Sustainer Main\",\"targetDescentRate\":\"20 ft/s\"}");
		assertTrue(size.contains("requiredCdA") && size.contains("presetMatches"));
		String shock = call("opening_shock", "{\"mass\":50,\"velocity\":36,\"cd\":2.2,\"area\":0.636,\"airDensity\":0.86,\"cx\":1.4}");
		assertTrue(shock.contains("1092 N"), shock);
		String bp = call("ejection_charge", "{\"bayDiameter\":\"4 in\",\"bayLength\":\"10 in\",\"pressure\":\"15 psi\",\"safetyFactor\":1}");
		assertTrue(bp.contains("0.97"), bp);
		String fit = call("recovery_bay_fit", "{\"items\":[{\"name\":\"26 in drogue\",\"packedVolume\":\"8.8 in3\"},{\"name\":\"cords\",\"cordLength\":\"252 in\"}],"
				+ "\"bayDiameter\":\"4.343 in\",\"availableLength\":\"11 in\"}");
		assertTrue(fit.contains("7.567 in"), fit);
		call("shear_pins", "{\"holdForce\":\"490 N\",\"pinType\":\"4-40 nylon\"}");
		call("descent_energy", "{\"sections\":[{\"name\":\"nose\",\"mass\":\"4 lb\"},{\"name\":\"booster\",\"mass\":\"12 lb\"}],"
				+ "\"descentRate\":\"20 ft/s\",\"energyLimit\":\"75 ft-lbf\"}");
		call("search_parachutes", "{\"minDiameter\":\"48 in\",\"maxDiameter\":\"72 in\",\"limit\":5}");
	}

	@Test
	@Order(5)
	void motorsAndEditing(@TempDir Path tmp) throws Exception {
		call("search_motors", "{\"maxDiameter\":\"38 mm\",\"certLevel\":\"L1\",\"limit\":5}");
		String design = call("get_design", "{}");
		assertTrue(design.contains("motor mount"), design);
		String rank = call("rank_motors", "{\"mount\":\"Sustainer Motor Mount\",\"objective\":\"max_apogee\",\"maxCandidates\":6}");
		assertTrue(rank.contains("ranking"));
		String custom = call("create_custom_motor", "{\"designation\":\"TestLiquid-K\",\"manufacturer\":\"UTAT\",\"type\":\"liquid\","
				+ "\"diameter\":\"38 mm\",\"length\":\"400 mm\",\"totalMass\":\"1.2 kg\",\"propellantMass\":\"0.5 kg\","
				+ "\"thrustCurve\":[[0,0],[0.1,400],[2.5,380],[2.6,0]],\"propellantCgFromTop\":\"120 mm\",\"dryCgFromTop\":\"260 mm\","
				+ "\"saveTo\":\"" + tmp.resolve("TestLiquid-K.rse").toString().replace("\\", "/") + "\"}");
		assertTrue(custom.contains("TestLiquid-K") && Files.exists(tmp.resolve("TestLiquid-K.rse")));
		call("set_motor", "{\"mount\":\"Sustainer Motor Mount\",\"motor\":\"TestLiquid-K\",\"manufacturer\":\"UTAT\"}");
		call("run_simulation", "{}");
		String edit = call("edit_components", "{\"changes\":[{\"component\":\"Sustainer Drogue\",\"properties\":{\"diameter\":\"30 in\",\"cd\":0.9}}]}");
		assertTrue(edit.contains("0.762 m") || edit.contains("762 mm") || edit.contains("30 in"), edit);
		String sweep = call("sweep", "{\"parameter\":\"windSpeed\",\"values\":[\"0 km/h\",\"30 km/h\"]}");
		assertTrue(sweep.contains("landingDistance"));
		String ambiguous = callExpectError("describe_component", "{\"component\":\"Trapezoidal Fin Set\"}");
		assertTrue(ambiguous.contains("Several components"), ambiguous);
		String finId = firstIdOfType(JsonParser.parseString(call("get_design", "{\"designId\":\"d1\"}")).getAsJsonObject()
				.getAsJsonArray("components"), "TrapezoidFinSet");
		String fins = call("describe_component", "{\"component\":\"" + finId + "\"}");
		assertTrue(fins.contains("height"), fins);
		String before = call("describe_component", "{\"component\":\"" + finId + "\"}");
		call("sweep", "{\"component\":\"" + finId + "\",\"parameter\":\"height\",\"values\":[\"40 mm\",\"60 mm\"]}");
		assertEquals(before, call("describe_component", "{\"component\":\"" + finId + "\"}"), "sweep must restore the property");
		String added = call("add_component", "{\"parent\":\"Sustainer\",\"type\":\"BodyTube\",\"name\":\"Test tube\",\"properties\":{\"length\":\"100 mm\"}}");
		assertTrue(added.contains("Test tube"));
		call("remove_component", "{\"component\":\"Test tube\"}");
		Path out = tmp.resolve("saved.ork");
		call("save_design", "{\"path\":\"" + out.toString().replace("\\", "/") + "\"}");
		assertTrue(Files.size(out) > 1000);
		String reopened = call("open_design", "{\"path\":\"" + out.toString().replace("\\", "/") + "\"}");
		assertTrue(reopened.contains("d2"));
	}

	static String firstIdOfType(JsonArray lines, String type) {
		for (JsonElement e : lines) {
			Matcher m = Pattern.compile("\\[([0-9a-f]{8})\\] (\\w+):").matcher(e.getAsString());
			if (m.find() && m.group(2).equals(type)) {
				return m.group(1);
			}
		}
		return null;
	}

	@Test
	@Order(6)
	void standardsAndUnits() {
		call("set_units", "{\"system\":\"imperial\"}");
		String shock = call("opening_shock", "{\"mass\":\"110 lb\",\"velocity\":\"100 ft/s\",\"cd\":0.8,\"diameter\":\"60 in\",\"altitudeMsl\":\"1000 ft\"}");
		assertTrue(shock.contains("lbf") && !shock.contains(" N "), shock);
		call("set_units", "{\"system\":\"both\"}");
		call("update_standards", "{\"patch\":{\"launchSite\":{\"altitudeMsl\":\"300 m\"},\"recovery\":{\"shearPins\":{\"2-56 nylon\":{\"strength\":\"35 lbf\"}}}}}");
		String pins = call("shear_pins", "{\"holdForce\":\"100 lbf\",\"pinType\":\"2-56 nylon\"}");
		assertTrue(pins.contains("\"pins\":6"), pins);
		JsonObject std = JsonParser.parseString(call("get_standards", "{}")).getAsJsonObject();
		assertEquals("300 m", std.getAsJsonObject("standards").getAsJsonObject("launchSite").get("altitudeMsl").getAsString());
	}

	@Test
	@Order(7)
	void designFromScratch() {
		String open = call("open_design", "{\"newRocketName\":\"Scratch\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();
		String design = call("get_design", "{\"designId\":\"" + id + "\"}");
		String stage = firstIdOfType(JsonParser.parseString(design).getAsJsonObject().getAsJsonArray("components"), "AxialStage");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"" + stage + "\",\"type\":\"NoseCone\",\"name\":\"Nose\","
				+ "\"properties\":{\"length\":\"16 in\",\"aftDiameter\":\"4 in\"}}");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"" + stage + "\",\"type\":\"BodyTube\",\"name\":\"Airframe\","
				+ "\"properties\":{\"length\":\"48 in\",\"outerDiameter\":\"4 in\",\"thickness\":\"2 mm\"}}");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"Airframe\",\"type\":\"InnerTube\",\"name\":\"MMT\","
				+ "\"properties\":{\"length\":\"12 in\",\"outerDiameter\":\"42 mm\",\"thickness\":\"1.5 mm\",\"axialMethod\":\"bottom\"}}");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"Airframe\",\"type\":\"TrapezoidFinSet\",\"name\":\"Fins\","
				+ "\"properties\":{\"finCount\":4,\"rootChord\":\"8 in\",\"tipChord\":\"3 in\",\"height\":\"4 in\",\"sweepAngle\":\"40 deg\",\"axialMethod\":\"bottom\"}}");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"Airframe\",\"type\":\"Parachute\",\"name\":\"Main\","
				+ "\"properties\":{\"diameter\":\"36 in\",\"cd\":0.8}}");
		String motor = call("set_motor", "{\"designId\":\"" + id + "\",\"mount\":\"MMT\",\"motor\":\"J350W\"}");
		assertTrue(motor.contains("J350"), motor);
		String sim = call("run_simulation", "{\"designId\":\"" + id + "\"}");
		assertTrue(sim.contains("apogee"), sim);
		String after = call("get_design", "{\"designId\":\"" + id + "\"}");
		assertTrue(after.contains("MCP - "), "new simulation is stored with the design");
		assertTrue(call("run_simulation", "{\"designId\":\"" + id + "\"}").contains("\"launchRodAngleFromVertical\":\"6 deg\""));
	}

	@Test
	@Order(8)
	void optimizeAndMonteCarlo() {
		String open = call("open_design", "{\"example\":\"Dual parachute\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();
		JsonObject design = JsonParser.parseString(call("get_design", "{\"designId\":\"" + id + "\"}")).getAsJsonObject();
		String fin = firstIdOfType(design.getAsJsonArray("components"), "TrapezoidFinSet");
		String before = call("describe_component", "{\"designId\":\"" + id + "\",\"component\":\"" + fin + "\"}");

		// Static stability target: fast, no simulation.
		JsonObject stab = JsonParser.parseString(call("optimize", "{\"designId\":\"" + id + "\",\"objective\":\"target_stability\","
				+ "\"targetStability\":2.0,\"variables\":[{\"component\":\"" + fin + "\",\"property\":\"height\",\"min\":\"20 mm\",\"max\":\"120 mm\"}],"
				+ "\"maxEvaluations\":24}")).getAsJsonObject();
		double margin = Double.parseDouble(stab.getAsJsonObject("best").get("launchMargin").getAsString().replace(" cal", ""));
		assertEquals(2.0, margin, 0.05, stab.toString());
		assertEquals(before, call("describe_component", "{\"designId\":\"" + id + "\",\"component\":\"" + fin + "\"}"),
				"optimize without apply must not change the design");

		// Simulated: max apogee with a stability floor.
		String maxAp = call("optimize", "{\"designId\":\"" + id + "\",\"objective\":\"max_apogee\",\"minStability\":1.5,\"minRailExit\":0,"
				+ "\"variables\":[{\"component\":\"" + fin + "\",\"property\":\"height\",\"min\":\"20 mm\",\"max\":\"120 mm\"}],"
				+ "\"maxEvaluations\":16,\"apply\":true}");
		JsonObject mo = JsonParser.parseString(maxAp).getAsJsonObject();
		assertTrue(mo.get("feasible").getAsBoolean(), maxAp);
		double minStab = Double.parseDouble(mo.getAsJsonObject("best").get("minAscentStability").getAsString().replace(" cal", ""));
		assertTrue(minStab >= 1.5, maxAp);
		assertTrue(mo.has("applied"));

		String mc1 = call("monte_carlo", "{\"designId\":\"" + id + "\",\"runs\":24,\"seed\":7}");
		String mc2 = call("monte_carlo", "{\"designId\":\"" + id + "\",\"runs\":24,\"seed\":7}");
		assertTrue(mc1.contains("ellipse2Sigma") && mc1.contains("maxDesignLoad"), mc1);
		// Feature suggestions come with the first call only; the numbers must match.
		String noise = "\"elapsed\":\"[^\"]*\"|,\"youCouldAlsoAsk\":\\[[^\\]]*\\]";
		assertEquals(mc1.replaceAll(noise, ""), mc2.replaceAll(noise, ""),
				"same seed must give the same result even though runs are parallel");
	}

	@Test
	@Order(9)
	void reportsExportAndBayVolume(@TempDir Path tmp) throws Exception {
		String dir = tmp.resolve("review").toString().replace("\\", "/");
		String rep = call("generate_report", "{\"designId\":\"d1\",\"outputDir\":\"" + dir + "\",\"pinType\":\"4-40 nylon\"}");
		assertTrue(rep.contains("report.md"), rep);
		String md = Files.readString(tmp.resolve("review/report.md"));
		assertTrue(md.contains("## 1. Requirement checks") && md.contains("stability-ascent.svg") && md.contains("## 5. Recovery"), md);
		assertTrue(md.contains("Wind sensitivity") && md.contains("Fin flutter") && md.contains("Aerodynamics vs Mach"), md);
		assertTrue(Files.readString(tmp.resolve("review/rocket.svg")).contains("<polygon"), "rocket drawing in the report");
		String svg = Files.readString(tmp.resolve("review/stability-ascent.svg"));
		assertTrue(svg.startsWith("<svg") && svg.contains("<path class=\"series\" d=\"M"), svg);
		assertTrue(Files.readAllLines(tmp.resolve("review/flight-data.csv")).size() > 50);
		String csv = tmp.resolve("booster.csv").toString().replace("\\", "/");
		call("export_flight_data", "{\"designId\":\"d1\",\"path\":\"" + csv + "\",\"branch\":\"Booster\",\"variables\":[\"altitude\"]}");
		assertTrue(Files.readString(tmp.resolve("booster.csv")).startsWith("\"Time"));
		String fit = call("recovery_bay_fit", "{\"designId\":\"d1\",\"bayComponent\":\"Nose Cone\",\"items\":[{\"name\":\"chute\",\"packedVolume\":\"20 in3\"}]}");
		assertTrue(fit.contains("availableVolume") && fit.contains("Nose Cone"), fit);
	}

	@Test
	@Order(10)
	void structuresAndVehicleDispersion() {
		String open = call("open_design", "{\"example\":\"Dual parachute\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();

		JsonObject fl = JsonParser.parseString(call("fin_flutter", "{\"designId\":\"" + id + "\"}")).getAsJsonObject();
		JsonObject set = fl.getAsJsonArray("finSets").get(0).getAsJsonObject();
		assertTrue(set.has("minMargin") && set.has("worstPoint") && set.get("shearModulus").getAsString().contains("GPa"), set.toString());
		// A thin, soft what-if fin must fail and come with a fix
		JsonObject thin = JsonParser.parseString(call("fin_flutter", "{\"designId\":\"" + id + "\",\"thickness\":\"0.3 mm\","
				+ "\"shearModulus\":\"0.1 GPa\"}")).getAsJsonObject().getAsJsonArray("finSets").get(0).getAsJsonObject();
		assertTrue(thin.get("status").getAsString().startsWith("FAIL") || thin.get("status").getAsString().startsWith("WARN"), thin.toString());
		assertTrue(thin.has("toReachRequiredMargin"), thin.toString());

		JsonObject bal = JsonParser.parseString(call("ballast", "{\"designId\":\"" + id + "\",\"targetStability\":4.5}")).getAsJsonObject();
		assertTrue(bal.has("ballastMass") && bal.getAsJsonObject("effect").has("apogee"), bal.toString());
		assertTrue(bal.get("note").getAsString().contains("overrides the mass"), "weighed-mass override explained: " + bal);
		String unreachable = callExpectError("ballast", "{\"designId\":\"" + id + "\",\"targetStability\":25}");
		assertTrue(unreachable.contains("not ahead of the CG"), unreachable);

		// Editing a component under a weighed-mass override warns that the change is hidden
		String added = call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"Nose cone\",\"type\":\"MassComponent\","
				+ "\"name\":\"Nose weight\",\"properties\":{\"componentMass\":\"50 g\"}}");
		assertTrue(added.contains("do not affect the simulation"), added);
		call("remove_component", "{\"designId\":\"" + id + "\",\"component\":\"Nose weight\"}");

		JsonObject mc = JsonParser.parseString(call("monte_carlo", "{\"designId\":\"" + id + "\",\"runs\":20,\"seed\":3,"
				+ "\"windSpeedSd\":0,\"launchAngleSd\":0,\"launchDirectionSd\":0,\"thrustSd\":0.05,\"massSd\":0.05,\"dragSd\":0.05}")).getAsJsonObject();
		JsonObject drivers = mc.getAsJsonObject("drivers").getAsJsonObject("apogee");
		assertTrue(drivers.has("motorThrust") && drivers.has("structureMass") && drivers.has("airframeDrag"), drivers.toString());
		assertTrue(Double.parseDouble(drivers.get("motorThrust").getAsString()) > 0, "more thrust, more apogee");
		assertTrue(Double.parseDouble(drivers.get("airframeDrag").getAsString()) < 0, "more drag, less apogee");
		String bad = callExpectError("monte_carlo", "{\"designId\":\"" + id + "\",\"runs\":4,\"massSd\":5}");
		assertTrue(bad.contains("from 0 to 0.5") && bad.contains("massSd"), bad);
	}

	@Test
	@Order(11)
	void openRocketDepth(@TempDir Path tmp) throws Exception {
		String open = call("open_design", "{\"example\":\"Two stage high power\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();

		JsonObject aero = JsonParser.parseString(call("aero_analysis", "{\"designId\":\"" + id + "\",\"maxMach\":1.1}")).getAsJsonObject();
		assertTrue(aero.getAsJsonArray("vsMach").size() >= 5 && aero.getAsJsonArray("dragBreakdown").size() >= 5, aero.toString());

		String before = call("run_simulation", "{\"designId\":\"" + id + "\",\"windSpeed\":\"5 m/s\"}");
		JsonObject prof = JsonParser.parseString(call("wind_profile", "{\"designId\":\"" + id + "\",\"mode\":\"levels\",\"levels\":["
				+ "{\"altitude\":0,\"speed\":\"5 m/s\",\"direction\":\"270 deg\"},{\"altitude\":\"300 m\",\"speed\":\"20 m/s\",\"direction\":\"270 deg\"}]}"))
				.getAsJsonObject();
		assertTrue(prof.toString().contains("multi-level"), prof.toString());
		String after = call("run_simulation", "{\"designId\":\"" + id + "\"}");
		assertTrue(after.contains("multi-level profile"), after);
		assertTrue(!before.equals(after));
		assertTrue(call("wind_profile", "{\"designId\":\"" + id + "\",\"mode\":\"show\"}").contains("multi-level"),
				"the profile survives a run");
		call("wind_profile", "{\"designId\":\"" + id + "\",\"mode\":\"average\"}");

		JsonObject parts = JsonParser.parseString(call("search_parts", "{\"type\":\"nose cone\",\"minOuterDiameter\":\"60 mm\","
				+ "\"maxOuterDiameter\":\"70 mm\",\"limit\":3}")).getAsJsonObject();
		String preset = parts.getAsJsonArray("parts").get(0).getAsJsonObject().get("preset").getAsString();
		String applied = call("apply_preset", "{\"designId\":\"" + id + "\",\"component\":\"Nose cone\",\"preset\":\"" + preset + "\"}");
		assertTrue(applied.contains(preset) && applied.contains("stability"), applied);
		String wrong = callExpectError("apply_preset", "{\"designId\":\"" + id + "\",\"component\":\"Nose cone\",\"preset\":\"nope 0000\"}");
		assertTrue(wrong.contains("search_parts"), wrong);

		String svg = tmp.resolve("r.svg").toString().replace("\\", "/");
		call("draw_rocket", "{\"designId\":\"" + id + "\",\"path\":\"" + svg + "\"}");
		assertTrue(Files.readString(tmp.resolve("r.svg")).contains("CP "));

		String png = tmp.resolve("x.png").toString().replace("\\", "/");
		JsonObject view = JsonParser.parseString(call("render_3d", "{\"designId\":\"" + id + "\",\"path\":\"" + png
				+ "\",\"width\":900}")).getAsJsonObject();
		assertTrue(Files.size(tmp.resolve("x.png")) > 10_000 && view.getAsJsonArray("parts").size() > 3, view.toString());
		String gif = tmp.resolve("f.gif").toString().replace("\\", "/");
		JsonObject anim = JsonParser.parseString(call("animate_flight", "{\"designId\":\"" + id + "\",\"path\":\"" + gif
				+ "\",\"duration\":10,\"fps\":5,\"width\":480,\"mp4\":false}")).getAsJsonObject();
		assertTrue(Files.size(tmp.resolve("f.gif")) > 10_000 && Files.exists(tmp.resolve("f-keyframes.png")), anim.toString());
		assertTrue(anim.get("timeline").toString().contains("Apogee"), anim.toString());
	}

	@Test
	@Order(12)
	void aeroTablesAndFlightLogs(@TempDir Path tmp) throws Exception {
		String open = call("open_design", "{\"example\":\"Dual parachute\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();
		String csv = "Mach,Alpha,CD Power-Off,CD Power-On,CP\\n0.01,0,0.60,0.55,44\\n0.5,0,0.62,0.57,44.5\\n1.0,0,0.95,0.9,46\\n2.0,0,0.7,0.65,47";
		JsonObject imp = JsonParser.parseString(call("import_aero_table", "{\"designId\":\"" + id + "\",\"csv\":\"" + csv
				+ "\",\"source\":\"RASAero test\"}")).getAsJsonObject();
		assertTrue(imp.getAsJsonObject("effect").has("apogeeImportedDrag") && imp.getAsJsonArray("vsOpenRocket").size() >= 3, imp.toString());
		String check = call("check_requirements", "{\"designId\":\"" + id + "\",\"includeWindCase\":false}");
		assertTrue(check.contains("Ascent stability with imported CP (RASAero test)"), check);
		assertTrue(call("import_aero_table", "{\"designId\":\"" + id + "\",\"mode\":\"show\"}").contains("with CP"));
		call("import_aero_table", "{\"designId\":\"" + id + "\",\"mode\":\"clear\"}");
		assertTrue(!call("check_requirements", "{\"designId\":\"" + id + "\",\"includeWindCase\":false}").contains("imported CP"));

		// Our own full-resolution export read back as an altimeter log: the model matches itself.
		String log = tmp.resolve("flight.csv").toString().replace("\\", "/");
		call("export_flight_data", "{\"designId\":\"" + id + "\",\"path\":\"" + log + "\",\"variables\":[\"altitude\"]}");
		String plot = tmp.resolve("overlay.svg").toString().replace("\\", "/");
		JsonObject cmp = JsonParser.parseString(call("compare_flight", "{\"designId\":\"" + id + "\",\"path\":\"" + log
				+ "\",\"plotPath\":\"" + plot + "\"}")).getAsJsonObject();
		String fit = cmp.getAsJsonObject("calibration").get("dragFactor").getAsString();
		assertEquals(1.0, Double.parseDouble(fit.split(" ")[0]), 0.03, cmp.toString());
		assertTrue(Files.exists(tmp.resolve("overlay.svg")));
	}

	@Test
	@Order(13)
	void designStudies() {
		String open = call("open_design", "{\"example\":\"Two stage high power\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();
		JsonObject shapes = JsonParser.parseString(call("compare_shapes", "{\"designId\":\"" + id + "\"}")).getAsJsonObject();
		assertTrue(shapes.getAsJsonArray("options").size() >= 11 && shapes.has("guidance"), shapes.toString());
		assertTrue(shapes.get("bestMeetingStability").getAsString().startsWith("none"), "two-stage example misses the floor");
		JsonObject sec = JsonParser.parseString(call("recovery_sections", "{\"designId\":\"" + id + "\"}")).getAsJsonObject();
		assertEquals(5, sec.getAsJsonArray("sections").size(), sec.toString());
		JsonObject named = JsonParser.parseString(call("recovery_sections", "{\"designId\":\"" + id
				+ "\",\"joints\":[\"Sustainer Forward Airframe\"]}")).getAsJsonObject();
		assertEquals(3, named.getAsJsonArray("sections").size(), "one named sustainer joint + the booster");
		JsonObject loads = JsonParser.parseString(call("structural_loads", "{\"designId\":\"" + id + "\",\"allowableStress\":\"100 MPa\"}"))
				.getAsJsonObject();
		assertTrue(loads.getAsJsonArray("joints").size() >= 5 && loads.has("highestWallStress"), loads.toString());
	}

	@Test
	@Order(14)
	void launchDay(@TempDir Path tmp) throws Exception {
		String open = call("open_design", "{\"example\":\"Dual parachute\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();
		String ask = callExpectError("weather_forecast", "{\"designId\":\"" + id + "\"}");
		assertTrue(ask.contains("GPS coordinates"), "asks for the site's coordinates: " + ask);
		String sample;
		try (var in = McpIntegrationTest.class.getResourceAsStream("/open-meteo-sample.json")) {
			sample = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		JsonObject args = new JsonObject();
		args.addProperty("designId", id);
		args.addProperty("time", "2027-08-21T09:00");
		args.addProperty("forecastJson", sample);
		JsonObject wx = JsonParser.parseString(call("weather_forecast", args.toString())).getAsJsonObject();
		assertTrue(wx.has("windsAloft") && wx.has("flightInForecast") && wx.getAsJsonObject("ground").has("vsRuleLimit"), wx.toString());
		assertTrue(call("wind_profile", "{\"designId\":\"" + id + "\",\"mode\":\"show\"}").contains("multi-level"));

		String card = tmp.resolve("card.md").toString().replace("\\", "/");
		JsonObject fc = JsonParser.parseString(call("flight_card", "{\"designId\":\"" + id + "\",\"path\":\"" + card + "\"}")).getAsJsonObject();
		String md = Files.readString(tmp.resolve("card.md"));
		assertTrue(md.contains("profile (AGL)") && md.contains("48.47"), "forecast wind and site on the card");
		assertTrue(fc.has("apogee"));

		JsonObject heat = JsonParser.parseString(call("aero_heating", "{\"designId\":\"" + id + "\"}")).getAsJsonObject();
		assertTrue(heat.getAsJsonArray("surfaces").size() >= 3, heat.toString());
		JsonObject roll = JsonParser.parseString(call("roll_analysis", "{\"designId\":\"" + id + "\",\"cantAngles\":[0,\"0.5 deg\",\"1 deg\"]}"))
				.getAsJsonObject();
		assertEquals(3, roll.getAsJsonArray("cases").size());
		assertTrue(roll.has("alignmentTolerance"));
	}

	@Test
	@Order(15)
	void interactiveView(@TempDir Path tmp) throws Exception {
		String open = call("open_design", "{\"example\":\"Dual parachute\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();
		String file = tmp.resolve("view.html").toString().replace("\\", "/");
		JsonObject r = rpc("tools/call", "{\"name\":\"show_design\",\"arguments\":{\"designId\":\"" + id + "\",\"path\":\"" + file
				+ "\",\"tab\":\"flight\"}}").getAsJsonObject("result");
		assertFalse(r.get("isError").getAsBoolean(), r.toString());
		JsonObject view = r.getAsJsonObject("structuredContent");
		assertTrue(view.has("viewId") && view.get("designId").getAsString().equals(id), view.toString());
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertTrue(text.contains("Apogee") && text.contains("view.html"), "the model reads the headline numbers: " + text);
		assertTrue(text.length() < 2000, "the bulky data stays out of the model's context: " + text.length());
		assertTrue(Files.readString(tmp.resolve("view.html")).contains("id=\"or-data\""), "standalone copy with the data");

		// The view loads its snapshot, then the design as it is now after an edit.
		JsonObject snap = rpc("tools/call", "{\"name\":\"workbench_data\",\"arguments\":{\"viewId\":\"" + view.get("viewId").getAsString()
				+ "\"}}").getAsJsonObject("result");
		assertFalse(snap.get("isError").getAsBoolean(), snap.toString());
		JsonObject data = snap.getAsJsonObject("structuredContent");
		assertEquals("flight", data.get("tab").getAsString());
		assertTrue(data.getAsJsonObject("rocket").getAsJsonArray("parts").size() >= 5);
		assertTrue(data.getAsJsonObject("checks").has("summary"), "rule checks are in the view");
		double apogee = apogee(data);
		// (this example's sustainer overrides its parts' masses: change the override itself)
		call("edit_components", "{\"designId\":\"" + id + "\",\"changes\":[{\"component\":\"Sustainer\",\"properties\":"
				+ "{\"overrideMass\":\"2.5 kg\"}}]}");
		JsonObject fresh = rpc("tools/call", "{\"name\":\"workbench_data\",\"arguments\":{\"viewId\":\""
				+ view.get("viewId").getAsString() + "\",\"refresh\":true}}").getAsJsonObject("result").getAsJsonObject("structuredContent");
		assertTrue(apogee(fresh) < apogee - 1, "a heavier rocket flies lower: " + apogee + " -> " + apogee(fresh));
		call("undo", "{\"designId\":\"" + id + "\"}");

		call("close_design", "{\"designId\":\"" + id + "\"}");
		String gone = callExpectError("workbench_data", "{\"designId\":\"" + id + "\",\"refresh\":true}");
		assertTrue(gone.contains("no longer open") && gone.contains("show the design again"), gone);
	}

	static double apogee(JsonObject data) {
		for (var m : data.getAsJsonArray("metrics")) {
			if (m.getAsJsonObject().get("key").getAsString().equals("apogee")) {
				return m.getAsJsonObject().get("value").getAsDouble();
			}
		}
		return Double.NaN;
	}
}
