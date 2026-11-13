package io.github.openrocketmcp.or;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import info.openrocket.core.database.Database;
import info.openrocket.core.database.Databases;
import info.openrocket.core.material.Material;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RingComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Component lookup, tree rendering and reflection-based property editing.
 *
 * <p>Components are addressed by the first 8 characters of their UUID ("a1b2c3d4"), the full UUID, or their
 * exact (case-insensitive) name when unique.
 */
public final class Components {

	/** Component types that can be created with add_component. */
	public static final List<String> CREATABLE = List.of(
			"AxialStage", "NoseCone", "BodyTube", "Transition", "TrapezoidFinSet", "EllipticalFinSet",
			"FreeformFinSet", "TubeFinSet", "LaunchLug", "RailButton", "InnerTube", "TubeCoupler", "CenteringRing",
			"Bulkhead", "EngineBlock", "Parachute", "Streamer", "ShockCord", "MassComponent", "ParallelStage",
			"PodSet");

	private static final Set<String> HIDDEN = Set.of("ID", "Name", "Parent", "Rocket", "Stage", "Class",
			"ComponentName", "PresetComponent", "Appearance", "InsideAppearance", "Color", "LineStyle", "Comment",
			"MotorConfigurationSet", "DeploymentConfigurations", "SeparationConfigurations", "Instances",
			"ClusterConfiguration", "ChildPosition", "FinPoints", "AxialMethod", "RadiusMethod", "AngleMethod",
			"SubcomponentsOverridden", "DisplayOrder_Side", "DisplayOrder_Back", "Visible", "InstanceCount",
			"CDOverridden", "CGOverridden", "OverrideCD", "OverrideCGX", "SubcomponentsOverriddenCD",
			"SubcomponentsOverriddenCG", "SubcomponentsOverriddenMass", "TabOffsetMethod", "PositionValue");

	private Components() {
	}

	public static String shortId(RocketComponent c) {
		return c.getID().toString().substring(0, 8);
	}

	/**
	 * Warning when an ancestor overrides the mass (or CG) of its subcomponents, so mass or position changes to {@code c}
	 * do not reach the simulation; null otherwise. Teams often set such overrides after weighing a section.
	 */
	public static String overrideWarning(RocketComponent c) {
		for (RocketComponent p = c.getParent(); p != null; p = p.getParent()) {
			boolean m = p.isMassOverridden() && p.isSubcomponentsOverriddenMass();
			boolean g = p.isCGOverridden() && p.isSubcomponentsOverriddenCG();
			if (m || g) {
				return "'" + p.getName() + "' overrides the " + (m && g ? "mass and CG" : m ? "mass" : "CG")
						+ " of its subcomponents, so " + (m ? "mass" : "CG") + " changes to '" + c.getName()
						+ "' do not affect the simulation. Update that override (e.g. to the re-weighed value) or turn it off.";
			}
		}
		return null;
	}

