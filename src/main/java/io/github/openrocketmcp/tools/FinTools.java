package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.ParallelStage;
import info.openrocket.core.rocketcomponent.PodSet;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.rocketcomponent.position.AxialMethod;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.CallContext;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.FinDesign;
import io.github.openrocketmcp.or.Optimizer;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.report.Png;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Fin planform optimization, and fin outlines from files. */
public final class FinTools {
	private FinTools() {
	}

	static Map<String, Object> importOutline(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		FinSet f = Components.find(d.doc.getRocket(), a.str("component"), FinSet.class, "fin set");
		Path p = ctx.path(a.str("path"));
		if (!Files.isRegularFile(p)) {
			throw new ToolException("Not found: " + p);
		}
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		List<double[]> pts;
		boolean picture = n.matches(".*\\.(png|jpe?g|gif|bmp)$");
		try {
			if (picture) {
				java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
				if (img == null) {
					throw new ToolException(p.getFileName() + " is not a picture this can read (PNG, JPG, GIF or BMP).");
				}
				List<double[]> px = io.github.openrocketmcp.or.FinOutline.trace(img);
				double chordPx = px.get(px.size() - 1)[0], heightPx = 0;
				for (double[] q : px) {
					heightPx = Math.max(heightPx, q[1]);
				}
				double k = a.has("height") ? a.qty("height", Dim.LENGTH) / heightPx
						: (a.has("rootChord") ? a.qty("rootChord", Dim.LENGTH) : f.getLength()) / chordPx;
				pts = new ArrayList<>();
				for (double[] q : px) {
					pts.add(new double[] { q[0] * k, q[1] * k });
				}
			} else {
				pts = io.github.openrocketmcp.or.FinOutline.csv(Files.readString(p), a.str("unit", null));
			}
		} catch (IOException e) {
			throw new ToolException("Could not read " + p + " (" + e.getMessage() + ").");
		} catch (IllegalArgumentException e) {
			throw new ToolException(e.getMessage());
		}
		info.openrocket.core.rocketcomponent.FreeformFinSet ff = f instanceof info.openrocket.core.rocketcomponent.FreeformFinSet x ? x
				: info.openrocket.core.rocketcomponent.FreeformFinSet.convertFinSet(f);
		info.openrocket.core.util.Coordinate[] c = new info.openrocket.core.util.Coordinate[pts.size()];
		for (int i = 0; i < c.length; i++) {
			c[i] = new info.openrocket.core.util.Coordinate(pts.get(i)[0], pts.get(i)[1]);
		}
		try {
			ff.setPoints(c);
		} catch (RuntimeException e) {
			throw new ToolException("OpenRocket refused the outline (" + e.getMessage() + "): the points must not cross, and the first "
					+ "and last must be the root's two ends.");
		}
		d.doc.setSaved(false);
		double span = 0;
		for (double[] q : pts) {
			span = Math.max(span, q[1]);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("component", Components.shortId(ff));
		out.put("name", ff.getName());
		out.put("from", picture ? "picture, traced" : "points");
		out.put("points", pts.size());
		out.put("rootChord", Units.fmt(pts.get(pts.size() - 1)[0], Dim.LENGTH));
		out.put("height", Units.fmt(span, Dim.LENGTH));
		out.put("stability", Analysis.stageStacks(d.doc.getRocket().getSelectedConfiguration(), 0.3));
		return out;
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("import_fin_outline", "Import a fin outline",
				"Give a fin set the outline in a file, as OpenRocket's freeform fin import: a CSV or text file of points (two "
						+ "numbers a line, x along the root and y out from the body; the unit from a heading such as \"x (mm)\" or "
						+ "unit, else mm), or a picture of the fin (PNG, JPG, GIF, BMP: a drawing, scan or photo of a template, dark "
						+ "on light paper, root along the bottom) traced round its edge and scaled to rootChord or height. The fins "
						+ "become freeform; one undo puts the old ones back.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("component", "Fin set id or name.", true)
						.str("path", "The file: .csv or .txt of points, or a picture.", true)
						.str("unit", "Unit of the numbers in a CSV (mm, cm, m, in, ft), if it does not say.", false)
						.qty("rootChord", "For a picture: the real length of the fin's root (default: the fin set's root chord now).", false)
						.qty("height", "For a picture: the real span of the fin, instead of rootChord.", false).build(),
				false, a -> importOutline(ctx, a)));
		s.tool(new ToolDef("export_fin_outline", "Export a fin outline",
				"Write a fin set's outline (any fin shape) as a CSV of points in mm, from the root's leading edge, to edit in a "
						+ "spreadsheet or CAD and import again.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("component", "Fin set id or name.", true)
						.str("path", "CSV file to write.", true).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					FinSet f = Components.find(d.doc.getRocket(), a.str("component"), FinSet.class, "fin set");
					StringBuilder b = new StringBuilder("x (mm),y (mm)\n");
					for (info.openrocket.core.util.Coordinate p : f.getFinPoints()) {
						b.append(String.format(Locale.ROOT, "%.3f,%.3f%n", p.x * 1000, p.y * 1000));
					}
					Path out = ctx.path(a.str("path"));
					try {
						if (out.getParent() != null) {
							Files.createDirectories(out.getParent());
						}
						Files.writeString(out, b.toString());
					} catch (IOException e) {
						throw new ToolException("Could not write " + out + " (" + e.getMessage() + ").");
					}
					return Map.of("written", out.toString(), "points", f.getFinPoints().length);
				}));
		s.tool(new ToolDef("optimize_fins", "Optimize the fin planform",
				"Search the whole trapezoidal fin shape at once (root chord, tip chord via taper, span and sweep, optionally "
						+ "the thickness from a list of stock sheets) for the highest apogee (least drag), the lightest fins or a "
						+ "target apogee, while meeting the stability rules along the simulated flight (also in the rule set's "
						+ "maximum wind), rail exit and the team's fin flutter margin. Only buildable shapes are tried: tip chord "
						+ "at least minTipChord, no tip trailing edge behind the root (unless allowAftOverhang), leading-edge sweep "
						+ "capped, root no longer than the tube. Returns the current and optimized planforms with apogee, mass, "
						+ "stability and flutter, runners-up, and an optional side-by-side planform drawing. The design is "
						+ "unchanged unless apply=true. For nose / fin edge profiles use compare_shapes; for structural checks of "
						+ "the result use fin_fea.",
				SimTools.simSelect(Schema.object())
						.str("finSet", "Fin set id or name (default: the only trapezoidal fin set).", false)
						.enumStr("objective", "What to optimize (default max_apogee).", false, "max_apogee", "min_mass", "target_apogee")
						.qty("targetApogee", "Target apogee for target_apogee.", false)
						.array("thicknesses", "Stock thicknesses to choose from, e.g. [\"1/8 in\", \"3/16 in\", \"1/4 in\"] "
								+ "(default: the current thickness).", Schema.quantityItem(), false)
						.qty("minRootChord", "Smallest root chord (default 0.75 body diameters).", false)
						.qty("maxRootChord", "Largest root chord (default 3 diameters, and no longer than the tube).", false)
						.qty("minSpan", "Smallest span (default 0.5 diameters).", false)
						.qty("maxSpan", "Largest span, e.g. for transport, the launch tower or ground clearance (default 2 diameters).", false)
						.qty("minTipChord", "Smallest tip chord; thin tips break (default 0.5 in).", false)
						.qty("maxSweepAngle", "Largest leading-edge sweep angle (default 65 deg).", false)
						.bool("allowAftOverhang", "Allow the tip trailing edge behind the root (default false: such tips take the "
								+ "landing).", false)
						.bool("meetRules", "Stability floor and ceiling from the rule set, rail exit from the rules, flutter margin from "
								+ "the team standards (default true).", false)
						.num("minStability", "Minimum simulated ascent stability, cal.", false)
						.num("maxStability", "Maximum simulated ascent stability, cal.", false)
						.num("minFlutterMargin", "Minimum fin flutter margin (flutter speed / airspeed).", false)
						.num("maxMach", "Maximum Mach number.", false)
						.bool("checkDesignWind", "Also fly each candidate in the rule set's maximum ground wind (default true).", false)
						.integer("maxEvaluations", "Simulations per thickness (default 48, max 300; more finds a slightly better shape).", false)
						.str("plotPath", "Also draw the current and optimized planforms (SVG), e.g. \"plots/fins.svg\".", false)
						.bool("apply", "Apply the optimized planform and thickness to the design (default false).", false).build(),
				false, a -> optimize(ctx, a)));
	}

	static TrapezoidFinSet trapezoid(Designs.Design d, Args a) {
		if (a.has("finSet")) {
			FinSet f = Components.find(d.doc.getRocket(), a.str("finSet"), FinSet.class, "fin set");
			if (f instanceof TrapezoidFinSet t) {
				return t;
			}
			throw new ToolException(f.getName() + " is a " + f.getComponentName() + "; optimize_fins shapes trapezoidal fin sets. "
					+ "Freeform and elliptical fins can be tuned with optimize (e.g. \"height\" or \"length\").");
		}
		List<TrapezoidFinSet> found = new ArrayList<>();
		for (RocketComponent c : d.doc.getRocket().getSelectedConfiguration().getAllActiveComponents()) {
			if (c instanceof TrapezoidFinSet t) {
				found.add(t);
			}
		}
		if (found.size() > 1) {
			// Fins on pods and side boosters (winglets, strakes) are not the rocket's fins: prefer the main airframe's.
			List<TrapezoidFinSet> main = new ArrayList<>();
			for (TrapezoidFinSet t : found) {
				boolean onPod = false;
				for (RocketComponent p = t.getParent(); p != null; p = p.getParent()) {
					onPod |= p instanceof PodSet || p instanceof ParallelStage;
				}
				if (!onPod) {
					main.add(t);
				}
			}
			if (main.size() == 1) {
				return main.get(0);
			}
		}
		if (found.size() != 1) {
			List<String> names = new ArrayList<>();
			for (TrapezoidFinSet t : found) {
				names.add(t.getName() + " [" + Components.shortId(t) + "]");
			}
			throw new ToolException(found.isEmpty() ? "The active configuration has no trapezoidal fin set."
					: "Several trapezoidal fin sets; choose one with finSet: " + String.join(", ", names) + ".");
		}
		return found.get(0);
	}

	private static Object optimize(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		TrapezoidFinSet fin = trapezoid(d, a);
		Optimizer.Objective obj = Optimizer.Objective.valueOf(a.str("objective", "max_apogee").toUpperCase(Locale.ROOT));
		double target = obj == Optimizer.Objective.TARGET_APOGEE ? a.qty("targetApogee", Dim.DISTANCE) : Double.NaN;
		FinDesign.Limits lim = FinDesign.defaults(fin, a.qtyOrNaN("minRootChord", Dim.LENGTH), a.qtyOrNaN("maxRootChord", Dim.LENGTH),
				a.qtyOrNaN("minSpan", Dim.LENGTH), a.qtyOrNaN("maxSpan", Dim.LENGTH), a.qtyOrNaN("minTipChord", Dim.LENGTH),
				a.bool("allowAftOverhang", false), a.qtyOrNaN("maxSweepAngle", Dim.ANGLE));
		List<Double> thick = a.has("thicknesses") ? a.qtyList("thicknesses", Dim.LENGTH) : List.of(fin.getThickness());
		if (thick.isEmpty() || thick.size() > 6) {
			throw new ToolException("Give 1 to 6 thicknesses.");
		}
		String[] basis = new String[1];
		Optimizer.Constraints c = AnalysisTools.constraints(ctx, d, a, obj, true, basis);
		String hidden = Components.overrideWarning(fin);
		Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
				ctx.standards(), false);
		double maxWind = ctx.standards().rule("maxGroundWind.value", Dim.VELOCITY);
		double windCase = a.bool("checkDesignWind", true) && !Double.isNaN(maxWind) ? maxWind : Double.NaN;
		int budget = Math.max(16, Math.min(300, a.integer("maxEvaluations", 48)));
		long t0 = System.nanoTime();
		CallContext.current().expect((thick.size() * budget + 1) * (Double.isNaN(windCase) ? 1 : 2), "simulations");
		List<FinDesign.Run> runs = FinDesign.optimize(base, d.doc, fin, lim, thick, obj, target, c, budget, windCase);

		// Best over all thicknesses (feasibility first, then the objective).
		FinDesign.Run best = null;
		double bestScore = Double.MAX_VALUE;
		List<Map<String, Object>> perThickness = new ArrayList<>();
		for (FinDesign.Run r : runs) {
			if (r.result().best() == null) {
				continue;
			}
			double sc = Optimizer.scoreOf(r.result().best(), obj, target, c);
			if (sc < bestScore) {
				bestScore = sc;
				best = r;
			}
			if (runs.size() > 1) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("thickness", Units.fmt(r.thickness(), Dim.LENGTH));
				row.put("feasible", r.result().feasible());
				Optimizer.Point p = r.result().best();
				if (p.simulated()) {
					row.put("apogee", Units.fmt(p.apogee(), Dim.DISTANCE));
					row.put("launchMass", Units.fmt(p.launchMass(), Dim.MASS));
					if (!Double.isNaN(p.flutterMargin())) {
						row.put("finFlutterMargin", Units.num(p.flutterMargin()));
					}
				}
				perThickness.add(row);
			}
		}
		if (best == null) {
			throw new ToolException("No candidate could be evaluated.");
		}
		Optimizer.Point bp = best.result().best();
		FinDesign.Planform bestShape = FinDesign.shape(bp.x(), lim, best.thickness());
		FinDesign.Planform now = FinDesign.of(fin);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("finSet", fin.getName() + " [" + Components.shortId(fin) + "], " + fin.getFinCount() + " fins, "
				+ fin.getMaterial().getName());
		out.put("objective", obj.name().toLowerCase(Locale.ROOT) + (Double.isNaN(target) ? "" : " " + Units.fmt(target, Dim.DISTANCE)));
		Map<String, Object> cons = AnalysisTools.describe(c, windCase, basis[0]);
		cons.put("shape", "root " + Units.fmt(lim.rootMin(), Dim.LENGTH) + " to " + Units.fmt(lim.rootMax(), Dim.LENGTH) + ", span "
				+ Units.fmt(lim.spanMin(), Dim.LENGTH) + " to " + Units.fmt(lim.spanMax(), Dim.LENGTH) + ", tip chord >= "
				+ Units.fmt(lim.minTip(), Dim.LENGTH) + ", leading-edge sweep <= " + Units.num(Math.toDegrees(lim.maxSweepAngle()))
				+ " deg, " + (lim.maxSweepFraction() > 1 ? "aft overhang allowed" : "no tip trailing edge behind the root"));
		out.put("constraints", cons);
		out.put("feasible", best.result().feasible());
		out.put("optimized", FinDesign.render(bp, bestShape));
		Optimizer.Point cur = Optimizer.evaluateOne(base, d.doc, Optimizer.Applier.withOpenRocketDrag((r, x) -> {
		}), new double[0], windCase, c.std());
		Map<String, Object> curOut = FinDesign.render(cur, now);
		List<String> curV = Optimizer.violations(cur, c);
		curOut.put("meetsConstraints", curV.isEmpty() ? "yes" : "NO: " + String.join("; ", curV));
		out.put("current", curOut);
		if (bp.simulated() && cur.simulated()) {
			Map<String, Object> gain = new LinkedHashMap<>();
			gain.put("apogee", signed(bp.apogee() - cur.apogee(), Dim.DISTANCE));
			gain.put("launchMass", signed(bp.launchMass() - cur.launchMass(), Dim.MASS));
			gain.put("finAreaPerFin", signed(bestShape.area() - now.area(), Dim.AREA));
			out.put("change", gain);
		}
		if (!perThickness.isEmpty()) {
			out.put("byThickness", perThickness);
		}
		List<Map<String, Object>> alts = new ArrayList<>();
		for (Optimizer.Point p : Optimizer.top(best.result(), obj, target, c, 4)) {
			if (p != bp && Optimizer.violations(p, c).isEmpty()) {
				alts.add(FinDesign.render(p, FinDesign.shape(p.x(), lim, best.thickness())));
			}
		}
		out.put("alternatives", alts);
		int n = runs.stream().mapToInt(r -> r.result().evaluated().size()).sum();
		out.put("evaluations", n + (Double.isNaN(windCase) ? "" : " candidates (each flown twice: nominal and maximum wind)"));
		out.put("elapsed", Units.num((System.nanoTime() - t0) / 1e9) + " s");
		if (!best.result().feasible()) {
			List<String> why = Optimizer.violations(bp, c);
			boolean flutter = why.stream().anyMatch(v -> v.startsWith("fin flutter"));
			out.put("note", "No planform met every constraint; 'optimized' is the least-violating (" + String.join("; ", why) + "). "
					+ (flutter ? "Try thicker stock (thicknesses) or a stiffer material; fin_flutter gives what is needed. " : "")
					+ "Widen the bounds (maxSpan, maxRootChord), add nose ballast, or relax a constraint.");
		}
		List<String> notes = new ArrayList<>();
		var table = AeroTable.any(d.doc.getRocket());
		notes.add("Aerodynamics are OpenRocket's (Barrowman with its transonic and supersonic extensions), because they follow the "
				+ "fin shape" + (table != null ? "; the imported aero table (" + table.source() + ") describes the current fins only and "
						+ "was not used. Re-run CFD / RASAero on the chosen fins (export_geometry) and import it again" : "")
				+ ". To confirm the result, export_geometry gives the CFD model and run matrix for the new shape.");
		notes.add("Check the chosen fins' strength and stiffness with fin_fea, and their flutter margin with fin_flutter.");
		if (hidden != null) {
			notes.add(hidden);
		}
		out.put("notes", notes);
		if (a.has("plotPath")) {
			Path p = ctx.path(a.str("plotPath"));
			try {
				if (p.getParent() != null) {
					Files.createDirectories(p.getParent());
				}
				Files.writeString(p, FinDesign.svg(d.name() + ": " + fin.getName() + " planform", now, bestShape,
						fin.getAxialMethod() == AxialMethod.BOTTOM));
				Png.attachFile(p, "Current and optimized fin planforms");
			} catch (IOException e) {
				throw new ToolException("Could not write " + p + ": " + e.getMessage());
			}
			out.put("plot", p.toAbsolutePath().toString());
		}
		if (a.bool("apply", false) && bp.error() == null) {
			FinDesign.applyTo(fin, bestShape);
			d.doc.setSaved(false);
			out.put("applied", "root " + Units.fmt(bestShape.root(), Dim.LENGTH) + ", tip " + Units.fmt(bestShape.tip(), Dim.LENGTH)
					+ ", span " + Units.fmt(bestShape.span(), Dim.LENGTH) + ", sweep " + Units.fmt(bestShape.sweep(), Dim.LENGTH)
					+ ", thickness " + Units.fmt(bestShape.thickness(), Dim.LENGTH));
			out.put("stability", Analysis.stageStacks(d.doc.getRocket().getSelectedConfiguration(), 0.3));
		}
		return out;
	}

	private static String signed(double v, Dim dim) {
		return (v >= 0 ? "+" : "") + Units.fmt(v, dim);
	}
}
