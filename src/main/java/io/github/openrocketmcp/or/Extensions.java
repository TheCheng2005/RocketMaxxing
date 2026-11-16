package io.github.openrocketmcp.or;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.extension.SimulationExtension;
import info.openrocket.core.simulation.extension.SimulationExtensionProvider;
import info.openrocket.core.simulation.extension.impl.ScriptingExtension;
import info.openrocket.core.simulation.extension.impl.ScriptingUtil;
import info.openrocket.core.startup.Application;
import info.openrocket.core.util.Config;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * OpenRocket's simulation extensions, set up per simulation as in its Extensions tab: air-start, roll control,
 * damping moment, stop the simulation, save every step as CSV, a script run during the flight (JavaScript), or a Java
 * class. A design's extensions run whenever its simulation flies. A script runs as code on this computer, so one
 * from a file stays off until a person turns it on, as OpenRocket asks.
 */
public final class Extensions {
	private Extensions() {
	}

	/** One setting of a known extension: its key, what it is, its dimension ("rate" for angular rates). */
	record Field(String key, String label, String help, Object dim) {
	}

	record Kind(String id, String name, String about, List<Field> fields) {
	}

	static final String EX = "info.openrocket.core.simulation.extension.example.", IMPL = "info.openrocket.core.simulation.extension.impl.";
	static final String RATE = "rate";

	static final List<Kind> KNOWN = List.of(
			new Kind(EX + "AirStart", "Air-start", "Starts the flight in the air: the rocket is placed at an altitude with an upward speed "
					+ "when the motor lights, as for an upper stage lit after a coast.", List.of(
							new Field("launchAltitude", "Start altitude", "Height above the launch site where the flight starts.", Dim.DISTANCE),
							new Field("launchVelocity", "Start speed", "Upward speed at the start.", Dim.VELOCITY))),
			new Kind(EX + "RollControl", "Roll control", "Active fins that hold the roll rate at a set point with a PI controller, "
					+ "turning the named fin set's cant angle.", List.of(
							new Field("controlFinName", "Control fins", "The fin set that turns (its name in the design).", null),
							new Field("startTime", "Start time", "When the controller switches on.", Dim.TIME),
							new Field("setPoint", "Roll rate wanted", "The roll rate to hold.", RATE),
							new Field("finRate", "Fin turn rate", "How fast the fins can turn.", RATE),
							new Field("maxFinAngle", "Largest fin angle", "The most the fins turn either way.", Dim.ANGLE),
							new Field("KP", "Proportional gain", "P gain of the controller.", Dim.DIMENSIONLESS),
							new Field("KI", "Integral gain", "I gain of the controller.", Dim.DIMENSIONLESS))),
			new Kind(EX + "DampingMoment", "Damping moment", "Adds the pitch damping moment coefficient to the flight data, to plot "
					+ "how well the rocket damps out a disturbance.", List.of()),
			new Kind(EX + "StopSimulation", "Stop the simulation", "Ends the simulation at a time or after a number of steps "
					+ "(0 = no limit); for a quick look at the boost.", List.of(
							new Field("stopTime", "Stop at time (s)", "Seconds after launch; 0 for no limit.", Dim.DIMENSIONLESS),
							new Field("stopStep", "Stop at step", "Number of steps; 0 for no limit.", Dim.DIMENSIONLESS),
							new Field("reportRate", "Report every", "Steps between progress reports.", Dim.DIMENSIONLESS))),
			new Kind(EX + "CSVSave", "Save every step as CSV", "Writes each step of the flight to simulation-NNN.csv in the folder "
					+ "the program was started from. Flights > CSV (export_flight_data) writes the same where you choose.",
					List.of()),
			new Kind(IMPL + "ScriptingExtension", "Script", "Your own code run during the flight (JavaScript): define any of "
					+ "OpenRocket's listener functions (startSimulation, preStep, postStep, preWindModel, postAerodynamicCalculation, "
					+ "handleFlightEvent, recoveryDeviceDeployment, motorIgnition, endSimulation...) and change the flight as it "
					+ "is computed.", List.of(
							new Field("language", "Language", "JavaScript.", null),
							new Field("script", "Script", "The code.", null),
							new Field("enabled", "On", "Whether it runs. A script from a file is off until a person turns it on.", null))),
			new Kind(IMPL + "JavaCode", "Java class", "A simulation listener class of your own, by its full class name, from a jar "
					+ "on OpenRocket's class path.", List.of(new Field("className", "Class", "The listener's full class name.", null))));