	public static RocketComponent find(Rocket rocket, String ref) {
		if (ref == null || ref.isBlank()) {
			throw new ToolException("A component reference (id or name) is required.");
		}
		String r = ref.trim();
		List<RocketComponent> byName = new ArrayList<>();
		for (RocketComponent c : rocket) {
			String id = c.getID().toString();
			if (id.equalsIgnoreCase(r) || (r.length() >= 6 && id.toLowerCase(Locale.ROOT).startsWith(r.toLowerCase(Locale.ROOT)))) {
				return c;
			}
			if (c.getName().equalsIgnoreCase(r)) {
				byName.add(c);
			}
		}
		if (byName.size() == 1) {
			return byName.get(0);
		}
		if (byName.size() > 1) {
			StringBuilder sb = new StringBuilder();
			for (RocketComponent c : byName) {
				sb.append(' ').append(shortId(c)).append(" (").append(c.getComponentName()).append(" in ")
						.append(c.getStage().getName()).append(")");
			}
			throw new ToolException("Several components are named '" + ref + "'; use an id:" + sb);
		}
		// No exact name: a piece of one ("Apex" for "Apex 12\" Drogue Parachute") when only one part has it.
		List<RocketComponent> partial = new ArrayList<>();
		String lower = r.toLowerCase(Locale.ROOT);
		for (RocketComponent c : rocket) {
			if (r.length() >= 3 && c.getName().toLowerCase(Locale.ROOT).contains(lower)) {
				partial.add(c);
			}
		}
		if (partial.size() == 1) {
			return partial.get(0);
		}
		if (partial.size() > 1) {
			StringBuilder sb = new StringBuilder();
			for (RocketComponent c : partial.subList(0, Math.min(8, partial.size()))) {
				sb.append(sb.length() > 0 ? ", " : " ").append(c.getName()).append(" [").append(shortId(c)).append(']');
			}
			throw new ToolException("'" + ref + "' matches several components:" + sb + (partial.size() > 8 ? ", ..." : "")
					+ ". Use a full name or an id.");
		}
		throw new ToolException("No component '" + ref + "'. Use get_design to list component ids.");
	}

	public static <T> T find(Rocket rocket, String ref, Class<T> type, String what) {
		RocketComponent c = find(rocket, ref);
		if (!type.isInstance(c)) {
			throw new ToolException("'" + c.getName() + "' is a " + c.getComponentName() + ", not a " + what + ".");
		}
		return type.cast(c);
	}

	/** Resolves a flight configuration by id prefix, index or name; defaults to the selected one. */
	public static FlightConfiguration config(Rocket rocket, String ref) {
		if (ref == null || ref.isBlank()) {
			return rocket.getSelectedConfiguration();
		}
		List<FlightConfigurationId> ids = rocket.getIds();
		for (FlightConfigurationId id : ids) {
			FlightConfiguration fc = rocket.getFlightConfiguration(id);
			if (id.toString().toLowerCase(Locale.ROOT).startsWith(ref.toLowerCase(Locale.ROOT))
					|| fc.getName().equalsIgnoreCase(ref)) {
				return fc;
			}
		}
		try {
			int idx = Integer.parseInt(ref.trim());
			if (idx >= 0 && idx < ids.size()) {
				return rocket.getFlightConfiguration(ids.get(idx));
			}
		} catch (NumberFormatException ignored) {
			// not an index
		}
		List<String> names = new ArrayList<>();
		for (FlightConfigurationId id : ids) {
			names.add(id.toString().substring(0, 8) + " \"" + rocket.getFlightConfiguration(id).getName() + "\"");
		}
		throw new ToolException("No flight configuration '" + ref + "'. Available: " + names);
	}

	/**
	 * Compact tree of the rocket for the model: one indented line per component,
	 * "Name [id] Type: key dimensions, mass".
	 */
	public static List<String> tree(RocketComponent root) {
		List<String> out = new ArrayList<>();
		for (RocketComponent c : root.getChildren()) {
			line(c, 0, out);
		}
		return out;
	}

