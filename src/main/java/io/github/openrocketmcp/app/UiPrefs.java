package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.ToolException;

/**
 * What the interface remembers between launches (recent designs, colour scheme, units, panels, tours seen), kept by the
 * engine in one file per user. The page's own storage cannot keep it: it belongs to the page's address, and the app's
 * port changes every launch. Only the interface's "rocketmaxxing." keys, small values, written whole and atomically.
 */
final class UiPrefs {
	static final String PREFIX = "rocketmaxxing.";
	static final int MAX_VALUE = 64 * 1024;
	static final int MAX_TOTAL = 512 * 1024;
	private final Path file;

	UiPrefs(Path file) {
		this.file = file;
	}

	/** Every stored preference (empty when there is no file yet, or it cannot be read). */
	synchronized Map<String, String> all() {
		Map<String, String> out = new TreeMap<>();
		if (!Files.isRegularFile(file)) {
			return out;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				if (e.getKey().startsWith(PREFIX) && e.getValue().isJsonPrimitive()) {
					out.put(e.getKey(), e.getValue().getAsString());
				}
			}
		} catch (IOException | RuntimeException e) {
			Log.info("Interface preferences in " + file + " could not be read (" + e.getMessage() + "); starting afresh.");
		}
		return out;
	}

	/** Applies changes (a null value removes the key) and returns everything stored. */
	synchronized Map<String, String> set(JsonObject changes) {
		Map<String, String> now = all();
		for (Map.Entry<String, JsonElement> e : changes.entrySet()) {
			String k = e.getKey();
			if (!k.startsWith(PREFIX) || k.length() > 100) {
				throw new ToolException("Preference names start with \"" + PREFIX + "\" and are at most 100 characters: " + k);
			}
			if (e.getValue() == null || e.getValue().isJsonNull()) {
				now.remove(k);
				continue;
			}
			if (!e.getValue().isJsonPrimitive()) {
				throw new ToolException("Preference " + k + " must be a string.");
			}
			String v = e.getValue().getAsString();
			if (v.length() > MAX_VALUE) {
				throw new ToolException("Preference " + k + " is too long (" + v.length() + " characters; at most " + MAX_VALUE + ").");
			}
			now.put(k, v);
		}
		int total = now.entrySet().stream().mapToInt(e -> e.getKey().length() + e.getValue().length()).sum();
		if (total > MAX_TOTAL) {
			throw new ToolException("The interface's preferences would take " + total + " characters; at most " + MAX_TOTAL + ".");
		}
		JsonObject o = new JsonObject();
		now.forEach(o::addProperty);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw ToolException.system("Could not save the interface's preferences to " + file + ": " + e.getMessage());
		}
		return new LinkedHashMap<>(now);
	}
}
