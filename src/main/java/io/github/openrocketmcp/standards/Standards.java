package io.github.openrocketmcp.standards;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.simulation.SimulationOptions;
import io.github.openrocketmcp.mcp.Limits;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.UnitSystem;
import io.github.openrocketmcp.units.Units;

/**
 * Team standards (safety factors, pin ratings, packing factors, launch site) merged over the built-in
 * defaults, plus the competition rule set they reference.
 */
public final class Standards {
	public static final String FILE_NAME = "openrocket-mcp.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

	private final JsonObject data;
	private final JsonObject rules;
	private final Path source;

	private Standards(JsonObject data, JsonObject rules, Path source) {
		this.data = data;
		this.rules = rules;
		this.source = source;
	}

	public static Standards defaults() {
		return build(new JsonObject(), null);
	}

	/**
	 * Loads the team file: an explicit path, else $OPENROCKET_MCP_STANDARDS, else ./openrocket-mcp.json, else
	 * built-in defaults only.
	 */
	public static Standards discover() {
		return discover(Path.of(""));
	}

	/** As {@link #discover()}, looking for openrocket-mcp.json in {@code dir}. */
	public static Standards discover(Path dir) {
		String env = System.getenv("OPENROCKET_MCP_STANDARDS");
		if (env != null && !env.isBlank()) {
			return load(Path.of(env));
		}
		Path local = dir.resolve(FILE_NAME);
		if (Files.exists(local)) {
			return load(local);
		}
		return defaults();
	}

	public static Standards load(Path path) {
		Path p = path.toAbsolutePath().normalize();
		try {
			JsonObject team = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
			return build(team, p);
		} catch (IOException e) {
			throw new ToolException("Cannot read standards file " + p + ": " + e.getMessage());
		} catch (ToolException e) {
			throw new ToolException("Standards file " + p + ": " + e.getMessage());
		} catch (RuntimeException e) {
			throw new ToolException("Invalid JSON in " + p + ": " + e.getMessage());
		}
	}

	private static Standards build(JsonObject team, Path source) {
		JsonObject merged = resource("/openrocketmcp/default-standards.json");
		deepMerge(merged, team);
		validate(merged);
		JsonObject rules = loadRules(merged.has("ruleset") ? merged.get("ruleset").getAsString() : null, source);
		Standards s = new Standards(merged, rules, source);
		if (merged.has("units")) {
			Units.setSystem(UnitSystem.parse(merged.get("units").getAsString()));
		}
		return s;
	}

	/** On a team server, rule-set files must be inside this directory (null = no restriction). */
	private static volatile Path sandbox;

	public static void sandbox(Path root) {
		sandbox = root == null ? null : root.toAbsolutePath().normalize();
	}

	private static JsonObject loadRules(String ruleset, Path standardsFile) {
		if (ruleset == null || ruleset.isBlank() || ruleset.equals("none")) {
			return new JsonObject();
		}
		if (ruleset.endsWith(".json")) {
			Path p = standardsFile != null && standardsFile.getParent() != null
					? standardsFile.getParent().resolve(ruleset) : Path.of(ruleset);
			p = p.toAbsolutePath().normalize();
			if (sandbox != null && !p.startsWith(sandbox)) {
				throw new ToolException("Rule set files must be inside the team workspace.");
			}
			try {
				return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
			} catch (IOException e) {
				throw new ToolException("Cannot read rule set " + p + ": " + e.getMessage());
			}
		}
		JsonObject r = resource("/openrocketmcp/rules/" + ruleset + ".json");
		if (r == null) {
			throw new ToolException("Unknown rule set '" + ruleset + "'. Built in: launch-canada-2027, launch-canada-r4, none, or a path to a .json file.");
		}
		return r;
	}

	/** The built-in defaults every team file is merged over. */
	public static JsonObject defaultsJson() {
		return resource("/openrocketmcp/default-standards.json");
	}

	/** The rule sets that come with the server (a team can also name a .json file of its own). */
	public static final List<String> BUILT_IN_RULES = List.of("launch-canada-2027", "launch-canada-r4");