	private static void line(RocketComponent c, int depth, List<String> out) {
		StringBuilder s = new StringBuilder("  ".repeat(depth));
		s.append(c.getName()).append(" [").append(shortId(c)).append("] ").append(c.getClass().getSimpleName());
		List<String> f = new ArrayList<>();
		if (c instanceof AxialStage st) {
			f.add("stage " + st.getStageNumber());
			f.add("mass " + Units.fmt(c.getSectionMass(), Dim.MASS) + " without motors");
		} else {
			try {
				f.add("top " + Units.fmt(c.toAbsolute(Coordinate.NUL)[0].x, Dim.LENGTH));
			} catch (RuntimeException e) {
				// position not resolvable; skip
			}
			if (c.getLength() > 0) {
				f.add("L " + Units.fmt(c.getLength(), Dim.LENGTH));
			}
			if (c instanceof BodyTube bt) {
				f.add("OD " + Units.fmt(bt.getOuterRadius() * 2, Dim.LENGTH));
				f.add("ID " + Units.fmt(bt.getInnerRadius() * 2, Dim.LENGTH));
			} else if (c instanceof SymmetricComponent sc) {
				f.add("D " + Units.fmt(sc.getForeRadius() * 2, Dim.LENGTH) + " -> " + Units.fmt(sc.getAftRadius() * 2, Dim.LENGTH));
			}
			if (c instanceof RecoveryDevice rd) {
				f.add("Cd " + Units.num(rd.getCD()) + ", area " + Units.fmt(rd.getArea(), Dim.AREA));
			}
			if (c instanceof MotorMount mm && mm.isMotorMount()) {
				f.add("motor mount");
			}
			f.add("mass " + Units.fmt(c.getMass(), Dim.MASS) + (c.isMassOverridden() ? " (overridden)" : ""));
		}
		s.append(": ").append(String.join(", ", f));
		out.add(s.toString());
		for (RocketComponent child : c.getChildren()) {
			line(child, depth + 1, out);
		}
	}

	// ---------------------------------------------------------------- reflection-based property access

	/** Guesses the physical dimension of a double property from its name. */
	public static Dim dimOf(String property) {
		String p = property.toLowerCase(Locale.ROOT);
		if (p.contains("mass")) {
			return Dim.MASS;
		}
		// Only explicit angle properties are angles: "sweepAngle", "cantAngle", "baseRotation", "angleOffset".
		// (TrapezoidFinSet "sweep" is the sweep LENGTH.)
		if (p.contains("angle") || p.contains("rotation") || p.contains("direction")) {
			return Dim.ANGLE;
		}
		// "ratio" only as a suffix: "instanceSeparation" and "duration" contain the letters too.
		if (p.equals("cd") || p.contains("coefficient") || p.contains("shapeparameter") || p.contains("count")
				|| p.endsWith("ratio") || p.contains("scale")) {
			return Dim.DIMENSIONLESS;
		}
		if (p.contains("area")) {
			return Dim.AREA;
		}
		if (p.contains("altitude")) {
			return Dim.DISTANCE;
		}
		if (p.contains("delay") || p.contains("time")) {
			return Dim.TIME;
		}
		if (p.contains("radius") || p.contains("diameter") || p.contains("length") || p.contains("thickness")
				|| p.contains("height") || p.contains("span") || p.contains("chord") || p.contains("offset")
				|| p.contains("width") || p.contains("position") || p.contains("overhang") || p.contains("shift")
				|| p.contains("separation") || p.contains("cgx") || p.contains("depth") || p.equals("sweep")) {
			return Dim.LENGTH;
		}
		return Dim.DIMENSIONLESS;
	}

	private static boolean simpleType(Class<?> t) {
		return t == double.class || t == int.class || t == boolean.class || t == String.class || t.isEnum()
				|| t == Double.class || t == Integer.class || t == Boolean.class || t == Material.class;
	}

	/** Editable properties (getter + matching single-argument setter) with current values. */
	public static Map<String, Object> describe(RocketComponent c) {
		Map<String, Object> props = new TreeMap<>();
		for (Method getter : c.getClass().getMethods()) {
			if (getter.getParameterCount() != 0 || Modifier.isStatic(getter.getModifiers())) {
				continue;
			}
			String name = getter.getName();
			String prop;
			if (name.startsWith("get") && name.length() > 3) {
				prop = name.substring(3);
			} else if (name.startsWith("is") && name.length() > 2) {
				prop = name.substring(2);
			} else {
				continue;
			}
			Class<?> type = getter.getReturnType();
			if (!simpleType(type) || HIDDEN.contains(prop) || setter(c, prop, type) == null) {
				continue;
			}
			try {
				Object v = getter.invoke(c);
				props.put(lowerFirst(prop), render(prop, type, v));
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// skip properties that cannot be read in the current state
			}
		}
		return props;
	}

