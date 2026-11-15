package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/**
 * A RocketMaxxing project carries a design with what the app knows about it to another team: its own motor, its
 * targets, its progress log and flight log come with it; their standards are offered, not put over the other team's.
 */
class ProjectsTest {
	static final Gson GSON = new Gson();

	record Team(Context ctx, McpServer server) {
		JsonObject call(String tool, String args, boolean error) {
			JsonObject r = server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
					+ "\",\"arguments\":" + args + "}}").getAsJsonObject("result");
			String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
			assertEquals(error, r.get("isError").getAsBoolean(), tool + ": " + text);
			return error ? JsonParser.parseString(GSON.toJson(java.util.Map.of("error", text))).getAsJsonObject() : JsonParser.parseString(text).getAsJsonObject();
		}

		JsonObject call(String tool, String args) {
			return call(tool, args, false);
		}
	}

	static Team team(Path ws) {
		Context c = new Context(Standards.defaults(), ws, false);
		return new Team(c, Main.build(c, s -> AppTools.register(s, c, ws.resolve(".app/assistant.json")), false));
	}

	static String q(Object o) {
		return GSON.toJson(o.toString());
	}

	@Test
	void aProjectTravelsWithWhatTheAppKnows(@TempDir Path ours, @TempDir Path theirs, @TempDir Path downloads) throws Exception {
		Team a = team(ours);
		// Their own motor, flown in the design.
		Path eng = downloads.resolve("team-c9.eng");
		Files.writeString(eng, "; a team motor\nTeamProj-C9 18 70 3-5 0.012 0.025 TeamProj\n0.05 12\n0.5 9\n1.0 8\n1.2 0\n;\n");
		a.call("app_import_motor", "{\"path\":" + q(eng) + "}");
		String d = a.call("open_design", "{\"example\":\"A simple model rocket\"}").get("designId").getAsString();
		String mount = a.call("get_design", "{\"designId\":\"" + d + "\"}").getAsJsonArray("flightConfigurations").get(0).getAsJsonObject()
				.getAsJsonArray("motors").get(0).getAsJsonObject().get("mountId").getAsString();
		a.call("set_motor", "{\"designId\":\"" + d + "\",\"mount\":\"" + mount + "\",\"motor\":\"TeamProj-C9\",\"ejectionDelay\":\"3 s\"}");
		// Their targets, a progress log entry and a flight log.
		a.call("targets", "{\"designId\":\"" + d + "\",\"set\":{\"apogee\":{\"min\":\"20 m\"}}}");
		Path log = downloads.resolve("flight 1.csv");
		Files.writeString(log, "time,altitude\n0,0\n1,10\n2,15\n3,12\n");

		Path rmx = ours.resolve("shared/Simple.rmx");
		JsonObject saved = a.call("save_design", "{\"designId\":\"" + d + "\",\"path\":" + q(rmx) + ",\"project\":{\"flightLogs\":[" + q(log)
				+ "],\"plots\":\"[{\\\"name\\\":\\\"Climb\\\",\\\"vars\\\":[\\\"Altitude\\\"]}]\",\"settings\":\"{\\\"system\\\":\\\"imperial\\\"}\"}}");
		assertEquals("RocketMaxxing project", saved.get("format").getAsString());
		try (ZipFile z = new ZipFile(rmx.toFile())) {
			for (String name : new String[] { "manifest.json", "design.ork", "standards.json", "motors/team-c9.eng", "logs/flight 1.csv",
					"plots.json", "settings.json" }) {
				assertTrue(z.getEntry(name) != null, name + " is in the project");
			}
		}
		assertEquals(rmx.toString(), a.call("app_design", "{\"designId\":\"" + d + "\"}").get("path").getAsString(),
				"the design now belongs to the project: Save writes it again");

		// Another team, with its own standards, opens it.
		Files.writeString(theirs.resolve(Standards.FILE_NAME), "{\"targets\": {\"apogee\": {\"max\": \"500 m\"}}}");
		Team b = team(theirs);
		b.ctx().setStandards(Standards.load(theirs.resolve(Standards.FILE_NAME)));
		JsonObject opened = b.call("open_design", "{\"path\":" + q(rmx) + "}");
		String id = opened.get("designId").getAsString();
		assertTrue(Files.exists(theirs.resolve("motors/team-c9.eng")), "the motor joins the other team's motors");
		JsonObject design = b.call("get_design", "{\"designId\":\"" + id + "\"}");
		assertTrue(design.toString().contains("TeamProj-C9"), "and the design flies it: " + design.getAsJsonArray("flightConfigurations"));
		assertTrue(opened.getAsJsonObject("project").get("standards").getAsString().contains("1 target"), opened.toString());
		assertTrue(Files.readString(theirs.resolve(Standards.FILE_NAME)).contains("500 m"), "their own standards stay until they choose");
		assertTrue(Files.exists(theirs.resolve("flight logs/Simple model rocket/flight 1.csv"))
				|| opened.getAsJsonObject("project").get("flightLogs").toString().contains("flight 1.csv"), opened.toString());
		JsonObject app = b.call("app_design", "{\"designId\":\"" + id + "\"}");
		assertEquals("Climb", app.getAsJsonObject("project").getAsJsonArray("plots").get(0).getAsJsonObject().get("name").getAsString());

		b.call("app_project_standards", "{\"designId\":\"" + id + "\"}");
		assertTrue(Files.readString(theirs.resolve(Standards.FILE_NAME)).contains("20 m"), "now the project's standards");
		assertTrue(Files.readString(theirs.resolve(Standards.FILE_NAME + ".bak")).contains("500 m"), "the old file is kept beside it");

		// Saving again writes the project in place.
		b.call("save_design", "{\"designId\":\"" + id + "\"}");
		assertTrue(Files.size(rmx) > 0);
	}

	@Test
	void refusesWhatIsNotAProject(@TempDir Path ws) throws Exception {
		Team t = team(ws);
		Path junk = ws.resolve("junk.rmx");
		Files.writeString(junk, "not a zip");
		assertTrue(t.call("open_design", "{\"path\":" + q(junk) + "}", true).get("error").getAsString().contains("damaged or incomplete"));
		Path other = ws.resolve("other.rmx");
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(other))) {
			z.putNextEntry(new ZipEntry("readme.txt"));
			z.write("hello".getBytes());
			z.closeEntry();
		}
		assertTrue(t.call("open_design", "{\"path\":" + q(other) + "}", true).get("error").getAsString().contains("not a RocketMaxxing project"));

		// A project whose motor entry tries to climb out of the folder lands inside it all the same.
		String d = t.call("open_design", "{\"example\":\"A simple model rocket\"}").get("designId").getAsString();
		Path good = ws.resolve("good.rmx");
		t.call("save_design", "{\"designId\":\"" + d + "\",\"path\":" + q(good) + "}");
		Path evil = ws.resolve("evil.rmx");
		try (ZipFile in = new ZipFile(good.toFile()); ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
			for (var e : java.util.Collections.list(in.entries())) {
				z.putNextEntry(new ZipEntry(e.getName()));
				z.write(in.getInputStream(e).readAllBytes());
				z.closeEntry();
			}
			z.putNextEntry(new ZipEntry("logs/../../../escaped.csv"));
			z.write("time,altitude\n0,0\n".getBytes());
			z.closeEntry();
		}
		t.call("open_design", "{\"path\":" + q(evil) + "}");
		assertFalse(Files.exists(ws.getParent().resolve("escaped.csv")), "nothing is written outside the designs folder");
		assertFalse(Files.exists(ws.resolve("escaped.csv")));
	}
}