	/** OpenRocket's providers of extensions (one may make several kinds). */
	static Set<SimulationExtensionProvider> providers() {
		OrRuntime.init();
		return Application.getInjector().getInstance(Key.get(new TypeLiteral<Set<SimulationExtensionProvider>>() {
		}));
	}

	static Kind kind(String id) {
		for (Kind k : KNOWN) {
			if (k.id().equals(id)) {
				return k;
			}
		}
		return null;
	}

	/** The kinds that can be added: the known ones first, then any other OpenRocket finds. */
	public static List<Map<String, Object>> kinds() {
		List<Map<String, Object>> out = new ArrayList<>();
		Set<String> seen = new TreeSet<>();
		for (Kind k : KNOWN) {
			seen.add(k.id());
			if (k.id().endsWith(".CSVSave")) {
				// It writes simulation-NNN.csv into whatever folder the program was started from, where nobody looks:
				// export_flight_data (Flights > CSV) writes the same where it is asked. A design that has it still runs it.
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("type", short_(k.id()));
			m.put("name", k.name());
			m.put("about", k.about());
			List<Map<String, Object>> fs = new ArrayList<>();
			for (Field f : k.fields()) {
				fs.add(Map.of("key", f.key(), "label", f.label(), "help", f.help(), "unit", unitName(f)));
			}
			m.put("settings", fs);
			out.add(m);
			seen.add(k.id());
		}
		for (SimulationExtensionProvider p : providers()) {
			for (String id : p.getIds()) {
				if (id.endsWith(".PrintSimulation")) {
					continue; // OpenRocket's example that prints to its console: nothing to see here
				}
				if (seen.add(id)) {
					out.add(Map.of("type", id, "name", String.join(" › ", p.getName(id)), "about", "", "settings", List.of()));
				}
			}
		}
		return out;
	}

	static String short_(String id) {
		return id.substring(id.lastIndexOf('.') + 1);
	}

	private static String unitName(Field f) {
		if (f.dim() == RATE) {
			return "deg/s";
		}
		if (f.dim() instanceof Dim d && d != Dim.DIMENSIONLESS) {
			return Units.system() == io.github.openrocketmcp.units.UnitSystem.IMPERIAL ? d.imperial : d.metric;
		}
		return "";
	}

	/** A new extension of a kind, named by its short or full id, or its name ("air-start"). */
	public static SimulationExtension create(String type) {
		String want = type.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		for (SimulationExtensionProvider p : providers()) {
			for (String id : p.getIds()) {
				Kind k = kind(id);
				List<String> names = new ArrayList<>(List.of(id, short_(id)));
				if (k != null) {
					names.add(k.name());
				}
				names.addAll(p.getName(id));
				for (String n : names) {
					if (n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").equals(want)) {
						return p.getInstance(id);
					}
				}
			}
		}
		List<String> names = new ArrayList<>();
		KNOWN.forEach(k -> names.add(k.name() + " (" + short_(k.id()) + ")"));
		throw new ToolException("No extension \"" + type + "\". Known: " + String.join(", ", names) + ".");
	}

	/** The extension's settings as stored, for telling a change apart. */
	public static String configText(SimulationExtension e) {
		Config c = e.getConfig();
		StringBuilder b = new StringBuilder();
		for (String k : new TreeSet<>(c.keySet())) {
			b.append(k).append('=').append(c.get(k, null)).append(';');
		}
		return b.toString();
	}

	/** One extension with its settings shown in the team's units. */
	public static Map<String, Object> describe(SimulationExtension e, int index) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("index", index);
		m.put("type", short_(e.getId()));
		Kind k = kind(e.getId());
		m.put("name", k != null ? k.name() : e.getName());
		if (k != null) {
			m.put("about", k.about());
		}
		Map<String, Object> settings = new LinkedHashMap<>();
		for (Method g : e.getClass().getMethods()) {
			String prop = property(g);
			if (prop == null || setter(e, prop, g.getReturnType()) == null) {
				continue;
			}
			try {
				Object v = g.invoke(e);
				Field f = field(k, prop);
				settings.put(prop, shown(v, f));
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// unreadable now
			}
		}
		m.put("settings", settings);
		if (e instanceof ScriptingExtension s && !s.isEnabled()) {
			m.put("off", "This script does not run until a person turns it on (it runs as code on this computer).");
		}
		return m;
	}

