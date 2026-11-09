package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.appearance.Appearance;
import info.openrocket.core.database.Databases;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.material.Material;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurableComponent;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.FreeformFinSet;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.StageSeparationConfiguration;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.ORColor;
import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Aero;
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Branch;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.FlightLog;
import io.github.openrocketmcp.or.Library;
import io.github.openrocketmcp.or.Motors;
import io.github.openrocketmcp.or.OrRuntime;
import io.github.openrocketmcp.or.Presets;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Targets;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.report.Workbench;
import io.github.openrocketmcp.standards.Standards;
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
				Schema.object().str("designId", DESIGN, true).build(), true, a -> design(ctx, a)));
		add(s, new ToolDef("app_component", "App: component editor", "One component's editor with its current values.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true).build(), true,
				a -> component(ctx, a)));
		add(s, new ToolDef("app_edit", "App: edit a field", "Set one field of a component's editor.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.str("key", "Field key.", true).prop("value", new JsonObject(), true).build(),
				false, a -> edit(ctx, a)));
		add(s, new ToolDef("app_move", "App: move a component",
				"Move a component up or down among its siblings, or into another part (parent, at index).",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.integer("by", "-1 up, +1 down.", false).str("parent", "Part to move it into (id, or rocket).", false)
						.integer("index", "Place among the new parent's parts, counted before the move (default the end).", false)
						.build(),
				false, a -> move(ctx, a)));
		add(s, new ToolDef("app_duplicate", "App: duplicate a component",
				"Copy a component (with its parts) right after it, or into another part (parent, at index): paste.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.str("parent", "Part to put the copy in (id, or rocket).", false)
						.integer("index", "Place among that part's parts (default the end).", false).build(), false,
				a -> duplicate(ctx, a)));
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
				Schema.object().build(), true, a -> standards(ctx)));
		// Read-only as far as undo goes: standards live in the team file, not in the design.
		add(s, new ToolDef("app_set_standards", "App: change team standards",
				"Merge a change into the team standards file ({\"recovery\": {\"harnessSafetyFactor\": 2.5}}); null returns a value "
						+ "to its default. Only the team's own values are written.",
				Schema.object().obj("patch", "The change.", true).build(), true, a -> setStandards(ctx, a.obj("patch").raw())));
		// Read-only as far as undo goes: targets live in the team standards file, not in the design.
		add(s, new ToolDef("app_set_targets", "App: set the team's targets",
				"Set or clear target limits ({\"apogee\": {\"min\": \"9500 ft\", \"goal\": null}}) and write them to the team "
						+ "standards file, keeping the rest of that file as the team wrote it.",
				Schema.object().obj("targets", "Targets to change; null clears a limit.", true).build(), true,
				a -> setTargets(ctx, a.obj("targets").raw())));
		add(s, new ToolDef("app_flight_log", "App: flight log against the simulation",
				"compare_flight with both altitude curves on one time grid and the numbers in SI, for the Flight data page.",
				Schema.object().str("designId", DESIGN, true).str("path", "Altimeter CSV.", true)
						.str("altitudeUnit", "ft or m when the header does not say.", false)
						.str("configuration", "Configuration id (default the active one).", false)
						.qty("windSpeed", "The day's wind.", false).qty("launchAngle", "Rail angle from vertical.", false)
						.qty("launchSiteAltitude", "Site altitude above sea level.", false).qty("temperature", "Ground temperature.", false)
						.build(),
				true, a -> flightLog(ctx, a)));
		add(s, new ToolDef("app_library", "App: past designs and flights",
				"design_library's scan of a folder, in SI: every design and every altimeter log with the design it belongs to.",
				Schema.object().str("folder", "Folder (default the designs folder).", false)
						.bool("simulate", "Simulate designs without saved results (default true).", false).build(),
				true, a -> library(ctx, a)));
		add(s, new ToolDef("app_fin_points", "App: fin outline", "A fin set's outline points (x along the root, y outwards), in SI.",
				Schema.object().str("designId", DESIGN, true).str("component", "Fin set id.", true).build(), true,
				a -> finPoints(ctx, a)));
		add(s, new ToolDef("app_set_fin_points", "App: set freeform fin points",
				"Replace a freeform fin set's outline: points from the root's leading edge (0, 0) to its trailing edge (x, 0), in SI.",
				Schema.object().str("designId", DESIGN, true).str("component", "Freeform fin set id.", true)
						.array("points", "[[x, y], ...] in m.",
								JsonParser.parseString("{\"type\": \"array\", \"items\": {\"type\": \"number\"}}").getAsJsonObject(), true)
						.build(),
				false, a -> setFinPoints(ctx, a)));
		add(s, new ToolDef("app_convert_fins", "App: convert fins to freeform",
				"Replace a trapezoidal or elliptical fin set with a freeform one of the same outline (OpenRocket's Convert).",
				Schema.object().str("designId", DESIGN, true).str("component", "Fin set id.", true).build(), false,
				a -> convertFins(ctx, a)));
		add(s, new ToolDef("app_presets", "App: catalogue parts for a component",
				"Parts-database presets of the component's kind, in SI, nearest the component's own diameter first.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.str("text", "Words that must appear (manufacturer, part number, material).", false)
						.bool("sameDiameter", "Only parts within 2% of the component's diameter.", false)
						.integer("limit", "Most parts (default 200).", false).build(),
				true, a -> presets(ctx, a)));
		add(s, new ToolDef("app_appearance", "App: paint colour", "Set or clear a component's paint colour and shine.",
				Schema.object().str("designId", DESIGN, true).str("component", "Component id.", true)
						.str("color", "#rrggbb, or empty to use the default look.", false).num("shine", "0 (matte) to 1 (glossy).", false)
						.build(),
				false, a -> appearance(ctx, a)));
		add(s, new ToolDef("app_model", "App: 3-D model", "Meshes of every part of the active configuration, with CG and CP.",
				Schema.object().str("designId", DESIGN, true)
						.array("known", "meshId of meshes the window already has: those are left out.", Schema.type("string", null), false)
						.build(), true, a -> model(ctx, a)));
		add(s, new ToolDef("app_quick_flight", "App: quick flight", "Flight summary of the active configuration (not saved).",
				Schema.object().str("designId", DESIGN, true).build(), true, a -> quickFlight(ctx, a)));
		add(s, new ToolDef("app_aero_compare", "App: RASAero beside OpenRocket",
				"The active configuration flown with an imported RASAero table's drag and with OpenRocket's own, side by side, with "
						+ "CD and CP from both models through Mach, in SI. Without a table: OpenRocket's numbers and the top Mach.",
				Schema.object().str("designId", DESIGN, true).build(), true, a -> aeroCompare(ctx, a)));
		add(s, new ToolDef("app_configs", "App: motors and configuration",
				"Flight configurations with each mount's motor, each recovery device's deployment and each stage's separation.",
				Schema.object().str("designId", DESIGN, true).build(), true, a -> configs(ctx, a)));
		add(s, new ToolDef("app_motor_delay", "App: motor delay and ignition",
				"Set a mount's ejection delay or ignition for one configuration.",
				Schema.object().str("designId", DESIGN, true).str("mount", "Mount id.", true).str("configuration", "Configuration id.", true)
						.num("ejectionDelay", "Ejection delay, s.", false).bool("plugged", "No ejection charge.", false)
						.str("ignitionEvent", "AUTOMATIC, LAUNCH, EJECTION_CHARGE, BURNOUT or NEVER.", false)
						.num("ignitionDelay", "Ignition delay, s.", false).bool("remove", "Remove the motor.", false).build(),
				false, a -> motorDelay(ctx, a)));
		add(s, new ToolDef("app_config", "App: copy or remove a configuration",
				"Copy a flight configuration (motors, deployment, separation) or remove it.",
				Schema.object().str("designId", DESIGN, true).enumStr("action", "copy or remove.", true, "copy", "remove")
						.str("configuration", "Configuration id.", true).build(),
				false, a -> config(ctx, a)));
		add(s, new ToolDef("app_motors", "App: motor database", "Motors from the database, in SI, for the motor picker.",
				Schema.object().str("designId", DESIGN, false).str("mount", "Only motors that fit this mount.", false)
						.str("text", "Designation or manufacturer contains.", false).str("minClass", "Lowest class letter.", false)
						.str("maxClass", "Highest class letter.", false).bool("availableOnly", "Only motors in production.", false)
						.integer("limit", "Maximum motors (default 300).", false).build(),
				true, a -> motors(ctx, a)));
		add(s, new ToolDef("app_import_motor", "App: import a motor file",
				"Load a .eng, .rse or zipped motor file into the motor list and keep a copy in the designs folder's motors/.",
				Schema.object().str("path", "Motor file.", true).build(), true, a -> importMotor(ctx, a.str("path"))));
		add(s, new ToolDef("app_motor_curve", "App: thrust curve", "One motor's thrust curve, in SI.",
				Schema.object().str("digest", "Motor digest.", true).build(), true, a -> {
					ThrustCurveMotor m = Motors.find(a.str("digest"), null);
					Map<String, Object> out = new LinkedHashMap<>(motorInfo(m));
					out.put("time", m.getTimePoints());
					out.put("thrust", m.getThrustPoints());
					return out;
				}));
		add(s, new ToolDef("app_sims", "App: simulations", "The design's simulations with their launch conditions and results.",
				Schema.object().str("designId", DESIGN, true).build(), true, a -> sims(ctx, a)));
		add(s, new ToolDef("app_sim_new", "App: new simulation", "Add a simulation of a configuration with the team's launch site.",
				Schema.object().str("designId", DESIGN, true).str("configuration", "Configuration id (default the active one).", false)
						.build(),
				false, a -> simNew(ctx, a)));
		add(s, new ToolDef("app_sim_edit", "App: edit a simulation", "Change a simulation's name, configuration or launch conditions.",
				Schema.object().str("designId", DESIGN, true).integer("simulation", "Index.", true).str("key", "Field.", true)
						.prop("value", new JsonObject(), true).build(),
				false, a -> simEdit(ctx, a)));
		add(s, new ToolDef("app_sim_run", "App: run simulations", "Run one simulation, or all of them.",
				Schema.object().str("designId", DESIGN, true).integer("simulation", "Index (omit for all).", false).build(), false,
				a -> simRun(ctx, a)));
		add(s, new ToolDef("app_sim_delete", "App: delete a simulation", "Delete a simulation.",
				Schema.object().str("designId", DESIGN, true).integer("simulation", "Index.", true).build(), false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId"));
					Simulation sim = sim(d, a.integer("simulation", -1));
					d.doc.removeSimulation(sim);
					d.doc.setSaved(false);
					return Map.of("deleted", sim.getName());
				}));
		add(s, new ToolDef("app_series", "App: flight data", "Flight data of a simulation for plotting, in SI.",
				Schema.object().str("designId", DESIGN, true).integer("simulation", "Index.", true)
						.integer("branch", "Branch (stage) index, default 0.", false).build(),
				true, a -> series(ctx, a)));
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
		teamMotors(ctx);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("version", App.VERSION);
		out.put("engineVersion", Main.VERSION);
		out.put("macOS", List.copyOf(MacDesktop.ACTIVE)); // Dock, Finder and menu handlers in place (empty elsewhere)
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

	private static List<Map<String, Object>> materials(Iterable<Material> db) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Material m : db) {
			out.add(Map.of("name", m.getName(), "density", m.getDensity()));
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------- design

	static Map<String, Object> design(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("designId", d.id);
		out.put("name", d.name());
		out.put("path", d.path == null ? null : d.path.toString());
		out.put("saved", d.doc.isSaved());
		out.put("canUndo", d.history.undoable());
		out.put("canRedo", d.history.redoable());
		out.put("tree", node(r));
		out.put("configurations", configList(r));
		out.put("selected", r.getSelectedConfiguration().getId().toString());
		AeroTable.Table aero = AeroTable.of(r);
		out.put("aeroTable", aero == null ? null : aero.source());
		Map<String, Object> st = stability(r.getSelectedConfiguration());
		double floor = Requirements.stabilityFloor(ctx.standards(), r.getSelectedConfiguration())[0];
		if (floor > 0) {
			st.put("floor", floor); // the rule set's minimum, for the stability readout's colour
		}
		out.put("stability", st);
		if (!d.loadWarnings.isEmpty()) {
			out.put("loadWarnings", d.loadWarnings);
		}
		return out;
	}

	static Map<String, Object> node(RocketComponent c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", c instanceof Rocket ? "rocket" : Components.shortId(c));
		m.put("name", c.getName());
		m.put("type", c.getClass().getSimpleName());
		m.put("label", c.getComponentName());
		if (c instanceof MotorMount mm && mm.isMotorMount()) {
			m.put("motorMount", true);
		}
		if (c.isMassOverridden() || c.isCGOverridden()) {
			m.put("overridden", true);
		}
		List<String> accepts = new ArrayList<>();
		for (String t : Components.CREATABLE) {
			if (c.isCompatible(Components.create(t))) {
				accepts.add(t);
			}
		}
		m.put("accepts", accepts);
		List<Map<String, Object>> kids = new ArrayList<>();
		for (RocketComponent ch : c.getChildren()) {
			kids.add(node(ch));
		}
		m.put("children", kids);
		return m;
	}

	static List<Map<String, Object>> configList(Rocket r) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (FlightConfigurationId id : r.getIds()) {
			FlightConfiguration fc = r.getFlightConfiguration(id);
			out.add(Map.of("id", id.toString(), "name", fc.getName(), "hasMotors", fc.hasMotors()));
		}
		return out;
	}

	static Map<String, Object> stability(FlightConfiguration fc) {
		Map<String, Object> m = new LinkedHashMap<>();
		try {
			Analysis.Stability st = Analysis.stability(fc, 0.3);
			m.put("length", st.length());
			m.put("diameter", st.referenceDiameter());
			m.put("cg", st.cgLaunchX());
			m.put("cgBurnout", st.cgBurnoutX());
			m.put("cp", finite(st.cpX()));
			m.put("margin", finite(st.marginCalibers()));
			m.put("marginBurnout", finite(st.burnoutMarginCalibers()));
			m.put("mass", st.launchMass());
			m.put("massBurnout", st.burnoutMass());
			m.put("mach", 0.3);
			if (!st.warnings().isEmpty()) {
				m.put("warnings", st.warnings());
			}
		} catch (RuntimeException e) {
			m.put("error", "Not enough of a rocket to analyse yet.");
		}
		return m;
	}

	private static Double finite(double v) {
		return Double.isFinite(v) ? v : null;
	}

	static RocketComponent find(Designs.Design d, String id) {
		Rocket r = d.doc.getRocket();
		return "rocket".equals(id) ? r : Components.find(r, id);
	}

	static Map<String, Object> component(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", a.str("component"));
		out.put("name", c.getName());
		out.put("type", c.getClass().getSimpleName());
		out.put("label", c.getComponentName());
		out.put("sections", Editors.render(c));
		if (!(c instanceof Rocket)) {
			out.put("paint", paint(c));
			out.put("shine", c.getAppearance() == null ? null : c.getAppearance().getShine());
			out.put("presetType", c.getPresetType() == null ? null : c.getPresetType().name());
			out.put("preset", c.getPresetComponent() == null ? null : Presets.name(c.getPresetComponent()));
		}
		try {
			out.put("mass", c.getSectionMass());
			out.put("componentMass", c.getComponentMass());
		} catch (RuntimeException ignored) {
			// mass not resolvable in this state
		}
		String w = Components.overrideWarning(c);
		if (w != null) {
			out.put("warning", w);
		}
		return out;
	}

	static Map<String, Object> edit(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		String key = a.str("key");
		if (Editors.field(c, key) == null) {
			throw new ToolException("'" + key + "' is not a field of a " + c.getComponentName() + ".");
		}
		String done = Components.set(c, key, a.get("value"));
		d.doc.setSaved(false);
		return Map.of("applied", done);
	}

	static Map<String, Object> move(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		RocketComponent p = c.getParent();
		if (p == null) {
			throw new ToolException("The rocket itself cannot move.");
		}
		if (a.has("parent")) {
			// Dragged in the parts list: into another part, or to a place among the new parent's parts.
			RocketComponent np = holder(d, a.str("parent"), c);
			int index = a.integer("index", np.getChildCount());
			if (np == p) {
				int from = p.getChildPosition(c);
				index = Math.max(0, Math.min(index > from ? index - 1 : index, p.getChildCount() - 1));
				if (index == from) {
					return Map.of("moved", c.getName(), "unchanged", true);
				}
				p.moveChild(c, index);
			} else {
				p.removeChild(c);
				np.addChild(c, Math.max(0, Math.min(index, np.getChildCount())));
			}
			d.doc.setSaved(false);
			return Map.of("moved", c.getName(), "into", np.getName());
		}
		int to = p.getChildPosition(c) + a.integer("by", 0);
		if (to < 0 || to >= p.getChildCount()) {
			throw new ToolException(c.getName() + " is already at the " + (to < 0 ? "top" : "bottom") + ".");
		}
		p.moveChild(c, to);
		d.doc.setSaved(false);
		return Map.of("moved", c.getName());
	}

	/** The part {@code parentId} names, checked that it can hold {@code c} and is not {@code c} or inside it. */
	static RocketComponent holder(Designs.Design d, String parentId, RocketComponent c) {
		RocketComponent np = find(d, parentId);
		for (RocketComponent x = np; x != null; x = x.getParent()) {
			if (x == c) {
				throw new ToolException(c.getName() + " cannot go inside itself.");
			}
		}
		if (!np.isCompatible(c)) {
			throw new ToolException(np.getName() + " (" + np.getComponentName() + ") cannot hold a " + c.getComponentName() + ".");
		}
		return np;
	}

	static Map<String, Object> duplicate(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		RocketComponent p = c.getParent();
		if (p == null) {
			throw new ToolException("The rocket itself cannot be duplicated.");
		}
		RocketComponent copy = c.copy();
		if (a.has("parent")) {
			// Pasted: into the part chosen, at the place given (default its end).
			RocketComponent np = holder(d, a.str("parent"), copy);
			np.addChild(copy, Math.max(0, Math.min(a.integer("index", np.getChildCount()), np.getChildCount())));
		} else {
			p.addChild(copy, p.getChildPosition(c) + 1);
		}
		d.doc.setSaved(false);
		return Map.of("id", Components.shortId(copy), "name", copy.getName());
	}

	// ------------------------------------------------------------------------------------------- 3-D model and flight

	/** A mesh's identity: its length and a CRC of its text (the window compares, nothing else relies on it). */
	static String meshId(String mesh) {
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(mesh.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		return Long.toHexString(crc.getValue()) + "-" + Integer.toHexString(mesh.length());
	}

	static Map<String, Object> model(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		Map<String, Object> out = new LinkedHashMap<>();
		java.util.Set<String> known = a.has("known") ? new java.util.HashSet<>(a.strList("known")) : java.util.Set.of();
		try {
			Map<String, Object> r = Workbench.rocket(fc);
			List<Map<String, Object>> parts = new ArrayList<>();
			for (Object o : (List<?>) r.get("parts")) {
				@SuppressWarnings("unchecked")
				Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) o);
				p.remove("props"); // the editor reads these itself, with values the app can edit
				p.remove("mass");
				// An edit usually changes one part: the window keeps the meshes (and what it built from them) by id, and
				// gets only the new ones.
				if (p.get("mesh") instanceof String mesh) {
					String id = meshId(mesh);
					p.put("meshId", id);
					if (known.contains(id)) {
						p.remove("mesh");
					}
				}
				if (p.get("id") instanceof String id) {
					RocketComponent pc = Components.find(d.doc.getRocket(), id);
					String painted = paint(pc);
					if (painted != null) {
						p.put("color", painted);
						p.put("shine", pc.getAppearance().getShine());
					}
				}
				parts.add(p);
			}
			out.put("parts", parts);
			out.put("finOut", r.get("finOut"));
		} catch (RuntimeException e) {
			out.put("parts", List.of());
		}
		out.put("stability", stability(fc));
		return out;
	}

	static Map<String, Object> quickFlight(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		if (!fc.hasMotors()) {
			return Map.of("noMotors", true);
		}
		Simulation sim = Sims.prepare(d, null, fc.getId().toString(), null, ctx.standards(), false);
		if (d.doc.getSimulations().contains(sim)) {
			// The document's flight of this configuration: the rule check and targets read the same one, so after an
			// edit it is flown once for all three (calls on a design run one at a time).
			Sims.ensure(sim);
		} else {
			sim = sim.copy();
			Sims.run(sim);
		}
		return results(sim);
	}

	/** Mach numbers for the side-by-side table, up to a little past the flight's top speed. */
	static final double[] COMPARE_MACHS = { 0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 1.0, 1.05, 1.1, 1.2, 1.3, 1.5, 1.75, 2.0, 2.5, 3.0 };

	/**
	 * The flight on both models: RASAero's drag (when a table for the whole vehicle is imported and its drag used) and
	 * OpenRocket's own; the stability margin with each model's CP; CD and CP by Mach from both. Transonic and supersonic
	 * flights are where the two differ most, and where OpenRocket warns its model is least sure.
	 */
	static Map<String, Object> aeroCompare(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket rocket = d.doc.getRocket();
		FlightConfiguration fc = rocket.getSelectedConfiguration();
		AeroTable.Table t = AeroTable.of(rocket);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("hasTable", t != null);
		if (t != null) {
			out.put("source", t.source());
			out.put("useDrag", t.useDrag());
			out.put("hasCp", t.hasCp());
			out.put("tableMaxMach", t.mach()[t.mach().length - 1]);
		}
		double ref = Analysis.stability(fc, 0.3).referenceDiameter();
		double topMach = Double.NaN;
		if (fc.hasMotors()) {
			Simulation base = Sims.prepare(d, null, fc.getId().toString(), null, ctx.standards(), false);
			List<Variants.Run> runs = Variants.runAll(t != null && t.useDrag()
					? List.of(AeroTable.without(Variants.of(base, d.doc, null, null)), Variants.of(base, d.doc, null, null))
					: List.of(AeroTable.without(Variants.of(base, d.doc, null, null))));
			Variants.Run or = runs.get(0);
			if (or.ok()) {
				out.put("openRocket", flightNumbers(or.sim(), null, ref));
				topMach = or.sim().getSimulatedData().getMaxMachNumber();
			}
			if (t != null) {
				Variants.Run tb = runs.size() > 1 ? runs.get(1) : or;
				if (tb.ok()) {
					// With the table's drag (or, when its drag is not used, the same flight), and the table's CP.
					out.put("rasaero", flightNumbers(tb.sim(), t, ref));
					topMach = Math.max(Double.isNaN(topMach) ? 0 : topMach, tb.sim().getSimulatedData().getMaxMachNumber());
				}
			}
		}
		out.put("maxMach", Double.isFinite(topMach) ? topMach : null);
		double upTo = Double.isFinite(topMach) ? Math.max(1.0, topMach + 0.15) : 1.5;
		List<Double> ms = new ArrayList<>();
		for (double m : COMPARE_MACHS) {
			if (m <= upTo + 1e-9 && (t == null || m <= t.mach()[t.mach().length - 1] + 1e-9 || ms.size() < 3)) {
				ms.add(m);
			}
		}
		if (Double.isFinite(topMach) && !ms.contains(topMach)) {
			ms.add(topMach);
			ms.sort(Double::compare);
		}
		double[] arr = ms.stream().mapToDouble(Double::doubleValue).toArray();
		List<Aero.Point> pts;
		try {
			pts = Aero.sweep(fc, arr);
		} catch (RuntimeException e) {
			pts = List.of();
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Aero.Point p : pts) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("mach", p.mach());
			r.put("cdOpenRocket", finiteOrNull(p.cd()));
			r.put("cpOpenRocket", finiteOrNull(p.cpX()));
			if (t != null && p.mach() <= t.mach()[t.mach().length - 1] + 1e-9) {
				r.put("cdRasaeroPowerOff", finiteOrNull(t.cd(p.mach(), false)));
				r.put("cdRasaeroPowerOn", finiteOrNull(t.cd(p.mach(), true)));
				if (t.hasCp()) {
					r.put("cpRasaero", finiteOrNull(t.cpAt(p.mach())));
				}
			}
			rows.add(r);
		}
		out.put("byMach", rows);
		out.put("referenceDiameter", ref);
		return out;
	}

	static Object finiteOrNull(double v) {
		return Double.isFinite(v) ? v : null;
	}

	/** A flight's main numbers in SI, and its lowest ascent margin (with the table's CP when one is given). */
	static Map<String, Object> flightNumbers(Simulation sim, AeroTable.Table cpFrom, double ref) {
		FlightData f = sim.getSimulatedData();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("apogee", finiteOrNull(f.getMaxAltitude()));
		m.put("maxVelocity", finiteOrNull(f.getMaxVelocity()));
		m.put("maxMach", finiteOrNull(f.getMaxMachNumber()));
		m.put("timeToApogee", finiteOrNull(f.getTimeToApogee()));
		m.put("railExitVelocity", finiteOrNull(f.getLaunchRodVelocity()));
		if (cpFrom != null && cpFrom.hasCp()) {
			AeroTable.Margin mg = AeroTable.minMargin(sim, cpFrom, ref);
			if (mg != null) {
				m.put("minStability", mg.min());
				m.put("minStabilityMach", mg.mach());
			}
		} else {
			Sims.Window w = Sims.ascentStability(f.getBranch(0));
			if (w != null) {
				m.put("minStability", w.min());
			}
		}
		return m;
	}

	static Map<String, Object> results(Simulation sim) {
		FlightData f = sim.getSimulatedData();
		Map<String, Object> m = new LinkedHashMap<>();
		if (f == null || f.getBranchCount() == 0) {
			return m;
		}
		m.put("apogee", finite(f.getMaxAltitude()));
		m.put("maxVelocity", finite(f.getMaxVelocity()));
		m.put("maxMach", finite(f.getMaxMachNumber()));
		m.put("maxAcceleration", finite(f.getMaxAcceleration()));
		m.put("timeToApogee", finite(f.getTimeToApogee()));
		m.put("flightTime", finite(f.getFlightTime()));
		m.put("groundHitVelocity", finite(f.getGroundHitVelocity()));
		m.put("railExitVelocity", finite(f.getLaunchRodVelocity()));
		m.put("optimumDelay", finite(f.getOptimumDelay()));
		m.put("deploymentVelocity", finite(f.getDeploymentVelocity()));
		Sims.Window w = Sims.ascentStability(f.getBranch(0));
		if (w != null) {
			m.put("minStability", w.min());
			m.put("maxStability", w.max());
		}
		List<String> problems = Sims.problems(sim);
		if (!problems.isEmpty()) {
			m.put("problems", problems);
		}
		return m;
	}

	// ------------------------------------------------------------------------------------------- fins, catalogue, paint

	static Map<String, Object> finPoints(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		if (!(find(d, a.str("component")) instanceof FinSet f)) {
			throw new ToolException("Not a fin set.");
		}
		List<double[]> pts = new ArrayList<>();
		for (Coordinate c : f.getFinPoints()) {
			pts.add(new double[] { c.x, c.y });
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("points", pts);
		out.put("editable", f instanceof FreeformFinSet);
		out.put("bodyRadius", f.getBodyRadius());
		out.put("thickness", f.getThickness());
		return out;
	}

	static Map<String, Object> setFinPoints(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		if (!(find(d, a.str("component")) instanceof FreeformFinSet f)) {
			throw new ToolException("Only a freeform fin set's points can be edited; convert the fins first.");
		}
		JsonArray arr = a.array("points");
		if (arr.size() < 3) {
			throw new ToolException("A fin needs at least three points.");
		}
		List<Coordinate> pts = new ArrayList<>();
		for (JsonElement e : arr) {
			JsonArray xy = e.getAsJsonArray();
			double x = xy.get(0).getAsDouble(), y = xy.get(1).getAsDouble();
			if (!Double.isFinite(x) || !Double.isFinite(y) || y < -1e-9 || Math.abs(x) > 10 || y > 10) {
				throw new ToolException("Fin points are [x, y] in metres with y >= 0.");
			}
			pts.add(new Coordinate(x, y));
		}
		// The root runs from (0, 0) to (x, 0), as in OpenRocket's editor.
		Coordinate last = pts.get(pts.size() - 1);
		pts.set(0, Coordinate.ZERO);
		pts.set(pts.size() - 1, new Coordinate(last.x, 0));
		if (last.x <= 0) {
			throw new ToolException("The trailing edge of the root must be behind the leading edge.");
		}
		if (crosses(pts)) {
			throw new ToolException("The outline crosses itself; points not changed.");
		}
		f.setPoints(pts.toArray(new Coordinate[0]));
		d.doc.setSaved(false);
		return Map.of("points", pts.size(), "span", f.getSpan());
	}

	/** Whether two edges of the closed outline (the root closes it) cross; edges that share a point do not count. */
	static boolean crosses(List<Coordinate> pts) {
		int n = pts.size();
		for (int i = 0; i < n; i++) {
			for (int j = i + 2; j < n; j++) {
				if (i == 0 && j == n - 1) {
					continue; // the root and the first edge meet at the leading edge
				}
				Coordinate a = pts.get(i), b = pts.get(i + 1), c = pts.get(j), e = pts.get((j + 1) % n);
				double d1 = side(c, e, a), d2 = side(c, e, b), d3 = side(a, b, c), d4 = side(a, b, e);
				if (d1 * d2 < 0 && d3 * d4 < 0) {
					return true;
				}
			}
		}
		return false;
	}

	private static double side(Coordinate p, Coordinate q, Coordinate r) {
		return (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x);
	}

	static Map<String, Object> convertFins(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		if (!(c instanceof FinSet f) || c instanceof FreeformFinSet) {
			throw new ToolException(c instanceof FreeformFinSet ? "These fins are already freeform." : "Not a fin set.");
		}
		FreeformFinSet ff = FreeformFinSet.convertFinSet(f);
		d.doc.setSaved(false);
		return Map.of("component", Components.shortId(ff), "name", ff.getName());
	}

	static Map<String, Object> presets(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		ComponentPreset.Type t = c.getPresetType();
		if (t == null) {
			throw new ToolException(c.getComponentName() + " parts have no catalogue.");
		}
		double own = diameterOf(c);
		List<ComponentPreset> found = Presets.search(t, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null,
				a.has("text") && !a.str("text").isBlank() ? a.str("text") : null);
		if (a.bool("sameDiameter", false) && own > 0) {
			found.removeIf(p -> !(Math.abs(Presets.outerDiameter(p) - own) <= 0.02 * own));
		}
		if (own > 0) {
			found.sort(java.util.Comparator.comparingDouble(p -> {
				double od = Presets.outerDiameter(p);
				return Double.isNaN(od) ? Double.MAX_VALUE : Math.abs(od - own);
			}));
		}
		int limit = Math.max(1, a.integer("limit", 200));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (ComponentPreset p : found.subList(0, Math.min(limit, found.size()))) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("ref", Presets.name(p));
			m.put("manufacturer", p.getManufacturer().getDisplayName());
			m.put("partNo", p.getPartNo());
			m.put("description", p.has(ComponentPreset.DESCRIPTION) ? p.get(ComponentPreset.DESCRIPTION) : "");
			m.put("outerDiameter", Presets.outerDiameter(p));
			m.put("innerDiameter", p.has(ComponentPreset.INNER_DIAMETER) ? p.get(ComponentPreset.INNER_DIAMETER) : null);
			m.put("length", p.has(ComponentPreset.LENGTH) ? p.get(ComponentPreset.LENGTH) : null);
			m.put("mass", p.has(ComponentPreset.MASS) ? p.get(ComponentPreset.MASS) : null);
			m.put("material", p.has(ComponentPreset.MATERIAL) ? p.get(ComponentPreset.MATERIAL).getName() : "");
			rows.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("type", t.name());
		out.put("diameter", own > 0 ? own : null);
		out.put("current", c.getPresetComponent() == null ? null : Presets.name(c.getPresetComponent()));
		out.put("matches", found.size());
		out.put("presets", rows);
		return out;
	}

	/** The diameter a catalogue part should match: the outside of tubes and cones, the bore a ring or coupler sits in. */
	static double diameterOf(RocketComponent c) {
		for (String k : new String[] { "outerRadius", "aftRadius", "outerDiameter", "diameter", "radius" }) {
			try {
				Object v = Components.getRaw(c, k);
				if (v instanceof Double r && r > 0) {
					return k.endsWith("Radius") || k.equals("radius") ? 2 * r : r;
				}
			} catch (ToolException e) {
				// not a property of this part
			}
		}
		return Double.NaN;
	}

	static Map<String, Object> appearance(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		String hex = a.str("color", "").trim();
		if (hex.isEmpty()) {
			c.setAppearance(null);
		} else {
			if (!hex.matches("#?[0-9a-fA-F]{6}")) {
				throw new ToolException("Colour as #rrggbb.");
			}
			int rgb = Integer.parseInt(hex.replace("#", ""), 16);
			double shine = Math.max(0, Math.min(1, a.num("shine", c.getAppearance() == null ? 0.3 : c.getAppearance().getShine())));
			Appearance old = c.getAppearance();
			c.setAppearance(new Appearance(new ORColor(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255), shine,
					old == null ? null : old.getTexture()));
		}
		d.doc.setSaved(false);
		return Map.of("component", c.getName(), "color", hex.isEmpty() ? "default" : hex);
	}

	/** The component's paint as #rrggbb, or null for OpenRocket's default look. */
	static String paint(RocketComponent c) {
		Appearance ap = c.getAppearance();
		if (ap == null || ap.getPaint() == null) {
			return null;
		}
		ORColor col = ap.getPaint();
		return String.format("#%02x%02x%02x", col.getRed(), col.getGreen(), col.getBlue());
	}

	// ------------------------------------------------------------------------------------------- flight data

	static Map<String, Object> flightLog(Context ctx, Args a) throws IOException {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		FlightLog.Log log = FlightLog.parse(Files.readString(ctx.path(a.str("path"))), a.str("altitudeUnit", null));
		Sims.Overrides o = new Sims.Overrides(a.qtyOrNaN("windSpeed", Dim.VELOCITY), Double.NaN, Double.NaN, Double.NaN,
				a.qtyOrNaN("launchAngle", Dim.ANGLE), Double.NaN, a.qtyOrNaN("launchSiteAltitude", Dim.DISTANCE), Double.NaN,
				Double.NaN, a.qtyOrNaN("temperature", Dim.TEMPERATURE), null, Double.NaN);
		Simulation base = Sims.prepare(d, null, a.str("configuration", null), o, ctx.standards(), false);
		Map<String, Object> out = FlightLog.compare(base, d.doc, log, null, d.name(), true);
		out.put("simulation", base.getName());
		return out;
	}

	static Map<String, Object> library(Context ctx, Args a) throws IOException {
		Path root = a.has("folder") ? ctx.path(a.str("folder")) : ctx.workspace();
		Library.Scan scan = Library.scan(root, a.bool("simulate", true));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("folder", scan.root().toString());
		List<Map<String, Object>> designs = new ArrayList<>();
		for (Library.Entry e : scan.designs()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", e.name());
			m.put("path", e.file().toString());
			m.put("file", e.relPath());
			m.put("year", e.year());
			m.put("yearFromPath", e.yearSource().equals("path"));
			if (e.error() != null) {
				m.put("error", e.error());
			} else {
				m.put("stages", e.stages());
				m.put("length", e.length());
				m.put("diameter", e.diameter());
				m.put("launchMass", e.launchMass());
				m.put("motors", e.motors());
				m.put("motorClass", e.motorClass());
				m.put("totalImpulse", e.totalImpulse());
				m.put("predictedApogee", e.predictedApogee());
				m.put("predictionSource", e.predictionSource());
				m.put("stability", e.stability());
				m.put("airframe", e.airframeMaterial());
				m.put("fins", e.finMaterial());
				m.put("recovery", e.recovery());
			}
			designs.add(m);
		}
		out.put("designs", designs);
		List<Map<String, Object>> flights = new ArrayList<>();
		for (Library.Flight f : scan.flights()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("log", f.relPath());
			m.put("path", f.file().toString());
			m.put("year", f.year());
			m.put("apogee", f.apogee());
			if (f.design() != null) {
				m.put("design", f.design().relPath());
				m.put("predictedApogee", f.design().predictedApogee());
			}
			flights.add(m);
		}
		out.put("flights", flights);
		out.put("skipped", scan.skipped());
		return out;
	}

	// ------------------------------------------------------------------------------------------- targets

	static Map<String, Object> setTargets(Context ctx, JsonObject patch) throws IOException {
		for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
			Targets.Metric m = Targets.metric(e.getKey());
			if (m == null || !e.getValue().isJsonObject()) {
				throw new ToolException("Unknown target '" + e.getKey() + "'.");
			}
			for (String k : e.getValue().getAsJsonObject().keySet()) {
				if (!List.of("min", "max", "goal", "note").contains(k)) {
					throw new ToolException("A target has min, max, goal and note, not '" + k + "'.");
				}
			}
			Targets.bound(e.getValue().getAsJsonObject(), "min", m);
			Targets.bound(e.getValue().getAsJsonObject(), "max", m);
			Targets.bound(e.getValue().getAsJsonObject(), "goal", m);
		}
		JsonObject wrapped = new JsonObject();
		wrapped.add("targets", patch);
		Map<String, Object> out = new LinkedHashMap<>(setStandards(ctx, wrapped));
		JsonObject targets = Targets.targetsOf(ctx.standards());
		out.put("targets", targets.keySet());
		return out;
	}

	/** The team standards file: the one loaded, else openrocket-mcp.json in the designs folder. */
	static Path standardsFile(Context ctx) {
		return ctx.standards().source() != null ? ctx.standards().source()
				: (ctx.workspace() != null ? ctx.workspace() : Path.of("").toAbsolutePath()).resolve(Standards.FILE_NAME);
	}

	static JsonObject teamFile(Path file) throws IOException {
		return Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
	}

	/**
	 * Merges a change into the team standards file and uses the result at once. The file keeps only what the team set
	 * (the built-in defaults stay defaults, so later improvements to them still apply); null puts a value back to its
	 * default. The change is checked before anything is written.
	 */
	static Map<String, Object> setStandards(Context ctx, JsonObject patch) throws IOException {
		Path file = standardsFile(ctx);
		JsonObject team = teamFile(file);
		apply(team, patch);
		Standards next = Standards.fromTeam(team, file);
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, Standards.pretty(team));
		ctx.setStandards(next);
		return Map.of("saved", file.toString());
	}

	/** Deep merge where null removes the key, and an object left empty goes too. */
	static void apply(JsonObject target, JsonObject patch) {
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

	/** Values not set (null, such as the launch site's altitude) as "", so they reach the page as fields to fill in. */
	static JsonObject unset(JsonObject o) {
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			if (e.getValue().isJsonNull()) {
				e.setValue(new com.google.gson.JsonPrimitive(""));
			} else if (e.getValue().isJsonObject()) {
				unset(e.getValue().getAsJsonObject());
			}
		}
		return o;
	}

	/** What the standards page shows: the team's own values, the values in use, the defaults, and the rule sets. */
	static Map<String, Object> standards(Context ctx) throws IOException {
		Path file = standardsFile(ctx);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("file", file.toString());
		out.put("fileExists", Files.exists(file));
		out.put("team", teamFile(file));
		out.put("merged", unset(ctx.standards().data().deepCopy()));
		out.put("defaults", unset(Standards.defaultsJson()));
		List<Map<String, String>> rules = new ArrayList<>();
		for (String id : Standards.BUILT_IN_RULES) {
			rules.add(Map.of("id", id, "name", Standards.ruleSetName(id)));
		}
		rules.add(Map.of("id", "none", "name", "None (team standards only)"));
		out.put("ruleSets", rules);
		out.put("ruleSet", ctx.standards().rulesName());
		return out;
	}

	// ------------------------------------------------------------------------------------------- motors and configuration

	static Map<String, Object> configs(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("configurations", configList(r));
		out.put("selected", r.getSelectedConfiguration().getId().toString());
		List<Map<String, Object>> mounts = new ArrayList<>(), recovery = new ArrayList<>(), stages = new ArrayList<>();
		for (RocketComponent c : r) {
			if (c instanceof MotorMount mm && mm.isMotorMount()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", Components.shortId(c));
				m.put("name", c.getName());
				m.put("stage", c.getStage().getName());
				m.put("innerDiameter", mm.getMotorMountDiameter());
				Map<String, Object> per = new LinkedHashMap<>();
				for (FlightConfigurationId id : r.getIds()) {
					per.put(id.toString(), motor(mm.getMotorConfig(id)));
				}
				m.put("motors", per);
				mounts.add(m);
			}
			if (c instanceof RecoveryDevice rd) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", Components.shortId(c));
				m.put("name", c.getName());
				m.put("type", c.getClass().getSimpleName());
				Map<String, Object> per = new LinkedHashMap<>();
				for (FlightConfigurationId id : r.getIds()) {
					DeploymentConfiguration dc = rd.getDeploymentConfigurations().get(id);
					per.put(id.toString(), Map.of("event", dc.getDeployEvent().name(), "altitude", dc.getDeployAltitude(), "delay",
							dc.getDeployDelay()));
				}
				m.put("deployment", per);
				recovery.add(m);
			}
			if (c instanceof AxialStage st) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", Components.shortId(c));
				m.put("name", c.getName());
				m.put("stageNumber", st.getStageNumber());
				Map<String, Object> per = new LinkedHashMap<>();
				for (FlightConfigurationId id : r.getIds()) {
					StageSeparationConfiguration sc = st.getSeparationConfigurations().get(id);
					per.put(id.toString(), Map.of("event", sc.getSeparationEvent().name(), "delay", sc.getSeparationDelay()));
				}
				m.put("separation", per);
				stages.add(m);
			}
		}
		out.put("mounts", mounts);
		out.put("recovery", recovery);
		out.put("stages", stages);
		return out;
	}

	static Map<String, Object> motor(MotorConfiguration mc) {
		Map<String, Object> m = new LinkedHashMap<>();
		Motor mo = mc.getMotor();
		if (mo == null) {
			return m;
		}
		m.put("designation", mo.getDesignation());
		m.put("manufacturer", mo instanceof ThrustCurveMotor t ? t.getManufacturer().getDisplayName() : "");
		m.put("totalImpulse", mo.getTotalImpulseEstimate());
		m.put("averageThrust", mo.getAverageThrustEstimate());
		m.put("burnTime", mo.getBurnTimeEstimate());
		m.put("diameter", mo.getDiameter());
		m.put("length", mo.getLength());
		m.put("ejectionDelay", mc.getEjectionDelay());
		m.put("plugged", mc.getEjectionDelay() == Motor.PLUGGED_DELAY);
		m.put("ignitionEvent", mc.getIgnitionEvent().name());
		m.put("ignitionDelay", mc.getIgnitionDelay());
		if (mo instanceof ThrustCurveMotor t) {
			m.put("delays", t.getStandardDelays());
		}
		return m;
	}

	static Map<String, Object> motorDelay(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		RocketComponent c = Components.find(r, a.str("mount"));
		if (!(c instanceof MotorMount mm) || !mm.isMotorMount()) {
			throw new ToolException(c.getName() + " is not a motor mount.");
		}
		FlightConfigurationId id = Components.config(r, a.str("configuration")).getId();
		MotorConfiguration mc = mm.getMotorConfig(id);
		if (a.bool("remove", false)) {
			mm.getMotorConfigurationSet().set(id, new MotorConfiguration(mm, id));
		} else {
			if (mc.getMotor() == null) {
				throw new ToolException("No motor in " + c.getName() + " for this configuration.");
			}
			if (a.bool("plugged", false)) {
				mc.setEjectionDelay(Motor.PLUGGED_DELAY);
			} else if (a.has("ejectionDelay")) {
				mc.setEjectionDelay(a.num("ejectionDelay"));
			}
			if (a.has("ignitionEvent")) {
				mc.setIgnitionEvent(info.openrocket.core.motor.IgnitionEvent.valueOf(a.str("ignitionEvent")));
			}
			if (a.has("ignitionDelay")) {
				mc.setIgnitionDelay(a.num("ignitionDelay"));
			}
		}
		r.fireComponentChangeEvent(info.openrocket.core.rocketcomponent.ComponentChangeEvent.MOTOR_CHANGE);
		d.doc.setSaved(false);
		return motor(mm.getMotorConfig(id));
	}

	static Map<String, Object> config(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		FlightConfiguration fc = Components.config(r, a.str("configuration"));
		FlightConfigurationId old = fc.getId();
		if (a.str("action").equals("remove")) {
			if (r.getIds().size() <= 1) {
				throw new ToolException("A design keeps at least one flight configuration.");
			}
			r.removeFlightConfiguration(old);
			d.doc.setSaved(false);
			return Map.of("removed", fc.getName(), "selected", r.getSelectedConfiguration().getId().toString());
		}
		FlightConfigurationId id = new FlightConfigurationId();
		FlightConfiguration copy = fc.copy(id);
		r.setFlightConfiguration(id, copy);
		for (RocketComponent c : r) {
			if (c instanceof FlightConfigurableComponent fcc) {
				fcc.copyFlightConfiguration(old, id);
			}
		}
		if (fc.isNameOverridden()) {
			copy.setName(fc.getNameRaw() + " (copy)");
		}
		r.setSelectedConfiguration(id);
		d.doc.setSaved(false);
		return Map.of("id", id.toString(), "name", copy.getName());
	}

	static Map<String, Object> motors(Context ctx, Args a) {
		double minD = Double.NaN, maxD = Double.NaN;
		if (a.has("mount") && a.has("designId")) {
			RocketComponent c = Components.find(ctx.designs.get(a.str("designId")).doc.getRocket(), a.str("mount"));
			if (c instanceof MotorMount mm) {
				maxD = mm.getMotorMountDiameter() + 1e-4;
				minD = mm.getMotorMountDiameter() - 1e-3;
			}
		}
		String text = a.str("text", "").trim();
		List<ThrustCurveMotor> all = Motors.search(new Motors.Filter(minD, maxD, Double.NaN, a.str("minClass", null),
				a.str("maxClass", null), null, null, null, a.bool("availableOnly", false)));
		List<Map<String, Object>> out = new ArrayList<>();
		int limit = Math.max(1, a.integer("limit", 300)), matches = 0;
		String t = text.toLowerCase(java.util.Locale.ROOT);
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (ThrustCurveMotor m : all) {
			// The same motor from two data files: same digest, or the same maker, designation and numbers.
			String same = m.getManufacturer().getDisplayName() + "|" + m.getDesignation() + "|"
					+ Math.round(m.getTotalImpulseEstimate()) + "|" + Math.round(m.getBurnTimeEstimate() * 100);
			if (!seen.add(m.getDigest()) || !seen.add(same)) {
				continue;
			}
			String hay = (m.getManufacturer().getDisplayName() + " " + m.getManufacturer().getSimpleName() + " " + m.getDesignation()
					+ " " + m.getCommonName()).toLowerCase(java.util.Locale.ROOT);
			if (!t.isEmpty() && !java.util.Arrays.stream(t.split("\\s+")).allMatch(hay::contains)) {
				continue;
			}
			matches++;
			if (out.size() < limit) {
				out.add(motorInfo(m));
			}
		}
		return Map.of("matches", matches, "motors", out);
	}

	/** The designs folder's motors/: motor files the team imported and motors made from its own test data. */
	static Path motorsDir(Context ctx) {
		return (ctx.workspace() != null ? ctx.workspace() : Path.of("").toAbsolutePath()).resolve("motors");
	}

	private static volatile Path teamMotorsLoaded;

	/**
	 * Loads the team's motors once, when the page first connects (before a design that uses one is opened), so a design
	 * keeps its own motor from one session to the next.
	 */
	static synchronized void teamMotors(Context ctx) {
		Path dir = motorsDir(ctx);
		if (dir.equals(teamMotorsLoaded) || !Files.isDirectory(dir)) {
			return;
		}
		teamMotorsLoaded = dir;
		try (var files = Files.list(dir)) {
			for (Path f : files.sorted().toList()) {
				String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
				if (n.endsWith(".eng") || n.endsWith(".rse") || n.endsWith(".zip")) {
					try {
						Motors.importFile(f);
					} catch (IOException | RuntimeException e) {
						io.github.openrocketmcp.mcp.Log.info("Skipped motor file " + f + ": " + e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			io.github.openrocketmcp.mcp.Log.info("Could not read " + dir + ": " + e.getMessage());
		}
	}

	/** Imports a motor file and keeps a copy in the designs folder's motors/, so it is there next time. */
	static Map<String, Object> importMotor(Context ctx, String path) throws IOException {
		Path src = ctx.path(path).toAbsolutePath().normalize();
		List<ThrustCurveMotor> found = Motors.importFile(src); // checks the file before it is kept
		Path dir = motorsDir(ctx);
		Path kept = src;
		if (!src.startsWith(dir)) {
			Files.createDirectories(dir);
			String name = src.getFileName().toString();
			kept = dir.resolve(name);
			for (int i = 2; Files.exists(kept) && Files.mismatch(kept, src) != -1; i++) {
				int dot = name.lastIndexOf('.');
				kept = dir.resolve(name.substring(0, dot) + "-" + i + name.substring(dot));
			}
			Files.copy(src, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		List<Map<String, Object>> motors = new ArrayList<>();
		for (ThrustCurveMotor m : found) {
			motors.add(motorInfo(m));
		}
		return Map.of("file", kept.toString(), "motors", motors);
	}

	static Map<String, Object> motorInfo(ThrustCurveMotor m) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("digest", m.getDigest());
		o.put("designation", m.getDesignation());
		o.put("commonName", m.getCommonName());
		o.put("manufacturer", m.getManufacturer().getDisplayName());
		o.put("class", Motors.impulseClass(m.getTotalImpulseEstimate()));
		o.put("diameter", m.getDiameter());
		o.put("length", m.getLength());
		o.put("totalImpulse", m.getTotalImpulseEstimate());
		o.put("averageThrust", m.getAverageThrustEstimate());
		o.put("maxThrust", m.getMaxThrustEstimate());
		o.put("burnTime", m.getBurnTimeEstimate());
		o.put("launchMass", m.getLaunchMass());
		o.put("propellantMass", m.getPropellantMass());
		o.put("type", m.getMotorType().name());
		o.put("delays", m.getStandardDelays());
		o.put("available", m.isAvailable());
		return o;
	}

	// ------------------------------------------------------------------------------------------- simulations

	static Simulation sim(Designs.Design d, int i) {
		List<Simulation> all = d.doc.getSimulations();
		if (i < 0 || i >= all.size()) {
			throw new ToolException("No simulation " + i + ".");
		}
		return all.get(i);
	}

	static Map<String, Object> sims(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		List<Map<String, Object>> out = new ArrayList<>();
		int i = 0;
		for (Simulation s : d.doc.getSimulations()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("index", i++);
			m.put("name", s.getName());
			FlightConfigurationId id = s.getFlightConfigurationId();
			m.put("configuration", id.toString());
			m.put("configurationName", r.getFlightConfiguration(id).getName());
			m.put("status", s.getStatus().name());
			m.put("options", options(s.getOptions()));
			m.put("results", results(s));
			out.add(m);
		}
		return Map.of("simulations", out, "configurations", configList(r));
	}

	static Map<String, Object> options(SimulationOptions o) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("rodLength", o.getLaunchRodLength());
		m.put("rodAngle", o.getLaunchRodAngle());
		m.put("rodDirection", o.getLaunchRodDirection());
		m.put("launchIntoWind", o.getLaunchIntoWind());
		m.put("windSpeed", Winds.speed(o));
		m.put("windDirection", Winds.direction(o));
		m.put("windTurbulence", Winds.turbulence(o));
		m.put("windProfile", Winds.isMultiLevel(o));
		m.put("launchAltitude", o.getLaunchAltitude());
		m.put("latitude", o.getLaunchLatitude());
		m.put("longitude", o.getLaunchLongitude());
		m.put("isaAtmosphere", o.isISAAtmosphere());
		m.put("temperature", o.getLaunchTemperature());
		m.put("pressure", o.getLaunchPressure());
		m.put("timeStep", o.getTimeStep());
		return m;
	}

	static Map<String, Object> simNew(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		FlightConfiguration fc = Components.config(r, a.str("configuration", null));
		Simulation s = new Simulation(d.doc, r);
		s.setFlightConfigurationId(fc.getId());
		s.setName("Simulation " + (d.doc.getSimulations().size() + 1));
		ctx.standards().applyLaunchDefaults(s.getOptions());
		d.doc.addSimulation(s);
		d.doc.setSaved(false);
		return Map.of("index", d.doc.getSimulations().size() - 1, "name", s.getName());
	}

	static Map<String, Object> simEdit(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Simulation s = sim(d, a.integer("simulation", -1));
		SimulationOptions o = s.getOptions();
		String key = a.str("key");
		var v = a.get("value");
		switch (key) {
			case "name" -> s.setName(v.getAsString());
			case "configuration" -> s.setFlightConfigurationId(Components.config(d.doc.getRocket(), v.getAsString()).getId());
			case "rodLength" -> o.setLaunchRodLength(positive(Args.qtyOf(v, key, Dim.LENGTH), key));
			case "rodAngle" -> o.setLaunchRodAngle(Args.qtyOf(v, key, Dim.ANGLE));
			case "rodDirection" -> o.setLaunchRodDirection(Args.qtyOf(v, key, Dim.ANGLE));
			case "launchIntoWind" -> o.setLaunchIntoWind(v.getAsBoolean());
			case "windSpeed" -> Winds.setGround(o, positive(Args.qtyOf(v, key, Dim.VELOCITY), key), Winds.direction(o));
			case "windDirection" -> Winds.setGround(o, Winds.speed(o), Args.qtyOf(v, key, Dim.ANGLE));
			case "windTurbulence" -> Winds.setTurbulence(o, positive(v.getAsDouble(), key));
			case "launchAltitude" -> o.setLaunchAltitude(Args.qtyOf(v, key, Dim.DISTANCE));
			case "latitude" -> o.setLaunchLatitude(v.getAsDouble());
			case "longitude" -> o.setLaunchLongitude(v.getAsDouble());
			case "isaAtmosphere" -> o.setISAAtmosphere(v.getAsBoolean());
			case "temperature" -> o.setLaunchTemperature(Args.qtyOf(v, key, Dim.TEMPERATURE));
			case "pressure" -> o.setLaunchPressure(positive(Args.qtyOf(v, key, Dim.PRESSURE), key));
			case "timeStep" -> o.setTimeStep(positive(Args.qtyOf(v, key, Dim.TIME), key));
			default -> throw new ToolException("Unknown simulation field '" + key + "'.");
		}
		d.doc.setSaved(false);
		return Map.of("options", options(o), "status", s.getStatus().name());
	}

	private static double positive(double v, String key) {
		if (!(v >= 0) || !Double.isFinite(v)) {
			throw new ToolException(key + " must be zero or more.");
		}
		return v;
	}

	static Map<String, Object> simRun(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		List<Simulation> todo = a.has("simulation") ? List.of(sim(d, a.integer("simulation", -1))) : d.doc.getSimulations();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Simulation s : todo) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", s.getName());
			if (!s.getRocket().getFlightConfiguration(s.getFlightConfigurationId()).hasMotors()) {
				m.put("error", "The configuration has no motors: choose one on the Motors page.");
				m.put("errorKind", ToolException.Kind.INPUT.id());
			} else {
				try {
					Sims.ensure(s);
					m.put("results", results(s));
				} catch (ToolException e) {
					m.put("error", e.getMessage());
					m.put("errorKind", e.kind().id());
				} catch (RuntimeException e) {
					// OpenRocket failing inside its own simulation: not the user's input; the log has the trace.
					Log.error("Simulation " + s.getName() + " failed", e);
					m.put("error", "OpenRocket failed to simulate it (" + e.getClass().getSimpleName()
							+ (e.getMessage() == null ? "" : ": " + e.getMessage()) + ").");
					m.put("errorKind", ToolException.Kind.SYSTEM.id());
				}
			}
			out.add(m);
		}
		d.doc.setSaved(false);
		return Map.of("runs", out);
	}

	static Map<String, Object> series(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Simulation s = sim(d, a.integer("simulation", -1));
		FlightData f = s.getSimulatedData();
		if (f == null || f.getBranchCount() == 0) {
			throw new ToolException("Run the simulation first.");
		}
		int bi = Math.max(0, Math.min(f.getBranchCount() - 1, a.integer("branch", 0)));
		FlightDataBranch b = f.getBranch(bi);
		Branch br = Branch.of(b);
		int n = br.size(), step = Math.max(1, (int) Math.ceil(n / 2000.0));
		List<Map<String, Object>> vars = new ArrayList<>();
		for (FlightDataType t : b.getTypes()) {
			double[] col = br.col(t);
			boolean any = false;
			for (double v : col) {
				if (Double.isFinite(v)) {
					any = true;
					break;
				}
			}
			if (!any) {
				continue;
			}
			List<Double> vals = new ArrayList<>();
			for (int i = 0; i < n; i += step) {
				vals.add(i < col.length && Double.isFinite(col[i]) ? col[i] : null);
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", t.getSymbol());
			m.put("name", t.getName());
			Dim dim = Sims.dimOf(t.getUnitGroup());
			if (dim != null) {
				m.put("dim", dim.name());
			}
			m.put("values", vals);
			vars.add(m);
		}
		List<Map<String, Object>> events = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			events.add(Map.of("type", e.getType().name(), "time", e.getTime()));
		}
		List<String> branches = new ArrayList<>();
		for (FlightDataBranch fb : f.getBranches()) {
			branches.add(fb.getName());
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("branches", branches);
		out.put("branch", bi);
		out.put("variables", vars);
		out.put("events", events);
		return out;
	}
}