	private static Object render(String prop, Class<?> type, Object v) {
		if (v == null) {
			return null;
		}
		if (type == double.class || type == Double.class) {
			Dim d = dimOf(prop);
			if (d == Dim.DIMENSIONLESS) {
				return Units.num((Double) v);
			}
			return Units.fmt((Double) v, d);
		}
		if (v instanceof Material mat) {
			return mat.getName() + " (" + Units.fmt(mat.getDensity(), densityDim(mat)) + "; set by name, e.g. "
					+ materialExamples(mat.getType()) + ")";
		}
		if (type.isEnum()) {
			Object[] constants = type.getEnumConstants();
			List<String> names = new ArrayList<>();
			for (Object o : constants) {
				names.add(((Enum<?>) o).name());
			}
			return ((Enum<?>) v).name() + " (one of " + String.join(", ", names) + ")";
		}
		return v;
	}

	private static Method setter(RocketComponent c, String prop, Class<?> type) {
		try {
			Method m = c.getClass().getMethod("set" + prop, type);
			return Modifier.isPublic(m.getModifiers()) ? m : null;
		} catch (NoSuchMethodException e) {
			if (type == Double.class) {
				return setter(c, prop, double.class);
			}
			return null;
		}
	}

	private static String lowerFirst(String s) {
		return Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}

	/**
	 * Sets one property by name (case-insensitive, e.g. "length", "outerRadius", "cd", "finCount").
	 * Returns a description of the change.
	 */
	private static final Map<String, String> ALIASES = Map.of(
			"sweeplength", "sweep", "span", "height", "finspan", "height", "semispan", "height",
			"mass", "componentMass", "cd", "cd", "noseshape", "shapeType", "shape", "shapeType");

