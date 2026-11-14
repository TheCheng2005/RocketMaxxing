package io.github.openrocketmcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.StageSeparationConfiguration;
import io.github.openrocketmcp.calc.Packing;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Opening, inspecting, editing and saving designs. */
public final class DesignTools {
	private DesignTools() {
	}

	static final String DESIGN_ID = "Design id from open_design (e.g. \"d1\"). Optional when only one design is open.";
	static final String CONFIG = "Flight configuration id prefix, index or name. Default: the selected configuration.";

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("open_design", "Open or create a rocket design",
				"Open an OpenRocket .ork file (any OpenRocket version) or a RockSim .rkt file, one of OpenRocket's bundled "
						+ "examples, or a new empty rocket. Anything OpenRocket had to change while reading an older or foreign file is "
						+ "listed as conversionWarnings. Returns a designId "
						+ "used by every other tool. Edits stay in memory until save_design. Examples: "
						+ String.join(", ", Designs.EXAMPLES) + ".",
				Schema.object()
						.str("path", "Path to an .ork file, or a RockSim .rkt or RASAero .CDX1 file to import.", false)
						.str("example", "Name of a bundled OpenRocket example (partial match).", false)
						.str("newRocketName", "Create a new empty rocket with this name.", false)
						.build(),
				false, a -> {
					Designs.Design d;
					if (a.has("path")) {
						d = ctx.designs.open(ctx.path(a.str("path")));
					} else if (a.has("example")) {
						d = ctx.designs.openExample(a.str("example"));
					} else if (a.has("newRocketName")) {
						d = ctx.designs.create(a.str("newRocketName"));
					} else {
						throw new ToolException("Pass path, example or newRocketName.");
					}
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("designId", d.id);
					out.put("name", d.name());
					out.put("file", d.path == null ? null : d.path.toString());
					if (d.importedFrom != null) {
						out.put("importedFrom", d.importedFrom.toString());
						out.put("save", "Imported, not an .ork: save_design with a path ending in .ork (the original is left as it is).");
					}
					if (!d.loadWarnings.isEmpty()) {
						out.put("conversionWarnings", d.loadWarnings);
						out.put("checkConversion", "OpenRocket changed or dropped the items above while reading this file (an older "
								+ "version or another program). Check them with get_design before trusting the numbers.");
					}
					if (d.importReport != null) {
						out.put("importReport", d.importReport.render());
						if (!d.importReport.findings().isEmpty()) {
							out.put("checkImport", "Tell the user what importReport found before working with the numbers: 'lost' was "
									+ "not brought in, 'check' came in but may be wrong, 'note' is what to set up next.");
						}
					}
					out.put("next", "Call get_design to see components, stability and simulations.");
					return out;
				}));

		s.tool(new ToolDef("list_designs", "List open designs", "List designs open in this session.",
				Schema.object().build(), true, a -> {
					List<Map<String, Object>> out = new ArrayList<>();
					for (Designs.Design d : ctx.designs.all()) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("designId", d.id);
						m.put("name", d.name());
						m.put("file", d.path == null ? null : d.path.toString());
						m.put("origin", d.origin);
						m.put("unsavedChanges", !d.doc.isSaved());
						out.add(m);
					}
					return out;
				}));

		s.tool(new ToolDef("save_design", "Save a design",
				"Write the design to an .ork file (OpenRocket's format, openable in the OpenRocket app). Without path, overwrites "
						+ "the file it was opened from. Ask the user before overwriting their file. A path ending in .rkt "
						+ "(RockSim) or .CDX1 (RASAero II) writes a copy for that program instead; the design stays tied to "
						+ "its .ork, and notes lists what the other format could not hold.",
				Schema.object().str("designId", DESIGN_ID, false).str("path", "Destination: .ork, or .rkt / .CDX1 for a copy.", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					List<String> notes = new ArrayList<>();
					Path p = ctx.designs.save(d, a.has("path") ? ctx.path(a.str("path")) : null, notes);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("saved", p.toString());
					String n = p.getFileName().toString();
					String format = Designs.FORMATS.get(n.substring(n.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT));
					out.put("format", format);
					if (!"OpenRocket".equals(format)) {
						out.put("copy", true);
						out.put("note", "A copy for " + format + ": simulations, flight configurations and some parts are not kept "
								+ "in that format. Keep the .ork as the design's own file.");
						if (!notes.isEmpty()) {
							out.put("notes", notes);
						}
					}
					return out;
				}));

		s.tool(new ToolDef("close_design", "Close a design", "Close a design without saving.",
				Schema.object().str("designId", DESIGN_ID, true).build(), false, a -> {
					ctx.designs.close(a.str("designId"));
					return Map.of("closed", a.str("designId"));
				}));

		s.tool(new ToolDef("get_design", "Design overview with stability",
				"Component tree (ids, dimensions, masses), flight configurations with motors, simulations, and static stability "
						+ "(mass, CG, CP, margin in calibers) for the full vehicle and for each stack left after a stage separates "
						+ "(e.g. the sustainer alone). Start here.",
				Schema.object().str("designId", DESIGN_ID, false).str("configuration", CONFIG, false)
						.num("mach", "Mach number for the CP calculation (default 0.3).", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Rocket rocket = d.doc.getRocket();
					FlightConfiguration fc = Components.config(rocket, a.str("configuration", null));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("designId", d.id);
					out.put("name", rocket.getName());
					out.put("unsavedChanges", !d.doc.isSaved());
					out.put("components", Components.tree(rocket));
					List<Map<String, Object>> configs = new ArrayList<>();
					for (FlightConfigurationId id : rocket.getIds()) {
						FlightConfiguration c = rocket.getFlightConfiguration(id);
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("id", id.toString().substring(0, 8));
						m.put("name", c.getName());
						m.put("selected", c == rocket.getSelectedConfiguration());
						m.put("motors", Analysis.motors(c));
						configs.add(m);
					}
					out.put("flightConfigurations", configs);
					out.put("analyzedConfiguration", fc.getName());
					out.put("stability", Analysis.stageStacks(fc, a.num("mach", 0.3)));
					List<String> sims = new ArrayList<>();
					List<Simulation> list = d.doc.getSimulations();
					for (int i = 0; i < list.size(); i++) {
						Simulation sim = list.get(i);
						sims.add(i + ": " + sim.getName() + " [" + rocket.getFlightConfiguration(sim.getFlightConfigurationId()).getName()
								+ ", " + sim.getStatus().name().toLowerCase(Locale.ROOT) + "]");
					}
					out.put("simulations", sims);
					if (Analysis.hasDiameterChange(fc)) {
						out.put("note", "Airframe diameter changes: Launch Canada requires RASAero CP/CD overrides and >= 2 cal margin in that case.");
					}
					return out;
				}));

		s.tool(new ToolDef("describe_component", "List a component's editable properties",
				"All editable properties of one component with current values (lengths in the display units). Use the property "
						+ "names with edit_components.",
				Schema.object().str("designId", DESIGN_ID, false).str("component", "Component id or unique name.", true).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					RocketComponent c = Components.find(d.doc.getRocket(), a.str("component"));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("id", Components.shortId(c));
					out.put("name", c.getName());
					out.put("type", c.getClass().getSimpleName());
					out.put("properties", Components.describe(c));
					if (c instanceof RecoveryDevice rd) {
						DeploymentConfiguration dc = rd.getDeploymentConfigurations().get(d.doc.getRocket().getSelectedConfiguration().getId());
						out.put("deployment", dc.getDeployEvent().name() + ", altitude " + Units.fmt(dc.getDeployAltitude(), Dim.DISTANCE)
								+ ", delay " + Units.fmt(dc.getDeployDelay(), Dim.TIME) + " (change with set_deployment)");
					}
					return out;
				}));

		JsonObject change = Schema.object()
				.str("component", "Component id or unique name.", true)
				.obj("properties", "Property -> value, e.g. {\"length\": \"24 in\", \"thickness\": \"2 mm\", \"finCount\": 4, "
						+ "\"cd\": 0.8, \"diameter\": \"60 in\", \"massOverridden\": true, \"overrideMass\": \"1.2 kg\", \"name\": \"Main\"}. "
						+ "Numbers are SI; strings may carry units. \"outerDiameter\"/\"diameter\" are accepted for radius properties.", true)
				.build();
		s.tool(new ToolDef("edit_components", "Change component properties",
				"Apply property changes to one or more components (in memory; save_design writes them). Returns each change and "
						+ "the new stability. Use describe_component for property names. Confirm with the user before large redesigns.",
				Schema.object().str("designId", DESIGN_ID, false)
						.array("changes", "List of {component, properties}.", change, true).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Rocket rocket = d.doc.getRocket();
					List<String> done = new ArrayList<>();
					Set<String> warnings = new LinkedHashSet<>();
					for (Args ch : a.objList("changes")) {
						RocketComponent c = Components.find(rocket, ch.str("component"));
						JsonObject props = ch.obj("properties").raw();
						for (Map.Entry<String, JsonElement> e : props.entrySet()) {
							done.add(Components.set(c, e.getKey(), e.getValue()));
						}
						String w = Components.overrideWarning(c);
						if (w != null) {
							warnings.add(w);
						}
					}
					d.doc.setSaved(false);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("applied", done);
					if (!warnings.isEmpty()) {
						out.put("warnings", warnings);
					}
					out.put("stability", Analysis.stageStacks(rocket.getSelectedConfiguration(), 0.3));
					return out;
				}));

		s.tool(new ToolDef("add_component", "Add a component",
				"Add a component under a parent (stage, body tube, inner tube...). Types: " + Components.CREATABLE
						+ ". Set dimensions via properties (same format as edit_components). New stages go under the rocket.",
				Schema.object().str("designId", DESIGN_ID, false)
						.str("parent", "Parent component id or name (use the rocket name to add a stage).", true)
						.str("type", "Component type, e.g. Parachute, ShockCord, MassComponent, TrapezoidFinSet.", true)
						.str("name", "Name for the new component.", false)
						.obj("properties", "Initial property values.", false)
						.integer("index", "Position among the parent's children (default: last).", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Rocket rocket = d.doc.getRocket();
					RocketComponent parent = a.str("parent").equalsIgnoreCase(rocket.getName())
							? rocket : Components.find(rocket, a.str("parent"));
					RocketComponent c = Components.create(a.str("type"));
					if (!parent.isCompatible(c)) {
						throw new ToolException("A " + c.getComponentName() + " cannot be placed in " + parent.getName()
								+ " (" + parent.getComponentName() + ").");
					}
					if (a.has("name")) {
						c.setName(a.str("name"));
					}
					if (a.has("index")) {
						parent.addChild(c, Math.max(0, Math.min(a.integer("index", 0), parent.getChildCount())));
					} else {
						parent.addChild(c);
					}
					// Atomic: if any property is rejected, take the component out again.
					List<String> done = new ArrayList<>();
					try {
						for (Map.Entry<String, JsonElement> e : a.obj("properties").raw().entrySet()) {
							done.add(Components.set(c, e.getKey(), e.getValue()));
						}
					} catch (RuntimeException e) {
						parent.removeChild(c);
						throw e instanceof ToolException te ? new ToolException(te.getMessage() + " Nothing was added.") : e;
					}
					// A new parachute without a packed size gets a realistic one (OpenRocket's default is a tiny bundle).
					var given = a.obj("properties").raw().keySet().stream().map(k -> k.toLowerCase(Locale.ROOT)).toList();
					if (c instanceof Parachute p && given.stream().noneMatch(k -> k.contains("length")
							|| k.contains("radius") || k.contains("packed"))) {
						double inner = parent instanceof BodyTube bt ? bt.getInnerRadius() * 2 : 0.08;
						double pd = 0.9 * inner;
						double vol = Packing.parachuteVolume(p.getDiameter());
						p.setRadius(pd / 2);
						p.setLength(Math.max(vol / (Math.PI * pd * pd / 4), 0.5 * pd));
						done.add("packed size estimated: " + Units.fmt(pd, Dim.LENGTH) + " dia x "
								+ Units.fmt(p.getLength(), Dim.LENGTH)
								+ " (typical for the canopy size; set length / radius from your chute's packed size)");
					}
					d.doc.setSaved(false);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("id", Components.shortId(c));
					out.put("name", c.getName());
					out.put("applied", done);
					String w = Components.overrideWarning(c);
					if (w != null) {
						out.put("warning", w);
					}
					return out;
				}));

		s.tool(new ToolDef("remove_component", "Remove a component", "Remove a component and its children from the design.",
				Schema.object().str("designId", DESIGN_ID, false).str("component", "Component id or unique name.", true).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					RocketComponent c = Components.find(d.doc.getRocket(), a.str("component"));
					if (c.getParent() == null) {
						throw new ToolException("Cannot remove the rocket itself.");
					}
					c.getParent().removeChild(c);
					d.doc.setSaved(false);
					return Map.of("removed", c.getName());
				}));

		s.tool(new ToolDef("set_deployment", "Set when a recovery device deploys",
				"Set the deployment event of a parachute/streamer for a flight configuration: apogee (with delay), altitude (main at "
						+ "e.g. \"1000 ft\" AGL on descent), ejection (motor ejection charge), lower_stage_separation, launch, never.",
				Schema.object().str("designId", DESIGN_ID, false)
						.str("component", "Recovery device id or name.", true)
						.enumStr("event", "Deployment event.", true, "apogee", "altitude", "ejection", "lower_stage_separation", "launch", "never")
						.qty("altitude", "Deployment altitude above ground for event=altitude.", false)
						.qty("delay", "Delay after the event, e.g. \"1 s\".", false)
						.str("configuration", CONFIG + " Use \"all\" for every configuration.", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Rocket rocket = d.doc.getRocket();
					RecoveryDevice rd = Components.find(rocket, a.str("component"), RecoveryDevice.class, "recovery device");
					DeploymentConfiguration.DeployEvent ev = DeploymentConfiguration.DeployEvent.valueOf(a.str("event").toUpperCase(Locale.ROOT));
					boolean all = "all".equalsIgnoreCase(a.str("configuration", ""));
					List<FlightConfigurationId> ids = all ? rocket.getIds()
							: List.of(Components.config(rocket, a.str("configuration", null)).getId());
					UnaryOperator<DeploymentConfiguration> edit = dc -> {
						dc.setDeployEvent(ev);
						if (a.has("altitude")) {
							dc.setDeployAltitude(aboveGround(a));
						}
						if (a.has("delay")) {
							dc.setDeployDelay(a.qty("delay", Dim.TIME));
						}
						return dc;
					};
					var set = rd.getDeploymentConfigurations();
					if (all) {
						// Also the default, so flight configurations created later (e.g. by set_motor) deploy the same way.
						set.setDefault(edit.apply(set.getDefault().copy(null)));
					}
					for (FlightConfigurationId id : ids) {
						set.set(id, edit.apply(set.get(id).copy(id)));
					}
					d.doc.setSaved(false);
					DeploymentConfiguration now = ids.isEmpty() ? set.getDefault() : set.get(ids.get(0));
					return Map.of("device", rd.getName(), "deployment", now.getDeployEvent().name() + ", altitude "
							+ Units.fmt(now.getDeployAltitude(), Dim.DISTANCE) + ", delay " + Units.fmt(now.getDeployDelay(), Dim.TIME),
							"configurations", all ? ids.size() + " existing, and the default for new ones" : "1");
				}));

		s.tool(new ToolDef("set_stage_separation", "Set when a stage separates",
				"Set the separation event of a (lower) stage: upper_ignition, ignition, burnout, ejection, apogee, launch, "
						+ "altitude_ascending/altitude_descending, never; plus an optional delay.",
				Schema.object().str("designId", DESIGN_ID, false)
						.str("stage", "Stage id or name.", true)
						.enumStr("event", "Separation event.", true, "upper_ignition", "ignition", "burnout", "ejection", "apogee",
								"launch", "altitude_ascending", "altitude_descending", "never")
						.qty("delay", "Delay after the event.", false)
						.qty("altitude", "Altitude for altitude events.", false)
						.str("configuration", CONFIG + " Use \"all\" for every configuration.", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Rocket rocket = d.doc.getRocket();
					AxialStage stage = Components.find(rocket, a.str("stage"), AxialStage.class, "stage");
					StageSeparationConfiguration.SeparationEvent ev = StageSeparationConfiguration.SeparationEvent.valueOf(a.str("event").toUpperCase(Locale.ROOT));
					boolean all = "all".equalsIgnoreCase(a.str("configuration", ""));
					List<FlightConfigurationId> ids = all ? rocket.getIds()
							: List.of(Components.config(rocket, a.str("configuration", null)).getId());
					UnaryOperator<StageSeparationConfiguration> edit = sc -> {
						sc.setSeparationEvent(ev);
						if (a.has("delay")) {
							sc.setSeparationDelay(a.qty("delay", Dim.TIME));
						}
						if (a.has("altitude")) {
							sc.setSeparationAltitude(aboveGround(a));
						}
						return sc;
					};
					if (all) {
						// Also the default, so flight configurations created later separate the same way.
						stage.getSeparationConfigurations().setDefault(edit.apply(stage.getSeparationConfigurations().getDefault().copy(null)));
					}
					for (FlightConfigurationId id : ids) {
						StageSeparationConfiguration sc = edit.apply(stage.getSeparationConfigurations().get(id).copy(id));
						stage.getSeparationConfigurations().set(id, sc);
					}
					d.doc.setSaved(false);
					return Map.of("stage", stage.getName(), "event", ev.name(), "configurations", ids.size());
				}));

		s.tool(new ToolDef("flight_configuration", "Create, select or rename flight configurations",
				"Flight configurations hold motor choices and deployment settings. action=create makes a new (empty) one, "
						+ "select makes one the default for other tools, rename renames it.",
				Schema.object().str("designId", DESIGN_ID, false)
						.enumStr("action", "What to do.", true, "create", "select", "rename")
						.str("configuration", "Existing configuration (for select/rename).", false)
						.str("name", "Name (for create/rename).", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Rocket rocket = d.doc.getRocket();
					FlightConfiguration fc;
					switch (a.str("action")) {
						case "create" -> {
							FlightConfigurationId id = new FlightConfigurationId();
							fc = rocket.createFlightConfiguration(id);
							if (a.has("name")) {
								fc.setName(a.str("name"));
							}
							rocket.setSelectedConfiguration(id);
						}
						case "select" -> {
							fc = Components.config(rocket, a.str("configuration"));
							rocket.setSelectedConfiguration(fc.getId());
						}
						case "rename" -> {
							fc = Components.config(rocket, a.str("configuration", null));
							fc.setName(a.str("name"));
						}
						default -> throw new ToolException("Unknown action.");
					}
					d.doc.setSaved(false);
					return Map.of("id", fc.getId().toString().substring(0, 8), "name", fc.getName(), "selected",
							rocket.getSelectedConfiguration() == fc);
				}));
	}

	/** An event altitude: above the pad, so not below it. */
	private static double aboveGround(Args a) {
		double h = a.qty("altitude", Dim.DISTANCE);
		if (h < 0) {
			throw new ToolException("altitude is above the launch site and cannot be negative (got " + Units.fmt(h, Dim.DISTANCE) + ").");
		}
		return h;
	}
}
