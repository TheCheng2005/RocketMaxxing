package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/** The desktop app's own tools, and its local server. */
class AppToolsTest {
	final Context ctx = new Context(Standards.defaults());
	final McpServer server = Main.build(ctx, s -> AppTools.register(s, ctx), false);
	int id;

	JsonObject call(String tool, String args, boolean error) {
		String line = "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":" + args + "}}";
		JsonObject r = server.handleLine(line).getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertEquals(error, r.get("isError").getAsBoolean(), tool + " " + args + ": " + text);
		if (error) {
			JsonObject o = new JsonObject();
			o.addProperty("text", text);
			return o;
		}
		return JsonParser.parseString(text).getAsJsonObject();
	}

	JsonObject call(String tool, String args) {
		return call(tool, args, false);
	}

	static void ids(JsonObject node, List<String> out) {
		out.add(node.get("id").getAsString());
		for (JsonElement c : node.getAsJsonArray("children")) {
			ids(c.getAsJsonObject(), out);
		}
	}

	@Test
	void everyEditorOpensOnEveryExample() {
		for (String ex : Designs.EXAMPLES) {
			String d = call("open_design", "{\"example\":\"" + ex + "\"}").get("designId").getAsString();
			JsonObject design = call("app_design", "{\"designId\":\"" + d + "\"}");
			List<String> ids = new ArrayList<>();
			ids(design.getAsJsonObject("tree"), ids);
			assertTrue(ids.size() > 3, ex);
			for (String c : ids) {
				JsonObject e = call("app_component", "{\"designId\":\"" + d + "\",\"component\":\"" + c + "\"}");
				assertTrue(e.getAsJsonArray("sections").size() > 0, ex + " " + c);
			}
			JsonObject model = call("app_model", "{\"designId\":\"" + d + "\"}");
			assertTrue(model.getAsJsonArray("parts").size() > 0, ex);
		}
	}

	@Test
	void editsGoThroughTheEngineAndUndo() {
		String d = call("open_design", "{\"path\":\"docs/examples/maple-10k.ork\"}").get("designId").getAsString();
		JsonObject design = call("app_design", "{\"designId\":\"" + d + "\"}");
		String fins = find(design.getAsJsonObject("tree"), "TrapezoidFinSet");
		double before = field(d, fins, "thickness");
		call("app_edit", "{\"designId\":\"" + d + "\",\"component\":\"" + fins + "\",\"key\":\"thickness\",\"value\":\"4 mm\"}");
		assertEquals(0.004, field(d, fins, "thickness"), 1e-9);
		JsonObject after = call("app_design", "{\"designId\":\"" + d + "\"}");
		assertEquals(1, after.get("canUndo").getAsInt());
		assertFalse(after.get("saved").getAsBoolean());
		call("undo", "{\"designId\":\"" + d + "\"}");
		assertEquals(before, field(d, fins, "thickness"), 1e-12);
		// An override value is greyed out until its override is switched on, as in OpenRocket.
		assertFalse(fieldInfo(d, fins, "overrideMass").has("enabled") && fieldInfo(d, fins, "massOverridden").get("value").getAsBoolean(),
				"overrideMass follows massOverridden");
		String nose = find(design.getAsJsonObject("tree"), "NoseCone");
		boolean overridden = fieldInfo(d, nose, "massOverridden").get("value").getAsBoolean();
		assertEquals(overridden, !fieldInfo(d, nose, "overrideMass").has("enabled"));
		// Choosing which configuration to look at is not an edit.
		int undo = call("app_design", "{\"designId\":\"" + d + "\"}").get("canUndo").getAsInt();
		call("app_select_configuration", "{\"designId\":\"" + d + "\",\"configuration\":\"0\"}");
		assertEquals(undo, call("app_design", "{\"designId\":\"" + d + "\"}").get("canUndo").getAsInt());
		// Only the editor's fields can be set, and bad values are refused with a reason.
		call("app_edit", "{\"designId\":\"" + d + "\",\"component\":\"" + fins + "\",\"key\":\"ID\",\"value\":\"x\"}", true);
		call("app_edit", "{\"designId\":\"" + d + "\",\"component\":\"" + fins + "\",\"key\":\"thickness\",\"value\":-1}", true);
		// Move, duplicate.
		String parent = parentOf(design.getAsJsonObject("tree"), fins, null);
		call("app_duplicate", "{\"designId\":\"" + d + "\",\"component\":\"" + fins + "\"}");
		JsonObject dup = call("app_design", "{\"designId\":\"" + d + "\"}");
		assertEquals(count(dup.getAsJsonObject("tree"), "TrapezoidFinSet"), 2);
		call("app_move", "{\"designId\":\"" + d + "\",\"component\":\"" + fins + "\",\"by\":-1}");
		call("undo", "{\"designId\":\"" + d + "\",\"steps\":2}");
		assertEquals(1, count(call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("tree"), "TrapezoidFinSet"));
		assertTrue(parent != null);
	}

	/** Dragging a part in the parts list: into another part, to a place among its parts; pasting a copy there. */
	@Test
	void partsMoveIntoOtherPartsAndPaste() {
		String d = call("open_design", "{\"path\":\"docs/examples/maple-10k.ork\"}").get("designId").getAsString();
		JsonObject tree = call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("tree");
		String chute = find(tree, "Parachute");
		String from = parentOf(tree, chute, null);
		String fins = find(tree, "TrapezoidFinSet");
		String lower = parentOf(tree, fins, null);
		assertNotEquals(from, lower);
		call("app_move", "{\"designId\":\"" + d + "\",\"component\":\"" + chute + "\",\"parent\":\"" + lower + "\",\"index\":0}");
		JsonObject moved = call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("tree");
		assertEquals(lower, parentOf(moved, chute, null));
		assertEquals(chute, child(moved, lower, 0));
		// Same parent: the index counts places before the move, so "after the second" lands second.
		String second = child(moved, lower, 2);
		call("app_move", "{\"designId\":\"" + d + "\",\"component\":\"" + chute + "\",\"parent\":\"" + lower + "\",\"index\":3}");
		assertEquals(chute, child(call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("tree"), lower, 2));
		assertEquals(second, child(call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("tree"), lower, 1));
		// Not into itself, not into a part that cannot hold it.
		assertTrue(call("app_move", "{\"designId\":\"" + d + "\",\"component\":\"" + lower + "\",\"parent\":\"" + fins + "\"}", true)
				.get("text").getAsString().contains("inside itself"));
		String nose = find(tree, "NoseCone");
		assertTrue(call("app_move", "{\"designId\":\"" + d + "\",\"component\":\"" + fins + "\",\"parent\":\"" + nose + "\"}", true)
				.get("text").getAsString().contains("cannot hold"));
		// Paste: a copy into another part, at its start.
		JsonObject copy = call("app_duplicate", "{\"designId\":\"" + d + "\",\"component\":\"" + chute + "\",\"parent\":\"" + from + "\",\"index\":0}");
		JsonObject pasted = call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("tree");
		assertEquals(copy.get("id").getAsString(), child(pasted, from, 0));
		assertEquals(count(tree, "Parachute") + 1, count(pasted, "Parachute"));
		// One undo per drop.
		call("undo", "{\"designId\":\"" + d + "\",\"steps\":3}");
		assertEquals(from, parentOf(call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("tree"), chute, null));
	}

	/** A fast flight: OpenRocket's numbers alone, then beside an imported RASAero table's (here: twice the drag). */
	@Test
	void rasaeroBesideOpenRocket(@TempDir Path dir) throws Exception {
		String d = call("open_design", "{\"path\":\"docs/examples/maple-10k.ork\"}").get("designId").getAsString();
		JsonObject alone = call("app_aero_compare", "{\"designId\":\"" + d + "\"}");
		assertFalse(alone.get("hasTable").getAsBoolean());
		assertTrue(alone.get("maxMach").getAsDouble() > 1, "Maple 10K is supersonic: " + alone);
		double apogee = alone.getAsJsonObject("openRocket").get("apogee").getAsDouble();
		assertTrue(alone.getAsJsonArray("byMach").size() > 5);
		JsonObject before = call("app_design", "{\"designId\":\"" + d + "\"}");
		assertTrue(!before.has("aeroTable") || before.get("aeroTable").isJsonNull(), "no table yet");

		StringBuilder csv = new StringBuilder("Mach,Alpha,CD,CD Power-Off,CD Power-On,CA Power-Off,CA Power-On,CN,CP\n");
		for (double m = 0.01; m <= 2.0; m += 0.05) {
			// About twice OpenRocket's CD for this rocket (1.1 subsonic, 1.38 near Mach 1.1).
			double cd = 2 * (m < 0.8 ? 1.1 : m < 1.2 ? 1.1 + 0.3 * (m - 0.8) / 0.4 : 1.4 - 0.1 * (m - 1.2));
			csv.append(String.format(java.util.Locale.ROOT, "%.2f,0,%.3f,%.3f,%.3f,%.3f,%.3f,0,%.1f%n", m, cd, cd, cd * 0.9, cd, cd * 0.9, 78.0));
		}
		Path f = dir.resolve("rasaero.csv");
		Files.writeString(f, csv.toString());
		call("import_aero_table", "{\"designId\":\"" + d + "\",\"path\":\"" + f.toString().replace("\\", "\\\\") + "\"}");
		assertEquals("rasaero.csv", call("app_design", "{\"designId\":\"" + d + "\"}").get("aeroTable").getAsString());
		JsonObject both = call("app_aero_compare", "{\"designId\":\"" + d + "\"}");
		assertTrue(both.get("hasTable").getAsBoolean() && both.get("useDrag").getAsBoolean());
		double or = both.getAsJsonObject("openRocket").get("apogee").getAsDouble();
		double ras = both.getAsJsonObject("rasaero").get("apogee").getAsDouble();
		assertEquals(apogee, or, apogee * 0.01, "the OpenRocket column flies on OpenRocket's own drag");
		assertTrue(ras < or * 0.9, "twice the drag flies lower: " + ras + " vs " + or);
		assertTrue(both.getAsJsonObject("rasaero").has("minStability"), "stability with the table's CP");
		JsonObject row = both.getAsJsonArray("byMach").get(1).getAsJsonObject();
		assertTrue(row.has("cdOpenRocket") && row.has("cdRasaeroPowerOff") && row.has("cpRasaero"), row.toString());
		assertEquals(78 * 0.0254, row.get("cpRasaero").getAsDouble(), 1e-6);
		call("import_aero_table", "{\"designId\":\"" + d + "\",\"mode\":\"clear\"}");
	}

	static String child(JsonObject tree, String parent, int i) {
		JsonObject p = node(tree, parent);
		return p.getAsJsonArray("children").get(i).getAsJsonObject().get("id").getAsString();
	}

	static JsonObject node(JsonObject n, String id) {
		if (n.get("id").getAsString().equals(id)) {
			return n;
		}
		for (JsonElement c : n.getAsJsonArray("children")) {
			JsonObject f = node(c.getAsJsonObject(), id);
			if (f != null) {
				return f;
			}
		}
		return null;
	}

	@Test
	void motorsSimulationsAndFlightData() {
		String d = call("open_design", "{\"path\":\"docs/examples/maple-10k.ork\"}").get("designId").getAsString();
		JsonObject quick = call("app_quick_flight", "{\"designId\":\"" + d + "\"}");
		assertTrue(quick.get("apogee").getAsDouble() > 1000, quick.toString());
		JsonObject cfg = call("app_configs", "{\"designId\":\"" + d + "\"}");
		JsonObject mount = cfg.getAsJsonArray("mounts").get(0).getAsJsonObject();
		String sel = cfg.get("selected").getAsString();
		assertTrue(mount.getAsJsonObject("motors").getAsJsonObject(sel).has("designation"), cfg.toString());
		assertTrue(mount.getAsJsonObject("motors").getAsJsonObject(sel).getAsJsonArray("delays").size() > 0, "the motor's own delays");
		JsonObject st = call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("stability");
		assertTrue(st.has("floor") && st.get("floor").getAsDouble() >= 1, "the rule set's stability minimum: " + st);
		assertTrue(cfg.getAsJsonArray("recovery").size() >= 2);
		JsonObject sims = call("app_sims", "{\"designId\":\"" + d + "\"}");
		int n = sims.getAsJsonArray("simulations").size();
		int idx = call("app_sim_new", "{\"designId\":\"" + d + "\"}").get("index").getAsInt();
		assertEquals(n, idx);
		call("app_sim_edit", "{\"designId\":\"" + d + "\",\"simulation\":" + idx + ",\"key\":\"windSpeed\",\"value\":\"10 mph\"}");
		call("app_sim_edit", "{\"designId\":\"" + d + "\",\"simulation\":" + idx + ",\"key\":\"rodLength\",\"value\":-2}", true);
		JsonObject run = call("app_sim_run", "{\"designId\":\"" + d + "\",\"simulation\":" + idx + "}");
		JsonObject res = run.getAsJsonArray("runs").get(0).getAsJsonObject().getAsJsonObject("results");
		assertTrue(res.get("apogee").getAsDouble() > 1000, run.toString());
		JsonObject s = call("app_series", "{\"designId\":\"" + d + "\",\"simulation\":" + idx + "}");
		assertTrue(s.getAsJsonArray("variables").size() > 20);
		assertTrue(s.getAsJsonArray("events").size() > 3);
		JsonObject listed = call("app_sims", "{\"designId\":\"" + d + "\"}").getAsJsonArray("simulations").get(idx).getAsJsonObject();
		assertEquals(4.4704, listed.getAsJsonObject("options").get("windSpeed").getAsDouble(), 1e-6);
		call("app_sim_delete", "{\"designId\":\"" + d + "\",\"simulation\":" + idx + "}");
		assertEquals(n, call("app_sims", "{\"designId\":\"" + d + "\"}").getAsJsonArray("simulations").size());
		JsonObject session = call("app_session", "{}");
		assertTrue(session.getAsJsonObject("materials").getAsJsonArray("BULK").size() > 10);
		assertTrue(session.getAsJsonObject("dims").has("LENGTH"));
	}

	@Test
	void listInputsDescribeTheirItems() {
		// The app builds a table for each list of items from the item's fields; Claude reads them too.
		JsonObject list = server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").getAsJsonObject("result");
		for (JsonElement t : list.getAsJsonArray("tools")) {
			String name = t.getAsJsonObject().get("name").getAsString();
			JsonObject props = t.getAsJsonObject().getAsJsonObject("inputSchema").getAsJsonObject("properties");
			for (String key : props == null ? List.<String>of() : props.keySet()) {
				if (props.getAsJsonObject(key).has("items") && props.getAsJsonObject(key).get("items").isJsonObject()) {
					JsonObject items = props.getAsJsonObject(key).getAsJsonObject("items");
					if (items.has("type") && items.get("type").toString().equals("\"object\"")) {
						assertTrue(items.has("properties"), name + "." + key + " describes its item fields");
					}
				}
			}
		}
	}

	@Test
	void appToolsAreNotInTheMcpServer() {
		McpServer plain = Main.build(new Context(Standards.defaults()));
		String list = plain.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").toString();
		assertFalse(list.contains("app_design"));
	}

	@Test
	void localServerNeedsTheToken(@TempDir Path ui) throws Exception {
		Files.writeString(ui.resolve("index.html"), "<!doctype html><title>t</title>");
		AppServer app = new AppServer(server, ui, List.of());
		var bound = app.start(0);
		try {
			HttpClient http = HttpClient.newHttpClient();
			String base = "http://127.0.0.1:" + bound.getPort();
			String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"app_session\",\"arguments\":{}}}";
			HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(200, page.statusCode());
			assertTrue(page.body().contains("<title>"));
			HttpResponse<String> noToken = http.send(HttpRequest.newBuilder(URI.create(base + "/rpc"))
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(401, noToken.statusCode());
			HttpResponse<String> foreign = http.send(HttpRequest.newBuilder(URI.create(base + "/rpc"))
					.header("Authorization", "Bearer " + app.token()).header("Origin", "https://evil.example")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(403, foreign.statusCode());
			HttpResponse<String> ok = http.send(HttpRequest.newBuilder(URI.create(base + "/rpc"))
					.header("Authorization", "Bearer " + app.token()).header("Origin", base)
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(200, ok.statusCode());
			assertTrue(ok.body().contains("examples"));
			HttpResponse<String> escape = http.send(HttpRequest.newBuilder(URI.create(base + "/../build.gradle")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertNotEquals(200, escape.statusCode());
		} finally {
			app.stop();
		}
	}

	@Test
	void servesWhatTheEngineWroteAndNothingElse(@TempDir Path ws, @TempDir Path outside) throws Exception {
		Files.createDirectories(ws.resolve("exports"));
		byte[] video = new byte[1000];
		for (int i = 0; i < video.length; i++) {
			video[i] = (byte) i;
		}
		Files.write(ws.resolve("exports/flight.mp4"), video);
		Files.writeString(ws.resolve("notes.html"), "<script>alert(1)</script>");
		Files.writeString(outside.resolve("secret.png"), "x");
		AppServer app = new AppServer(server, null, List.of());
		app.files(ws);
		var bound = app.start(0);
		try {
			HttpClient http = HttpClient.newHttpClient();
			String base = "http://127.0.0.1:" + bound.getPort() + "/file?token=" + app.token() + "&path=";
			java.util.function.Function<String, HttpResponse<byte[]>> get = q -> {
				try {
					return http.send(HttpRequest.newBuilder(URI.create(q)).build(), HttpResponse.BodyHandlers.ofByteArray());
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};
			HttpResponse<byte[]> ok = get.apply(base + "exports/flight.mp4");
			assertEquals(200, ok.statusCode());
			assertEquals("video/mp4", ok.headers().firstValue("Content-Type").orElse(""));
			assertTrue(ok.headers().firstValue("Content-Security-Policy").orElse("").contains("sandbox"));
			assertEquals(1000, ok.body().length);
			HttpResponse<byte[]> part = http.send(HttpRequest.newBuilder(URI.create(base + "exports/flight.mp4")).header("Range", "bytes=10-19").build(),
					HttpResponse.BodyHandlers.ofByteArray());
			assertEquals(206, part.statusCode());
			assertEquals(10, part.body().length);
			assertEquals(10, part.body()[0]);
			assertEquals(403, get.apply(base.replace(app.token(), "wrong") + "exports/flight.mp4").statusCode());
			assertEquals(404, get.apply(base + "notes.html").statusCode(), "only pictures and videos");
			assertEquals(404, get.apply(base + java.net.URLEncoder.encode(outside.resolve("secret.png").toString(), "UTF-8")).statusCode(),
					"only inside the designs folder");
			assertEquals(404, get.apply(base + "../" + outside.getFileName() + "/secret.png").statusCode());
		} finally {
			app.stop();
		}
	}

	JsonObject fieldInfo(String d, String comp, String key) {
		JsonObject e = call("app_component", "{\"designId\":\"" + d + "\",\"component\":\"" + comp + "\"}");
		for (JsonElement s : e.getAsJsonArray("sections")) {
			for (JsonElement f : s.getAsJsonObject().getAsJsonArray("fields")) {
				if (f.getAsJsonObject().get("key").getAsString().equals(key)) {
					return f.getAsJsonObject();
				}
			}
		}
		throw new AssertionError("no field " + key);
	}

	double field(String d, String comp, String key) {
		JsonObject e = call("app_component", "{\"designId\":\"" + d + "\",\"component\":\"" + comp + "\"}");
		for (JsonElement s : e.getAsJsonArray("sections")) {
			for (JsonElement f : s.getAsJsonObject().getAsJsonArray("fields")) {
				if (f.getAsJsonObject().get("key").getAsString().equals(key)) {
					return f.getAsJsonObject().get("value").getAsDouble();
				}
			}
		}
		throw new AssertionError("no field " + key);
	}

	static String find(JsonObject node, String type) {
		if (node.get("type").getAsString().equals(type)) {
			return node.get("id").getAsString();
		}
		for (JsonElement c : node.getAsJsonArray("children")) {
			String f = find(c.getAsJsonObject(), type);
			if (f != null) {
				return f;
			}
		}
		return null;
	}

	static int count(JsonObject node, String type) {
		int n = node.get("type").getAsString().equals(type) ? 1 : 0;
		for (JsonElement c : node.getAsJsonArray("children")) {
			n += count(c.getAsJsonObject(), type);
		}
		return n;
	}

	static String parentOf(JsonObject node, String id, String parent) {
		if (node.get("id").getAsString().equals(id)) {
			return parent;
		}
		for (JsonElement c : node.getAsJsonArray("children")) {
			String p = parentOf(c.getAsJsonObject(), id, node.get("id").getAsString());
			if (p != null) {
				return p;
			}
		}
		return null;
	}

	@Test
	void targetsGoToTheTeamFileOnly(@TempDir Path ws) throws Exception {
		Context c = new Context(Standards.defaults(), ws, false);
		McpServer app = Main.build(c, s -> AppTools.register(s, c), false);
		Files.writeString(ws.resolve(Standards.FILE_NAME), "{\"units\": \"metric\"}");
		String line = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"app_set_targets\",\"arguments\":"
				+ "{\"targets\": {\"apogee\": {\"min\": \"9500 ft\", \"max\": \"3.5 km\"}, \"minStability\": {\"min\": \"1.5 cal\"}}}}}";
		assertFalse(app.handleLine(line).getAsJsonObject("result").get("isError").getAsBoolean());
		line = line.replace("{\"apogee\": {\"min\": \"9500 ft\", \"max\": \"3.5 km\"}, \"minStability\": {\"min\": \"1.5 cal\"}}",
				"{\"apogee\": {\"max\": null}, \"minStability\": {\"min\": null}}");
		assertFalse(app.handleLine(line).getAsJsonObject("result").get("isError").getAsBoolean());
		JsonObject file = JsonParser.parseString(Files.readString(ws.resolve(Standards.FILE_NAME))).getAsJsonObject();
		assertEquals("{\"units\":\"metric\",\"targets\":{\"apogee\":{\"min\":\"9500 ft\"}}}", file.toString(),
				"the team's file keeps what the team set, not the defaults; cleared limits are gone");
		assertEquals(2895.6, Standards.load(ws.resolve(Standards.FILE_NAME)).q("targets.apogee.min", io.github.openrocketmcp.units.Dim.DISTANCE, 0), 1e-6);
		String bad = line.replace("{\"apogee\": {\"max\": null}, \"minStability\": {\"min\": null}}", "{\"apogee\": {\"min\": \"30 m/s\"}}");
		assertTrue(app.handleLine(bad).getAsJsonObject("result").get("isError").getAsBoolean());
	}

	@Test
	void finOutlineCatalogueAndPaint() {
		String d = call("open_design", "{\"example\": \"A simple model rocket\"}").get("designId").getAsString();
		List<String> all = new ArrayList<>();
		ids(call("app_design", "{\"designId\": \"" + d + "\"}").getAsJsonObject("tree"), all);
		String fins = null, tube = null;
		for (String c : all) {
			String type = call("app_component", "{\"designId\": \"" + d + "\", \"component\": \"" + c + "\"}").get("type").getAsString();
			if (type.equals("TrapezoidFinSet")) {
				fins = c;
			} else if (type.equals("BodyTube") && tube == null) {
				tube = c;
			}
		}
		String args = "{\"designId\": \"" + d + "\", \"component\": \"" + fins + "\"}";
		JsonObject before = call("app_fin_points", args);
		assertFalse(before.get("editable").getAsBoolean());
		String ff = call("app_convert_fins", args).get("component").getAsString();
		String ffArgs = "{\"designId\": \"" + d + "\", \"component\": \"" + ff + "\"";
		JsonObject after = call("app_fin_points", ffArgs + "}");
		assertTrue(after.get("editable").getAsBoolean());
		assertEquals(before.getAsJsonArray("points").toString(), after.getAsJsonArray("points").toString(), "same outline after converting");
		call("app_set_fin_points", ffArgs + ", \"points\": [[0.001, 0.002], [0.03, 0.05], [0.06, 0.05], [0.07, 0.003]]}");
		JsonObject set = call("app_fin_points", ffArgs + "}");
		assertEquals("[[0.0,0.0],[0.03,0.05],[0.06,0.05],[0.07,0.0]]", set.getAsJsonArray("points").toString(), "the root stays on the body");
		assertTrue(call("app_set_fin_points", ffArgs + ", \"points\": [[0, 0], [0.06, 0.05], [0.0, 0.05], [0.07, 0]]}", true)
				.get("text").getAsString().contains("crosses itself"));
		assertEquals(set.getAsJsonArray("points").toString(), call("app_fin_points", ffArgs + "}").getAsJsonArray("points").toString());

		String tubeArgs = "{\"designId\": \"" + d + "\", \"component\": \"" + tube + "\"";
		JsonObject p = call("app_presets", tubeArgs + ", \"sameDiameter\": true}");
		assertEquals("BODY_TUBE", p.get("type").getAsString());
		assertTrue(p.getAsJsonArray("presets").size() > 0, p.toString());
		double own = p.get("diameter").getAsDouble();
		for (JsonElement e : p.getAsJsonArray("presets")) {
			assertEquals(own, e.getAsJsonObject().get("outerDiameter").getAsDouble(), 0.021 * own);
		}

		call("app_appearance", tubeArgs + ", \"color\": \"#3366cc\"}");
		assertEquals("#3366cc", call("app_component", tubeArgs + "}").get("paint").getAsString());
		boolean painted = false;
		for (JsonElement e : call("app_model", "{\"designId\": \"" + d + "\"}").getAsJsonArray("parts")) {
			painted |= "#3366cc".equals(e.getAsJsonObject().get("color").getAsString());
		}
		assertTrue(painted, "the 3-D model shows the paint");
		call("app_appearance", tubeArgs + ", \"color\": \"\"}");
		assertFalse(call("app_component", tubeArgs + "}").has("paint"), "back to the default look");
	}

	@Test
	void interfacePreferencesOutliveTheLaunch(@TempDir Path ws) throws Exception {
		java.util.function.Function<McpServer, java.util.function.Function<String, JsonObject>> client = app -> args -> {
			JsonObject r = app.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"app_prefs\","
					+ "\"arguments\":" + args + "}}").getAsJsonObject("result");
			JsonObject o = new JsonObject();
			o.addProperty("isError", r.get("isError").getAsBoolean());
			String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
			if (!r.get("isError").getAsBoolean()) {
				o.add("out", JsonParser.parseString(text));
			}
			return o;
		};
		Context c = new Context(Standards.defaults(), ws, false);
		var first = client.apply(Main.build(c, s -> AppTools.register(s, c, ws.resolve("assistant.json")), false));
		assertEquals("{}", first.apply("{}").getAsJsonObject("out").getAsJsonObject("prefs").toString(), "nothing stored yet");
		first.apply("{\"set\": {\"rocketmaxxing.scheme\": \"slate\", \"rocketmaxxing.recent\": \"[\\\"a.ork\\\"]\"}}");
		first.apply("{\"set\": {\"rocketmaxxing.recent\": null}}");
		// The next launch (a new engine, a new port) reads them back.
		var next = client.apply(Main.build(c, s -> AppTools.register(s, c, ws.resolve("assistant.json")), false));
		JsonObject prefs = next.apply("{}").getAsJsonObject("out").getAsJsonObject("prefs");
		assertEquals("slate", prefs.get("rocketmaxxing.scheme").getAsString());
		assertFalse(prefs.has("rocketmaxxing.recent"), "null removed it");
		assertTrue(Files.exists(ws.resolve("ui-prefs.json")), "kept next to the assistant's settings");
		assertTrue(next.apply("{\"set\": {\"other.key\": \"x\"}}").get("isError").getAsBoolean(), "only the interface's keys");
		assertTrue(next.apply("{\"set\": {\"rocketmaxxing.big\": \"" + "x".repeat(UiPrefs.MAX_VALUE + 1) + "\"}}")
				.get("isError").getAsBoolean(), "values stay small");
		Files.writeString(ws.resolve("ui-prefs.json"), "not json");
		assertEquals("{}", next.apply("{}").getAsJsonObject("out").getAsJsonObject("prefs").toString(), "a broken file starts afresh");
	}

	@Test
	void teamStandardsKeepOnlyWhatTheTeamSet(@TempDir Path ws) throws Exception {
		Context c = new Context(Standards.defaults(), ws, false);
		McpServer app = Main.build(c, s -> AppTools.register(s, c, ws.resolve("assistant.json")), false);
		java.util.function.BiFunction<String, String, JsonObject> rpc = (tool, args) -> {
			JsonObject r = app.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
					+ "\",\"arguments\":" + args + "}}").getAsJsonObject("result");
			JsonObject o = new JsonObject();
			o.addProperty("isError", r.get("isError").getAsBoolean());
			o.addProperty("text", r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
			return o;
		};
		rpc.apply("app_set_standards", "{\"patch\": {\"recovery\": {\"harnessSafetyFactor\": 2.5, \"shearPins\": {\"2-56 nylon\": {\"strength\": \"35 lbf\"}}}, \"launchSite\": {\"railLength\": \"6 m\"}}}");
		Path file = ws.resolve(Standards.FILE_NAME);
		assertEquals("{\"recovery\":{\"harnessSafetyFactor\":2.5,\"shearPins\":{\"2-56 nylon\":{\"strength\":\"35 lbf\"}}},\"launchSite\":{\"railLength\":\"6 m\"}}",
				JsonParser.parseString(Files.readString(file)).toString(), "only the team's values, not the defaults");
		assertEquals(2.5, c.standards().q("recovery.harnessSafetyFactor", io.github.openrocketmcp.units.Dim.DIMENSIONLESS, 0));
		assertEquals(155.687, c.standards().pinStrength("2-56 nylon"), 0.01);
		assertEquals(140, c.standards().pinStrength("4-40 nylon"), 1e-9, "the default pins are still there");
		// Back to the default: the key leaves the file and the default applies again.
		rpc.apply("app_set_standards", "{\"patch\": {\"launchSite\": {\"railLength\": null}}}");
		assertFalse(Files.readString(file).contains("launchSite"));
		assertEquals(5.6388, c.standards().q("launchSite.railLength", io.github.openrocketmcp.units.Dim.LENGTH, 0), 1e-4);
		// A value the checks would misread is refused, and the file is left as it was.
		String before = Files.readString(file);
		JsonObject bad = rpc.apply("app_set_standards", "{\"patch\": {\"recovery\": \"lots\"}}");
		assertTrue(bad.get("isError").getAsBoolean(), bad.toString());
		assertEquals(before, Files.readString(file));
		JsonObject read = JsonParser.parseString(rpc.apply("app_standards", "{}").get("text").getAsString()).getAsJsonObject();
		assertTrue(read.getAsJsonObject("defaults").has("recovery"));
		assertEquals(2.5, read.getAsJsonObject("team").getAsJsonObject("recovery").get("harnessSafetyFactor").getAsDouble());
		assertTrue(read.getAsJsonArray("ruleSets").size() >= 3);
	}

	@Test
	void motorFilesAreKeptWithTheDesigns(@TempDir Path ws, @TempDir Path downloads) throws Exception {
		Context c = new Context(Standards.defaults(), ws, false);
		McpServer app = Main.build(c, s -> AppTools.register(s, c, ws.resolve("assistant.json")), false);
		Path eng = downloads.resolve("team-k500.eng");
		Files.writeString(eng, "; a test motor\nTeamTest-K500 54 400 0 0.8 1.4 TeamTest\n0.05 600\n1.0 550\n2.0 500\n2.2 0\n;\n");
		JsonObject r = app.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"app_import_motor\",\"arguments\":{\"path\":"
				+ new com.google.gson.Gson().toJson(eng.toString()) + "}}}").getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertFalse(r.get("isError").getAsBoolean(), text);
		JsonObject out = JsonParser.parseString(text).getAsJsonObject();
		assertEquals("TeamTest-K500", out.getAsJsonArray("motors").get(0).getAsJsonObject().get("designation").getAsString());
		Path kept = ws.resolve("motors/team-k500.eng");
		assertTrue(Files.exists(kept), "a copy stays in the designs folder");
		assertEquals(Files.readString(eng), Files.readString(kept));
		// A bad file is refused and nothing is kept.
		Path junk = downloads.resolve("junk.eng");
		Files.writeString(junk, "not a motor");
		JsonObject bad = app.handleLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"app_import_motor\",\"arguments\":{\"path\":"
				+ new com.google.gson.Gson().toJson(junk.toString()) + "}}}").getAsJsonObject("result");
		assertTrue(bad.get("isError").getAsBoolean());
		assertFalse(Files.exists(ws.resolve("motors/junk.eng")));
	}
}
