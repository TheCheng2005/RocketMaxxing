package io.github.openrocketmcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.report.Pdf;
import io.github.openrocketmcp.report.Reports;
import io.github.openrocketmcp.report.Templates;
import io.github.openrocketmcp.units.Dim;

/** Design-review reports and data export. */
public final class ReportTools {
	private ReportTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("generate_report", "Write a design-review report",
				"Simulate and write a design-review package to a folder: report.md (requirement checks with rule references, "
						+ "stability per stage stack, motors, flight results, recovery chain, methods and assumptions), the two "
						+ "stability-vs-time plots Launch Canada asks for (stability-to-rail-exit.svg, stability-ascent.svg; "
						+ "DTEG R10.3.2) and flight-data.csv. Good for design reviews and team documentation.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.str("outputDir", "Folder to write into (created if needed), e.g. \"reports/2026-sustainer\".", true)
						.str("title", "Report title.", false)
						.str("pinType", "Shear pin type from the team standards for pin sizing.", false)
						.qty("pinStrength", "Shear strength of one pin (overrides pinType).", false)
						.bool("includeWindCase", "Also check at the rule set's maximum wind (default true).", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation sim = SimTools.runSelected(ctx, a);
					Simulation wind = null;
					double maxWind = ctx.standards().rule("maxGroundWind.value", Dim.VELOCITY);
					if (a.bool("includeWindCase", true) && !Double.isNaN(maxWind)) {
						wind = Variants.windCase(sim, d.doc, maxWind);
					}
					String pinName = a.has("pinType") && !a.has("pinStrength") ? a.str("pinType") : null;
					List<Path> files = Reports.write(d, sim, wind, ctx.standards(), RecoveryTools.pinStrength(ctx, a), pinName,
							a.str("title", null), ctx.path(a.str("outputDir")).toAbsolutePath().normalize());
					List<String> names = new ArrayList<>();
					files.forEach(p -> names.add(p.toString()));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("files", names);
					out.put("note", "report.md references the SVG plots by relative path; keep the folder together.");
					return out;
				}));

		s.tool(new ToolDef("print_templates", "Print templates for building",
				"Write a PDF of full-size templates to print and cut, as OpenRocket prints them: each fin set's outline with its "
						+ "tab, centering rings with the motor tube holes and bulkheads, a paper shroud (flat pattern) for each "
						+ "transition, the nose cone's profile to check a turned or printed nose against, and a marking guide to wrap "
						+ "round each body tube with the fin, lug and rail button lines. Every page has a 100 mm and a 4 in bar to "
						+ "check it printed at 100%; parts bigger than a page are split over several pages to tape together.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("path", "PDF file to write (default \"templates/<design> templates.pdf\" in the designs folder).", false)
						.enumStr("paper", "Paper size (default A4).", false, "A4", "Letter", "A3", "Tabloid")
						.array("include", "Which templates (default all): fins, rings, transitions, nose, marking.", Schema.type("string", null), false)
						.array("components", "Only these parts and what is inside them (names or ids); default the whole rocket.",
								Schema.type("string", null), false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					java.util.Set<String> kinds = new java.util.LinkedHashSet<>(a.has("include") ? a.strList("include") : List.copyOf(Templates.KINDS));
					for (String k : kinds) {
						if (!Templates.KINDS.contains(k)) {
							throw new ToolException("\"" + k + "\" is not a kind of template: use fins, rings, transitions, nose or marking.");
						}
					}
					java.util.Set<info.openrocket.core.rocketcomponent.RocketComponent> only = null;
					if (a.has("components")) {
						only = new java.util.HashSet<>();
						for (String ref : a.strList("components")) {
							only.add(io.github.openrocketmcp.or.Components.find(d.doc.getRocket(), ref));
						}
					}
					Pdf.Paper paper;
					try {
						paper = Pdf.Paper.of(a.str("paper", null));
					} catch (IllegalArgumentException e) {
						throw new ToolException(e.getMessage());
					}
					List<String> skipped = new ArrayList<>();
					List<Templates.Item> items = Templates.items(d.doc.getRocket(), kinds, only, skipped);
					if (items.isEmpty()) {
						throw new ToolException("Nothing to print: the design has no " + String.join(", ", kinds)
								+ (only == null ? "" : " in the parts asked for") + ".");
					}
					String title = d.name() + " templates";
					Templates.Result r = Templates.print(title, items, paper);
					Path file = ctx.path(a.str("path", "templates/" + d.name().replaceAll("[\\\\/:*?\"<>|]", "_") + " templates.pdf"));
					try {
						r.pdf().write(file, title);
					} catch (java.io.IOException e) {
						throw new ToolException("The templates could not be written to " + file + " (" + e.getMessage() + ").");
					}
					List<Map<String, Object>> list = new ArrayList<>();
					for (Templates.Placed p : r.placed()) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("part", p.part());
						m.put("template", p.kind());
						m.put("size", p.size());
						m.put("page", p.page());
						if (p.tiles() > 1) {
							m.put("pieces", p.tiles());
						}
						list.add(m);
					}
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("written", file.toString());
					out.put("paper", paper.name());
					out.put("pages", r.pdf().pages());
					out.put("templates", list);
					if (!skipped.isEmpty()) {
						out.put("notDrawn", skipped);
					}
					out.put("note", "Print at 100% (actual size, not fit to page) and check the 100 mm bar before cutting.");
					return out;
				}));

		s.tool(new ToolDef("export_flight_data", "Export flight data to CSV",
				"Write a simulation's full-resolution time series to a CSV file (display units in the header) for plotting in "
						+ "a spreadsheet, MATLAB or Python, or for comparing with altimeter logs. Variables as in get_flight_data; "
						+ "default: altitude, velocities, acceleration, Mach, stability, CG/CP, thrust, drag, mass, AoA, density.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.str("path", "CSV file to write.", true)
						.str("branch", "Stage branch name or index (default 0).", false)
						.array("variables", "Flight variables (time is always included).", Schema.type("string", null), false).build(),
				false, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					List<String> vars = a.has("variables") ? a.strList("variables")
							: List.of("altitude", "velocitytotal", "velocityz", "accelerationtotal", "machnumber", "stability",
									"cglocation", "cplocation", "thrustforce", "dragforce", "mass", "aoa", "airdensity");
					Path p = Reports.csv(sim, a.str("branch", null), vars, ctx.path(a.str("path")));
					return Map.of("written", p.toString());
				}));
	}
}