	/** A built-in rule set's full name ("Launch Canada 2027: ..."), or its id. */
	public static String ruleSetName(String id) {
		JsonObject r = resource("/openrocketmcp/rules/" + id + ".json");
		return r != null && r.has("name") ? r.get("name").getAsString() : id;
	}

	/** Standards from a team file's contents (merged over the defaults and checked), as if loaded from {@code source}. */
	public static Standards fromTeam(JsonObject team, Path source) {
		return build(team.deepCopy(), source);
	}

	private static JsonObject resource(String name) {
		try (InputStream in = Standards.class.getResourceAsStream(name)) {
			if (in == null) {
				return null;
			}
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Recursively merges {@code patch} into {@code target}; JSON null removes a key. */
	public static void deepMerge(JsonObject target, JsonObject patch) {
		for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
			JsonElement v = e.getValue();
			if (v.isJsonObject() && target.has(e.getKey()) && target.get(e.getKey()).isJsonObject()) {
				deepMerge(target.getAsJsonObject(e.getKey()), v.getAsJsonObject());
			} else {
				target.add(e.getKey(), v.deepCopy());
			}
		}
	}

	/** Returns a new Standards with {@code patch} merged in. */
	/** Changed in this session with update_standards (possibly not saved to a file yet). */
	private boolean edited;

	public boolean edited() {
		return edited;
	}

	public Standards patched(JsonObject patch) {
		JsonObject copy = data.deepCopy();
		deepMerge(copy, patch);
		validate(copy);
		JsonObject rulesNow = loadRules(copy.has("ruleset") ? copy.get("ruleset").getAsString() : null, source);
		Standards s = new Standards(copy, rulesNow, source);
		s.edited = true;
		if (copy.has("units")) {
			Units.setSystem(UnitSystem.parse(copy.get("units").getAsString()));
		}
		return s;
	}

	/**
	 * Refuses standards a tool would later misread: a section replaced by a value of another shape (an object by a
	 * string), or a quantity outside its physical range (a negative modulus or pin strength, a zero air density).
	 * Checked when standards are loaded or patched, so the mistake is reported where it was made.
	 */
	static void validate(JsonObject merged) {
		List<String> problems = new ArrayList<>();
		shape(resource("/openrocketmcp/default-standards.json"), merged, "", problems);
		quantities(merged, "", null, problems);
		if (!problems.isEmpty()) {
			List<String> shown = problems.subList(0, Math.min(5, problems.size())).stream().map(x -> x.replaceAll("\\.+$", "")).toList();
			throw new ToolException("Standards not changed: " + String.join("; ", shown)
					+ (problems.size() > 5 ? " (and " + (problems.size() - 5) + " more)" : "") + ".");
		}
	}

	private static void shape(JsonElement def, JsonElement got, String where, List<String> problems) {
		if (got == null || got.isJsonNull() || def == null || def.isJsonNull()) {
			return;
		}
		// {"value": "16 GPa", "source": ...} may also be written as just "16 GPa".
		boolean valueForm = def.isJsonObject() && def.getAsJsonObject().has("value") && got.isJsonPrimitive();
		if (!valueForm && (def.isJsonObject() != got.isJsonObject() || def.isJsonArray() != got.isJsonArray())) {
			problems.add(where.substring(Math.min(1, where.length())) + " must be "
					+ (def.isJsonObject() ? "an object" : def.isJsonArray() ? "a list" : "a single value") + " as in the defaults (got "
					+ abbreviate(got.toString()) + ")");
			return;
		}
		if (def.isJsonObject() && got.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : def.getAsJsonObject().entrySet()) {
				shape(e.getValue(), got.getAsJsonObject().get(e.getKey()), where + "." + e.getKey(), problems);
			}
		}
	}

