package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.ElectronicsSummary;

/**
 * Helps teams find what the server can do without reading the documentation. Each result carries, under
 * {@code youCouldAlsoAsk}, up to two related things the team has not used yet in this session, worded as they would
 * ask them; the server instructions tell the model to offer one when it fits. Each suggestion is made once per
 * session. {@code capabilities} gives the whole map by what the team is doing. Teams can turn the suggestions off
 * with {@code "suggestions": false} in their standards.
 */
public final class Suggestions {
	private Suggestions() {
	}

	/** Something the server can do, as a team member would ask for it. */
	record Feature(String tool, String group, String ask) {
	}

	static final List<Feature> FEATURES = List.of(
			new Feature("design_status", "Start and review", "Where do we stand, and what's left before the next review?"),
			new Feature("verify_claims", "Start and review", "Check our meeting notes and Slack threads against the design: do the numbers we quote still hold?"),
			new Feature("progress_log", "Start and review", "Keep a weekly record of where the design stands and draft our status update."),
			new Feature("show_design", "Start and review", "Show us the rocket and its flight: 3-D, cut-away, flight plots and checks we can click through."),
			new Feature("check_requirements", "Start and review", "Does it pass the competition rules, including in maximum wind?"),
			new Feature("targets", "Start and review", "How far are we from our own targets (apogee, rail exit speed, descent rates)?"),
			new Feature("generate_report", "Start and review", "Make the design review package (report, stability plots, data)."),
			new Feature("compare_designs", "Start and review", "What changed since the version we showed at the last review?"),
			new Feature("undo", "Start and review", "Undo that (every change in the session can be undone or redone)."),

			new Feature("add_avionics_bay", "Design and parts", "Lay out the avionics bay: two altimeters, a GPS tracker, charges and switches."),
			new Feature("search_parts", "Design and parts", "Use a real catalog tube, nose cone or coupler instead of a generic one."),
			new Feature("add_from_link", "Design and parts", "Here's a link to a part we want to buy: put it in the design."),
			new Feature("mass_budget", "Design and parts", "Here are our weigh-ins: how do they compare with the model?"),
			new Feature("design_library", "Design and parts", "Search our past rockets and flight logs (and how good our predictions were)."),

			new Feature("rank_motors", "Motors and flight", "Which motor gets us closest to our target altitude?"),
			new Feature("create_custom_motor", "Motors and flight", "Model our own motor or liquid engine from static-fire data."),
			new Feature("simulate_engine", "Liquid and hybrid programmes", "Predict our hybrid, liquid or solid engine's thrust before we fire it."),
			new Feature("monte_carlo", "Motors and flight", "Where will it land? Give us a landing map for Google Earth."),
			new Feature("optimize", "Motors and flight", "Find the ballast or fin size that hits our target apogee."),
			new Feature("sweep", "Motors and flight", "Show how apogee and stability change as we vary one thing (fin span, ballast, wind)."),
			new Feature("get_flight_data", "Motors and flight", "Plot or export any flight variable (altitude, Mach, stability) against time."),

			new Feature("ballast", "Stability, fins and aero", "How much nose weight do we need to meet the stability rules?"),
			new Feature("optimize_fins", "Stability, fins and aero", "Optimize our fin shape for the least drag within the rules."),
			new Feature("compare_shapes", "Stability, fins and aero", "Which nose cone and fin shapes fly best?"),
			new Feature("aero_analysis", "Stability, fins and aero", "Where does our drag come from, and how does it change with speed? (per stage: the sustainer alone)"),
			new Feature("import_aero_table", "Stability, fins and aero", "Use our RASAero or CFD aerodynamic data in the simulations."),

			new Feature("fin_flutter", "Structures and heating", "Will our fins flutter, and how thick do they need to be?"),
			new Feature("compare_materials", "Structures and heating", "Which material should our fins or airframe be: fiberglass, carbon or aluminum?"),
			new Feature("fin_fea", "Structures and heating", "Check the fins in finite-element analysis (CalculiX)."),
			new Feature("structural_loads", "Structures and heating", "What loads do our couplers and joints see in flight?"),
			new Feature("aero_heating", "Structures and heating", "Will the nose tip or fin edges get too hot?"),
			new Feature("roll_analysis", "Structures and heating", "How straight do our fins need to be (roll rate, resonance)?"),
			new Feature("export_geometry", "Structures and heating", "Export it for CFD or CAD (STL, fin templates, the CFD cases to run)."),

			new Feature("recovery_analysis", "Recovery", "What loads do the parachutes see when they open, and how many shear pins?"),
			new Feature("parachute_sizes", "Recovery", "Plot parachute size against descent rate for a few Cd values, and size ours."),
			new Feature("deployment_delay_sweep", "Recovery", "How late can the drogue fire before the pins or the harness break?"),
			new Feature("ejection_charge", "Recovery", "How much black powder for each bay?"),
			new Feature("recovery_bay_fit", "Recovery", "Will the parachute and shock cord fit in the bay?"),
			new Feature("descent_energy", "Recovery", "What is the landing energy of each section, and what if the main fails?"),

			new Feature("sensor_check", "Electronics", "Will our flight computer's sensors cope with this flight?"),
			new Feature("altimeter_settings", "Electronics", "What do we set the altimeters to (primary, backup, Mach lockout)?"),
			new Feature("sensor_data", "Electronics", "Give us simulated sensor data to test our flight software."),
			new Feature("power_budget", "Electronics", "Do our batteries last the pad wait, and do the e-matches get enough current?"),
			new Feature("radio_link", "Electronics", "Will we hear the GPS tracker all the way down and after landing?"),

			new Feature("weather_forecast", "Launch day", "What will the winds do at our launch site on launch day?"),
			new Feature("flight_card", "Launch day", "Make the launch-day flight card."),
			new Feature("rail_buttons", "Launch day", "Where should the rail buttons go, and what is our real rail exit speed?"),

			new Feature("compare_flight", "After the flight", "Here's our altimeter log: how did the flight compare with the simulation?"),

			new Feature("pressure_vessel", "Liquid and hybrid programmes", "Check our tank or chamber against the proof and burst-pressure rules."),
			new Feature("advanced_probation", "Liquid and hybrid programmes", "Which advanced probation level are we in, and what static-fire Isp do we need?"),

			new Feature("render_3d", "Pictures and video", "Show the rocket in 3-D, exploded, with a parts list."),
			new Feature("draw_rocket", "Pictures and video", "Draw a cut-away showing where everything sits inside."),
			new Feature("animate_flight", "Pictures and video", "Animate the flight for a presentation or social media."));

