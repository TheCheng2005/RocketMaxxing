package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.tools.Context;

/** Launch plans kept in the designs folder's plans/ (written by launch_planner's savePath). */
final class AppPlans {
	private AppPlans() {
	}

	static Path dir(Context ctx) {
		return (ctx.workspace() != null ? ctx.workspace() : Path.of("").toAbsolutePath()).resolve("plans");
	}

	static Map<String, Object> plans(Context ctx, Args a) throws IOException {
		Path dir = dir(ctx);
		String action = a.str("action", "list");
		if (action.equals("save")) {
			String name = a.str("name", "").replaceAll("[^A-Za-z0-9._ -]", "-").trim();
			if (name.isEmpty()) {
				throw new ToolException("Give the plan a name.");
			}
			Files.createDirectories(dir);
			Path f = dir.resolve(name.endsWith(".json") ? name : name + ".json");
			JsonObject saved = new JsonObject();
			saved.addProperty("savedAt", java.time.Instant.now().toString());
			saved.add("plan", a.obj("plan").raw());
			Files.writeString(f, saved.toString());
			return Map.of("saved", f.getFileName().toString(), "path", f.toString());
		}
		if (!action.equals("list")) {
			String name = a.str("name", "");
			Path f = dir.resolve(name).normalize();
			if (name.isBlank() || !f.startsWith(dir) || !name.endsWith(".json") || !Files.isRegularFile(f)) {
				throw new ToolException("No saved plan called " + name + ".");
			}
			if (action.equals("delete")) {
				Files.delete(f);
				return Map.of("deleted", name);
			}
			return Map.of("name", name, "saved", JsonParser.parseString(Files.readString(f)));
		}
		List<Map<String, Object>> out = new ArrayList<>();
		if (Files.isDirectory(dir)) {
			try (var files = Files.list(dir)) {
				for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("name", f.getFileName().toString());
					try {
						JsonObject j = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
						JsonObject plan = j.getAsJsonObject("plan");
						m.put("savedAt", j.has("savedAt") ? j.get("savedAt").getAsString() : "");
						if (plan != null) {
							m.put("design", plan.has("design") ? plan.get("design").getAsString() : "");
							JsonObject fc = plan.getAsJsonObject("forecast");
							m.put("time", fc != null && fc.has("time") ? fc.get("time").getAsString() : "");
							m.put("verdict", plan.has("verdict") ? plan.get("verdict").getAsString() : "");
						}
					} catch (IOException | RuntimeException e) {
						m.put("verdict", "Could not be read.");
					}
					out.add(m);
				}
			}
		}
		return Map.of("folder", dir.toString(), "plans", out);
	}
}
