package io.github.openrocketmcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/**
 * Writes the manifest.json of the Claude Desktop extension (.mcpb): the server runs on the Java runtime bundled in the
 * extension, so installing it is one click with nothing else to set up. Used by the {@code mcpb} Gradle task.
 */
public final class Manifest {
	private Manifest() {
	}

	/** Args: platform (darwin | win32 | linux), output file. */
	public static void main(String[] args) throws Exception {
		String platform = args[0];
		Path out = Path.of(args[1]);
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.writeString(out, json(platform), StandardCharsets.UTF_8);
	}

	static String json(String platform) {
		JsonObject m = new JsonObject();
		m.addProperty("manifest_version", "0.2");
		m.addProperty("name", "openrocket-mcp");
		m.addProperty("display_name", "OpenRocket");
		m.addProperty("version", Main.VERSION);
		m.addProperty("description", "Design, simulate and check competition rockets with OpenRocket's physics.");
		m.addProperty("long_description", """
				Lets Claude open your OpenRocket (.ork) designs, simulate flights, and check them against Launch Canada rules \
				and your team's standards: stability, motors, parachutes, deployment loads, shear pins, ejection charges, \
				fin flutter, ballast, Monte Carlo dispersion, two-stage flights, launch-day weather and flight cards, design \
				reviews, 3-D exploded views and flight animations, and more. \
				Java and OpenRocket are included; choose the folder that holds your designs.""");
		JsonObject author = new JsonObject();
		author.addProperty("name", "RocketMaxxing contributors");
		m.add("author", author);
		JsonObject repo = new JsonObject();
		repo.addProperty("type", "git");
		repo.addProperty("url", "https://github.com/TheCheng2005/RocketMaxxing");
		m.add("repository", repo);
		m.addProperty("homepage", "https://github.com/TheCheng2005/RocketMaxxing");
		m.addProperty("license", "GPL-3.0-or-later");
		m.addProperty("icon", "icon.png");
		JsonArray keywords = new JsonArray();
		List.of("rocketry", "openrocket", "simulation", "high-power rocketry", "launch canada").forEach(keywords::add);
		m.add("keywords", keywords);

		JsonObject server = new JsonObject();
		server.addProperty("type", "binary");
		server.addProperty("entry_point", "runtime/bin/java");
		JsonObject cfg = new JsonObject();
		cfg.addProperty("command", "${__dirname}/runtime/bin/java");
		JsonArray a = new JsonArray();
		List.of("-Djava.awt.headless=true", "-Xss4m", "-XX:+UseParallelGC", "-cp", "${__dirname}/lib/*", "io.github.openrocketmcp.Main").forEach(a::add);
		cfg.add("args", a);
		JsonObject env = new JsonObject();
		env.addProperty("OPENROCKET_MCP_WORKSPACE", "${user_config.workspace}");
		env.addProperty("OPENROCKET_MCP_STANDARDS", "${user_config.standards_file}");
		cfg.add("env", env);
		JsonObject win = new JsonObject();
		win.addProperty("command", "${__dirname}/runtime/bin/java.exe");
		JsonObject overrides = new JsonObject();
		overrides.add("win32", win);
		cfg.add("platform_overrides", overrides);
		server.add("mcp_config", cfg);
		m.add("server", server);

		JsonArray tools = new JsonArray();
		for (ToolDef t : Main.build(new Context(Standards.defaults())).tools().values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", t.name());
			o.addProperty("description", firstSentence(t.description()));
			tools.add(o);
		}
		m.add("tools", tools);
		m.addProperty("tools_generated", false);

		JsonObject uc = new JsonObject();
		JsonObject ws = new JsonObject();
		ws.addProperty("type", "directory");
		ws.addProperty("title", "Rocket folder");
		ws.addProperty("description", "Folder with your .ork designs. Reports and saved designs go here too.");
		ws.addProperty("required", true);
		ws.addProperty("default", "${DOCUMENTS}");
		uc.add("workspace", ws);
		JsonObject std = new JsonObject();
		std.addProperty("type", "file");
		std.addProperty("title", "Team standards file (optional)");
		std.addProperty("description", "openrocket-mcp.json with your team's safety factors, launch site, units and rule set. "
				+ "Without it the server uses openrocket-mcp.json in the rocket folder, or Launch Canada 2027 defaults.");
		std.addProperty("required", false);
		uc.add("standards_file", std);
		m.add("user_config", uc);

		JsonObject compat = new JsonObject();
		compat.addProperty("claude_desktop", ">=0.10.0");
		JsonArray platforms = new JsonArray();
		platforms.add(platform);
		compat.add("platforms", platforms);
		m.add("compatibility", compat);
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(m) + "\n";
	}

	static String firstSentence(String d) {
		int i = d.indexOf(". ");
		String s = i > 0 ? d.substring(0, i + 1) : d;
		return s.length() > 300 ? s.substring(0, 297) + "..." : s;
	}
}