	/** What naturally comes next after each tool, most useful first. */
	static final Map<String, List<String>> NEXT = Map.ofEntries(
			Map.entry("open_design", List.of("show_design", "design_status", "capabilities")),
			Map.entry("get_design", List.of("render_3d", "draw_rocket", "mass_budget")),
			Map.entry("design_status", List.of("check_requirements", "progress_log", "generate_report")),
			Map.entry("verify_claims", List.of("progress_log", "design_status", "compare_designs")),
			Map.entry("progress_log", List.of("verify_claims", "generate_report", "show_design")),
			Map.entry("run_simulation", List.of("show_design", "check_requirements", "monte_carlo", "fin_flutter", "aero_heating")),
			Map.entry("show_design", List.of("check_requirements", "animate_flight", "generate_report")),
			Map.entry("check_requirements", List.of("targets", "generate_report", "compare_designs")),
			Map.entry("targets", List.of("check_requirements", "optimize", "rank_motors")),
			Map.entry("set_motor", List.of("rail_buttons", "fin_flutter", "flight_card")),
			Map.entry("rank_motors", List.of("optimize", "rail_buttons", "fin_flutter")),
			Map.entry("size_parachute", List.of("parachute_sizes", "add_from_link", "recovery_bay_fit")),
			Map.entry("recovery_analysis", List.of("parachute_sizes", "deployment_delay_sweep", "ejection_charge", "descent_energy")),
			Map.entry("parachute_sizes", List.of("add_from_link", "recovery_analysis", "descent_energy")),
			Map.entry("search_parachutes", List.of("parachute_sizes", "add_from_link")),
			Map.entry("ejection_charge", List.of("recovery_bay_fit", "deployment_delay_sweep")),
			Map.entry("add_avionics_bay", List.of("altimeter_settings", "power_budget", "radio_link", "draw_rocket")),
			Map.entry("altimeter_settings", List.of("sensor_check", "power_budget", "radio_link")),
			Map.entry("sensor_check", List.of("sensor_data", "altimeter_settings")),
			Map.entry("power_budget", List.of("radio_link", "altimeter_settings")),
			Map.entry("radio_link", List.of("power_budget", "monte_carlo")),
			Map.entry("weather_forecast", List.of("flight_card", "monte_carlo", "rail_buttons")),
			Map.entry("monte_carlo", List.of("weather_forecast", "flight_card", "descent_energy")),
			Map.entry("flight_card", List.of("weather_forecast", "rail_buttons", "altimeter_settings")),
			Map.entry("fin_flutter", List.of("compare_materials", "fin_fea", "optimize_fins")),
			Map.entry("compare_materials", List.of("optimize_fins", "fin_fea", "aero_heating")),
			Map.entry("optimize_fins", List.of("fin_flutter", "export_geometry", "compare_shapes")),
			Map.entry("ballast", List.of("optimize_fins", "compare_shapes")),
			Map.entry("aero_analysis", List.of("compare_shapes", "import_aero_table")),
			Map.entry("structural_loads", List.of("compare_materials", "fin_fea", "recovery_analysis")),
			Map.entry("edit_components", List.of("check_requirements", "undo")),
			Map.entry("add_component", List.of("check_requirements", "add_from_link", "undo")),
			Map.entry("mass_budget", List.of("check_requirements", "ballast")),
			Map.entry("generate_report", List.of("compare_designs", "flight_card", "render_3d")),
			Map.entry("render_3d", List.of("show_design", "animate_flight", "draw_rocket")),
			Map.entry("draw_rocket", List.of("render_3d", "add_avionics_bay")),
			Map.entry("add_from_link", List.of("parachute_sizes", "check_requirements")),
			Map.entry("design_library", List.of("compare_flight", "compare_designs")),
			Map.entry("compare_flight", List.of("design_library", "import_aero_table")),
			Map.entry("save_design", List.of("compare_designs", "generate_report")));

