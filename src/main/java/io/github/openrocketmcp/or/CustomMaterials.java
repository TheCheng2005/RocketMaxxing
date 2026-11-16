package io.github.openrocketmcp.or;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.database.Database;
import info.openrocket.core.database.Databases;
import info.openrocket.core.material.Material;
import io.github.openrocketmcp.mcp.Log;

/**
 * The team's own materials, as OpenRocket's Preferences > Materials: a laminate, a printed filament, a canopy cloth
 * or a cord with its density, kept beside the designs ({@value #FILE}) and added to OpenRocket's material lists so
 * every part, analysis and the app can use them by name. Designs carry the materials they use, so files stay readable
 * elsewhere.
 */
public final class CustomMaterials {
	private CustomMaterials() {
	}

	public static final String FILE = "materials.json";

	public static Material.Type type(String s) {
		return switch (s == null ? "bulk" : s.trim().toLowerCase(Locale.ROOT)) {
			case "bulk", "solid", "structural" -> Material.Type.BULK;
			case "surface", "sheet", "cloth", "fabric" -> Material.Type.SURFACE;
			case "line", "cord", "thread" -> Material.Type.LINE;
			default -> throw new IllegalArgumentException("Material type \"" + s + "\" is not known: use bulk (solid parts), surface (canopies, "
					+ "streamers) or line (shroud lines, cords).");
		};
	}

	static Database<Material> db(Material.Type t) {
		OrRuntime.init(); // OpenRocket's material lists exist once it has started
		return switch (t) {
			case SURFACE -> Databases.SURFACE_MATERIAL;
			case LINE -> Databases.LINE_MATERIAL;
			default -> Databases.BULK_MATERIAL;
		};
	}

	/** The materials in the folder's file, added to OpenRocket's lists (once). Returns how many were read. */
	public static synchronized int load(Path workspace) {
		Path f = workspace.resolve(FILE);
		if (!Files.isRegularFile(f)) {
			return 0;
		}
		int n = 0;
		try {
			JsonArray all = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("materials");
			for (JsonElement e : all) {
				JsonObject o = e.getAsJsonObject();
				try {
					register(type(o.get("type").getAsString()), o.get("name").getAsString(), o.get("density").getAsDouble());
					n++;
				} catch (RuntimeException bad) {
					Log.info("A material in " + f + " was left out (" + bad.getMessage() + ")");
				}
			}
		} catch (IOException | RuntimeException e) {
			Log.info("The team's materials could not be read from " + f + " (" + e.getMessage() + ")");
		}
		return n;
	}

	/** Adds the material to OpenRocket's list, replacing one of the team's with the same name. */
	static Material register(Material.Type t, String name, double density) {
		Material old = find(t, name);
		if (old != null && !old.isUserDefined()) {
			throw new IllegalArgumentException("OpenRocket already has a material called \"" + old.getName() + "\"; give yours another name.");
		}
		if (old != null) {
			db(t).remove(old);
		}
		Material m = Material.newMaterial(t, name.trim(), density, true);
		db(t).add(m);
		return m;
	}

	static Material find(Material.Type t, String name) {
		for (Material m : db(t)) {
			if (m.getType() == t && m.getName().equalsIgnoreCase(name.trim())) {
				return m;
			}
		}
		return null;
	}

	/** The team's materials (those not built into OpenRocket). */
	public static List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Material.Type t : List.of(Material.Type.BULK, Material.Type.SURFACE, Material.Type.LINE)) {
			for (Material m : db(t)) {
				if (m.isUserDefined() && m.getType() == t) {
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("type", t.name().toLowerCase(Locale.ROOT));
					e.put("name", m.getName());
					e.put("density", m.getDensity());
					out.add(e);
				}
			}
		}
		return out;
	}

	public static synchronized Material add(Path workspace, Material.Type t, String name, double density) throws IOException {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A material needs a name.");
		}
		if (!(density > 0)) {
			throw new IllegalArgumentException("A material's density must be more than zero.");
		}
		Material m = register(t, name, density);
		save(workspace);
		return m;
	}

	public static synchronized boolean remove(Path workspace, String name) throws IOException {
		for (Material.Type t : List.of(Material.Type.BULK, Material.Type.SURFACE, Material.Type.LINE)) {
			Material m = find(t, name);
			if (m != null) {
				if (!m.isUserDefined()) {
					throw new IllegalArgumentException("\"" + m.getName() + "\" is one of OpenRocket's own materials; only the team's can be removed.");
				}
				db(t).remove(m);
				save(workspace);
				return true;
			}
		}
		return false;
	}

	static void save(Path workspace) throws IOException {
		JsonArray all = new JsonArray();
		for (Map<String, Object> m : list()) {
			JsonObject o = new JsonObject();
			o.addProperty("type", (String) m.get("type"));
			o.addProperty("name", (String) m.get("name"));
			o.addProperty("density", (Double) m.get("density"));
			all.add(o);
		}
		JsonObject root = new JsonObject();
		root.addProperty("about", "The team's own materials for RocketMaxxing: density in kg/m3 (bulk), kg/m2 (surface) or kg/m (line).");
		root.add("materials", all);
		Files.createDirectories(workspace);
		Files.writeString(workspace.resolve(FILE), new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
	}
}
