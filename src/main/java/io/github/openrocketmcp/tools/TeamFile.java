package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.standards.Standards;

/**
 * The team standards file as the app keeps it: only what the team set, merged into the built-in defaults. The app's
 * Standards and Targets pages and the targets tool write through here, so a change made in either place is in the file.
 */
public final class TeamFile {
	private TeamFile() {
	}

	/** The team standards file: the one loaded, else openrocket-mcp.json in the designs folder. */
	public static Path path(Context ctx) {
		return ctx.standards().source() != null ? ctx.standards().source()
				: (ctx.workspace() != null ? ctx.workspace() : Path.of("").toAbsolutePath()).resolve(Standards.FILE_NAME);
	}

	public static JsonObject read(Path file) throws IOException {
		return Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
	}

	/**
	 * Merges a change into the team standards file and uses the result at once. The file keeps only what the team set
	 * (the built-in defaults stay defaults, so later improvements to them still apply); null puts a value back to its
	 * default. The change is checked before anything is written.
	 */
	public static Map<String, Object> save(Context ctx, JsonObject patch) throws IOException {
		Path file = path(ctx);
		JsonObject team = read(file);
		apply(team, patch);
		Standards next = Standards.fromTeam(team, file);
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, Standards.pretty(team));
		ctx.setStandards(next);
		return Map.of("saved", file.toString());
	}

	/** Deep merge where null removes the key, and an object left empty goes too. */
	public static void apply(JsonObject target, JsonObject patch) {
		for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
			JsonElement v = e.getValue();
			if (v.isJsonNull()) {
				target.remove(e.getKey());
			} else if (v.isJsonObject() && target.has(e.getKey()) && target.get(e.getKey()).isJsonObject()) {
				apply(target.getAsJsonObject(e.getKey()), v.getAsJsonObject());
				if (target.getAsJsonObject(e.getKey()).size() == 0) {
					target.remove(e.getKey());
				}
			} else if (v.isJsonObject()) {
				JsonObject fresh = new JsonObject();
				apply(fresh, v.getAsJsonObject());
				if (fresh.size() > 0) {
					target.add(e.getKey(), fresh);
				}
			} else {
				target.add(e.getKey(), v.deepCopy());
			}
		}
	}
}