	public static String set(RocketComponent c, String property, JsonElement value) {
		String prop = property.trim();
		String alias = ALIASES.get(prop.toLowerCase(Locale.ROOT));
		if (alias != null && !hasSetter(c, prop)) {
			prop = alias;
		}
		Method target = null;
		for (Method m : c.getClass().getMethods()) {
			if (m.getName().equalsIgnoreCase("set" + prop) && m.getParameterCount() == 1
					&& simpleType(m.getParameterTypes()[0])) {
				target = m;
				// prefer double over int overloads when the value is not integral
				if (m.getParameterTypes()[0] == double.class) {
					break;
				}
			}
		}
		// Friendly aliases for diameters of radius-based components.
		if (target == null && prop.toLowerCase(Locale.ROOT).endsWith("diameter")) {
			String radiusProp = prop.substring(0, prop.length() - "diameter".length()) + "Radius";
			double d = Units.toSi(value.isJsonPrimitive() && ((JsonPrimitive) value).isNumber()
					? (Object) value.getAsDouble() : value.getAsString(), Dim.LENGTH);
			return set(c, radiusProp.isEmpty() || radiusProp.equals("Radius") ? "radius" : radiusProp,
					new JsonPrimitive(d / 2)) + " (from diameter " + Units.fmt(d, Dim.LENGTH) + ")";
		}
		if (target == null) {
			throw new ToolException("'" + c.getName() + "' (" + c.getClass().getSimpleName() + ") has no settable property '"
					+ property + "'. Use describe_component to list properties.");
		}
		Class<?> type = target.getParameterTypes()[0];
		String canonical = target.getName().substring(3);
		Object arg;
		try {
			if (type == double.class || type == Double.class) {
				Dim d = dimOf(canonical);
				double v = value.isJsonPrimitive() && ((JsonPrimitive) value).isNumber()
						? value.getAsDouble()
						: Units.toSi(value.getAsString(), d);
				arg = physical(c, canonical, d, v);
			} else if (type == int.class || type == Integer.class) {
				double v;
				try {
					v = value.getAsDouble();
				} catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
					v = Double.NaN;
				}
				if (!(v == Math.rint(v)) || v < 0 || v > 10_000) {
					throw new ToolException(spaced(canonical) + " is a count: give a whole number such as 3 or 4, in digits (got "
							+ value + ").");
				}
				arg = (int) v;
			} else if (type == boolean.class || type == Boolean.class) {
				String b = value.getAsString().trim().toLowerCase(Locale.ROOT);
				if (!b.equals("true") && !b.equals("false")) {
					throw new ToolException(canonical + " is true or false (got " + value + ").");
				}
				arg = Boolean.parseBoolean(b);
			} else if (type == Material.class) {
				Object current = getRaw(c, canonical);
				Material.Type mt = current instanceof Material m ? m.getType() : Material.Type.BULK;
				arg = material(c, mt, value.getAsString());
			} else if (type.isEnum()) {
				arg = enumValue(type, value.getAsString());
			} else {
				arg = value.getAsString();
			}
			target.invoke(c, arg);
		} catch (UnsupportedOperationException | IllegalStateException e) {
			throw new ToolException("Property '" + canonical + "' needs " + (type == double.class ? "a number or a quantity "
					+ "with units" : "a single value") + ", got " + value + ".");
		} catch (IllegalArgumentException e) {
			throw new ToolException("Property '" + canonical + "': " + e.getMessage());
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new ToolException("OpenRocket rejected " + canonical + " = " + value + ": " + cause.getMessage());
		} catch (ReflectiveOperationException e) {
			throw new ToolException("Could not set " + canonical + ": " + e.getMessage());
		}
		Object shown = arg instanceof Double dv ? render(canonical, double.class, dv)
				: arg instanceof Material m ? m.getName() : arg;
		// OpenRocket clamps some values to what the part allows (an inner radius to the outer, a length to zero):
		// say what it kept rather than echo what was asked.
		if (arg instanceof Double dv) {
			Object kept = readBack(c, canonical);
			if (kept instanceof Double k && Math.abs(k - dv) > 1e-9 * Math.max(1, Math.abs(dv))) {
				return c.getName() + "." + lowerFirst(canonical) + " = " + render(canonical, double.class, k) + " (asked for "
						+ shown + "; OpenRocket keeps it within what the part allows)";
			}
		} else if (arg instanceof Integer iv && readBack(c, canonical) instanceof Integer k && !k.equals(iv)) {
			return c.getName() + "." + lowerFirst(canonical) + " = " + k + " (asked for " + iv
					+ "; OpenRocket keeps it within what the part allows)";
		}
		return c.getName() + "." + lowerFirst(canonical) + " = " + shown;
	}

	private static Database<Material> materials(Material.Type t) {
		return switch (t) {
			case SURFACE -> Databases.SURFACE_MATERIAL;
			case LINE -> Databases.LINE_MATERIAL;
			default -> Databases.BULK_MATERIAL;
		};
	}

	private static Dim densityDim(Material m) {
		return m.getType() == Material.Type.BULK ? Dim.DENSITY : Dim.DIMENSIONLESS;
	}

	private static String materialExamples(Material.Type t) {
		return switch (t) {
			case BULK -> "Fiberglass, Carbon fiber, Blue tube, Aluminum, Plywood (birch), PLA - 100% infill";
			case SURFACE -> "Ripstop nylon";
			default -> "Tubular nylon";
		};
	}

	/**
	 * Material by (case-insensitive, partial) name: OpenRocket's material database first, then the materials already
	 * used in the design (custom materials carried in the file, common in older team designs).
	 */
	static Material material(RocketComponent c, Material.Type t, String name) {
		String n = name.trim().toLowerCase(Locale.ROOT);
		Material partial = null;
		List<Material> known = new ArrayList<>();
		materials(t).forEach(known::add);
		for (Material m : used(c.getRoot(), t)) {
			if (known.stream().noneMatch(k -> k.getName().equalsIgnoreCase(m.getName()))) {
				known.add(m);
			}
		}
		for (Material m : known) {
			String mn = m.getName().toLowerCase(Locale.ROOT);
			if (mn.equals(n)) {
				return m;
			}
			if (partial == null && n.length() >= 2 && mn.contains(n)) { // one letter would match almost anything
				partial = m;
			}
		}
		if (partial != null) {
			return partial;
		}
		List<String> names = new ArrayList<>();
		for (Material m : known) {
			names.add(m.getName());
		}
		throw new IllegalArgumentException("unknown " + t.name().toLowerCase(Locale.ROOT) + " material '" + name + "'. Known: " + names);
	}

	/** Materials of type t used anywhere in the rocket (component materials, parachute canopies and lines, cords). */
	static List<Material> used(RocketComponent root, Material.Type t) {
		List<Material> out = new ArrayList<>();
		for (RocketComponent c : root) {
			for (String getter : new String[] { "getMaterial", "getLineMaterial" }) {
				try {
					Object m = c.getClass().getMethod(getter).invoke(c);
					if (m instanceof Material mat && mat.getType() == t && !out.contains(mat)) {
						out.add(mat);
					}
				} catch (ReflectiveOperationException e) {
					// this component has no such material
				}
			}
		}
		return out;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object enumValue(Class<?> type, String name) {
		for (Object o : type.getEnumConstants()) {
			if (((Enum) o).name().equalsIgnoreCase(name.trim())) {
				return o;
			}
		}
		List<String> names = new ArrayList<>();
		for (Object o : type.getEnumConstants()) {
			names.add(((Enum<?>) o).name());
		}
		throw new IllegalArgumentException("'" + name + "' is not one of " + names);
	}

	private static boolean hasSetter(RocketComponent c, String prop) {
		for (Method m : c.getClass().getMethods()) {
			if (m.getName().equalsIgnoreCase("set" + prop) && m.getParameterCount() == 1) {
				return true;
			}
		}
		return false;
	}

	/** Raw (SI) value of a property, for exact restore after temporary edits. */
	/**
	 * Refuses values no part can have: non-finite numbers, and negative sizes, masses, densities, areas or drag
	 * coefficients. Positions, offsets, angles, sweep and motor overhang may be negative.
	 */
	public static double physical(RocketComponent c, String property, Dim d, double v) {
		if (!Double.isFinite(v)) {
			throw new ToolException(property + " must be a finite number (got " + v + ").");
		}
		String p = property.toLowerCase(Locale.ROOT);
		boolean signed = p.contains("offset") || p.contains("position") || p.contains("shift") || p.contains("overhang")
				|| p.equals("sweep") || p.contains("cgx") || d == Dim.ANGLE || d == Dim.DISTANCE;
		boolean sized = d == Dim.LENGTH || d == Dim.MASS || d == Dim.AREA || d == Dim.DENSITY || d == Dim.TIME
				|| p.equals("cd");
		if (sized && !signed && v < 0) {
			throw new ToolException(c.getName() + ": " + property + " cannot be negative (got " + v + " " + d.si + ").");
		}
		if (v == 0 && mustExist(c, p)) {
			throw new ToolException(c.getName() + ": " + property + " cannot be zero (a part needs a real size to fly; "
					+ "remove the part to take it out of the rocket).");
		}
		if (d == Dim.LENGTH && Math.abs(v) > 1000 || d == Dim.MASS && v > 1e5 || p.equals("cd") && v > 10) {
			throw new ToolException(c.getName() + ": " + property + " = " + v + " " + d.si
					+ " is far beyond any rocket part; check the value and its unit.");
		}
		return v;
	}

	/** Sizes a part cannot do without: a fin needs thickness, chord and span; a tube a length; a parachute area and Cd. */
	private static boolean mustExist(RocketComponent c, String p) {
		if (c instanceof FinSet) {
			return p.equals("thickness") || p.equals("rootchord") || p.equals("height");
		}
		if (c instanceof SymmetricComponent
				|| c instanceof RingComponent) {
			return p.equals("length") || p.equals("outerradius") || p.equals("aftradius") && c instanceof BodyTube;
		}
		if (c instanceof RecoveryDevice) {
			return p.equals("cd") || p.equals("diameter") || p.equals("striplength") || p.equals("stripwidth");
		}
		return false;
	}

	private static Object readBack(RocketComponent c, String property) {
		try {
			return getRaw(c, property);
		} catch (ToolException e) {
			return null;
		}
	}

	public static Object getRaw(RocketComponent c, String property) {
		for (Method m : c.getClass().getMethods()) {
			String n = m.getName();
			if (m.getParameterCount() == 0 && (n.equalsIgnoreCase("get" + property) || n.equalsIgnoreCase("is" + property))) {
				try {
					return m.invoke(c);
				} catch (ReflectiveOperationException e) {
					throw new ToolException("Cannot read " + property + ": " + e.getMessage());
				}
			}
		}
		throw new ToolException("'" + c.getName() + "' has no property '" + property + "'. Use describe_component.");
	}

	/** Sets a property from a raw value previously read with {@link #getRaw}. */
	public static void setRaw(RocketComponent c, String property, Object value) {
		for (Method m : c.getClass().getMethods()) {
			if (m.getName().equalsIgnoreCase("set" + property) && m.getParameterCount() == 1
					&& (value == null || wrap(m.getParameterTypes()[0]).isInstance(value))) {
				try {
					m.invoke(c, value);
					return;
				} catch (ReflectiveOperationException e) {
					throw new ToolException("Cannot restore " + property + ": " + e.getMessage());
				}
			}
		}
	}

	private static Class<?> wrap(Class<?> t) {
		if (t == double.class) {
			return Double.class;
		}
		if (t == int.class) {
			return Integer.class;
		}
		if (t == boolean.class) {
			return Boolean.class;
		}
		return t;
	}

	/**
	 * Usable interior volume of a hollow airframe component: body tubes (inner cylinder over {@code length}, or the
	 * whole tube when NaN), nose cones and transitions (integrated wall-offset profile plus the aft shoulder).
	 */
	public static double interiorVolume(RocketComponent c, double length) {
		if (c instanceof BodyTube bt) {
			double r = bt.getInnerRadius();
			return Math.PI * r * r * (Double.isNaN(length) ? bt.getLength() : length);
		}
		if (c instanceof Transition t) {
			if (t.isFilled()) {
				return 0;
			}
			int n = 400;
			double len = t.getLength(), th = t.getThickness(), v = 0;
			for (int i = 0; i < n; i++) {
				double x = (i + 0.5) * len / n;
				double ri = Math.max(0, t.getRadius(x) - th);
				v += Math.PI * ri * ri * len / n;
			}
			double sr = t.getAftShoulderRadius() - t.getAftShoulderThickness();
			if (t.getAftShoulderLength() > 0 && sr > 0) {
				v += Math.PI * sr * sr * t.getAftShoulderLength();
			}
			return v;
		}
		throw new ToolException("'" + c.getName() + "' is a " + c.getComponentName()
				+ "; interior volume is available for body tubes, nose cones and transitions.");
	}

	/** Instantiates a component type by simple class name. */
	public static RocketComponent create(String type) {
		String match = null;
		for (String t : CREATABLE) {
			if (t.equalsIgnoreCase(type.trim())) {
				match = t;
			}
		}
		if (match == null) {
			throw new ToolException("Unknown component type '" + type + "'. Types: " + CREATABLE);
		}
		try {
			Class<?> cls = Class.forName("info.openrocket.core.rocketcomponent." + match);
			Constructor<?> ctor = cls.getConstructor();
			return (RocketComponent) ctor.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new ToolException("Cannot create " + match + ": " + e.getMessage());
		}
	}

	/** A property's name as words: FinCount becomes "Fin count". */
	static String spaced(String canonical) {
		String w = canonical.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
		return w.isEmpty() ? w : w.substring(0, 1) + w.substring(1).toLowerCase(Locale.ROOT);
	}
}