	private static String property(Method g) {
		if (g.getParameterCount() != 0 || Modifier.isStatic(g.getModifiers()) || g.getDeclaringClass() == Object.class) {
			return null;
		}
		Class<?> t = g.getReturnType();
		if (!(t == double.class || t == int.class || t == boolean.class || t == String.class)) {
			return null;
		}
		String n = g.getName();
		String p = n.startsWith("get") ? n.substring(3) : n.startsWith("is") ? n.substring(2) : null;
		if (p == null || p.isEmpty() || p.equals("Id") || p.equals("Name") || p.equals("Description")) {
			return null;
		}
		return p.length() > 1 && Character.isUpperCase(p.charAt(1)) ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1);
	}

	private static Method setter(Object o, String prop, Class<?> type) {
		try {
			return o.getClass().getMethod("set" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1), type);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	private static Field field(Kind k, String prop) {
		if (k != null) {
			for (Field f : k.fields()) {
				if (f.key().equalsIgnoreCase(prop)) {
					return f;
				}
			}
		}
		return null;
	}

	private static Object shown(Object v, Field f) {
		if (v instanceof Double d && f != null) {
			if (f.dim() == RATE) {
				return Units.num(Math.toDegrees(d)) + " deg/s";
			}
			if (f.dim() instanceof Dim dim && dim != Dim.DIMENSIONLESS) {
				return Units.fmt(d, dim);
			}
			return Units.num(d);
		}
		return v instanceof Double d ? Units.num(d) : v;
	}

	/** Sets one setting from what was typed; units as anywhere else, angular rates in deg/s or rad/s. */
	public static void set(SimulationExtension e, String key, JsonElement value) {
		String prop = null;
		Method get = null;
		for (Method g : e.getClass().getMethods()) {
			String p = property(g);
			if (p != null && p.equalsIgnoreCase(key)) {
				prop = p;
				get = g;
			}
		}
		if (prop == null) {
			throw new ToolException(e.getName() + " has no setting \"" + key + "\". Settings: " + describe(e, 0).get("settings"));
		}
		Class<?> t = get.getReturnType();
		Method set = setter(e, prop, t);
		Field f = field(kind(e.getId()), prop);
		try {
			Object v;
			if (t == boolean.class) {
				v = value.getAsBoolean();
			} else if (t == String.class) {
				v = value.getAsString();
			} else if (t == int.class) {
				v = (int) Math.round(Double.parseDouble(value.getAsString().trim()));
			} else if (f != null && f.dim() == RATE) {
				String s = value.getAsString().trim().toLowerCase(Locale.ROOT).replace(" ", "");
				if (s.endsWith("rad/s")) {
					v = Double.parseDouble(s.substring(0, s.length() - 5));
				} else if (s.endsWith("rpm")) {
					v = Double.parseDouble(s.substring(0, s.length() - 3)) * 2 * Math.PI / 60;
				} else {
					v = Math.toRadians(Double.parseDouble(s.replace("deg/s", "").replace("°/s", "")));
				}
			} else if (f != null && f.dim() instanceof Dim dim) {
				v = Units.toSi(value.getAsString(), dim);
			} else {
				v = Double.parseDouble(value.getAsString().trim());
			}
			set.invoke(e, v);
		} catch (NumberFormatException ex) {
			throw new ToolException("\"" + value + "\" is not a number for " + prop + (f != null && f.dim() == RATE ? " (e.g. 20 deg/s)" : "") + ".");
		} catch (ReflectiveOperationException ex) {
			throw new ToolException("OpenRocket refused " + prop + " = " + value + " (" + ex.getCause() + ").");
		}
	}

	/** Turns a script on (or off), marking it trusted on this computer as OpenRocket does. */
	public static void trust(ScriptingExtension s, boolean on) {
		OrRuntime.init();
		ScriptingUtil util = Application.getInjector().getInstance(ScriptingUtil.class);
		util.setTrustedScript(s.getLanguage(), s.getScript(), on);
		s.setEnabled(on);
	}

	public static List<SimulationExtension> of(Simulation sim) {
		return sim.getSimulationExtensions();
	}
}