	/** Only when it applies: no av-bay to lay out twice, no heating screen for a subsonic rocket. */
	static final Map<String, BiPredicate<Context, Map<?, ?>>> WHEN = Map.of(
			"add_avionics_bay", (ctx, r) -> openDesign(ctx) != null && ElectronicsSummary.bay(openDesign(ctx).doc.getRocket()) == null,
			"aero_heating", (ctx, r) -> maxMach(r) > 1.2,
			"fin_flutter", (ctx, r) -> Double.isNaN(maxMach(r)) || maxMach(r) > 0.6,
			"undo", (ctx, r) -> true);

	static final String CAPABILITIES_ASK = "What else can you help our team with?";

	/** Wraps a tool so its successful map results carry suggestions; records the tools the session has used. */
	public static ToolDef suggesting(Context ctx, ToolDef t) {
		return t.withHandler(a -> {
			Object out = t.handler().call(a);
			ctx.toolsUsed.add(t.name());
			if (!(out instanceof Map<?, ?> m) || !ctx.standards().bool("suggestions", true)) {
				return out;
			}
			List<String> asks = next(ctx, t.name(), m);
			if (asks.isEmpty()) {
				return out;
			}
			Map<Object, Object> copy = new LinkedHashMap<>(m);
			copy.put("youCouldAlsoAsk", asks);
			return copy;
		});
	}