	private static void quantities(JsonElement e, String where, String key, List<String> problems) {
		// Targets are flight numbers (an apogee is kilometres, not a part length): the targets tool checks them.
		if (e == null || e.isJsonNull() || where.equals(".targets")) {
			return;
		}
		if (e.isJsonObject()) {
			for (Map.Entry<String, JsonElement> c : e.getAsJsonObject().entrySet()) {
				// {"value": "16 GPa", "source": ...} is checked under its own key.
				quantities(c.getValue(), where + "." + c.getKey(), c.getKey().equals("value") ? key : c.getKey(), problems);
			}
		} else if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) {
				quantities(c, where, key, problems);
			}
		} else if (e.isJsonPrimitive() && key != null) {
			try {
				if (e.getAsJsonPrimitive().isNumber()) {
					if (Limits.hasRule(key)) {
						Limits.check(key, null, e.getAsDouble());
					}
				} else if (e.getAsJsonPrimitive().isString()) {
					Units.Parsed q;
					try {
						q = Units.parse(e.getAsString());
					} catch (IllegalArgumentException notAQuantity) {
						return; // text: names, notes, sources
					}
					if (q.dim() != null || Limits.hasRule(key)) {
						Limits.check(key, q.dim(), q.si());
					}
				}
			} catch (ToolException bad) {
				problems.add(where.substring(1) + ": " + bad.getMessage().replace(" Check the value and its unit.", ""));
			}
		}
	}

	private static String abbreviate(String s) {
		return s.length() > 40 ? s.substring(0, 40) + "..." : s;
	}

	public void save(Path path) throws IOException {
		Files.writeString(path, GSON.toJson(data));
	}

	public Path source() {
		return source;
	}

	public JsonObject data() {
		return data;
	}

	public JsonObject rules() {
		return rules;
	}

	public String json() {
		return GSON.toJson(data);
	}

	public String rulesJson() {
		return GSON.toJson(rules);
	}

	private static JsonElement path(JsonObject root, String dotted) {
		JsonElement cur = root;
		for (String part : dotted.split("\\.")) {
			if (cur == null || !cur.isJsonObject() || !cur.getAsJsonObject().has(part)) {
				return null;
			}
			cur = cur.getAsJsonObject().get(part);
		}
		return cur == null || cur.isJsonNull() ? null : cur;
	}

	/** A quantity from the team standards, SI; {@code fallback} when absent or null. */
	public double q(String dotted, Dim dim, double fallback) {
		return qOf(data, dotted, dim, fallback);
	}

	/** A quantity from the rule set, SI; NaN when the rule set does not define it. */
	public double rule(String dotted, Dim dim) {
		return qOf(rules, dotted, dim, Double.NaN);
	}

	public String ruleRef(String key) {
		JsonElement e = path(rules, key + ".ref");
		return e == null ? rules.has("id") ? rules.get("id").getAsString() : "" : e.getAsString();
	}

	public boolean hasRules() {
		return rules.size() > 0;
	}

	public String rulesName() {
		return rules.has("name") ? rules.get("name").getAsString() : "none";
	}

	public String str(String dotted, String fallback) {
		JsonElement e = path(data, dotted);
		return e == null ? fallback : e.getAsString();
	}

	public boolean bool(String dotted, boolean fallback) {
		JsonElement e = path(data, dotted);
		return e == null ? fallback : e.getAsBoolean();
	}

	private static double qOf(JsonObject root, String dotted, Dim dim, double fallback) {
		JsonElement e = path(root, dotted);
		if (e == null) {
			return fallback;
		}
		try {
			if (e.getAsJsonPrimitive().isNumber()) {
				return e.getAsDouble();
			}
			return Units.toSi(e.getAsString(), dim);
		} catch (RuntimeException ex) {
			throw new ToolException("Standards value '" + dotted + "' = " + e + " is invalid: " + ex.getMessage());
		}
	}

	/** Pin strength (N) by pin name from recovery.shearPins, NaN if unknown. */
	public double pinStrength(String name) {
		JsonElement pins = path(data, "recovery.shearPins");
		if (pins == null || !pins.isJsonObject()) {
			return Double.NaN;
		}
		for (Map.Entry<String, JsonElement> e : pins.getAsJsonObject().entrySet()) {
			if (e.getKey().equalsIgnoreCase(name.trim())) {
				JsonElement s = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("strength") : e.getValue();
				double v = s.getAsJsonPrimitive().isNumber() ? s.getAsDouble() : Units.toSi(s.getAsString(), Dim.FORCE);
				if (!(v > 0) || !Double.isFinite(v)) {
					throw new ToolException("recovery.shearPins." + e.getKey() + " strength must be greater than zero (got " + s + ").");
				}
				return v;
			}
		}
		return Double.NaN;
	}

	/**
	 * Shear modulus (Pa) for a material name from structures.shearModulus, whose keys are '|'-separated
	 * case-insensitive name fragments (e.g. "fiberglass|g10"). Returns {modulus, key} or null.
	 */
	public Object[] shearModulus(String material) {
		return materialValue("structures.shearModulus", material, Dim.PRESSURE);
	}

	/**
	 * A per-material value from a standards table whose keys are '|'-separated case-insensitive name fragments (e.g.
	 * "fiberglass|g10"). Returns {SI value, key} or null. Team entries win over default ones.
	 */
	public Object[] materialValue(String tablePath, String material, Dim dim) {
		JsonElement table = path(data, tablePath);
		if (table == null || !table.isJsonObject() || material == null) {
			return null;
		}
		String m = material.toLowerCase(Locale.ROOT);
		// Team entries are merged after the defaults: search from the end so a team's own key wins over a default one.
		List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(table.getAsJsonObject().entrySet());
		Collections.reverse(entries);
		for (Map.Entry<String, JsonElement> e : entries) {
			for (String frag : e.getKey().toLowerCase(Locale.ROOT).split("\\|")) {
				if (!frag.isBlank() && m.contains(frag.trim())) {
					JsonElement v = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("value") : e.getValue();
					double g = v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : Units.toSi(v.getAsString(), dim);
					if (!(g > 0) || !Double.isFinite(g)) {
						throw new ToolException(tablePath + "." + e.getKey() + " must be greater than zero (got " + v + ").");
					}
					return new Object[] { g, e.getKey() };
				}
			}
		}
		return null;
	}

	/**
	 * The team's usual shear pin: recovery.defaultShearPin when set, else the first one listed under
	 * recovery.shearPins; null when the standards list none.
	 */
	public String defaultPin() {
		JsonElement d = path(data, "recovery.defaultShearPin");
		if (d != null && d.isJsonPrimitive() && !Double.isNaN(pinStrength(d.getAsString()))) {
			return d.getAsString();
		}
		Set<String> names = pinNames();
		return names.isEmpty() ? null : names.iterator().next();
	}

	public Set<String> pinNames() {
		JsonElement pins = path(data, "recovery.shearPins");
		return pins == null || !pins.isJsonObject() ? Set.of() : pins.getAsJsonObject().keySet();
	}

	/** Applies launch-site defaults to options of a newly created simulation. */
	public void applyLaunchDefaults(SimulationOptions opt) {
		double rod = q("launchSite.railLength", Dim.LENGTH, Double.NaN);
		if (!Double.isNaN(rod)) {
			opt.setLaunchRodLength(rod);
		}
		double angle = q("launchSite.launchAngleFromVertical", Dim.ANGLE, Double.NaN);
		if (!Double.isNaN(angle)) {
			opt.setLaunchRodAngle(angle);
		}
		double alt = q("launchSite.altitudeMsl", Dim.DISTANCE, Double.NaN);
		if (!Double.isNaN(alt)) {
			opt.setLaunchAltitude(alt);
		}
		double lat = q("launchSite.latitude", Dim.DIMENSIONLESS, Double.NaN);
		if (!Double.isNaN(lat)) {
			opt.setLaunchLatitude(lat);
		}
		double lon = q("launchSite.longitude", Dim.DIMENSIONLESS, Double.NaN);
		if (!Double.isNaN(lon)) {
			opt.setLaunchLongitude(lon);
		}
	}

	public static String pretty(Object o) {
		return GSON.toJson(o);
	}
}
