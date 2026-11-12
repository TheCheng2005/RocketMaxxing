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
import info.openrocket.core.database.Databases;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.material.Material;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.Rocket;
import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Branch;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Motors;
import io.github.openrocketmcp.or.OrRuntime;
import io.github.openrocketmcp.or.Targets;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.UnitSystem;
import io.github.openrocketmcp.units.Units;

/**
 * Tools only the desktop app registers. Reads return plain numbers in SI (the app formats them in the team's units);
 * edits go through the same property code as the chat tools and get undo like them. The app also calls the ordinary
 * tools (open_design, add_component, set_motor, undo, save_design...) directly.
 */
public final class AppTools {
	private AppTools() {
	}

	static final String DESIGN = "Design id.";

	/** Units the app offers for each quantity, the team's usual one first. */
	static final Map<Dim, String[]> CHOICES = new LinkedHashMap<>();

	static {
		CHOICES.put(Dim.LENGTH, new String[] { "mm", "cm", "m", "in", "ft" });
		CHOICES.put(Dim.DISTANCE, new String[] { "m", "km", "ft", "mi" });
		CHOICES.put(Dim.VELOCITY, new String[] { "m/s", "km/h", "ft/s", "mph" });
		CHOICES.put(Dim.ACCELERATION, new String[] { "m/s2", "ft/s2", "G" });
		CHOICES.put(Dim.MASS, new String[] { "g", "kg", "oz", "lb" });
		CHOICES.put(Dim.FORCE, new String[] { "N", "lbf", "kgf" });
		CHOICES.put(Dim.AREA, new String[] { "cm2", "m2", "in2", "ft2" });
		CHOICES.put(Dim.VOLUME, new String[] { "cm3", "L", "in3" });
		CHOICES.put(Dim.PRESSURE, new String[] { "kPa", "bar", "psi" });
		CHOICES.put(Dim.DENSITY, new String[] { "kg/m3", "g/cm3", "lb/ft3" });
		CHOICES.put(Dim.TIME, new String[] { "s", "ms", "min" });
		CHOICES.put(Dim.ANGLE, new String[] { "deg", "rad" });
		CHOICES.put(Dim.IMPULSE, new String[] { "Ns", "lbf-s" });
		CHOICES.put(Dim.TEMPERATURE, new String[] { "C", "F", "K" });
	}

	/** Registers a tool whose result has no NaN or infinity: JSON has neither, and the page could not read it. */
	static void add(McpServer s, ToolDef t) {
		s.tool(t.withHandler(args -> clean(t.handler().call(args))));
	}

	public static void register(McpServer s, Context ctx) {
		register(s, ctx, Assistant.defaultSettings());
	}