	/** Up to two related things the team has not used or been offered yet. */
	static List<String> next(Context ctx, String tool, Map<?, ?> result) {
		List<String> out = new ArrayList<>();
		for (String n : NEXT.getOrDefault(tool, List.of())) {
			if (out.size() >= 2) {
				break;
			}
			if (ctx.toolsUsed.contains(n) || ctx.suggested.contains(n) || !WHEN.getOrDefault(n, (c, r) -> true).test(ctx, result)) {
				continue;
			}
			String ask = n.equals("capabilities") ? CAPABILITIES_ASK
					: FEATURES.stream().filter(f -> f.tool().equals(n)).map(Feature::ask).findFirst().orElse(null);
			if (ask != null) {
				out.add(ask + " (" + n + ")");
				ctx.suggested.add(n);
			}
		}
		return out;
	}

	private static Designs.Design openDesign(Context ctx) {
		List<Designs.Design> all = ctx.designs.all();
		return all.size() == 1 ? all.get(0) : null;
	}

	/** Max Mach from a run_simulation result, NaN when the result has none. */
	static double maxMach(Map<?, ?> r) {
		if (r.get("flight") instanceof Map<?, ?> f && f.get("maxMach") != null) {
			try {
				return Double.parseDouble(String.valueOf(f.get("maxMach")));
			} catch (NumberFormatException e) {
				return Double.NaN;
			}
		}
		return Double.NaN;
	}

	public static void register(McpServer s, Context ctx) {
		List<String> groups = FEATURES.stream().map(f -> f.group()).distinct().toList();
		s.tool(new ToolDef("capabilities", "What this server can do",
				"A short map of what the team can ask for, grouped by what they are doing (start and review, design and "
						+ "parts, motors and flight, stability, structures, recovery, electronics, launch day, after the flight, "
						+ "liquid and hybrid programmes, pictures), each worded as a team member would ask it, with the tool and whether it was used in this "
						+ "session. Call it when someone is new, asks what you can do, or seems unaware of a feature that would "
						+ "help; then introduce the few that fit their project rather than reading out the list.",
				Schema.object().enumStr("group", "Only this group.", false, groups.stream()
						.map(g -> g.toLowerCase(Locale.ROOT).replace(' ', '_').replace(",", "")).toArray(String[]::new)).build(),
				true, a -> capabilities(ctx, a)));
	}

	private static Map<String, Object> capabilities(Context ctx, Args a) {
		String only = a.str("group", null);
		Map<String, List<String>> byGroup = new LinkedHashMap<>();
		int unused = 0;
		for (Feature f : FEATURES) {
			String key = f.group().toLowerCase(Locale.ROOT).replace(' ', '_').replace(",", "");
			if (only != null && !only.equals(key)) {
				continue;
			}
			boolean used = ctx.toolsUsed.contains(f.tool());
			unused += used ? 0 : 1;
			byGroup.computeIfAbsent(f.group(), g -> new ArrayList<>()).add(f.ask() + " (" + f.tool() + (used ? ", used" : "") + ")");
			ctx.suggested.add(f.tool());
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("youCanAsk", byGroup);
		out.put("notUsedYet", unused + " of " + byGroup.values().stream().mapToInt(List::size).sum());
		out.put("howToIntroduce", "Pick the two or three that fit what this team is building and where they are (e.g. a "
				+ "new design: design_status, check_requirements, rank_motors; before launch: weather_forecast, flight_card), "
				+ "and offer them in their words. Units can be given as people speak (\"20 ft/s\", \"4 in\"), and every "
				+ "change can be undone.");
		return out;
	}
}
