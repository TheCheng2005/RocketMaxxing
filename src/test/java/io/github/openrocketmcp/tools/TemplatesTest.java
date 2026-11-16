package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;

/** Full-size templates to print: a PDF whose pages carry the fins, rings, shrouds, nose profile and marking guides. */
class TemplatesTest {
	static McpServer server(Path ws) {
		return Main.build(new Context(Standards.defaults(), ws, false), s -> {
		}, false);
	}

	static JsonObject call(McpServer s, String tool, String args, boolean error) {
		JsonObject r = s.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + args + "}}")
				.getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertEquals(error, r.get("isError").getAsBoolean(), text);
		return error ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
	}

	static info.openrocket.core.rocketcomponent.FreeformFinSet first(info.openrocket.core.rocketcomponent.Rocket r) {
		for (info.openrocket.core.rocketcomponent.RocketComponent c : r) {
			if (c instanceof info.openrocket.core.rocketcomponent.FreeformFinSet f) {
				return f;
			}
		}
		throw new AssertionError("no freeform fins");
	}

	@Test
	void printsEveryTemplateAtFullSize(@TempDir Path ws) throws Exception {
		McpServer s = server(ws);
		call(s, "open_design", "{\"path\":" + new com.google.gson.Gson().toJson(Path.of("docs/examples/maple-10k.ork").toAbsolutePath().toString()) + "}", false);
		JsonObject r = call(s, "print_templates", "{\"paper\":\"Letter\"}", false);
		Path pdf = Path.of(r.get("written").getAsString());
		assertTrue(Files.isRegularFile(pdf) && pdf.startsWith(ws.resolve("templates")), r.toString());
		String body = new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
		assertTrue(body.startsWith("%PDF-1.4") && body.trim().endsWith("%%EOF"));
		Matcher count = Pattern.compile("/Type /Pages /Kids \\[[^]]*\\] /Count (\\d+)").matcher(body);
		assertTrue(count.find());
		assertEquals(r.get("pages").getAsInt(), Integer.parseInt(count.group(1)));
		assertTrue(body.contains("/MediaBox [0 0 612 792]"), "US Letter in points");
		String kinds = r.getAsJsonArray("templates").toString();
		for (String k : new String[] { "\"fins\"", "\"rings\"", "\"nose\"", "\"marking\"" }) {
			assertTrue(kinds.contains(k), k + " in " + kinds);
		}
		// The xref table points at each object.
		Matcher start = Pattern.compile("startxref\\n(\\d+)").matcher(body);
		assertTrue(start.find());
		assertTrue(body.startsWith("xref", Integer.parseInt(start.group(1))));

		JsonObject only = call(s, "print_templates", "{\"include\":[\"fins\"],\"path\":\"fins.pdf\"}", false);
		for (var t : only.getAsJsonArray("templates")) {
			assertEquals("fins", t.getAsJsonObject().get("template").getAsString());
		}
		call(s, "print_templates", "{\"include\":[\"decals\"]}", true);
		call(s, "print_templates", "{\"paper\":\"B5\"}", true);
	}
}