	/** As above, with the assistant's settings (on/off, model, the key) kept in {@code assistantSettings}. */
	public static void register(McpServer s, Context ctx, Path assistantSettings) {
		Assistant assistant = new Assistant(s, ctx, assistantSettings);
		// What the interface remembers between launches, next to the assistant's settings (~/.rocketmaxxing).
		UiPrefs prefs = new UiPrefs(assistantSettings.resolveSibling("ui-prefs.json"));
		Path updateCache = assistantSettings.resolveSibling("update-check.json");
		add(s, new ToolDef("app_update_check", "App: check for a newer version",
				"Whether a newer RocketMaxxing is on the Releases page: the latest version, its page and the installer for "
						+ "this computer. Asks GitHub at most once a day unless force is true; offline it says so.",
				Schema.object().bool("force", "Ask GitHub now rather than use today's answer (default false).", false).build(), true,
				a -> Updates.check(App.VERSION, updateCache, a.bool("force", false))));
		add(s, new ToolDef("app_prefs", "App: interface preferences",
				"What the interface remembers between launches (recent designs, colour scheme, units, panels, tours seen): "
						+ "returns them all; with set, changes some first (null removes one).",
				Schema.object().obj("set", "Preference name -> new value (string) or null.", false).build(), false,
				a -> Map.of("prefs", a.has("set") ? prefs.set(a.get("set").getAsJsonObject()) : prefs.all())));
		// The assistant's own tools are read-only as far as undo goes: the tools Claude calls record their own edits.
		add(s, new ToolDef("app_assistant", "App: assistant settings", "Whether the assistant is on, its model and whether a key is set.",
				Schema.object().build(), true, a -> assistant.settings()));
		add(s, new ToolDef("app_assistant_settings", "App: change assistant settings",
				"Turn the assistant on or off, choose how it connects (api, claude-code, codex) and its model, save or forget "
						+ "the API key (kept in the user's home folder).",
				Schema.object().bool("enabled", "On or off.", false).str("provider", "api, claude-code or codex.", false)
						.str("model", "Model id (API).", false).str("claudeCodeModel", "Claude Code model alias, or empty.", false)
						.str("codexModel", "Codex model, or empty for Codex's own.", false)
						.str("key", "Anthropic API key to save.", false).bool("forgetKey", "Forget the saved key.", false).build(),
				true, a -> assistant.configure(a.has("enabled") ? a.bool("enabled", false) : null, a.str("provider", null),
						a.str("model", null), a.str("key", null), a.bool("forgetKey", false), a.str("claudeCodeModel", null),
						a.str("codexModel", null))));
		add(s, new ToolDef("app_assistant_connections", "App: Claude Code and Codex on this computer",
				"Whether Claude Code and Codex are installed and signed in.", Schema.object().build(), true,
				a -> Map.of("connections", Assistant.connections())));
		add(s, new ToolDef("app_assistant_send", "App: message the assistant",
				"Send a message; Claude works in the background with the engine's tools (poll for what it does).",
				Schema.object().str("text", "The message.", true).str("designId", DESIGN, false).build(), true, a -> {
					Designs.Design d = a.has("designId") ? ctx.designs.get(a.str("designId")) : null;
					assistant.send(a.str("text"), Assistant.context(d));
					return Map.of("sent", true);
				}));
		add(s, new ToolDef("app_assistant_poll", "App: what the assistant did",
				"Events since index from (messages, tool calls with their results), and whether it is still working.",
				Schema.object().integer("from", "First event index.", false).build(), true,
				a -> assistant.poll(a.integer("from", 0))));
		add(s, new ToolDef("app_assistant_stop", "App: stop the assistant", "Stop after the current step.",
				Schema.object().build(), true, a -> {
					assistant.stop();
					return Map.of("stopping", true);
				}));
		add(s, new ToolDef("app_assistant_reset", "App: new conversation", "Forget the conversation.", Schema.object().build(),
				true, a -> {
					assistant.reset();
					return Map.of("reset", true);
				}));
		add(s, new ToolDef("app_session", "App: session", "Examples, open designs, units and materials for the desktop app.",
				Schema.object().build(), true, a -> session(ctx)));
		add(s, new ToolDef("app_design", "App: design", "The component tree, configurations and stability of a design.",
				Schema.object().str("designId", DESIGN, true).build(), true, a -> AppDesign.design(ctx, a)));
		add(s, new ToolDef("app_component", "App: component editor", "One component's editor with its current values.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true).build(), true,
				a -> AppDesign.component(ctx, a)));
		add(s, new ToolDef("app_edit", "App: edit a field", "Set one field of a component's editor.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.str("key", "Field key.", true).prop("value", new JsonObject(), true).build(),
				false, a -> AppDesign.edit(ctx, a)));
		add(s, new ToolDef("app_move", "App: move a component",
				"Move a component up or down among its siblings, or into another part (parent, at index).",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.integer("by", "-1 up, +1 down.", false).str("parent", "Part to move it into (id, or rocket).", false)
						.integer("index", "Place among the new parent's parts, counted before the move (default the end).", false)
						.build(),
				false, a -> AppDesign.move(ctx, a)));
		add(s, new ToolDef("app_duplicate", "App: duplicate a component",
				"Copy a component (with its parts) right after it, or into another part (parent, at index): paste.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.str("parent", "Part to put the copy in (id, or rocket).", false)
						.integer("index", "Place among that part's parts (default the end).", false).build(), false,
				a -> AppDesign.duplicate(ctx, a)));
		// Read-only as far as undo goes: choosing which configuration to look at is not an edit of the design.
		add(s, new ToolDef("app_select_configuration", "App: select configuration", "Make a flight configuration the active one.",
				Schema.object().str("designId", DESIGN, true).str("configuration", "Configuration id.", true).build(), true,
				a -> {
					Designs.Design d = ctx.designs.get(a.str("designId"));
					Rocket r = d.doc.getRocket();
					r.setSelectedConfiguration(Components.config(r, a.str("configuration")).getId());
					return Map.of("selected", r.getSelectedConfiguration().getId().toString());
				}));
		add(s, new ToolDef("app_standards", "App: team standards",
				"The team standards file's own values, the values in use, the built-in defaults and the rule sets.",
				Schema.object().build(), true, a -> AppTeam.standards(ctx)));
		// Read-only as far as undo goes: standards live in the team file, not in the design.
		add(s, new ToolDef("app_set_standards", "App: change team standards",
				"Merge a change into the team standards file ({\"recovery\": {\"harnessSafetyFactor\": 2.5}}); null returns a value "
						+ "to its default. Only the team's own values are written.",
				Schema.object().obj("patch", "The change.", true).build(), true, a -> AppTeam.setStandards(ctx, a.obj("patch").raw())));
		// Read-only as far as undo goes: targets live in the team standards file, not in the design.
		add(s, new ToolDef("app_set_targets", "App: set the team's targets",
				"Set or clear target limits ({\"apogee\": {\"min\": \"9500 ft\", \"goal\": null}}) and write them to the team "
						+ "standards file, keeping the rest of that file as the team wrote it.",
				Schema.object().obj("targets", "Targets to change; null clears a limit.", true).build(), true,
				a -> AppTeam.setTargets(ctx, a.obj("targets").raw())));
		add(s, new ToolDef("app_flight_log", "App: flight log against the simulation",
				"compare_flight with both altitude curves on one time grid and the numbers in SI, for the Flight data page.",
				Schema.object().str("designId", DESIGN, true).str("path", "Altimeter CSV.", true)
						.str("altitudeUnit", "ft or m when the header does not say.", false)
						.str("configuration", "Configuration id (default the active one).", false)
						.qty("windSpeed", "The day's wind.", false).qty("launchAngle", "Rail angle from vertical.", false)
						.qty("launchSiteAltitude", "Site altitude above sea level.", false).qty("temperature", "Ground temperature.", false)
						.build(),
				true, a -> AppTeam.flightLog(ctx, a)));
		add(s, new ToolDef("app_library", "App: past designs and flights",
				"design_library's scan of a folder, in SI: every design and every altimeter log with the design it belongs to.",
				Schema.object().str("folder", "Folder (default the designs folder).", false)
						.bool("simulate", "Simulate designs without saved results (default true).", false).build(),
				true, a -> AppTeam.library(ctx, a)));
		add(s, new ToolDef("app_fin_points", "App: fin outline", "A fin set's outline points (x along the root, y outwards), in SI.",
				Schema.object().str("designId", DESIGN, true).str("component", "Fin set id.", true).build(), true,
				a -> AppDesign.finPoints(ctx, a)));
		add(s, new ToolDef("app_set_fin_points", "App: set freeform fin points",
				"Replace a freeform fin set's outline: points from the root's leading edge (0, 0) to its trailing edge (x, 0), in SI.",
				Schema.object().str("designId", DESIGN, true).str("component", "Freeform fin set id.", true)
						.array("points", "[[x, y], ...] in m.",
								JsonParser.parseString("{\"type\": \"array\", \"items\": {\"type\": \"number\"}}").getAsJsonObject(), true)
						.build(),
				false, a -> AppDesign.setFinPoints(ctx, a)));
		add(s, new ToolDef("app_convert_fins", "App: convert fins to freeform",
				"Replace a trapezoidal or elliptical fin set with a freeform one of the same outline (OpenRocket's Convert).",
				Schema.object().str("designId", DESIGN, true).str("component", "Fin set id.", true).build(), false,
				a -> AppDesign.convertFins(ctx, a)));
		add(s, new ToolDef("app_presets", "App: catalogue parts for a component",
				"Parts-database presets of the component's kind, in SI, nearest the component's own diameter first.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.str("text", "Words that must appear (manufacturer, part number, material).", false)
						.bool("sameDiameter", "Only parts within 2% of the component's diameter.", false)
						.integer("limit", "Most parts (default 200).", false).build(),
				true, a -> AppDesign.presets(ctx, a)));
		add(s, new ToolDef("app_appearance", "App: paint colour", "Set or clear a component's paint colour and shine.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.str("color", "#rrggbb, or empty to use the default look.", false).num("shine", "0 (matte) to 1 (glossy).", false)
						.build(),
				false, a -> AppDesign.appearance(ctx, a)));
		add(s, new ToolDef("app_model", "App: 3-D model", "Meshes of every part of the active configuration, with CG and CP.",
				Schema.object().str("designId", DESIGN, true)
						.array("known", "meshId of meshes the window already has: those are left out.", Schema.type("string", null), false)
						.build(), true, a -> AppDesign.model(ctx, a)));
		add(s, new ToolDef("app_quick_flight", "App: quick flight", "Flight summary of the active configuration (not saved).",
				Schema.object().str("designId", DESIGN, true).build(), true, a -> AppFlights.quickFlight(ctx, a)));
		add(s, new ToolDef("app_aero_compare", "App: RASAero beside OpenRocket",
				"The active configuration flown with an imported RASAero table's drag and with OpenRocket's own, side by side, with "
						+ "CD and CP from both models through Mach, in SI. Without a table: OpenRocket's numbers and the top Mach.",
				Schema.object().str("designId", DESIGN, true).build(), true, a -> AppFlights.aeroCompare(ctx, a)));
		add(s, new ToolDef("app_configs", "App: motors and configuration",
				"Flight configurations with each mount's motor, each recovery device's deployment and each stage's separation.",
				Schema.object().str("designId", DESIGN, true).build(), true, a -> AppMotors.configs(ctx, a)));
		add(s, new ToolDef("app_motor_delay", "App: motor delay and ignition",
				"Set a mount's ejection delay or ignition for one configuration.",
				Schema.object().str("designId", DESIGN, true).str("mount", "Mount id.", true).str("configuration", "Configuration id.", true)
						.num("ejectionDelay", "Ejection delay, s.", false).bool("plugged", "No ejection charge.", false)
						.str("ignitionEvent", "AUTOMATIC, LAUNCH, EJECTION_CHARGE, BURNOUT or NEVER.", false)
						.num("ignitionDelay", "Ignition delay, s.", false).bool("remove", "Remove the motor.", false).build(),
				false, a -> AppMotors.motorDelay(ctx, a)));
		add(s, new ToolDef("app_config", "App: copy or remove a configuration",
				"Copy a flight configuration (motors, deployment, separation) or remove it.",
				Schema.object().str("designId", DESIGN, true).enumStr("action", "copy or remove.", true, "copy", "remove")
						.str("configuration", "Configuration id.", true).build(),
				false, a -> AppMotors.config(ctx, a)));
		add(s, new ToolDef("app_motors", "App: motor database", "Motors from the database, in SI, for the motor picker.",
				Schema.object().str("designId", DESIGN, false).str("mount", "Only motors that fit this mount.", false)
						.str("text", "Designation or manufacturer contains.", false).str("minClass", "Lowest class letter.", false)
						.str("maxClass", "Highest class letter.", false).bool("availableOnly", "Only motors in production.", false)
						.integer("limit", "Maximum motors (default 300).", false).build(),
				true, a -> AppMotors.motors(ctx, a)));
		add(s, new ToolDef("app_import_motor", "App: import a motor file",
				"Load a .eng, .rse or zipped motor file into the motor list and keep a copy in the designs folder's motors/.",
				Schema.object().str("path", "Motor file.", true).build(), true, a -> AppMotors.importMotor(ctx, a.str("path"))));
		add(s, new ToolDef("app_motor_curve", "App: thrust curve", "One motor's thrust curve, in SI.",
				Schema.object().str("digest", "Motor digest.", true).build(), true, a -> {
					ThrustCurveMotor m = Motors.find(a.str("digest"), null);
					Map<String, Object> out = new LinkedHashMap<>(AppMotors.motorInfo(m));
					out.put("time", m.getTimePoints());
					out.put("thrust", m.getThrustPoints());
					return out;
				}));
		add(s, new ToolDef("app_sims", "App: simulations", "The design's simulations with their launch conditions and results.",
				Schema.object().str("designId", DESIGN, true).build(), true, a -> AppFlights.sims(ctx, a)));
		add(s, new ToolDef("app_sim_new", "App: new simulation", "Add a simulation of a configuration with the team's launch site.",
				Schema.object().str("designId", DESIGN, true).str("configuration", "Configuration id (default the active one).", false)
						.build(),
				false, a -> AppFlights.simNew(ctx, a)));
		add(s, new ToolDef("app_sim_edit", "App: edit a simulation", "Change a simulation's name, configuration or launch conditions.",
				Schema.object().str("designId", DESIGN, true).integer("simulation", "Index.", true).str("key", "Field.", true)
						.prop("value", new JsonObject(), true).build(),
				false, a -> AppFlights.simEdit(ctx, a)));
		add(s, new ToolDef("app_sim_run", "App: run simulations", "Run one simulation, or all of them.",
				Schema.object().str("designId", DESIGN, true).integer("simulation", "Index (omit for all).", false).build(), false,
				a -> AppFlights.simRun(ctx, a)));
		add(s, new ToolDef("app_sim_delete", "App: delete a simulation", "Delete a simulation.",
				Schema.object().str("designId", DESIGN, true).integer("simulation", "Index.", true).build(), false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId"));
					Simulation sim = AppFlights.sim(d, a.integer("simulation", -1));
					d.doc.removeSimulation(sim);
					d.doc.setSaved(false);
					return Map.of("deleted", sim.getName());
				}));
		add(s, new ToolDef("app_series", "App: flight data", "Flight data of a simulation for plotting, in SI.",
				Schema.object().str("designId", DESIGN, true).integer("simulation", "Index.", true)
						.integer("branch", "Branch (stage) index, default 0.", false).build(),
				true, a -> AppFlights.series(ctx, a)));
		add(s, new ToolDef("app_choose_file", "App: choose a file", "The system's open or save dialog; returns the chosen path.",
				Schema.object().enumStr("mode", "open, save or folder.", true, "open", "save", "folder").str("name", "Suggested file name.", false)
						.str("types", "File extensions to show, e.g. \"csv,txt\" (default: designs).", false).build(),
				true, a -> FileDialogs.choose(a.str("mode"), a.str("name", null), ctx.workspace(), a.str("types", "ork,rkt"))));
		add(s, new ToolDef("app_download_path", "App: where to save a download",
				"A path for a file the user takes away (the flight video): in the Downloads folder (else the Desktop, else the "
						+ "designs folder), numbered so it replaces nothing.",
				Schema.object().str("name", "File name, e.g. \"Maple 10K flight.mp4\".", true).build(), true, a -> {
					Path folder = Downloads.folder(Path.of(System.getProperty("user.home")), ctx.workspace());
					Path p = Downloads.freePath(folder, a.str("name"));
					return Map.of("path", p.toString(), "folder", folder.toString(), "folderName", folder.getFileName().toString());
				}));
		add(s, new ToolDef("app_export", "App: save a plot",
				"Saves a file the page made (a plot as a .png or .svg picture, or its data as .csv) in the Downloads folder "
						+ "(else the Desktop, else the designs folder), numbered so it replaces nothing.",
				Schema.object().str("name", "File name ending in .png, .csv or .svg.", true)
						.str("text", "The file's text (CSV or SVG).", false).str("base64", "The file's bytes in base64 (PNG).", false).build(),
				true, a -> {
					Path folder = Downloads.folder(Path.of(System.getProperty("user.home")), ctx.workspace());
					Path p = Downloads.export(folder, a.str("name"), a.str("text", null), a.str("base64", null));
					return Map.of("path", p.toString(), "folder", folder.toString(), "folderName", folder.getFileName().toString());
				}));
		add(s, new ToolDef("app_reveal", "App: show a file",
				"Show a file the engine wrote, or its folder, in the system's file manager; open: true opens the file itself in "
						+ "its own program (videos, pictures, documents only).",
				Schema.object().str("path", "File or folder.", true).bool("open", "Open the file itself (default false).", false).build(),
				true, a -> {
					Path p = ctx.path(a.str("path")).toAbsolutePath().normalize();
					if (!Files.exists(p)) {
						throw new ToolException("Not found: " + p);
					}
					boolean file = a.bool("open", false) && Files.isRegularFile(p);
					if (file && !Downloads.openable(p)) {
						throw new ToolException("Only videos, pictures and documents open from here; the file is at " + p);
					}
					Path open = file || Files.isDirectory(p) ? p : p.getParent();
					try {
						if (java.awt.GraphicsEnvironment.isHeadless() || !java.awt.Desktop.isDesktopSupported()) {
							throw new ToolException("No file manager here. The file is at " + p);
						}
						java.awt.Desktop.getDesktop().open(open.toFile());
					} catch (java.io.IOException | UnsupportedOperationException | LinkageError e) {
						throw new ToolException("Could not open " + (file ? "the file" : "the folder") + ". The file is at " + p);
					}
					return Map.of("opened", open.toString());
				}));
		add(s, new ToolDef("app_set_units", "App: unit system", "Metric or imperial for numbers the tools write as text.",
				Schema.object().enumStr("system", "metric or imperial.", true, "metric", "imperial").build(), false, a -> {
					Units.setSystem(UnitSystem.parse(a.str("system")));
					return Map.of("system", Units.system().name());
				}));
	}

	/** The result with every non-finite number (NaN, infinity) replaced by null. */
	static Object clean(Object v) {
		if (v instanceof Double d) {
			return Double.isFinite(d) ? d : null;
		}
		if (v instanceof Float f) {
			return Float.isFinite(f) ? f : null;
		}
		if (v instanceof double[] a) {
			List<Double> out = new ArrayList<>(a.length);
			for (double x : a) {
				out.add(Double.isFinite(x) ? x : null);
			}
			return out;
		}
		if (v instanceof Map<?, ?> m) {
			Map<Object, Object> out = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				out.put(e.getKey(), clean(e.getValue()));
			}
			return out;
		}
		if (v instanceof List<?> l) {
			List<Object> out = new ArrayList<>(l.size());
			for (Object x : l) {
				out.add(clean(x));
			}
			return out;
		}
		return v;
	}

	// ------------------------------------------------------------------------------------------- session

	static Map<String, Object> session(Context ctx) {
		OrRuntime.init(); // the material lists need OpenRocket started (the page may ask before anything else)
		AppMotors.teamMotors(ctx);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("version", App.VERSION);
		out.put("engineVersion", Main.VERSION);
		out.put("openRocketVersion", io.github.openrocketmcp.Versions.openRocket());
		out.put("macOS", List.copyOf(MacDesktop.ACTIVE)); // Dock, Finder and menu handlers in place (empty elsewhere)
		out.put("notices", App.notices()); // the third-party licences shipped with the app (Help > About), or null
		out.put("window", App.windowKind()); // "app" once the app's own window is open, else "none" (the installers' check)
		out.put("examples", List.of(Designs.EXAMPLES));
		List<Map<String, Object>> open = new ArrayList<>();
		for (Designs.Design d : ctx.designs.all()) {
			open.add(Map.of("designId", d.id, "name", d.name(), "path", d.path == null ? "" : d.path.toString()));
		}
		out.put("designs", open);
		out.put("workspace", ctx.workspace().toAbsolutePath().toString());
		out.put("system", Units.system() == UnitSystem.IMPERIAL ? "imperial" : "metric");
		Map<String, Object> dims = new LinkedHashMap<>();
		for (Map.Entry<Dim, String[]> e : CHOICES.entrySet()) {
			List<Map<String, Object>> units = new ArrayList<>();
			for (String u : e.getValue()) {
				double[] sc = Units.scale(u);
				if (sc != null) {
					units.add(Map.of("unit", u, "factor", sc[0], "offset", sc[1]));
				}
			}
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("metric", e.getKey().metric);
			d.put("imperial", e.getKey().imperial);
			d.put("units", units);
			dims.put(e.getKey().name(), d);
		}
		out.put("dims", dims);
		Map<String, Object> mats = new LinkedHashMap<>();
		mats.put("BULK", materials(Databases.BULK_MATERIAL));
		mats.put("SURFACE", materials(Databases.SURFACE_MATERIAL));
		mats.put("LINE", materials(Databases.LINE_MATERIAL));
		out.put("materials", mats);
		out.put("creatable", Components.CREATABLE);
		return out;
	}

	static List<Map<String, Object>> materials(Iterable<Material> db) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Material m : db) {
			out.add(Map.of("name", m.getName(), "density", m.getDensity()));
		}
		return out;
	}
}
