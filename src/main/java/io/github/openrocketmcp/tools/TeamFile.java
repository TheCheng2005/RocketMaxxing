package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
		JsonObject before = team.deepCopy();
		apply(team, patch);
		Standards next = Standards.fromTeam(team, file);
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, Standards.pretty(team));
		ctx.setStandards(next);
		journal(ctx, file, before, team, changes(patch, ""));
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

	/** Puts a change to the team standards in the journal, with what the file held before and after (to put back). */
	static void journal(Context ctx, Path file, JsonObject before, JsonObject after, String what) {
		JsonObject x = new JsonObject();
		x.add("before", before);
		x.add("after", after.deepCopy());
		Journal.add(ctx, "standards", null, file.toString(), what, x);
	}

	/** "targets.apogee.min = 2800 m; launchSite.altitude cleared": what a change sets, in a line. */
	static String changes(JsonObject patch, String at) {
		List<String> out = new java.util.ArrayList<>();
		for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
			String k = at.isEmpty() ? e.getKey() : at + "." + e.getKey();
			JsonElement v = e.getValue();
			if (v.isJsonNull()) {
				out.add(words(k) + " cleared");
			} else if (v.isJsonObject()) {
				String inner = changes(v.getAsJsonObject(), k);
				if (!inner.isEmpty()) {
					out.add(inner);
				}
			} else {
				out.add(words(k) + " set to " + (v.isJsonPrimitive() ? v.getAsString() : v.toString()));
			}
		}
		String s = String.join("; ", out);
		return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + s.substring(1);
	}

	/** "targets.apogee.goal" as "apogee goal", "launchSite.altitude" as "launch site altitude". */
	static String words(String path) {
		String p = path.replaceFirst("^targets\\.", "").replace('.', ' ').replace('_', ' ');
		return p.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT);
	}
}
